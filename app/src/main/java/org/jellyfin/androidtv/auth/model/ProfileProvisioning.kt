package org.jellyfin.androidtv.auth.model

data class ProfileProvisioningFailure(
	val userName: String,
	val reason: String,
)

data class ProfileProvisioningSummary(
	val provisioned: List<String>,
	val skipped: List<String>,
	val failed: List<ProfileProvisioningFailure>,
) {
	val isSuccess: Boolean get() = failed.isEmpty()
}

sealed class ProfileProvisioningError {
	data object NotJellyfinServer : ProfileProvisioningError()
	data object NoAdminSession : ProfileProvisioningError()
	data object QuickConnectDisabled : ProfileProvisioningError()
	data object NotAdministrator : ProfileProvisioningError()
}
