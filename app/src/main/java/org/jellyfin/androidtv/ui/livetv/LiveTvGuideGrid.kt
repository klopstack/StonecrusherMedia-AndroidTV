package org.jellyfin.androidtv.ui.livetv

import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.HorizontalScrollViewListener
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.util.UUID

/**
 * Virtualized Live TV guide grid with synced channel and program columns.
 */
class LiveTvGuideGrid(
	private val fragment: Fragment,
	private val guide: LiveTvGuide,
	private val filters: GuideFilters,
	private val channelList: RecyclerView,
	private val programList: RecyclerView,
	private val programHScroller: ObservableHorizontalScrollView,
	private val timelineScroller: HorizontalScrollView,
) {
	val rowHeightPx: Int = Utils.convertDpToPixel(fragment.requireContext(), LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP)
	private val rowWidthPerMinPx: Int = Utils.convertDpToPixel(fragment.requireContext(), LiveTvGuideFragment.GUIDE_ROW_WIDTH_PER_MIN_DP)

	private val rowBuilder = LiveTvGuideRowBuilder(
		fragment.requireContext(),
		guide,
		filters,
		rowHeightPx,
		rowWidthPerMinPx,
	)

	private var guideStart: LocalDateTime = LocalDateTime.now()
	private var guideEnd: LocalDateTime = LocalDateTime.now()
	private var prefetchRows: Int = 12

	private lateinit var coordinator: LiveTvGuideRowsCoordinator
	private lateinit var programLoader: LiveTvGuideProgramLoader

	fun initialize() {
		val channelLm = LinearLayoutManager(fragment.requireContext(), LinearLayoutManager.VERTICAL, false)
		val programLm = LinearLayoutManager(fragment.requireContext(), LinearLayoutManager.VERTICAL, false)

		channelList.layoutManager = channelLm
		programList.layoutManager = programLm
		channelList.setHasFixedSize(true)
		programList.setHasFixedSize(false)
		channelList.isFocusable = false
		programList.isFocusable = false
		programHScroller.isFocusable = false

		programLoader = LiveTvGuideProgramLoader(fragment) { range ->
			coordinator.notifyProgramsChanged(range)
		}

		coordinator = LiveTvGuideRowsCoordinator(
			guide = guide,
			rowBuilder = rowBuilder,
			filters = filters,
			guideStart = guideStart,
			guideEnd = guideEnd,
			rowHeightPx = rowHeightPx,
			onVisibleRangeChanged = { first, last ->
				programLoader.requestRange(first, last, prefetchRows)
			},
		)

		channelList.adapter = coordinator.channelAdapter
		programList.adapter = coordinator.programAdapter
		coordinator.attachScrollListener(channelList, programList)

		programHScroller.scrollViewListener = HorizontalScrollViewListener { _, x, y, _, _ ->
			timelineScroller.scrollTo(x, y)
		}

		channelList.post {
			prefetchRows = LiveTvGuideRowsCoordinator.computePrefetchRows(channelList.height, rowHeightPx)
			channelLm.initialPrefetchItemCount = prefetchRows
			programLm.initialPrefetchItemCount = prefetchRows
		}
	}

	fun setGuideRange(start: LocalDateTime, end: LocalDateTime, clearCache: Boolean) {
		guideStart = start
		guideEnd = end
		if (clearCache) {
			programLoader.clearAndReload()
		}
		programLoader.updateGuideRange(start, end)
		coordinator.updateGuideRange(start, end)
	}

	fun setChannels(count: Int) {
		coordinator.setChannelCount(count)
	}

	@JvmOverloads
	fun scrollToChannel(index: Int, smooth: Boolean = false) {
		if (index < 0) return
		if (smooth) {
			channelList.smoothScrollToPosition(index)
			programList.smoothScrollToPosition(index)
		} else {
			channelList.scrollToPosition(index)
			programList.scrollToPosition(index)
		}
		channelList.post {
			val lm = channelList.layoutManager as LinearLayoutManager
			val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
			val last = lm.findLastVisibleItemPosition().coerceAtLeast(0)
			programLoader.requestRange(first, last, prefetchRows)
		}
	}

	fun requestInitialPrograms() {
		channelList.post {
			val lm = channelList.layoutManager as? LinearLayoutManager ?: return@post
			val first = lm.findFirstVisibleItemPosition().coerceAtLeast(0)
			val last = lm.findLastVisibleItemPosition().coerceAtLeast(first)
			programLoader.requestRange(first, last, prefetchRows * 2)
		}
	}

	fun refreshFavorite(channelId: UUID) {
		val count = TvManager.getChannelCount()
		for (i in 0 until count) {
			val channel = TvManager.getChannel(i)
			if (channel.id == channelId) {
				coordinator.channelAdapter.notifyItemChanged(i)
				break
			}
		}
	}

	fun findProgramRowForChannelHeader(header: GuideChannelHeader): LinearLayout? {
		val count = TvManager.getChannelCount()
		for (i in 0 until count) {
			if (TvManager.getChannel(i).id == header.channel.id) {
				return coordinator.getProgramRowAt(i)
			}
		}
		return null
	}

	fun findFocusViewForChannel(channelId: UUID, focusAtEnd: Boolean): View? {
		val count = TvManager.getChannelCount()
		for (i in 0 until count) {
			val channel = TvManager.getChannel(i)
			if (channel.id != channelId) continue
			scrollToChannel(i)
			val row = coordinator.getProgramRowAt(i) ?: return coordinator.getChannelHeaderAt(i)
			if (row.childCount == 0) return row
			return if (focusAtEnd) row.getChildAt(row.childCount - 1) else row.getChildAt(0)
		}
		return null
	}

	fun findCurrentProgramInRow(row: LinearLayout): BaseItemDto? {
		for (i in 0 until row.childCount) {
			val cell = row.getChildAt(i) as? ProgramGridCell ?: continue
			val program = cell.program ?: continue
			val start = program.startDate ?: continue
			val end = program.endDate ?: continue
			if (start.isBefore(LocalDateTime.now()) && end.isAfter(LocalDateTime.now())) {
				return program
			}
		}
		return null
	}
}
