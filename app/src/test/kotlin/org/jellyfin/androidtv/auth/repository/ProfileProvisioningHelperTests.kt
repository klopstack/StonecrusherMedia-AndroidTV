package org.jellyfin.androidtv.auth.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.androidtv.auth.model.PrivateUser
import org.jellyfin.androidtv.auth.model.PublicUser
import java.util.UUID

class ProfileProvisioningHelperTests : FunSpec({
	val serverId = UUID.randomUUID()

	fun publicUser(id: UUID, name: String) = PublicUser(
		id = id,
		serverId = serverId,
		name = name,
		accessToken = null,
		imageTag = null,
	)

	fun privateUser(id: UUID, name: String, accessToken: String?) = PrivateUser(
		id = id,
		serverId = serverId,
		name = name,
		accessToken = accessToken,
		imageTag = null,
		lastUsed = 0,
	)

	test("planProvisioning returns users without stored tokens") {
		val alice = publicUser(UUID.randomUUID(), "Alice")
		val bob = publicUser(UUID.randomUUID(), "Bob")

		val plan = ProfileProvisioningHelper.planProvisioning(
			publicUsers = listOf(alice, bob),
			storedUsers = listOf(privateUser(alice.id, "Alice", "token")),
		)

		plan.toProvision.map { it.name } shouldContainExactly listOf("Bob")
		plan.skipped shouldContainExactly listOf("Alice")
	}

	test("planProvisioning treats blank tokens as missing") {
		val alice = publicUser(UUID.randomUUID(), "Alice")

		val plan = ProfileProvisioningHelper.planProvisioning(
			publicUsers = listOf(alice),
			storedUsers = listOf(privateUser(alice.id, "Alice", "   ")),
		)

		plan.toProvision.map { it.name } shouldBe listOf("Alice")
		plan.skipped shouldBe emptyList()
	}

	test("planProvisioning skips users not on public list") {
		val alice = publicUser(UUID.randomUUID(), "Alice")
		val hiddenId = UUID.randomUUID()

		val plan = ProfileProvisioningHelper.planProvisioning(
			publicUsers = listOf(alice),
			storedUsers = listOf(privateUser(hiddenId, "Hidden", "token")),
		)

		plan.toProvision.map { it.name } shouldContainExactly listOf("Alice")
		plan.skipped shouldBe emptyList()
	}
})
