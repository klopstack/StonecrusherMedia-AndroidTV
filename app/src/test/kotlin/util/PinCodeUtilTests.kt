package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PinCodeUtilTests : FunSpec({
	test("valid PIN lengths are 4 through 10") {
		PinCodeUtil.isValidPinLength(3) shouldBe false
		PinCodeUtil.isValidPinLength(4) shouldBe true
		PinCodeUtil.isValidPinLength(10) shouldBe true
		PinCodeUtil.isValidPinLength(11) shouldBe false
	}

	test("out-of-range stored lengths are treated as unknown for auto-submit") {
		listOf(0, 3, 42).forEach { length ->
			PinCodeUtil.isValidPinLength(length) shouldBe false
		}
	}
})
