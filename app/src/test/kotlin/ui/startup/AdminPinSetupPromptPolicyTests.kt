package org.jellyfin.androidtv.ui.startup

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AdminPinSetupPromptPolicyTests : FunSpec({
	test("prompts for administrator with no PIN hash and not declined") {
		AdminPinSetupPromptPolicy.shouldPrompt(
			isAdministrator = true,
			userPinHash = "",
			userPinEnabled = false,
			userPinSetupDeclined = false,
		) shouldBe true
	}

	test("prompts for administrator with PIN hash but disabled PIN") {
		AdminPinSetupPromptPolicy.shouldPrompt(
			isAdministrator = true,
			userPinHash = "hashed",
			userPinEnabled = false,
			userPinSetupDeclined = false,
		) shouldBe true
	}

	test("does not prompt when administrator already has enabled PIN") {
		AdminPinSetupPromptPolicy.shouldPrompt(
			isAdministrator = true,
			userPinHash = "hashed",
			userPinEnabled = true,
			userPinSetupDeclined = false,
		) shouldBe false
	}

	test("does not prompt when administrator declined setup") {
		AdminPinSetupPromptPolicy.shouldPrompt(
			isAdministrator = true,
			userPinHash = "",
			userPinEnabled = false,
			userPinSetupDeclined = true,
		) shouldBe false
	}

	test("does not prompt for non-administrator users") {
		AdminPinSetupPromptPolicy.shouldPrompt(
			isAdministrator = false,
			userPinHash = "",
			userPinEnabled = false,
			userPinSetupDeclined = false,
		) shouldBe false
	}
})
