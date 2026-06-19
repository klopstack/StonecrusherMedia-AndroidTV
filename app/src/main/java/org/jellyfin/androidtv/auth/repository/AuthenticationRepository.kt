package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.auth.model.AccessScheduleDeniedLoginState
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticateMethod
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.AuthenticationStoreUser
import org.jellyfin.androidtv.auth.model.AutomaticAuthenticateMethod
import org.jellyfin.androidtv.auth.model.CredentialAuthenticateMethod
import org.jellyfin.androidtv.auth.model.LoginForbiddenState
import org.jellyfin.androidtv.auth.model.LoginState
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.QuickConnectAuthenticateMethod
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerTypeNotSupportedLoginState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.auth.model.User
import org.jellyfin.androidtv.auth.store.AuthenticationPreferences
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.androidtv.data.repository.AccessScheduleRepository
import org.jellyfin.androidtv.data.repository.AccessScheduleStatus
import org.jellyfin.androidtv.data.repository.JellyseerrRepository
import org.jellyfin.androidtv.preference.JellyseerrPreferences
import org.jellyfin.androidtv.util.JellyfinAuthenticationHelper
import org.jellyfin.androidtv.util.apiclient.JellyfinImage
import org.jellyfin.androidtv.util.apiclient.JellyfinImageSource
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.androidtv.util.sdk.forUser
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.api.client.extensions.authenticateUserByName
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.moonfin.server.core.model.ServerType
import org.jellyfin.sdk.model.serializer.toUUID
import org.moonfin.server.emby.EmbyApiClient
import timber.log.Timber
import java.time.Instant
import java.util.UUID

/**
 * Repository to manage authentication of the user in the app.
 */
interface AuthenticationRepository {
	fun authenticate(server: Server, method: AuthenticateMethod): Flow<LoginState>
	fun logout(user: User): Boolean
	fun getUserImageUrl(server: Server, user: User): String?
}

