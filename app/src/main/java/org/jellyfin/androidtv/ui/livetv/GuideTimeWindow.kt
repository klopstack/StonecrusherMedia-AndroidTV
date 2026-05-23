package org.jellyfin.androidtv.ui.livetv

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.jvm.JvmStatic
import kotlin.math.max

object GuideTimeWindow {
	const val INITIAL_LOAD_HOURS = 3L
	const val FILTERED_LOAD_HOURS = 4L
	const val HORIZONTAL_CHUNK_HOURS = 2L
	const val HORIZONTAL_PREFETCH_MINUTES = 45L
	const val MAX_HORIZON_HOURS = 24L
	const val BACKGROUND_PREFETCH_HOURS = 3L
	const val DISK_CACHE_TTL_MINUTES = 20L
	const val PAST_BUFFER_MINUTES = 15L
	const val TIMELINE_RETAIN_BEHIND_HOURS = 1L

	@JvmStatic
	fun roundGuideStart(start: LocalDateTime): LocalDateTime =
		start.withSecond(0).withNano(0)

	@JvmStatic
	fun initialDisplayEnd(guideStart: LocalDateTime, filtered: Boolean): LocalDateTime =
		guideStart.plusHours(if (filtered) FILTERED_LOAD_HOURS else INITIAL_LOAD_HOURS)

	@JvmStatic
	fun maxHorizon(): LocalDateTime = maxHorizon(LocalDateTime.now())

	@JvmStatic
	fun maxHorizon(from: LocalDateTime): LocalDateTime =
		from.plusHours(MAX_HORIZON_HOURS)

	fun scrollXToTime(guideStart: LocalDateTime, scrollXPx: Int, rowWidthPerMinPx: Int): LocalDateTime {
		if (rowWidthPerMinPx <= 0) return guideStart
		val minutes = scrollXPx / rowWidthPerMinPx
		return guideStart.plusMinutes(minutes.toLong())
	}

	fun timeToScrollX(guideStart: LocalDateTime, time: LocalDateTime, rowWidthPerMinPx: Int): Int {
		if (rowWidthPerMinPx <= 0) return 0
		val minutes = ChronoUnit.MINUTES.between(guideStart, time).coerceAtLeast(0)
		return (minutes * rowWidthPerMinPx).toInt()
	}

	fun visibleTimeRange(
		guideStart: LocalDateTime,
		scrollXPx: Int,
		viewportWidthPx: Int,
		rowWidthPerMinPx: Int,
	): Pair<LocalDateTime, LocalDateTime> {
		val visibleStart = scrollXToTime(guideStart, scrollXPx, rowWidthPerMinPx)
		val visibleEnd = scrollXToTime(
			guideStart,
			scrollXPx + max(viewportWidthPx, rowWidthPerMinPx),
			rowWidthPerMinPx,
		)
		return visibleStart to visibleEnd
	}

	fun initialFetchRange(
		guideStart: LocalDateTime,
		displayEnd: LocalDateTime,
	): Pair<LocalDateTime, LocalDateTime> {
		val fetchStart = guideStart.minusMinutes(PAST_BUFFER_MINUTES).coerceAtLeast(LocalDateTime.now().minusHours(1))
		val fetchEnd = displayEnd.plusMinutes(HORIZONTAL_PREFETCH_MINUTES)
			.coerceAtMost(maxHorizon(guideStart))
		return fetchStart to fetchEnd
	}

	fun fetchRangeForVisible(
		visibleStart: LocalDateTime,
		visibleEnd: LocalDateTime,
		loadedStart: LocalDateTime?,
		loadedEnd: LocalDateTime?,
	): Pair<LocalDateTime, LocalDateTime>? {
		val now = LocalDateTime.now()
		val wantStart = visibleStart
			.minusMinutes(HORIZONTAL_PREFETCH_MINUTES)
			.coerceAtLeast(now.minusHours(1))
		val wantEnd = visibleEnd
			.plusMinutes(HORIZONTAL_PREFETCH_MINUTES)
			.coerceAtMost(maxHorizon(now))

		if (loadedStart == null || loadedEnd == null) {
			return if (wantStart.isBefore(wantEnd)) wantStart to wantEnd else null
		}

		if (wantEnd.isAfter(loadedEnd)) {
			val fetchStart = loadedEnd
			val fetchEnd = wantEnd
			if (fetchStart.isBefore(fetchEnd)) return fetchStart to fetchEnd
		}
		if (wantStart.isBefore(loadedStart)) {
			val fetchStart = wantStart
			val fetchEnd = loadedStart
			if (fetchStart.isBefore(fetchEnd)) return fetchStart to fetchEnd
		}
		return null
	}

	fun nextChunkEnd(from: LocalDateTime): LocalDateTime =
		from.plusHours(HORIZONTAL_CHUNK_HOURS).coerceAtMost(maxHorizon(from))
}
