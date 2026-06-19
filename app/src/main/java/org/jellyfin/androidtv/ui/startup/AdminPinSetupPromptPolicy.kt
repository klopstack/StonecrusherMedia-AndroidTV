package org.jellyfin.androidtv.ui.startup

object AdminPinSetupPromptPolicy {
	fun shouldPrompt(
		isAdministrator: Boolean,
		userPinHash: String,
		userPinEnabled: Boolean,
		userPinSetupDeclined: Boolean,
	): Boolean {
		if (!isAdministrator) return false
		if (userPinSetupDeclined) return false

		val hasPinConfigured = userPinHash.isNotBlank() && userPinEnabled
		return !hasPinConfigured
	}
}
