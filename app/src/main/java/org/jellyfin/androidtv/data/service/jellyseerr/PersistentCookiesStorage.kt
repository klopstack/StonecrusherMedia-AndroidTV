package org.jellyfin.androidtv.data.service.jellyseerr

import android.content.Context
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.util.Base64

/**
 * Delegating cookie storage that can switch between different user storages
 * This allows the HTTP client to maintain the same CookiesStorage reference
 * while the underlying storage changes when users switch
 */
class DelegatingCookiesStorage(private val context: Context) : CookiesStorage {
	private var delegate: PersistentCookiesStorage = PersistentCookiesStorage(context, null)
	private var currentUserId: String? = null
	
	fun switchToUser(userId: String) {
		if (currentUserId != userId) {
			currentUserId = userId
			delegate = PersistentCookiesStorage(context, userId)
		}
	}
	
	override suspend fun get(requestUrl: Url): List<Cookie> = delegate.get(requestUrl)
	
	override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = delegate.addCookie(requestUrl, cookie)
	
	override fun close() = delegate.close()
	
	suspend fun clearAll() = delegate.clearAll()
}

/**
 * Persistent cookie storage that saves cookies to SharedPreferences
 * This allows cookies to survive app restarts
 * Each Jellyfin user gets their own cookie storage to maintain separate Jellyseerr sessions
 */
class PersistentCookiesStorage(context: Context, userId: String? = null) : CookiesStorage {
	private val prefsKey = if (userId != null) "jellyseerr_cookies_$userId" else "jellyseerr_cookies"
	private val preferences = context.getSharedPreferences(prefsKey, Context.MODE_PRIVATE)
	private val mutex = Mutex()
	private val cookies = mutableMapOf<String, Cookie>()

	init {
		// Load cookies from preferences on initialization
		loadCookies()
	}

	private fun loadCookies() {
		try {
			preferences.all.forEach { (key, value) ->
				if (value is String) {
					val cookie = deserializeCookie(value)
					if (cookie != null && !isExpired(cookie)) {
						cookies[key] = cookie
					}
				}
			}
		} catch (e: Exception) {
			Timber.e(e, "PersistentCookiesStorage: Error loading cookies")
		}
	}

	private fun saveCookies() {
		try {
			preferences.edit().apply {
				clear()
				cookies.forEach { (key, cookie) ->
					putString(key, serializeCookie(cookie))
				}
				apply()
			}
		} catch (e: Exception) {
			Timber.e(e, "PersistentCookiesStorage: Error saving cookies")
		}
	}

	override suspend fun get(requestUrl: Url): List<Cookie> = mutex.withLock {
		// Remove expired cookies
		val expiredKeys = cookies.filter { (_, cookie) ->
			isExpired(cookie)
		}.keys
		
		if (expiredKeys.isNotEmpty()) {
			expiredKeys.forEach { cookies.remove(it) }
			saveCookies()
		}

		// Return cookies that match the URL
		cookies.values.filter { cookie ->
			matchesDomain(cookie, requestUrl) && matchesPath(cookie, requestUrl)
		}
	}

	override suspend fun addCookie(requestUrl: Url, cookie: Cookie): Unit = mutex.withLock {
		val key = "${cookie.name}_${requestUrl.host}"
		// Force RAW encoding to prevent Ktor from double-encoding cookie values.
		// Jellyseerr (Express.js) sends connect.sid values already URL-encoded (e.g. s%3A...).
		// Without RAW encoding, Ktor re-encodes on send, turning %3A into %253A,
		// which Jellyseerr doesn't recognize → 401 Unauthorized on API calls.
		val rawCookie = cookie.copy(encoding = CookieEncoding.RAW)
		cookies[key] = rawCookie
		saveCookies()
	}

	override fun close() {
		try {
			saveCookies()
		} catch (e: Exception) {
			Timber.e(e, "PersistentCookiesStorage: Error during close")
		}
	}