class AuthenticationRepositoryImpl(
	private val jellyfin: Jellyfin,
	private val sessionRepository: SessionRepository,
	private val authenticationStore: AuthenticationStore,
	private val userApiClient: ApiClient,
	private val authenticationPreferences: AuthenticationPreferences,
	private val defaultDeviceInfo: DeviceInfo,
	private val jellyseerrRepository: JellyseerrRepository,
	private val jellyseerrPreferences: JellyseerrPreferences,
	private val embyApiClient: EmbyApiClient,
	private val accessScheduleRepository: AccessScheduleRepository,
	private val jellyfinAuthenticationHelper: JellyfinAuthenticationHelper,
) : AuthenticationRepository {
	override fun authenticate(server: Server, method: AuthenticateMethod): Flow<LoginState> {
		return when (method) {
			is AutomaticAuthenticateMethod -> authenticateAutomatic(server, method.user)
			is CredentialAuthenticateMethod -> authenticateCredential(server, method.username, method.password)
			is QuickConnectAuthenticateMethod -> authenticateQuickConnect(server, method.secret)
		}
	}

	private fun authenticateAutomatic(server: Server, user: User): Flow<LoginState> {
		Timber.i("Authenticating user ${user.id}")

		// Automatic logic is disabled when the always authenticate preference is enabled
		if (authenticationPreferences[AuthenticationPreferences.alwaysAuthenticate]) return flowOf(RequireSignInState)

		val authStoreUser = authenticationStore.getUser(server.id, user.id)
		// Try login with access token
		return if (authStoreUser?.accessToken != null) authenticateToken(server, user.withToken(authStoreUser.accessToken))
		// Require login
		else flowOf(RequireSignInState)
	}

	private fun resolveSessionSetupFailure(server: Server): Flow<LoginState> = flow {
		when {
			!server.isSupportedByBuild -> emit(ServerTypeNotSupportedLoginState(server))
			!server.versionSupported -> emit(ServerVersionNotSupported(server))
			accessScheduleRepository.hasPendingLoginDenied() -> {
				val nextAccess = accessScheduleRepository.consumeLoginDenied()
				emit(AccessScheduleDeniedLoginState(nextAccess))
			}
			else -> emit(RequireSignInState)
		}
	}

	private fun findStoredUserId(serverId: UUID, username: String): UUID? =
		authenticationStore.getServer(serverId)?.users?.entries
			?.firstOrNull { it.value.name.equals(username, ignoreCase = true) }
			?.key

	private suspend fun FlowCollector<LoginState>.emitIfScheduleDenied(serverId: UUID, userId: UUID?): Boolean {
		if (userId == null) return false
		val status = accessScheduleRepository.evaluateCachedPolicyForUser(serverId, userId) ?: return false
		if (status is AccessScheduleStatus.Denied) {
			emit(AccessScheduleDeniedLoginState(status.nextAccessStart))
			return true
		}
		return false
	}

	private fun rememberUserPolicy(serverId: UUID, userId: UUID, policy: UserPolicy?) {
		accessScheduleRepository.cacheUserPolicy(serverId, userId, policy)
	}

	private suspend fun emitLoginApiError(
		err: ApiClientException,
		serverId: UUID,
		userId: UUID?,
		responseBody: String? = null,
	): LoginState {
		if (accessScheduleRepository.isScheduleRelatedApiError(err, serverId, userId, responseBody)) {
			val nextAccess = userId?.let { id ->
				(accessScheduleRepository.evaluateCachedPolicyForUser(serverId, id) as? AccessScheduleStatus.Denied)?.nextAccessStart
			}
			return AccessScheduleDeniedLoginState(nextAccess)
		}
		if (err is InvalidStatusException && err.status == 403) {
			return LoginForbiddenState
		}
		return ApiClientErrorLoginState(err)
	}

	private fun authenticateCredential(server: Server, username: String, password: String) = flow {
		if (server.serverType == ServerType.EMBY && !BuildConfig.EMBY_ENABLED) {
			emit(ServerTypeNotSupportedLoginState(server))
			return@flow
		}
		if (server.serverType == ServerType.EMBY) {
			emitAll(authenticateCredentialEmby(server, username, password))
			return@flow
		}

		val userId = findStoredUserId(server.id, username)
		if (emitIfScheduleDenied(server.id, userId)) return@flow

		val api = jellyfin.createApi(server.address, deviceInfo = defaultDeviceInfo.forUser(username))
		val result = try {
			// For users without passwords, pass empty string to the API
			val response = api.userApi.authenticateUserByName(username, password)
			response.content
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server trying to sign in $username")
			emit(ServerUnavailableState)
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to sign in as $username")
			val responseBody = if (err is InvalidStatusException && err.status == 403 && shouldFetch403ResponseBody(server.id, userId)) {
				jellyfinAuthenticationHelper.readAuthenticateByNameErrorBody(
					serverAddress = server.address,
					username = username,
					password = password,
					deviceInfo = defaultDeviceInfo.forUser(username),
				)
			} else {
				null
			}
			emit(emitLoginApiError(err, server.id, userId, responseBody))
			return@flow
		}

		// After successful Jellyfin authentication, attempt Jellyseerr auto-login
		tryJellyseerrAutoLogin(server, username, password)

		emitAll(authenticateAuthenticationResult(server, result))
	}.flowOn(Dispatchers.IO)

	private fun authenticateQuickConnect(server: Server, secret: String) = flow {
		val api = jellyfin.createApi(server.address, deviceInfo = defaultDeviceInfo)
		val result = try {
			val response = api.userApi.authenticateWithQuickConnect(secret)
			response.content
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server")
			emit(ServerUnavailableState)
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to sign in with Quick Connect secret")
			emit(emitLoginApiError(err, server.id, userId = null))
			return@flow
		}

		emitAll(authenticateAuthenticationResult(server, result))
	}.flowOn(Dispatchers.IO)

	private fun authenticateAuthenticationResult(server: Server, result: AuthenticationResult) = flow {
		val accessToken = result.accessToken ?: return@flow emit(RequireSignInState)
		val userInfo = result.user ?: return@flow emit(RequireSignInState)
		val user = PrivateUser(
			id = userInfo.id,
			serverId = server.id,
			name = userInfo.name!!,
			accessToken = result.accessToken,
			imageTag = userInfo.primaryImage?.tag,
			lastUsed = Instant.now().toEpochMilli(),
		)

		authenticateFinish(server, userInfo, accessToken)
		rememberUserPolicy(server.id, userInfo.id, userInfo.policy)

		if (server.serverType == ServerType.JELLYFIN) {
			when (val scheduleStatus = accessScheduleRepository.evaluatePolicy(userInfo.policy)) {
				is AccessScheduleStatus.Denied -> {
					emit(AccessScheduleDeniedLoginState(scheduleStatus.nextAccessStart))
					return@flow
				}
				AccessScheduleStatus.Allowed -> Unit
			}
		}

		val success = setActiveSession(user, server)
		if (success) {
			emit(AuthenticatedState)
		} else {
			Timber.w("Failed to set active session after authenticating")
			emitAll(resolveSessionSetupFailure(server))
		}
	}.flowOn(Dispatchers.IO)

	private fun authenticateToken(server: Server, user: User) = flow {
		emit(AuthenticatingState)

		if (emitIfScheduleDenied(server.id, user.id)) return@flow

		val accessToken = user.accessToken.orEmpty()
		var prefetchedUserInfo: UserDto? = null
		if (server.serverType == ServerType.JELLYFIN) {
			val api = jellyfin.createApi(
				server.address,
				accessToken = accessToken,
				deviceInfo = defaultDeviceInfo.forUser(user.id),
			)
			try {
				val userInfo = api.userApi.getCurrentUser().content
				prefetchedUserInfo = userInfo
				rememberUserPolicy(server.id, user.id, userInfo.policy)
				when (val scheduleStatus = accessScheduleRepository.evaluatePolicy(userInfo.policy)) {
					is AccessScheduleStatus.Denied -> {
						emit(AccessScheduleDeniedLoginState(scheduleStatus.nextAccessStart))
						return@flow
					}
					AccessScheduleStatus.Allowed -> Unit
				}
			} catch (err: TimeoutException) {
				Timber.e(err, "Failed to connect to server")
				emit(ServerUnavailableState)
				return@flow
			} catch (err: ApiClientException) {
				Timber.e(err, "Unable to get current user data")
				emit(emitLoginApiError(err, server.id, user.id))
				return@flow
			}
		}

		val success = setActiveSession(user, server)
		if (!success) {
			emitAll(resolveSessionSetupFailure(server))
		} else try {
			if (server.serverType == ServerType.EMBY) {
				val embyUser = embyApiClient.validateCurrentUser()
				authenticateFinishEmby(server, embyUser, accessToken, user.id)
			} else {
				val userInfo = prefetchedUserInfo ?: userApiClient.userApi.getCurrentUser().content
				authenticateFinish(server, userInfo, accessToken)
			}
			emit(AuthenticatedState)
		} catch (err: TimeoutException) {
			Timber.e(err, "Failed to connect to server")
			emit(ServerUnavailableState)
			return@flow
		} catch (err: ApiClientException) {
			Timber.e(err, "Unable to get current user data")
			emit(emitLoginApiError(err, server.id, user.id))
		} catch (err: Exception) {
			Timber.e(err, "Unable to get current user data")
			emit(RequireSignInState)
		}
	}.flowOn(Dispatchers.IO)

	private suspend fun authenticateFinish(server: Server, userInfo: UserDto, accessToken: String) {
		val currentUser = authenticationStore.getUser(server.id, userInfo.id)

		val updatedUser = currentUser?.copy(
			name = userInfo.name!!,
			lastUsed = Instant.now().toEpochMilli(),
			imageTag = userInfo.primaryImage?.tag,
			accessToken = accessToken,
		) ?: AuthenticationStoreUser(
			name = userInfo.name!!,
			imageTag = userInfo.primaryImage?.tag,
			accessToken = accessToken,
		)
		authenticationStore.putUser(server.id, userInfo.id, updatedUser)
	}

	private suspend fun authenticateFinishEmby(
		server: Server,
		userInfo: org.moonfin.server.emby.EmbyUserInfo,
		accessToken: String,
		userId: java.util.UUID,
	) {
		val currentUser = authenticationStore.getUser(server.id, userId)
		val updatedUser = currentUser?.copy(
			name = userInfo.name ?: currentUser.name,
			lastUsed = Instant.now().toEpochMilli(),
			imageTag = userInfo.primaryImageTag,
			accessToken = accessToken,
		) ?: AuthenticationStoreUser(
			name = userInfo.name ?: "User",
			imageTag = userInfo.primaryImageTag,
			accessToken = accessToken,
		)
		authenticationStore.putUser(server.id, userId, updatedUser)
	}

	private fun authenticateCredentialEmby(server: Server, username: String, password: String) = flow {
		embyApiClient.configure(server.address, null, null)
		val result = try {
			embyApiClient.authenticateByName(username, password)
		} catch (err: Exception) {
			Timber.e(err, "Failed to authenticate as $username on Emby")
			emit(ServerUnavailableState)
			return@flow
		}
		val accessToken = result.accessToken ?: return@flow emit(RequireSignInState)
		val userInfo = result.user ?: return@flow emit(RequireSignInState)
		val userIdStr = userInfo.id.ifEmpty { return@flow emit(RequireSignInState) }
		val userId = runCatching { userIdStr.toUUID() }.getOrElse {
			Timber.e(it, "Failed to parse Emby user ID: $userIdStr")
			return@flow emit(RequireSignInState)
		}
		val user = PrivateUser(
			id = userId,
			serverId = server.id,
			name = userInfo.name ?: username,
			accessToken = accessToken,
			imageTag = userInfo.primaryImageTag,
			lastUsed = Instant.now().toEpochMilli(),
		)
		embyApiClient.configure(server.address, accessToken, userIdStr)
		authenticateFinishEmby(server, userInfo, accessToken, userId)
		val success = setActiveSession(user, server)
		if (success) emit(AuthenticatedState)
		else {
			emitAll(resolveSessionSetupFailure(server))
		}
	}.flowOn(Dispatchers.IO)

	private suspend fun setActiveSession(user: User, server: Server): Boolean {
		val authenticated = sessionRepository.switchCurrentSession(server.id, user.id)

		if (authenticated) {
			// Update last use in store
			authenticationStore.getServer(server.id)?.let { storedServer ->
				authenticationStore.putServer(server.id, storedServer.copy(lastUsed = Instant.now().toEpochMilli()))
			}

			authenticationStore.getUser(server.id, user.id)?.let { storedUser ->
				authenticationStore.putUser(server.id, user.id, storedUser.copy(lastUsed = Instant.now().toEpochMilli()))
			}
		}

		return authenticated
	}

	override fun logout(user: User): Boolean {
		val authStoreUser = authenticationStore
			.getUser(user.serverId, user.id)
			?.copy(accessToken = null)

		return if (authStoreUser != null) authenticationStore.putUser(user.serverId, user.id, authStoreUser)
		else false
	}

	/**
	 * Attempt to automatically login to Jellyseerr using Jellyfin credentials.
	 * This is called after successful Jellyfin authentication to provide a seamless single sign-on experience.
	 * 
	 * Note: The password is only held in memory temporarily and never stored on disk.
	 * The Jellyseerr session is maintained via HTTP cookies stored by Ktor's PersistentCookiesStorage,
	 * which persists across app restarts and updates. Users only need to login again after:
	 * - Fresh install/reinstall (cookies cleared)
	 * - Manual logout
	 * - Cookie expiration (controlled by Jellyseerr server settings)
	 * 
	 * IMPORTANT: This method first checks if the session is already valid (using cached result)
	 * to prevent excessive login attempts that can trigger rate limiting/lockouts on Jellyseerr.
	 */
	private fun tryJellyseerrAutoLogin(server: Server, username: String, password: String) {
		if (jellyseerrRepository.isMoonfinMode.value) {
			Timber.d("Jellyseerr auto-login skipped: using Moonfin proxy mode")
			return
		}

		// Check if Jellyseerr is enabled and configured
		val enabled = jellyseerrPreferences[JellyseerrPreferences.enabled]
		val jellyseerrUrl = jellyseerrPreferences[JellyseerrPreferences.serverUrl]
		
		if (!enabled || jellyseerrUrl.isNullOrBlank()) {
			Timber.d("Jellyseerr auto-login skipped: not enabled or configured")
			return
		}

		// Launch async login attempt (non-blocking)
		kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
			try {
				// First check if session is already valid (uses cache to prevent excessive checks)
				val sessionAlreadyValid = jellyseerrRepository.isSessionValidCached()
				if (sessionAlreadyValid) {
					Timber.d("Jellyseerr auto-login skipped: session already valid for user: $username")
					return@launch
				}
				
				Timber.d("Attempting Jellyseerr auto-login for user: $username (session invalid or expired)")
				val result = jellyseerrRepository.loginWithJellyfin(
					username = username,
					password = password,
					jellyfinUrl = server.address,
					jellyseerrUrl = jellyseerrUrl
				)
				
				if (result.isSuccess) {
					val user = result.getOrNull()
					Timber.i("Jellyseerr auto-login successful for user: ${user?.username ?: username}")
					// Cookie is automatically stored by PersistentCookiesStorage in JellyseerrHttpClient
					// No need to store API key - cookie-based auth persists across app restarts
				} else {
					Timber.w("Jellyseerr auto-login failed: ${result.exceptionOrNull()?.message}")
				}
			} catch (err: Exception) {
				Timber.w(err, "Jellyseerr auto-login exception")
			}
		}
	}

	override fun getUserImageUrl(server: Server, user: User): String? = user.imageTag?.let { primaryImageTag ->
		JellyfinImage(
			item = user.id,
			source = JellyfinImageSource.USER,
			type = ImageType.PRIMARY,
			tag = primaryImageTag,
			blurHash = null,
			aspectRatio = null,
			index = null
		)
	}?.getUrl(jellyfin.createApi(server.address))

	private fun shouldFetch403ResponseBody(serverId: UUID, userId: UUID?): Boolean {
		if (userId == null) return true
		return accessScheduleRepository.evaluateCachedPolicyForUser(serverId, userId) == null
	}
}
