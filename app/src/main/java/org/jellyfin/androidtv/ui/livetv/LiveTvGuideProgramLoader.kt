package org.jellyfin.androidtv.ui.livetv

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/**
 * Batched, debounced loader for Live TV guide program data with incremental time windows.
 */
class LiveTvGuideProgramLoader(
	private val fragment: Fragment,
	private val programRepository: GuideProgramRepository,
	private val onProgramsLoaded: (channelIndices: List<Int>) -> Unit,
) {
	private val handler = Handler(Looper.getMainLooper())
	private val flushRunnable = Runnable { flushPending() }
	private var pendingFirst = 0
	private var pendingLast = 0
	private var pendingFetchStart: LocalDateTime? = null
	private var pendingFetchEnd: LocalDateTime? = null
	private var scheduled = false
	private var generation = 0
	private var inFlightBatches = 0

	var onSettled: (() -> Unit)? = null

	fun clearAndReload() {
		generation++
		cancelPending()
		programRepository.clear()
	}

	private fun cancelPending() {
		handler.removeCallbacks(flushRunnable)
		scheduled = false
		pendingFetchStart = null
		pendingFetchEnd = null
	}

	fun loadChannelsExact(
		first: Int,
		last: Int,
		fetchStart: LocalDateTime,
		fetchEnd: LocalDateTime,
		priorityIndex: Int = -1,
	) {
		cancelPending()
		val channelCount = TvManager.getChannelCount()
		if (channelCount == 0) return
		val clampedFirst = first.coerceIn(0, channelCount - 1)
		val clampedLast = last.coerceIn(clampedFirst, channelCount - 1)
		loadIndices(clampedFirst, clampedLast, fetchStart, fetchEnd, priorityIndex)
	}

	fun requestChannelsRange(
		firstVisible: Int,
		lastVisible: Int,
		prefetchRows: Int,
		fetchStart: LocalDateTime,
		fetchEnd: LocalDateTime,
	) {
		val channelCount = TvManager.getChannelCount()
		if (channelCount == 0) return

		val first = (firstVisible - prefetchRows).coerceAtLeast(0)
		val last = (lastVisible + prefetchRows).coerceAtMost(channelCount - 1)

		pendingFirst = first
		pendingLast = last
		pendingFetchStart = fetchStart
		pendingFetchEnd = fetchEnd

		if (!scheduled) {
			scheduled = true
			handler.postDelayed(flushRunnable, DEBOUNCE_MS)
		}
	}

	private fun flushPending() {
		scheduled = false
		val start = pendingFetchStart ?: return
		val end = pendingFetchEnd ?: return
		loadIndices(pendingFirst, pendingLast, start, end)
		pendingFetchStart = null
		pendingFetchEnd = null
	}

	private fun loadIndices(
		first: Int,
		last: Int,
		fetchStart: LocalDateTime,
		fetchEnd: LocalDateTime,
		priorityIndex: Int = -1,
	) {
		val channelCount = TvManager.getChannelCount()
		if (channelCount == 0 || first > last) {
			notifySettledIfIdle()
			return
		}

		val requests = mutableListOf<Triple<Int, LocalDateTime, LocalDateTime>>()
		for (i in first..last) {
			val channel = TvManager.getChannel(i)
			val channelId = channel.id ?: continue
			val interval = programRepository.getFetchInterval(channelId, fetchStart, fetchEnd) ?: continue
			requests.add(Triple(i, interval.first, interval.second))
		}

		if (requests.isEmpty()) {
			notifySettledIfIdle()
			return
		}

		val grouped = requests.groupBy { it.second to it.third }
		val sortedGroups = grouped.entries.sortedBy { entry ->
			val containsPriority = entry.value.any { it.first == priorityIndex }
			if (containsPriority) 0 else 1
		}

		for ((interval, indices) in sortedGroups) {
			val (batchStart, batchEnd) = interval
			val batchIndices = indices.map { it.first }
			var batchOffset = 0
			while (batchOffset < batchIndices.size) {
				val batchEndIndex = minOf(batchOffset + BATCH_SIZE, batchIndices.size)
				fetchBatch(batchIndices.subList(batchOffset, batchEndIndex), batchStart, batchEnd)
				batchOffset = batchEndIndex
			}
		}
	}

	private fun fetchBatch(indices: List<Int>, fetchStart: LocalDateTime, fetchEnd: LocalDateTime) {
		val channelIds = indices.mapNotNull { TvManager.getChannel(it).id }.toTypedArray()
		if (channelIds.isEmpty()) return

		val batchGeneration = generation
		inFlightBatches++

		for (id in channelIds) {
			TvManager.markProgramsLoading(id, true)
		}

		TvManager.getProgramsForChannelsAsync(
			fragment,
			channelIds,
			fetchStart,
			fetchEnd,
			object : org.jellyfin.androidtv.util.apiclient.EmptyResponse(fragment.lifecycle) {
				override fun onResponse() {
					if (!fragment.isAdded) return
					if (batchGeneration != generation) return
					inFlightBatches--
					Timber.d("Guide programs loaded for %d channels (%s - %s)", channelIds.size, fetchStart, fetchEnd)
					onProgramsLoaded(indices)
					notifySettledIfIdle()
				}
			},
		)
	}

	private fun notifySettledIfIdle() {
		if (inFlightBatches == 0 && !scheduled) {
			onSettled?.invoke()
		}
	}

	companion object {
		const val BATCH_SIZE = 25
		const val DEBOUNCE_MS = 100L
	}
}
