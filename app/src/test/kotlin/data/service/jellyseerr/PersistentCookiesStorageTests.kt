package org.jellyfin.androidtv.data.service.jellyseerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.Url
import android.content.Context
import kotlinx.coroutines.runBlocking

class PersistentCookiesStorageTests : FunSpec({
	val context = createFakeContext()
	val requestUrl = Url("http://jellyseerr.example/api/v1/request")

	test("persists and reloads cookies across storage instances") {
		runBlocking {
			val storage = PersistentCookiesStorage(context, "cookie-user-1")
			storage.addCookie(
				requestUrl,
				Cookie(name = "session", value = "abc123", encoding = CookieEncoding.RAW),
			)

			val reloaded = PersistentCookiesStorage(context, "cookie-user-1")
			val cookies = reloaded.get(requestUrl)

			cookies shouldHaveSize 1
			cookies.single().name shouldBe "session"
			cookies.single().value shouldBe "abc123"
		}
	}

	test("preserves URL-encoded connect.sid values with RAW encoding") {
		runBlocking {
			val encodedSid = "s%3Aexpress-session-id%2Esignature"
			val storage = PersistentCookiesStorage(context, "cookie-user-raw")
			storage.addCookie(
				requestUrl,
				Cookie(name = "connect.sid", value = encodedSid, encoding = CookieEncoding.RAW),
			)

			val reloaded = PersistentCookiesStorage(context, "cookie-user-raw")
			val cookie = reloaded.get(requestUrl).single()

			cookie.value shouldBe encodedSid
			cookie.encoding shouldBe CookieEncoding.RAW
		}
	}

	test("clearAll removes persisted cookies before new instance loads") {
		runBlocking {
			val storage = PersistentCookiesStorage(context, "cookie-user-clear")
			storage.addCookie(
				requestUrl,
				Cookie(name = "session", value = "to-clear", encoding = CookieEncoding.RAW),
			)
			storage.clearAll()

			val reloaded = PersistentCookiesStorage(context, "cookie-user-clear")
			reloaded.get(requestUrl).shouldBeEmpty()
		}
	}

	test("persists cookie values containing pipe and percent-encoded pipe sequences") {
		runBlocking {
			val trickyValue = "a|b%7Cc"
			val storage = PersistentCookiesStorage(context, "cookie-user-tricky")
			storage.addCookie(
				requestUrl,
				Cookie(name = "session", value = trickyValue, encoding = CookieEncoding.RAW),
			)

			val reloaded = PersistentCookiesStorage(context, "cookie-user-tricky")
			reloaded.get(requestUrl).single().value shouldBe trickyValue
		}
	}

	test("loads legacy pipe-delimited cookies written before v2 encoding") {
		runBlocking {
			val legacySerialized = "session|legacy%7Cvalue|-|/|0|0|false|false"
			context.getSharedPreferences("jellyseerr_cookies_legacy-user", Context.MODE_PRIVATE)
				.edit()
				.putString("session_jellyseerr.example", legacySerialized)
				.commit()

			val storage = PersistentCookiesStorage(context, "legacy-user")
			storage.get(requestUrl).single().value shouldBe "legacy|value"
		}
	}

	test("DelegatingCookiesStorage isolates cookies per Jellyfin user") {
		runBlocking {
			val delegating = DelegatingCookiesStorage(context)
			delegating.switchToUser("user-a")
			delegating.addCookie(
				requestUrl,
				Cookie(name = "session", value = "user-a-session", encoding = CookieEncoding.RAW),
			)

			delegating.switchToUser("user-b")
			delegating.get(requestUrl).shouldBeEmpty()

			delegating.addCookie(
				requestUrl,
				Cookie(name = "session", value = "user-b-session", encoding = CookieEncoding.RAW),
			)

			delegating.switchToUser("user-a")
			delegating.get(requestUrl).single().value shouldBe "user-a-session"
		}
	}
})
