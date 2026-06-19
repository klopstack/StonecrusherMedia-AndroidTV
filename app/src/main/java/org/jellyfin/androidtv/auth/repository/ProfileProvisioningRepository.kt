package org.jellyfin.androidtv.auth.repository

import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.model.AuthenticationStoreUser
import org.jellyfin.androidtv.auth.model.ProfileProvisioningError
import org.jellyfin.androidtv.auth.model.ProfileProvisioningFailure
import org.jellyfin.androidtv.auth.model.ProfileProvisioningSummary
import org.jellyfin.androidtv.auth.model.PublicUser
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.jellyfin.androidtv.util.apiclient.primaryImage
import org.jellyfin.androidtv.util.sdk.forUser
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.extensions.authenticateWithQuickConnect
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.DeviceInfo
import org.moonfin.server.core.model.ServerType
import timber.log.Timber
import java.util.UUID

interface ProfileProvisioningRepository {
	suspend fun provisionAllProfiles(server: Server): Result<ProfileProvisioningSummary>
}

private data class AdminSession(
	val userId: UUID,
	val accessToken: String,
)

class ProfileProvisioningRepositoryImpl(
	private val jellyfin: Jellyfin,
	private val defaultDeviceInfo: DeviceInfo,
	private val authenticationStore: AuthenticationStore,
	private val serverUserRepository: ServerUserRepository,
) : ProfileProvisioningRepository {
	private suspend fun findAdminSession(server: Server): AdminSession? = withContext(Dispatchers.IO) {
		serverUserRepository.getStoredServerUsers(server)
			.filter { !it.accessToken.isNullOrBlank() }
			.firstNotNullOfOrNull { user ->
				val api = jellyfin.createApi(
					baseUrl = server.address,
					accessToken = user.accessToken,
					deviceInfo = defaultDeviceInfo.forUser(user.id),
				)

				try {
					val currentUser = api.userApi.getCurrentUser().content
					if (currentUser.policy?.isAdministrator == true) {
						AdminSession(userId = user.id, accessToken = user.accessToken!!)
					} else {
						null
					}
				} catch (err: ApiClientException) {
					Timber.w(err, "Unable to verify admin status for stored user ${user.name}")
					null
				}
			}
	}

	override suspend fun provisionAllProfiles(server: Server): Result<ProfileProvisioningSummary> =
		withContext(Dispatchers.IO) {
			if (server.serverType != ServerType.JELLYFIN) {
				return@withContext Result.failure(ProvisioningException(ProfileProvisioningError.NotJellyfinServer))
			}

			val adminSession = findAdminSession(server)
				?: return@withContext Result.failure(ProvisioningException(ProfileProvisioningError.NoAdminSession))

			val adminApi = jellyfin.createApi(
				baseUrl = server.address,
				accessToken = adminSession.accessToken,
				deviceInfo = defaultDeviceInfo.forUser(adminSession.userId),
			)

			try {
				val adminUser = adminApi.userApi.getCurrentUser().content
				if (adminUser.policy?.isAdministrator != true) {
					return@withContext Result.failure(ProvisioningException(ProfileProvisioningError.NotAdministrator))
				}

				if (!adminApi.quickConnectApi.getQuickConnectEnabled().content) {
					return@withContext Result.failure(ProvisioningException(ProfileProvisioningError.QuickConnectDisabled))
				}
			} catch (err: ApiClientException) {
				Timber.e(err, "Unable to verify provisioning prerequisites")
				return@withContext Result.failure(err)
			}

			val publicUsers = serverUserRepository.getPublicServerUsers(server)
			val storedUsers = serverUserRepository.getStoredServerUsers(server)
			val plan = ProfileProvisioningHelper.planProvisioning(publicUsers, storedUsers)

			val provisioned = mutableListOf<String>()
			val failed = mutableListOf<ProfileProvisioningFailure>()

			for (user in plan.toProvision) {
				try {
					provisionUser(server, adminApi, user)
					provisioned.add(user.name)
				} catch (err: CancellationException) {
					throw err
				} catch (err: Exception) {
					Timber.w(err, "Failed to provision profile for ${user.name}")
					failed.add(ProfileProvisioningFailure(user.name, err.message ?: "Unknown error"))
				}
			}

			Result.success(
				ProfileProvisioningSummary(
					provisioned = provisioned,
					skipped = plan.skipped,
					failed = failed,
				)
			)
		}

	private suspend fun provisionUser(server: Server, adminApi: org.jellyfin.sdk.api.client.ApiClient, user: PublicUser) {
		val deviceInfo = defaultDeviceInfo.forUser(user.id)
		val targetApi = jellyfin.createApi(
			baseUrl = server.address,
			deviceInfo = deviceInfo,
		)

		val quickConnect = targetApi.quickConnectApi.initiateQuickConnect().content
		val code = quickConnect.code ?: error("Quick Connect did not return a code")
		val secret = quickConnect.secret ?: error("Quick Connect did not return a secret")

		adminApi.quickConnectApi.authorizeQuickConnect(code = code, userId = user.id)

		val result = targetApi.userApi.authenticateWithQuickConnect(secret).content
		val accessToken = result.accessToken ?: error("Authentication did not return an access token")
		val userInfo = result.user ?: error("Authentication did not return user info")

		val userName = userInfo.name?.takeIf { it.isNotBlank() } ?: user.name

		val currentUser = authenticationStore.getUser(server.id, user.id)
		val updatedUser = currentUser?.copy(
			name = userName,
			imageTag = userInfo.primaryImage?.tag,
			accessToken = accessToken,
		) ?: AuthenticationStoreUser(
			name = userName,
			imageTag = userInfo.primaryImage?.tag,
			accessToken = accessToken,
		)

		if (!authenticationStore.putUser(server.id, user.id, updatedUser)) {
			error("Unable to store credentials for ${user.name}")
		}
	}
}

class ProvisioningException(val error: ProfileProvisioningError) : Exception()
