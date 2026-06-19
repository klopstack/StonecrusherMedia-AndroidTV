package org.jellyfin.androidtv.util

import android.content.Context
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.startup.PinEntryDialog
import java.security.MessageDigest
import java.util.UUID

/**
 * Utility class for PIN code operations
 */
object PinCodeUtil {
	const val MIN_PIN_LENGTH = 4
	const val MAX_PIN_LENGTH = 10

	/**
	 * Check if PIN protection is enabled for a specific user
	 */
	fun isPinEnabled(context: Context, userId: UUID): Boolean {
		val prefs = UserSettingPreferences(context, userId)
		return prefs[UserSettingPreferences.userPinEnabled] &&
			prefs[UserSettingPreferences.userPinHash].isNotEmpty()
	}

	fun getStoredPinLength(prefs: UserSettingPreferences): Int =
		normalizeStoredPinLength(prefs[UserSettingPreferences.userPinLength])

	fun savePin(prefs: UserSettingPreferences, pin: String) {
		prefs[UserSettingPreferences.userPinHash] = hashPin(pin)
		prefs[UserSettingPreferences.userPinLength] = pin.length
	}

	fun clearPin(prefs: UserSettingPreferences) {
		prefs[UserSettingPreferences.userPinHash] = ""
		prefs[UserSettingPreferences.userPinLength] = 0
	}

	/** Record PIN length after a successful verify when the stored length is missing or invalid. */
	fun recordPinLengthIfUnknown(prefs: UserSettingPreferences, pin: String) {
		val stored = prefs[UserSettingPreferences.userPinLength]
		if (shouldUpdateStoredPinLength(stored, pin.length)) {
			prefs[UserSettingPreferences.userPinLength] = pin.length
		}
	}

	internal fun isValidPinLength(length: Int): Boolean = length in MIN_PIN_LENGTH..MAX_PIN_LENGTH

	internal fun normalizeStoredPinLength(stored: Int): Int =
		if (isValidPinLength(stored)) stored else 0

	internal fun shouldUpdateStoredPinLength(stored: Int, enteredPinLength: Int): Boolean =
		isValidPinLength(enteredPinLength) && !isValidPinLength(stored)

	/**
	 * Verify PIN code for a user by showing a dialog
	 * @param onResult callback with true if PIN is correct, false otherwise
	 */
	fun verifyPin(context: Context, userId: UUID, onResult: (Boolean) -> Unit) {
		val prefs = UserSettingPreferences(context, userId)
		val storedHash = prefs[UserSettingPreferences.userPinHash]

		if (storedHash.isEmpty()) {
			// No PIN set, allow access
			onResult(true)
			return
		}

		PinEntryDialog.show(
			context = context,
			mode = PinEntryDialog.Mode.VERIFY,
			expectedPinLength = getStoredPinLength(prefs),
			onComplete = { pin ->
				if (pin != null) {
					val enteredHash = hashPin(pin)
					val verified = enteredHash == storedHash
					if (verified) recordPinLengthIfUnknown(prefs, pin)
					onResult(verified)
				} else {
					// User cancelled
					onResult(false)
				}
			}
		)
	}

	/**
	 * Hash a PIN code using SHA-256
	 */
	fun hashPin(pin: String): String {
		val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
		return bytes.joinToString("") { "%02x".format(it) }
	}
}
