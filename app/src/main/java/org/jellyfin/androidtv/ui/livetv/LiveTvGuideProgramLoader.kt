package org.jellyfin.androidtv.ui.livetv

import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

/**
 * Batched, debounced loader for Live TV guide program data.
 */
class LiveTvGuideProgramLoader(
	private val fragment: Fragment,
	private val onProgramsLoaded: (channelIndices: IntRange) -> Unit,
) {
	private val handler = Handler(Looper.getMainLooper())
	private var pendingFirst = 0
	private var pendingLast = 0
	private var guideStart: LocalDateTime = LocalDateTime.now()
	private var guideEnd: LocalDateTime = LocalDateTime.now()
	private var scheduled = false

	fun updateGuideRange(start: LocalDateTime, end: LocalDateTime) {
		guideStart = start
		guideEnd = end
	}

	fun clearAndReload() {
		TvManager.clearProgramsCache()
	}

	fun requestRange(firstVisible: Int, lastVisible: Int, prefetchRows: Int) {
		val channelCount = TvManager.getChannelCount()
		if (channelCount == 0) return

		val first = (firstVisible - prefetchRows).coerceAtLeast(0)
		val last = (lastVisible + prefetchRows).coerceAtMost(channelCount - 1)

		pendingFirst = if (scheduled) minOf(pendingFirst, first) else first
		pendingLast = if (scheduled) maxOf(pendingLast, last) else last

		if (!scheduled) {
			scheduled = true
			handler.postDelayed(::flushPending, DEBOUNCE_MS)
		}
	}

	private fun flushPending() {
		scheduled = false
		val first = pendingFirst
		val last = pendingLast
		loadIndices(first, last)
	}

	private fun loadIndices(first: Int, last: Int) {
		val channelCount = TvManager.getChannelCount()
		if (channelCount == 0 || first > last) return

		val toLoad = mutableListOf<Int>()
		for (i in first..last) {
			val channel = TvManager.getChannel(i)
			val channelId = channel.id ?: continue
			if (!TvManager.hasProgramsForChannel(channelId) && !TvManager.isProgramsLoadingForChannel(channelId)) {
				toLoad.add(i)
			}
		}

		if (toLoad.isEmpty()) return

		var batchStart = 0
		while (batchStart < toLoad.size) {
			val batchEnd = minOf(batchStart + BATCH_SIZE, toLoad.size)
			val batchIndices = toLoad.subList(batchStart, batchEnd)
			fetchBatch(batchIndices)
			batchStart = batchEnd
		}
	}

	private fun fetchBatch(indices: List<Int>) {
		val channelIds = indices.mapNotNull { TvManager.getChannel(it).id }.toTypedArray()
		if (channelIds.isEmpty()) return

		for (id in channelIds) {
			TvManager.markProgramsLoading(id, true)
		}

		val loadedRange = indices.first()..indices.last()

		TvManager.getProgramsForChannelsAsync(
			fragment,
			channelIds,
			guideStart,
			guideEnd,
			object : org.jellyfin.androidtv.util.apiclient.EmptyResponse(fragment.lifecycle) {
				override fun onResponse() {
					if (!fragment.isAdded) return
					Timber.d("Guide programs loaded for %d channels", channelIds.size)
					onProgramsLoaded(loadedRange)
				}
			},
		)
	}

	companion object {
		const val BATCH_SIZE = 10
		const val DEBOUNCE_MS = 50L
	}
}
