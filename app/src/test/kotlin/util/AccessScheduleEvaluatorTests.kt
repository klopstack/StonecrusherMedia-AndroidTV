package org.jellyfin.androidtv.util

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.AccessSchedule
import org.jellyfin.sdk.model.api.DynamicDayOfWeek
import org.jellyfin.sdk.model.api.UserPolicy
import org.jellyfin.sdk.model.api.SyncPlayUserAccessType
import java.time.LocalDateTime
import java.util.UUID

class AccessScheduleEvaluatorTests : FunSpec({
	val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")

	fun schedule(
		day: DynamicDayOfWeek,
		start: Double,
		end: Double,
	) = AccessSchedule(
		dayOfWeek = day,
		startHour = start,
		endHour = end,
		id = 1,
		userId = userId,
	)

	fun policy(
		schedules: List<AccessSchedule>? = null,
		isAdministrator: Boolean = false,
	) = UserPolicy(
		isAdministrator = isAdministrator,
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

	test("empty schedules allow access") {
		AccessScheduleEvaluator.isAccessAllowed(policy(emptyList()), LocalDateTime.of(2026, 6, 18, 23, 0)) shouldBe true
		AccessScheduleEvaluator.isAccessAllowed(policy(null), LocalDateTime.of(2026, 6, 18, 23, 0)) shouldBe true
	}

	test("administrators bypass schedules") {
		val restricted = policy(listOf(schedule(DynamicDayOfWeek.EVERYDAY, 8.0, 20.0)), isAdministrator = true)
		AccessScheduleEvaluator.isAccessAllowed(restricted, LocalDateTime.of(2026, 6, 18, 23, 0)) shouldBe true
	}

	test("everyday schedule allows within window") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.EVERYDAY, 8.0, 20.0)))
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 10, 30)) shouldBe true
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 8, 0)) shouldBe true
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 20, 0)) shouldBe true
	}

	test("everyday schedule denies outside window") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.EVERYDAY, 8.0, 20.0)))
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 7, 59)) shouldBe false
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 20, 1)) shouldBe false
	}

	test("weekday schedule matches Monday through Friday") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.WEEKDAY, 9.0, 17.0)))
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 12, 0)) shouldBe true // Thursday
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 20, 12, 0)) shouldBe false // Saturday
	}

	test("weekend schedule matches Saturday and Sunday") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.WEEKEND, 10.0, 22.0)))
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 20, 15, 0)) shouldBe true // Saturday
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 19, 15, 0)) shouldBe false // Friday
	}

	test("single day schedule only matches that day") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.MONDAY, 18.0, 21.0)))
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 15, 19, 0)) shouldBe true // Monday
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 16, 19, 0)) shouldBe false // Tuesday
	}

	test("any matching schedule grants access") {
		val p = policy(
			listOf(
				schedule(DynamicDayOfWeek.WEEKDAY, 8.0, 9.0),
				schedule(DynamicDayOfWeek.WEEKDAY, 17.0, 20.0),
			)
		)
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 18, 0)) shouldBe true
		AccessScheduleEvaluator.isAccessAllowed(p, LocalDateTime.of(2026, 6, 18, 12, 0)) shouldBe false
	}

	test("getNextAccessStart returns next allowed time when currently denied") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.EVERYDAY, 8.0, 20.0)))
		val now = LocalDateTime.of(2026, 6, 18, 21, 0)
		val next = AccessScheduleEvaluator.getNextAccessStart(p, now)
		next?.toLocalDate() shouldBe LocalDateTime.of(2026, 6, 19, 0, 0).toLocalDate()
		next?.hour shouldBe 8
	}

	test("getNextAccessStart returns null when currently allowed") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.EVERYDAY, 8.0, 20.0)))
		AccessScheduleEvaluator.getNextAccessStart(p, LocalDateTime.of(2026, 6, 18, 12, 0)) shouldBe null
	}

	test("getNextAccessStart returns exact schedule start when denied just before window") {
		val p = policy(listOf(schedule(DynamicDayOfWeek.EVERYDAY, 8.0, 20.0)))
		val now = LocalDateTime.of(2026, 6, 18, 7, 58)
		val next = AccessScheduleEvaluator.getNextAccessStart(p, now)
		next shouldBe LocalDateTime.of(2026, 6, 18, 8, 0)
	}
})
