package org.jellyfin.androidtv.ui.livetv

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.ProgramGridCell
import java.time.LocalDateTime

class GuideChannelViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

class GuideProgramViewHolder(val container: FrameLayout) : RecyclerView.ViewHolder(container)

class LiveTvGuideRowsCoordinator(
	private val guide: LiveTvGuide,
	private val rowBuilder: LiveTvGuideRowBuilder,
	private val rowCache: LiveTvGuideRowCache,
	private val filters: GuideFilters,
	private var guideStart: LocalDateTime,
	private var guideEnd: LocalDateTime,
	private val rowHeightPx: Int,
	private val onVisibleRangeChanged: (first: Int, last: Int) -> Unit,
	private val onProgramRowAttached: (position: Int, row: LinearLayout) -> Unit,
) {
	var channelCount: Int = 0
		private set

	val channelAdapter = ChannelAdapter()
	val programAdapter = ProgramAdapter()

	fun setChannelCount(count: Int) {
		channelCount = count
		channelAdapter.notifyDataSetChanged()
		programAdapter.notifyDataSetChanged()
	}

	fun resetForOpen(start: LocalDateTime, end: LocalDateTime, count: Int, clearRowCache: Boolean) {
		guideStart = start
		guideEnd = end
		rowBuilder.updateGuideRange(start, end)
		if (clearRowCache) {
			rowCache.clear()
		}
		channelCount = count
		channelAdapter.notifyDataSetChanged()
		programAdapter.notifyDataSetChanged()
	}

	fun updateGuideRange(start: LocalDateTime, end: LocalDateTime) {
		guideStart = start
		guideEnd = end
		rowBuilder.updateGuideRange(start, end)
	}

	fun resetGuideRange(start: LocalDateTime, end: LocalDateTime) {
		guideStart = start
		guideEnd = end
		rowBuilder.updateGuideRange(start, end)
		rowCache.clear()
		channelAdapter.notifyDataSetChanged()
		programAdapter.notifyDataSetChanged()
	}

	fun extendGuideEnd(
		start: LocalDateTime,
		end: LocalDateTime,
		affectedFirst: Int,
		affectedLast: Int,
		rowCache: LiveTvGuideRowCache,
	) {
		guideStart = start
		guideEnd = end
		rowBuilder.updateGuideRange(start, end)
		if (affectedFirst > affectedLast || channelCount == 0) return
		val first = affectedFirst.coerceIn(0, channelCount - 1)
		val last = affectedLast.coerceIn(first, channelCount - 1)
		val channelIds = (first..last).mapNotNull { TvManager.getChannel(it).id }
		rowCache.invalidateChannels(channelIds)
		for (i in first..last) {
			programAdapter.notifyItemChanged(i)
		}
	}

	fun evictChannelsOutside(first: Int, last: Int, radius: Int, rowCache: LiveTvGuideRowCache): List<Int> {
		if (channelCount == 0) return emptyList()
		val keepStart = (first - radius).coerceAtLeast(0)
		val keepEnd = (last + radius).coerceAtMost(channelCount - 1)
		val keepIds = (keepStart..keepEnd).mapNotNull { TvManager.getChannel(it).id }.toSet()
		TvManager.evictProgramsOutsideChannels(keepIds)
		val evictIndices = (0 until channelCount).filter { it !in keepStart..keepEnd }
		val evictIds = evictIndices.mapNotNull { TvManager.getChannel(it).id }
		rowCache.invalidateChannels(evictIds)
		for (index in evictIndices) {
			programAdapter.notifyItemChanged(index)
		}
		return evictIndices
	}

	fun onProgramsLoaded(indices: List<Int>, priorityIndex: Int? = null) {
		val channelIds = indices.mapNotNull { TvManager.getChannel(it).id }
		rowCache.invalidateChannels(channelIds)
		rowCache.scheduleBuild(indices, priorityIndex)
	}

	fun notifyItemChanged(position: Int) {
		channelAdapter.notifyItemChanged(position)
		programAdapter.notifyItemChanged(position)
	}

	fun getChannelHeaderAt(position: Int): GuideChannelHeader? =
		channelAdapter.getBoundHeader(position)

	fun getProgramRowAt(position: Int): LinearLayout? =
		programAdapter.getBoundRow(position)

	private fun linkFocus(channelHeader: GuideChannelHeader?, programRow: LinearLayout?) {
		if (channelHeader == null || programRow == null || programRow.childCount == 0) return
		val firstCell = programRow.getChildAt(0) as? ProgramGridCell ?: return
		channelHeader.nextFocusRightId = firstCell.id
		firstCell.nextFocusLeftId = channelHeader.id
		relinkHeaderVerticalFocus(channelHeader, firstCell)
	}

	private fun relinkHeaderVerticalFocus(channelHeader: GuideChannelHeader, anchorCell: ProgramGridCell) {
		if (anchorCell.nextFocusUpId != View.NO_ID) {
			channelHeader.nextFocusUpId = anchorCell.nextFocusUpId
		}
		if (anchorCell.nextFocusDownId != View.NO_ID) {
			channelHeader.nextFocusDownId = anchorCell.nextFocusDownId
		}
	}

	private fun linkVerticalFocus(currentRow: LinearLayout, position: Int) {
		if (position > 0) {
			getProgramRowAt(position - 1)?.let { prev ->
				TvManager.setFocusParams(currentRow, prev, true)
				TvManager.setFocusParams(prev, currentRow, false)
				channelAdapter.getBoundHeader(position - 1)?.let { header ->
					val anchor = prev.getChildAt(0) as? ProgramGridCell
					if (anchor != null) relinkHeaderVerticalFocus(header, anchor)
				}
			}
		}
		getProgramRowAt(position + 1)?.let { next ->
			TvManager.setFocusParams(currentRow, next, false)
			TvManager.setFocusParams(next, currentRow, true)
			channelAdapter.getBoundHeader(position + 1)?.let { header ->
				val anchor = next.getChildAt(0) as? ProgramGridCell
				if (anchor != null) relinkHeaderVerticalFocus(header, anchor)
			}
		}
		channelAdapter.getBoundHeader(position)?.let { header ->
			val anchor = currentRow.getChildAt(0) as? ProgramGridCell
			if (anchor != null) relinkHeaderVerticalFocus(header, anchor)
		}
	}

	inner class ChannelAdapter : RecyclerView.Adapter<GuideChannelViewHolder>() {
		private val headers = mutableMapOf<Int, GuideChannelHeader>()

		override fun getItemCount(): Int = channelCount

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideChannelViewHolder {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.item_live_tv_guide_channel, parent, false)
			return GuideChannelViewHolder(view as FrameLayout)
		}

		override fun onBindViewHolder(holder: GuideChannelViewHolder, position: Int) {
			holder.container.removeAllViews()
			if (position >= TvManager.getChannelCount()) return

			val channel = TvManager.getChannel(position)
			val header = rowBuilder.createChannelHeader(channel)
			header.layoutParams = FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				rowHeightPx,
			)
			holder.container.addView(header)
			header.loadImage()
			headers[position] = header

			val channelId = channel.id
			if (channelId != null) {
				linkFocus(header, rowCache.getRow(channelId))
			}
		}

		override fun onViewRecycled(holder: GuideChannelViewHolder) {
			headers.entries.removeIf { it.value.parent == holder.container }
		}

		fun getBoundHeader(position: Int): GuideChannelHeader? = headers[position]
	}

	inner class ProgramAdapter : RecyclerView.Adapter<GuideProgramViewHolder>() {
		private val rows = mutableMapOf<Int, LinearLayout>()

		override fun getItemCount(): Int = channelCount

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuideProgramViewHolder {
			val view = LayoutInflater.from(parent.context)
				.inflate(R.layout.item_live_tv_guide_program_row, parent, false)
			return GuideProgramViewHolder(view as FrameLayout)
		}

		override fun onBindViewHolder(holder: GuideProgramViewHolder, position: Int) {
			holder.container.removeAllViews()
			rows.remove(position)

			if (position >= TvManager.getChannelCount()) {
				holder.itemView.layoutParams = RecyclerView.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					0,
				)
				return
			}

			val channel = TvManager.getChannel(position)
			val channelId = channel.id
			if (channelId == null) return

			holder.itemView.layoutParams = RecyclerView.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				rowHeightPx,
			)

			val cached = rowCache.getRow(channelId)
			if (cached != null) {
				rowCache.attachRow(holder.container, cached)
				rows[position] = cached
				linkVerticalFocus(cached, position)
				linkFocus(channelAdapter.getBoundHeader(position), cached)
				onProgramRowAttached(position, cached)
				return
			}

			if (!TvManager.hasProgramsForChannel(channelId)) {
				rowCache.scheduleBuild(position)
				return
			}

			if (filters.any()) {
				holder.itemView.layoutParams = RecyclerView.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					0,
				)
				return
			}

			rowCache.scheduleBuild(position)
		}

		override fun onViewRecycled(holder: GuideProgramViewHolder) {
			rows.entries.removeIf { it.value.parent == holder.container }
		}

		fun getBoundRow(position: Int): LinearLayout? = rows[position]
	}

	fun attachScrollListener(
		channelList: RecyclerView,
		programList: RecyclerView,
		onProgramRowBuilt: ((channelId: java.util.UUID) -> Unit)? = null,
		onScrollIdle: (() -> Unit)? = null,
	) {
		rowCache.attachProgramList(programList) { position ->
			notifyItemChanged(position)
			TvManager.getChannel(position).id?.let { channelId ->
				onProgramRowBuilt?.invoke(channelId)
			}
		}

		val channelLm = channelList.layoutManager as LinearLayoutManager
		val programLm = programList.layoutManager as LinearLayoutManager
		var syncingScroll = false

		fun syncProgramToChannel() {
			if (syncingScroll) return
			val pos = channelLm.findFirstVisibleItemPosition()
			if (pos == RecyclerView.NO_POSITION) return
			val top = channelLm.findViewByPosition(pos)?.top ?: 0
			syncingScroll = true
			programLm.scrollToPositionWithOffset(pos, top)
			syncingScroll = false
		}

		fun syncChannelToProgram() {
			if (syncingScroll) return
			val pos = programLm.findFirstVisibleItemPosition()
			if (pos == RecyclerView.NO_POSITION) return
			val top = programLm.findViewByPosition(pos)?.top ?: 0
			syncingScroll = true
			channelLm.scrollToPositionWithOffset(pos, top)
			syncingScroll = false
		}

		val reportRange = {
			val first = channelLm.findFirstVisibleItemPosition().coerceAtLeast(0)
			val last = channelLm.findLastVisibleItemPosition().coerceAtLeast(0)
			if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
				onVisibleRangeChanged(first, last)
			}
		}

		channelList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
				if (dy != 0) syncProgramToChannel()
			}

			override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
				if (newState == RecyclerView.SCROLL_STATE_IDLE) {
					reportRange()
					onScrollIdle?.invoke()
				}
			}
		})

		programList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
				if (dy != 0) syncChannelToProgram()
			}
		})
	}

	companion object {
		fun computePrefetchRows(viewportHeightPx: Int, rowHeightPx: Int): Int {
			if (rowHeightPx <= 0) return 12
			return (viewportHeightPx / rowHeightPx).coerceAtLeast(1)
		}
	}
}
