package org.jellyfin.androidtv.auth.store

import android.content.Context
import android.content.SharedPreferences
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.jellyfin.androidtv.preference.constant.UserSelectBehavior
import org.jellyfin.preference.store.SharedPreferenceStore

class AuthenticationPreferencesMigrationTests : FunSpec({

	fun createPreferences(
		storeVersion: Int,
		autoLoginUserBehavior: String? = null,
		autoLoginUserId: String = "",
		lastUserId: String = "",
	): Pair<AuthenticationPreferences, InMemorySharedPreferences> {
		val sharedPreferences = InMemorySharedPreferences(
			mutableMapOf<String, Any?>(
				SharedPreferenceStore.VERSION.key to storeVersion,
				"auto_login_user_id" to autoLoginUserId,
				"last_user_id" to lastUserId,
			).apply {
				if (autoLoginUserBehavior != null) {
					put("auto_login_user_behavior", autoLoginUserBehavior)
				}
			},
		)
		val context = mockk<Context> {
			every { getSharedPreferences("authentication", Context.MODE_PRIVATE) } returns sharedPreferences
		}
		return AuthenticationPreferences(context) to sharedPreferences
	}

	test("v2 migration resets specific-user auto login and clears last user id") {
		val (preferences, sharedPreferences) = createPreferences(
			storeVersion = 1,
			autoLoginUserBehavior = UserSelectBehavior.SPECIFIC_USER.name,
			autoLoginUserId = "specific-user-id",
			lastUserId = "last-user-id",
		)

		preferences[AuthenticationPreferences.autoLoginUserBehavior] shouldBe UserSelectBehavior.DISABLED
		preferences[AuthenticationPreferences.autoLoginUserId] shouldBe ""
		preferences[AuthenticationPreferences.lastUserId] shouldBe ""
		sharedPreferences.getInt(SharedPreferenceStore.VERSION.key, -1) shouldBe 2
	}

	test("v2 migration preserves last user id for non-specific-user auto login") {
		val (preferences, sharedPreferences) = createPreferences(
			storeVersion = 1,
			autoLoginUserBehavior = UserSelectBehavior.LAST_USER.name,
			lastUserId = "last-user-id",
		)

		preferences[AuthenticationPreferences.autoLoginUserBehavior] shouldBe UserSelectBehavior.LAST_USER
		preferences[AuthenticationPreferences.lastUserId] shouldBe "last-user-id"
		sharedPreferences.getInt(SharedPreferenceStore.VERSION.key, -1) shouldBe 2
	}
})

private class InMemorySharedPreferences(
	initialValues: MutableMap<String, Any?>,
) : SharedPreferences {
	private val values = initialValues

	override fun getAll(): Map<String, *> = values.toMap()

	override fun getString(key: String, defValue: String?) =
		values[key] as? String ?: defValue

	override fun getStringSet(key: String, defValue: MutableSet<String>?) = defValue

	override fun getInt(key: String, defValue: Int) =
		values[key] as? Int ?: defValue

	override fun getLong(key: String, defValue: Long) =
		values[key] as? Long ?: defValue

	override fun getFloat(key: String, defValue: Float) =
		values[key] as? Float ?: defValue

	override fun getBoolean(key: String, defValue: Boolean) =
		values[key] as? Boolean ?: defValue

	override fun contains(key: String) = key in values

	override fun edit(): SharedPreferences.Editor = Editor()

	override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

	override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

	private inner class Editor : SharedPreferences.Editor {
		private val pending = linkedMapOf<String, Any>()
		private var clearAll = false

		override fun putString(key: String, value: String?) = apply { pending[key] = value ?: "" }

		override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values ?: emptySet<String>() }

		override fun putInt(key: String, value: Int) = apply { pending[key] = value }

		override fun putLong(key: String, value: Long) = apply { pending[key] = value }

		override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

		override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

		override fun remove(key: String) = apply { pending[key] = RemovedMarker }

		override fun clear() = apply {
			clearAll = true
			pending.clear()
		}

		override fun commit(): Boolean {
			applyChanges()
			return true
		}

		override fun apply() = applyChanges()

		private fun applyChanges() {
			if (clearAll) values.clear()
			pending.forEach { (key, value) ->
				if (value === RemovedMarker) values.remove(key)
				else values[key] = value
			}
			pending.clear()
			clearAll = false
		}
	}

	private object RemovedMarker
}
