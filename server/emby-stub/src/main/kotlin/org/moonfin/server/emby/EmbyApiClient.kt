package org.moonfin.server.emby

data class EmbyUserInfo(
	val id: String,
	val name: String?,
	val serverId: String?,
	val primaryImageTag: String?,
	val hasPassword: Boolean?,
	val hasConfiguredPassword: Boolean?,
)

data class EmbyAuthResult(
	val accessToken: String?,
	val user: EmbyUserInfo?,
	val serverId: String?,
)

class EmbyApiClient(
	private val appVersion: String,
	private val clientName: String,
	val deviceId: String,
	private val deviceName: String,
) {
	var baseUrl: String = ""
		private set
	var accessToken: String? = null
		private set
	var userId: String? = null
		private set

	val isConfigured: Boolean get() = false

	fun configure(baseUrl: String, accessToken: String?, userId: String?) {
		this.baseUrl = baseUrl
		this.accessToken = accessToken
		this.userId = userId
	}

	fun reset() = configure("", null, null)

	suspend fun validateCurrentUser(): EmbyUserInfo =
		error("Emby support is disabled in this build")

	suspend fun validateToken(): Boolean = false

	suspend fun authenticateByName(username: String, password: String): EmbyAuthResult =
		error("Emby support is disabled in this build")

	suspend fun postCapabilities(
		playableMediaTypes: String,
		supportedCommands: String,
		supportsMediaControl: Boolean,
	) = Unit

	suspend fun logout() = Unit

	suspend fun getPublicUsers(): List<EmbyUserInfo> = emptyList()
}
