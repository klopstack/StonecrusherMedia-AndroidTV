package org.jellyfin.androidtv.data.service.jellyseerr

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.ConcurrentHashMap

internal fun createFakeContext(): Context {
	val prefsByName = ConcurrentHashMap<String, SharedPreferences>()
	val context = mockk<Context>(relaxed = true)
	every { context.applicationContext } returns context
	every { context.getSharedPreferences(any(), any()) } answers {
		val name = firstArg<String>()
		prefsByName.getOrPut(name) { InMemorySharedPreferences() }
	}
	return context
}

internal class InMemorySharedPreferences : SharedPreferences {
	private val data = ConcurrentHashMap<String, Any?>()

	override fun getAll(): Map<String, *> = data.toMap()

	override fun getString(key: String, defValue: String?) = data[key] as? String ?: defValue

	override fun getStringSet(key: String, defValues: MutableSet<String>?) =
		(data[key] as? Set<String>)?.toMutableSet() ?: defValues

	override fun getInt(key: String, defValue: Int) = data[key] as? Int ?: defValue

	override fun getLong(key: String, defValue: Long) = data[key] as? Long ?: defValue

	override fun getFloat(key: String, defValue: Float) = data[key] as? Float ?: defValue

	override fun getBoolean(key: String, defValue: Boolean) = data[key] as? Boolean ?: defValue

	override fun contains(key: String) = data.containsKey(key)

	override fun edit(): SharedPreferences.Editor = Editor(data)

	override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

	override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

	private class Editor(
		private val data: ConcurrentHashMap<String, Any?>,
	) : SharedPreferences.Editor {
		private val pending = mutableMapOf<String, Any?>()
		private var clearRequested = false

		override fun putString(key: String, value: String?) = apply { pending[key] = value }

		override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values?.toSet() }

		override fun putInt(key: String, value: Int) = apply { pending[key] = value }

		override fun putLong(key: String, value: Long) = apply { pending[key] = value }

		override fun putFloat(key: String, value: Float) = apply { pending[key] = value }

		override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }

		override fun remove(key: String) = apply { pending[key] = null }

		override fun clear() = apply { clearRequested = true }

		override fun commit(): Boolean {
			apply()
			return true
		}

		override fun apply() {
			if (clearRequested) {
				data.clear()
				clearRequested = false
			}
			pending.forEach { (key, value) ->
				if (value == null) data.remove(key) else data[key] = value
			}
			pending.clear()
		}
	}
}

internal fun MockWebServer.enqueueJson(body: String, code: Int = 200) {
	enqueue(
		MockResponse()
			.setResponseCode(code)
			.addHeader("Content-Type", "application/json")
			.setBody(body),
	)
}

internal fun MockWebServer.baseUrl(): String = url("/").toString().trimEnd('/')

internal suspend fun clearJellyseerrCookiesForUsers(context: Context, vararg userIds: String) {
	for (userId in userIds) {
		PersistentCookiesStorage(context, userId).clearAll()
	}
}

internal fun createJellyseerrClient(
	context: Context,
	server: MockWebServer,
	apiKey: String = "test-api-key",
	userId: String = "test-user",
): JellyseerrHttpClient {
	val client = JellyseerrHttpClient(context, server.baseUrl(), apiKey)
	JellyseerrHttpClient.switchCookieStorage(userId)
	return client
}
