package org.jellyfin.androidtv.data.repository

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.util.AccessScheduleEvaluator
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.AccessSchedule
import org.jellyfin.sdk.model.api.DynamicDayOfWeek
import org.jellyfin.sdk.model.api.UserPolicy
import org.jellyfin.sdk.model.api.SyncPlayUserAccessType
import java.time.LocalDateTime
import java.util.UUID

class AccessScheduleRepositoryTests : FunSpec({
	val serverId = UUID.fromString("00000000-0000-0000-0000-000000000010")
	val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")

	fun schedule(day: DynamicDayOfWeek, start: Double, end: Double) = AccessSchedule(
		dayOfWeek = day,
		startHour = start,
		endHour = end,
		id = 1,
		userId = userId,
	)

	fun policy(schedules: List<AccessSchedule>) = UserPolicy(
		isAdministrator = false,
		isHidden = false,
		enableCollectionManagement = false,
		enableSubtitleManagement = false,
		enableLyricManagement = false,
		isDisabled = false,
		enableUserPreferenceAccess = true,
		accessSchedules = schedules,
		enableRemoteControlOfOtherUsers = false,
		enableSharedDeviceControl = true,
		enableRemoteAccess = true,
		enableLiveTvManagement = false,
		enableLiveTvAccess = true,
		enableMediaPlayback = true,
		enableAudioPlaybackTranscoding = true,
		enableVideoPlaybackTranscoding = true,
		enablePlaybackRemuxing = true,
		forceRemoteSourceTranscoding = false,
		enableContentDeletion = false,
		enableContentDownloading = true,
		enableSyncTranscoding = true,
		enableMediaConversion = true,
		enableAllDevices = true,
		enableAllChannels = true,
		enableAllFolders = true,
		invalidLoginAttemptCount = 0,
		loginAttemptsBeforeLockout = -1,
		maxActiveSessions = 0,
		enablePublicSharing = true,
		remoteClientBitrateLimit = 0,
		authenticationProviderId = "",
		passwordResetProviderId = "",
		syncPlayAccess = SyncPlayUserAccessType.CREATE_AND_JOIN_GROUPS,
	)

	fun createRepository(): AccessScheduleRepositoryImpl {
		val userRepository = mockk<UserRepository>()
		every { userRepository.currentUser } returns MutableStateFlow(null)
		return AccessScheduleRepositoryImpl(userRepository, mockk(relaxed = true))
	}

	test("isScheduleDenialMessage matches Jellyfin schedule denial messages") {
		AccessScheduleEvaluator.isScheduleDenialMessage("User is not allowed access at this time.") shouldBe true
		AccessScheduleEvaluator.isScheduleDenialMessage("Invalid HTTP status in response: 403") shouldBe false
	}

	test("evaluateCachedPolicyForUser uses persisted access schedules") {
		val repository = createRepository()
		val deniedPolicy = policy(listOf(schedule(DynamicDayOfWeek.EVERYDAY, 8.0, 20.0)))
		repository.cacheUserPolicy(serverId, userId, deniedPolicy)

		val deniedAt = LocalDateTime.of(2026, 6, 18, 22, 0)
		repository.evaluateCachedPolicyForUser(serverId, userId, deniedAt) shouldBe AccessScheduleStatus.Denied(
			LocalDateTime.of(2026, 6, 19, 8, 0),
		)
	}

	test("isScheduleRelatedApiError uses response body text when SDK strips the message") {
		val repository = createRepository()
		val error = InvalidStatusException(403)

		repository.isScheduleRelatedApiError(
			error,
			userId = null,
			responseBody = "User is not allowed access at this time.",
		) shouldBe true
	}
})
