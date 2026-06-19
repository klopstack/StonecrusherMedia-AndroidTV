package org.jellyfin.androidtv.data.service.jellyseerr

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.Base64

class JellyseerrHttpClientTests : FunSpec({
	val context = createFakeContext()

	afterEach {
		runBlocking { clearJellyseerrCookiesForUsers(context, "test-user") }
	}

	test("getRequests fails on non-2xx via requireSuccessStatus") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"error":"unauthorized"}""", code = 401)
				val client = createJellyseerrClient(context, server)

				val result = client.getRequests()

				result.isFailure.shouldBeTrue()
				result.exceptionOrNull()?.message shouldContain "requests: HTTP 401"
				server.takeRequest().path shouldBe "/api/v1/request?skip=0&take=50"
			}
		}
	}

	test("getRequests succeeds on 2xx and sends direct-mode auth header") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"pageInfo":null,"results":[]}""")
				val client = createJellyseerrClient(context, server, apiKey = "secret-key")

				val result = client.getRequests()

				result.isSuccess.shouldBeTrue()
				val request = server.takeRequest()
				request.path shouldBe "/api/v1/request?skip=0&take=50"
				request.getHeader("X-Api-Key") shouldBe "secret-key"
			}
		}
	}

	test("proxy mode builds Moonfin API path and MediaBrowser auth header") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"pageInfo":null,"results":[]}""")
				val client = createJellyseerrClient(context, server, apiKey = "")
				client.proxyConfig = MoonfinProxyConfig(
					jellyfinBaseUrl = server.baseUrl(),
					jellyfinToken = "jellyfin-token",
				)

				val result = client.getRequests()

				result.isSuccess.shouldBeTrue()
				val request = server.takeRequest()
				request.path shouldBe "/Moonfin/Jellyseerr/Api/request?skip=0&take=50"
				request.getHeader("Authorization") shouldBe "MediaBrowser Token=\"jellyfin-token\""
				request.getHeader("X-Api-Key").shouldBe(null)
			}
		}
	}

	test("proxy mode unwraps FileContents envelope before deserialization") {
		runBlocking {
			MockWebServer().use { server ->
				val innerJson = """{"pageInfo":null,"results":[]}"""
				val encoded = Base64.getEncoder().encodeToString(innerJson.toByteArray(Charsets.UTF_8))
				server.enqueueJson("""{"FileContents":"$encoded","ContentType":"application/json"}""")
				val client = createJellyseerrClient(context, server, apiKey = "")
				client.proxyConfig = MoonfinProxyConfig(
					jellyfinBaseUrl = server.baseUrl(),
					jellyfinToken = "jellyfin-token",
				)

				val result = client.getRequests()

				result.isSuccess.shouldBeTrue()
				result.getOrNull()?.results shouldBe emptyList()
			}
		}
	}

	test("getStatus does not enforce requireSuccessStatus on HTTP errors") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"error":"unauthorized"}""", code = 401)
				val client = createJellyseerrClient(context, server)

				val result = client.getStatus()

				// Known gap: error JSON deserializes into an empty DTO instead of failing.
				result.isSuccess.shouldBeTrue()
				result.getOrNull()?.appData shouldBe null
			}
		}
	}

	test("testConnection returns false for non-2xx without throwing") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"error":"service unavailable"}""", code = 503)
				val client = createJellyseerrClient(context, server)

				val result = client.testConnection()

				result.isSuccess.shouldBeTrue()
				result.getOrNull().shouldBeFalse()
			}
		}
	}

	test("createRequest sends CSRF headers in direct mode") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueue(
					MockResponse()
						.addHeader("Set-Cookie", "XSRF-TOKEN=csrf-token-value; Path=/")
						.setBody("{}"),
				)
				server.enqueueJson("""{"id":1,"status":1,"type":"movie"}""")
				val client = createJellyseerrClient(context, server, apiKey = "secret-key")

				val result = client.createRequest(mediaId = 42, mediaType = "movie")

				result.isSuccess.shouldBeTrue()
				result.getOrNull()?.id shouldBe 1

				val csrfRequest = server.takeRequest()
				csrfRequest.method shouldBe "GET"
				csrfRequest.path shouldBe "/api/v1/request"

				val createRequest = server.takeRequest()
				createRequest.method shouldBe "POST"
				createRequest.path shouldBe "/api/v1/request"
				createRequest.getHeader("X-CSRF-Token") shouldBe "csrf-token-value"
				createRequest.getHeader("X-XSRF-TOKEN") shouldBe "csrf-token-value"
			}
		}
	}

	test("getMoonfinStatus uses Moonfin path prefix") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"enabled":true,"authenticated":true}""")
				val client = createJellyseerrClient(context, server, apiKey = "")
				client.proxyConfig = MoonfinProxyConfig(
					jellyfinBaseUrl = server.baseUrl(),
					jellyfinToken = "jellyfin-token",
				)

				val result = client.getMoonfinStatus()

				result.isSuccess.shouldBeTrue()
				result.getOrNull()?.authenticated.shouldBeTrue()
				server.takeRequest().path shouldBe "/Moonfin/Jellyseerr/Status"
			}
		}
	}
})
