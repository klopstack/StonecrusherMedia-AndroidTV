package org.jellyfin.androidtv.ui.livetv

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.data.repository.GuideDiskCache
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.HorizontalScrollViewListener
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.java.KoinJavaComponent
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

	private val programRepository: GuideProgramRepository =
		KoinJavaComponent.get(GuideProgramRepository::class.java)
	private val diskCache: GuideDiskCache =
		KoinJavaComponent.get(GuideDiskCache::class.java)

	private var guideStart: LocalDateTime = LocalDateTime.now()
	private var guideEnd: LocalDateTime = LocalDateTime.now()
	private var prefetchRows: Int = CHANNEL_LOAD_RADIUS
	private var priorityChannelIndex: Int? = null
	private var trackedChannelFirst = 0
	private var trackedChannelLast = 0
	private var initialLoadPending = false
	private var lastEvictionFirst = -1
	private var lastEvictionLast = -1

	private val horizontalHandler = Handler(Looper.getMainLooper())
	private val horizontalScrollRunnable = Runnable { onHorizontalScrollIdle() }
	private val evictionHandler = Handler(Looper.getMainLooper())
	private val evictionRunnable = Runnable { runEviction() }

	private val displayOptions: GuideCellDisplayOptions by lazy {
		GuideCellDisplayOptions.from(KoinJavaComponent.get(org.jellyfin.androidtv.preference.LiveTvPreferences::class.java))
	}

	private val rowBuilder = LiveTvGuideRowBuilder(
		fragment.requireContext(),
		guide,
		filters,
		guideStart,
		guideEnd,
		rowHeightPx,
		rowWidthPerMinPx,
		displayOptions,
	)
	private val rowCache = LiveTvGuideRowCache(rowBuilder, filters)

	private lateinit var coordinator: LiveTvGuideRowsCoordinator
	private lateinit var programLoader: LiveTvGuideProgramLoader
	private var pendingFocusChannelId: UUID? = null
	private var pendingFocusAtEnd: Boolean = false
	private var pendingHeaderRedirectChannelId: UUID? = null
	var lastFocusedProgramStart: LocalDateTime? = null
		private set

	fun onProgramCellFocused(program: BaseItemDto?) {
		lastFocusedProgramStart = program?.startDate
	}

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

		coordinator = LiveTvGuideRowsCoordinator(
			guide = guide,
			rowBuilder = rowBuilder,
			rowCache = rowCache,
			filters = filters,
			guideStart = guideStart,
			guideEnd = guideEnd,
			rowHeightPx = rowHeightPx,
			onVisibleRangeChanged = { first, last ->
				trackedChannelFirst = first
				trackedChannelLast = last
				requestProgramsForVisibleChannels(first, last)
			},
			onProgramRowAttached = { position, row ->
				onProgramRowAttached(position, row)
			},
		)

		programLoader = LiveTvGuideProgramLoader(fragment, programRepository) { indices ->
			coordinator.onProgramsLoaded(indices, priorityChannelIndex)
		}
		programLoader.onSettled = { initialLoadPending = false }

		channelList.adapter = coordinator.channelAdapter
		programList.adapter = coordinator.programAdapter
		coordinator.attachScrollListener(channelList, programList, { channelId ->
			onProgramRowBuilt(channelId)
		}) {
			redirectFocusedChannelHeader()
			scheduleEviction()
			scheduleHorizontalPrefetch()
		}

		programHScroller.scrollViewListener = HorizontalScrollViewListener { _, x, _, _, _ ->
			timelineScroller.scrollTo(x, 0)
			scheduleHorizontalPrefetch()
		}

		channelList.post {
			prefetchRows = LiveTvGuideRowsCoordinator.computePrefetchRows(
				channelList.height,
				rowHeightPx,
			).coerceAtMost(CHANNEL_LOAD_RADIUS)
			channelLm.initialPrefetchItemCount = prefetchRows
			programLm.initialPrefetchItemCount = prefetchRows
		}
	}

	fun hydrateFromDisk(): Boolean {
		val snapshot = readGuideDiskSnapshot(fragment, diskCache) ?: return false
		val map = diskCache.toRepositoryMap(snapshot)
		if (map.isEmpty()) return false
		programRepository.hydrate(map, diskCache.windowStart(snapshot), diskCache.windowEnd(snapshot))
		TvManager.clearForceReload()
		return true
	}

	/**
	 * Single entry point for guide open / page — sets range, channel count, and cache state once.
	 */
	fun prepareGuideWindow(
		start: LocalDateTime,
		end: LocalDateTime,
		channelCount: Int,
		hydrated: Boolean,
	) {
		guideStart = GuideTimeWindow.roundGuideStart(start)
		guideEnd = end
		initialLoadPending = true
		lastEvictionFirst = -1
		lastEvictionLast = -1

		if (!hydrated) {
			if (::programLoader.isInitialized) {
				programLoader.clearAndReload()
			} else {
				programRepository.clear()
			}
		}

		coordinator.resetForOpen(guideStart, guideEnd, channelCount, clearRowCache = !hydrated)

		if (hydrated) {
			val buildLast = minOf(channelCount - 1, CHANNEL_LOAD_RADIUS * 2)
			if (buildLast >= 0) {
				scheduleBuildFromRepository(0, buildLast)
			}
		}
	}

	private fun scheduleBuildFromRepository(first: Int, last: Int) {
		val indices = (first..last).filter { index ->
			val channelId = TvManager.getChannel(index).id ?: return@filter false
			TvManager.hasProgramsForChannel(channelId)
		}
		if (indices.isNotEmpty()) {
			coordinator.onProgramsLoaded(indices)
		}
	}

	fun requestProgramsAroundChannel(centerIndex: Int, radius: Int = CHANNEL_LOAD_RADIUS) {
		val count = TvManager.getChannelCount()
		if (count == 0 || centerIndex < 0) return
		val clampedCenter = centerIndex.coerceAtMost(count - 1)
		priorityChannelIndex = clampedCenter
		initialLoadPending = true
		val (fetchStart, fetchEnd) = GuideTimeWindow.initialFetchRange(guideStart, guideEnd)
		programLoader.loadChannelsExact(
			(clampedCenter - radius).coerceAtLeast(0),
			(clampedCenter + radius).coerceAtMost(count - 1),
			fetchStart,
			fetchEnd,
			clampedCenter,
		)
	}

	fun requestProgramsForVisibleChannels(first: Int, last: Int) {
		if (initialLoadPending) return
		val (fetchStart, fetchEnd) = GuideTimeWindow.initialFetchRange(guideStart, guideEnd)
		programLoader.requestChannelsRange(first, last, prefetchRows, fetchStart, fetchEnd)
	}

	fun extendHorizontally() {
		val loadedEnd = programRepository.loadedEnd ?: guideEnd
		val chunkEnd = GuideTimeWindow.nextChunkEnd(loadedEnd)
		if (chunkEnd.isAfter(guideEnd)) {
			extendDisplayEnd(chunkEnd)
		}
		val fetchStart = loadedEnd
		val fetchEnd = chunkEnd
		programLoader.loadChannelsExact(
			trackedChannelFirst,
			trackedChannelLast,
			fetchStart,
			fetchEnd,
		)
	}

	private fun extendDisplayEnd(newEnd: LocalDateTime) {
		if (!newEnd.isAfter(guideEnd)) return
		guideEnd = newEnd
		val affectedFirst = (trackedChannelFirst - CHANNEL_LOAD_RADIUS).coerceAtLeast(0)
		val affectedLast = (trackedChannelLast + CHANNEL_LOAD_RADIUS).coerceAtMost(coordinator.channelCount - 1)
		coordinator.extendGuideEnd(guideStart, guideEnd, affectedFirst, affectedLast, rowCache)
		guide.onGuideDisplayEndExtended(newEnd)
		guide.extendTimeLineTo(newEnd)
	}

	private fun scheduleHorizontalPrefetch() {
		horizontalHandler.removeCallbacks(horizontalScrollRunnable)
		horizontalHandler.postDelayed(horizontalScrollRunnable, LiveTvGuideProgramLoader.DEBOUNCE_MS)
	}

	private fun onHorizontalScrollIdle() {
		val scrollX = programHScroller.scrollX
		val viewportWidth = programHScroller.width
		if (viewportWidth <= 0) return

		val (visibleStart, visibleEnd) = GuideTimeWindow.visibleTimeRange(
			guideStart,
			scrollX,
			viewportWidth,
			rowWidthPerMinPx,
		)
		val fetchRange = GuideTimeWindow.fetchRangeForVisible(
			visibleStart,
			visibleEnd,
			programRepository.loadedStart,
			programRepository.loadedEnd,
		) ?: return

		val (fetchStart, fetchEnd) = fetchRange
		if (fetchEnd.isAfter(guideEnd)) {
			extendDisplayEnd(fetchEnd)
		}
		programLoader.loadChannelsExact(
			trackedChannelFirst,
			trackedChannelLast,
			fetchStart,
			fetchEnd,
		)
	}

	private fun scheduleEviction() {
		evictionHandler.removeCallbacks(evictionRunnable)
		evictionHandler.postDelayed(evictionRunnable, EVICTION_DEBOUNCE_MS)
	}

	private fun runEviction() {
		val first = trackedChannelFirst
		val last = trackedChannelLast
		if (first < 0 || last < 0) return

		val rangeChanged = lastEvictionFirst < 0 ||
			kotlin.math.abs(first - lastEvictionFirst) >= EVICTION_ROW_THRESHOLD ||
			kotlin.math.abs(last - lastEvictionLast) >= EVICTION_ROW_THRESHOLD
		if (!rangeChanged) return

		lastEvictionFirst = first
		lastEvictionLast = last
		coordinator.evictChannelsOutside(first, last, CHANNEL_LOAD_RADIUS, rowCache)
	}

	/** Clears cache and resets range for large time jumps (date picker / page). */
	fun resetGuideWindow(start: LocalDateTime, end: LocalDateTime) {
		guideStart = GuideTimeWindow.roundGuideStart(start)
		guideEnd = end
		initialLoadPending = true
		lastEvictionFirst = -1
		lastEvictionLast = -1
		rowCache.clear()
		if (::programLoader.isInitialized) {
			programLoader.clearAndReload()
		}
		rowBuilder.updateGuideRange(guideStart, guideEnd)
		coordinator.resetGuideRange(guideStart, guideEnd)
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
	}

	fun requestInitialPrograms(centerIndex: Int) {
		requestProgramsAroundChannel(centerIndex)
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

	fun requestFocusForChannel(channelId: UUID, focusAtEnd: Boolean = false) {
		pendingFocusChannelId = channelId
		pendingFocusAtEnd = focusAtEnd
		programList.post { tryApplyPendingFocus() }
	}

	private fun onProgramRowBuilt(channelId: UUID) {
		if (pendingFocusChannelId == channelId) {
			programList.post { tryApplyPendingFocus() }
		}
		if (pendingHeaderRedirectChannelId == channelId) {
			programList.post { tryRedirectPendingHeader() }
		}
	}

	private fun onProgramRowAttached(position: Int, row: LinearLayout) {
		val channelId = TvManager.getChannel(position).id ?: return
		if (pendingHeaderRedirectChannelId == channelId) {
			tryRedirectPendingHeader()
		}
		val header = coordinator.getChannelHeaderAt(position) ?: return
		if (header.isFocused) {
			redirectChannelHeaderFocus(header)
		}
	}

	private fun tryRedirectPendingHeader() {
		val channelId = pendingHeaderRedirectChannelId ?: return
		val header = findChannelHeader(channelId) ?: return
		val row = findProgramRowForChannelHeader(header) ?: return
		if (row.childCount == 0) return
		pendingHeaderRedirectChannelId = null
		redirectChannelHeaderFocus(header)
	}

	private fun redirectFocusedChannelHeader() {
		val focused = programList.findFocus() ?: channelList.findFocus() ?: return
		if (focused is GuideChannelHeader) {
			redirectChannelHeaderFocus(focused)
		}
	}

	fun redirectChannelHeaderFocus(header: GuideChannelHeader) {
		val row = findProgramRowForChannelHeader(header)
		if (row == null || row.childCount == 0) {
			pendingHeaderRedirectChannelId = header.channel?.id
			return
		}
		pendingHeaderRedirectChannelId = null
		val cell = findPreferredProgramCellInRow(row, lastFocusedProgramStart) ?: return
		cell.post {
			if (header.isFocused) cell.requestFocus()
		}
	}

	fun findVerticalProgramFocusTarget(header: GuideChannelHeader, direction: Int): View? {
		val channelId = header.channel?.id ?: return null
		val index = TvManager.getAllChannelsIndex(channelId)
		if (index < 0) return null

		val targetIndex = when (direction) {
			View.FOCUS_UP -> index - 1
			View.FOCUS_DOWN -> index + 1
			else -> return null
		}
		if (targetIndex !in 0 until TvManager.getChannelCount()) return null

		val row = coordinator.getProgramRowAt(targetIndex) ?: return null
		return findPreferredProgramCellInRow(row, lastFocusedProgramStart)
	}

	private fun findChannelHeader(channelId: UUID): GuideChannelHeader? {
		val count = TvManager.getChannelCount()
		for (i in 0 until count) {
			if (TvManager.getChannel(i).id == channelId) {
				return coordinator.getChannelHeaderAt(i)
			}
		}
		return null
	}

	private fun findPreferredProgramCellInRow(
		row: LinearLayout,
		alignTime: LocalDateTime?,
	): ProgramGridCell? {
		if (row.childCount == 0) return null

		val targetTime = alignTime ?: LocalDateTime.now()
		for (i in 0 until row.childCount) {
			val cell = row.getChildAt(i) as? ProgramGridCell ?: continue
			val program = cell.program ?: continue
			val start = program.startDate ?: continue
			val end = program.endDate ?: continue
			if (!start.isAfter(targetTime) && end.isAfter(targetTime)) {
				return cell
			}
		}

		return row.getChildAt(0) as? ProgramGridCell
	}

	private fun tryApplyPendingFocus() {
		val channelId = pendingFocusChannelId ?: return
		val cell = findProgramCellForChannel(channelId, pendingFocusAtEnd) ?: return
		pendingFocusChannelId = null
		cell.requestFocus()
	}

	fun findProgramCellForChannel(channelId: UUID, focusAtEnd: Boolean): ProgramGridCell? {
		val count = TvManager.getChannelCount()
		for (i in 0 until count) {
			val channel = TvManager.getChannel(i)
			if (channel.id != channelId) continue
			scrollToChannel(i)
			requestProgramsAroundChannel(i)
			val row = coordinator.getProgramRowAt(i) ?: return null
			if (row.childCount == 0) return null
			if (focusAtEnd) {
				return row.getChildAt(row.childCount - 1) as? ProgramGridCell
			}
			return findPreferredProgramCellInRow(row, lastFocusedProgramStart)
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

	companion object {
		const val CHANNEL_LOAD_RADIUS = 8
		private const val EVICTION_DEBOUNCE_MS = 300L
		private const val EVICTION_ROW_THRESHOLD = 2
	}
}