	/**
	 * Clear all stored cookies
	 */
	suspend fun clearAll() = mutex.withLock {
		cookies.clear()
		// Use commit() (synchronous) instead of apply() (async) to ensure
		// cookies are removed from disk before any new storage instance can load them
		preferences.edit().clear().commit()
	}

	private fun isExpired(cookie: Cookie): Boolean {
		val expires = cookie.expires ?: return false
		return expires.timestamp < GMTDate().timestamp
	}

	private fun matchesDomain(cookie: Cookie, url: Url): Boolean {
		val domain = cookie.domain?.lowercase() ?: return true
		val host = url.host.lowercase()
		
		return if (domain.startsWith(".")) {
			// Domain cookie: matches host and all subdomains
			host == domain.substring(1) || host.endsWith(domain)
		} else {
			// Exact match
			host == domain
		}
	}

	private fun matchesPath(cookie: Cookie, url: Url): Boolean {
		val cookiePath = cookie.path ?: "/"
		val urlPath = url.encodedPath
		return urlPath.startsWith(cookiePath)
	}

	private companion object {
		const val SERIALIZATION_VERSION_V2 = "v2"
	}

	private fun encodeCookieFieldV2(value: String) =
		Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

	private fun decodeCookieFieldV2(encoded: String) =
		String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)

	private fun decodeLegacyOptionalField(value: String) = when (value) {
		"-", "" -> null
		else -> value.replace("%7C", "|")
	}

	private fun serializeCookie(cookie: Cookie): String {
		return buildString {
			append(SERIALIZATION_VERSION_V2)
			append("|")
			append(encodeCookieFieldV2(cookie.name))
			append("|")
			append(encodeCookieFieldV2(cookie.value))
			append("|")
			append(encodeCookieFieldV2(cookie.domain.orEmpty()))
			append("|")
			append(encodeCookieFieldV2(cookie.path.orEmpty()))
			append("|")
			append(cookie.expires?.timestamp ?: 0)
			append("|")
			append(cookie.maxAge)
			append("|")
			append(cookie.secure)
			append("|")
			append(cookie.httpOnly)
		}
	}

	private fun deserializeCookie(data: String): Cookie? {
		return try {
			if (data.startsWith("$SERIALIZATION_VERSION_V2|")) {
				deserializeCookieV2(data)
			} else {
				deserializeCookieLegacy(data)
			}
		} catch (e: Exception) {
			Timber.e(e, "PersistentCookiesStorage: Error deserializing cookie")
			null
		}
	}

	private fun deserializeCookieV2(data: String): Cookie? {
		val parts = data.split("|")
		if (parts.size < 9) return null

		return Cookie(
			name = decodeCookieFieldV2(parts[1]),
			value = decodeCookieFieldV2(parts[2]),
			encoding = CookieEncoding.RAW,
			domain = decodeCookieFieldV2(parts[3]).takeIf { it.isNotEmpty() },
			path = decodeCookieFieldV2(parts[4]).takeIf { it.isNotEmpty() },
			expires = parts[5].toLongOrNull()?.takeIf { it > 0L }?.let { GMTDate(it) },
			maxAge = parts[6].toIntOrNull() ?: 0,
			secure = parts[7].toBoolean(),
			httpOnly = parts[8].toBoolean(),
		)
	}

	private fun deserializeCookieLegacy(data: String): Cookie? {
		val parts = data.split("|")
		if (parts.size < 8) return null

		return Cookie(
			name = parts[0],
			value = parts[1].replace("%7C", "|"),
			encoding = CookieEncoding.RAW,
			domain = decodeLegacyOptionalField(parts[2]),
			path = decodeLegacyOptionalField(parts[3]),
			expires = parts[4].toLongOrNull()?.takeIf { it > 0L }?.let { GMTDate(it) },
			maxAge = parts[5].toIntOrNull() ?: 0,
			secure = parts[6].toBoolean(),
			httpOnly = parts[7].toBoolean(),
		)
	}
}
