package org.jellyfin.androidtv.auth.repository

import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.PublicUser

object ProfileProvisioningHelper {
	data class ProvisioningPlan(
		val toProvision: List<PublicUser>,
		val skipped: List<String>,
	)

	fun planProvisioning(
		publicUsers: List<PublicUser>,
		storedUsers: List<PrivateUser>,
	): ProvisioningPlan {
		val storedWithToken = storedUsers
			.filter { !it.accessToken.isNullOrBlank() }
			.map { it.id }
			.toSet()

		val toProvision = publicUsers.filter { it.id !in storedWithToken }
		val skipped = publicUsers
			.filter { it.id in storedWithToken }
			.map { it.name }

		return ProvisioningPlan(toProvision = toProvision, skipped = skipped)
	}
}
