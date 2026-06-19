package org.jellyfin.androidtv.data.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class UpdateCheckerServiceSignatureTests : FunSpec({
	test("signaturesMatch returns true when certificates match") {
		val certA = byteArrayOf(1, 2, 3)
		val certB = byteArrayOf(4, 5, 6)

		UpdateCheckerService.signaturesMatch(
			installed = listOf(certA, certB),
			downloaded = listOf(certB),
		) shouldBe true
	}

	test("signaturesMatch returns false when no certificates match") {
		UpdateCheckerService.signaturesMatch(
			installed = listOf(byteArrayOf(1, 2, 3)),
			downloaded = listOf(byteArrayOf(9, 8, 7)),
		) shouldBe false
	}

	test("signaturesMatch returns false for empty downloaded list") {
		UpdateCheckerService.signaturesMatch(
			installed = listOf(byteArrayOf(1)),
			downloaded = emptyList(),
		) shouldBe false
	}
})
