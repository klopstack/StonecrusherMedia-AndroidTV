package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.service.jellyseerr.JellyseerrHttpClient
import org.jellyfin.androidtv.data.service.jellyseerr.baseUrl
import org.jellyfin.androidtv.data.service.jellyseerr.clearJellyseerrCookiesForUsers
import org.jellyfin.androidtv.data.service.jellyseerr.createFakeContext
import org.jellyfin.androidtv.data.service.jellyseerr.enqueueJson
import org.jellyfin.androidtv.preference.JellyseerrPreferences
import org.jellyfin.sdk.model.api.UserDto
import java.util.UUID

class JellyseerrRepositoryTests : FunSpec({
	val context = createFakeContext()
	val globalPreferences = JellyseerrPreferences(context)

	fun user(id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")) = UserDto(
		id = id,
		name = "Test User",
		hasPassword = false,
		hasConfiguredPassword = false,
		hasConfiguredEasyPassword = false,
	)

	fun createRepository(currentUser: UserDto?): JellyseerrRepositoryImpl {
		val userRepository = mockk<UserRepository>()
		every { userRepository.currentUser } returns MutableStateFlow(currentUser)
		return JellyseerrRepositoryImpl(context, globalPreferences, userRepository)
	}

	fun configureUserPrefs(userId: UUID, serverUrl: String) {
		val prefs = JellyseerrPreferences.migrateToUserPreferences(context, userId.toString())
		prefs[JellyseerrPreferences.enabled] = true
		prefs[JellyseerrPreferences.serverUrl] = serverUrl
		prefs[JellyseerrPreferences.apiKey] = "api-key"
		prefs[JellyseerrPreferences.authMethod] = "apikey"
	}

	suspend fun JellyseerrRepositoryImpl.autoInitialize(serverUrl: String, userId: UUID) {
		configureUserPrefs(userId, serverUrl)
		JellyseerrHttpClient.initializeCookieStorage(context)
		ensureInitialized()
	}

	afterEach {
		runBlocking {
			clearJellyseerrCookiesForUsers(
				context,
				"00000000-0000-0000-0000-000000000001",
				"00000000-0000-0000-0000-000000000002",
			)
		}
	}

	test("testConnection delegates to initialized HTTP client") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"appData":{"version":"1.0","initialized":true}}""")
				val currentUser = user()
				val repository = createRepository(currentUser)
				repository.autoInitialize(server.baseUrl(), currentUser.id)

				val result = repository.testConnection()

				result.isSuccess.shouldBeTrue()
				result.getOrNull().shouldBeTrue()
				server.takeRequest().path shouldBe "/api/v1/status"
			}
		}
	}

	test("isSessionValidCached avoids repeat auth/me calls within cache window") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"id":1,"email":"user@example.com"}""")
				val currentUser = user()
				val repository = createRepository(currentUser)
				repository.autoInitialize(server.baseUrl(), currentUser.id)

				repository.isSessionValidCached().shouldBeTrue()
				repository.isSessionValidCached().shouldBeTrue()

				server.requestCount shouldBe 1
			}
		}
	}

	test("isSessionValid returns false when auth/me fails without throwing") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"error":"unauthorized"}""", code = 401)
				val currentUser = user()
				val repository = createRepository(currentUser)
				repository.autoInitialize(server.baseUrl(), currentUser.id)

				val result = repository.isSessionValid()

				result.isSuccess.shouldBeTrue()
				result.getOrNull().shouldBeFalse()
			}
		}
	}

	test("ensureInitialized invalidates session cache when Jellyfin user changes") {
		runBlocking {
			MockWebServer().use { server ->
				server.enqueueJson("""{"id":1,"email":"a@example.com"}""")
				server.enqueueJson("""{"id":2,"email":"b@example.com"}""")

				val userA = user(UUID.fromString("00000000-0000-0000-0000-000000000001"))
				val userB = user(UUID.fromString("00000000-0000-0000-0000-000000000002"))
				configureUserPrefs(userA.id, server.baseUrl())
				configureUserPrefs(userB.id, server.baseUrl())

				val userFlow = MutableStateFlow<UserDto?>(userA)
				val userRepository = mockk<UserRepository>()
				every { userRepository.currentUser } returns userFlow

				val repository = JellyseerrRepositoryImpl(context, globalPreferences, userRepository)
				JellyseerrHttpClient.initializeCookieStorage(context)
				repository.ensureInitialized()
				repository.isSessionValidCached().shouldBeTrue()

				userFlow.value = userB
				repository.ensureInitialized()
				repository.isSessionValidCached().shouldBeTrue()

				server.requestCount shouldBe 2
				server.takeRequest().path shouldBe "/api/v1/auth/me"
				server.takeRequest().path shouldBe "/api/v1/auth/me"
			}
		}
	}
})
