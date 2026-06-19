package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinCodeUtilTests : FunSpec({
	test("isValidPinLength accepts 4 through 10 only") {
		PinCodeUtil.isValidPinLength(3) shouldBe false
		PinCodeUtil.isValidPinLength(4) shouldBe true
		PinCodeUtil.isValidPinLength(10) shouldBe true
		PinCodeUtil.isValidPinLength(11) shouldBe false
	}

	test("normalizeStoredPinLength returns valid lengths unchanged") {
		PinCodeUtil.normalizeStoredPinLength(6) shouldBe 6
		PinCodeUtil.normalizeStoredPinLength(4) shouldBe 4
	}

	test("normalizeStoredPinLength treats missing and invalid lengths as unknown") {
		PinCodeUtil.normalizeStoredPinLength(0) shouldBe 0
		PinCodeUtil.normalizeStoredPinLength(3) shouldBe 0
		PinCodeUtil.normalizeStoredPinLength(42) shouldBe 0
	}

	test("shouldUpdateStoredPinLength overwrites missing or invalid stored lengths") {
		PinCodeUtil.shouldUpdateStoredPinLength(stored = 0, enteredPinLength = 6) shouldBe true
		PinCodeUtil.shouldUpdateStoredPinLength(stored = 3, enteredPinLength = 6) shouldBe true
		PinCodeUtil.shouldUpdateStoredPinLength(stored = 42, enteredPinLength = 4) shouldBe true
	}

	test("shouldUpdateStoredPinLength preserves valid stored lengths") {
		PinCodeUtil.shouldUpdateStoredPinLength(stored = 6, enteredPinLength = 4) shouldBe false
		PinCodeUtil.shouldUpdateStoredPinLength(stored = 8, enteredPinLength = 8) shouldBe false
	}

	test("shouldUpdateStoredPinLength rejects invalid entered lengths") {
		PinCodeUtil.shouldUpdateStoredPinLength(stored = 0, enteredPinLength = 3) shouldBe false
		PinCodeUtil.shouldUpdateStoredPinLength(stored = 3, enteredPinLength = 11) shouldBe false
	}
})
