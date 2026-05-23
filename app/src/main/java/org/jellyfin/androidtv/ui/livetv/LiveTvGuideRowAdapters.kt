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
	private val filters: GuideFilters,
	private var guideStart: LocalDateTime,
	private var guideEnd: LocalDateTime,
	private val rowHeightPx: Int,
	private val onVisibleRangeChanged: (first: Int, last: Int) -> Unit,
) {
	var channelCount: Int = 0
		private set

	private var previousProgramRow: LinearLayout? = null

	val channelAdapter = ChannelAdapter()
	val programAdapter = ProgramAdapter()

	fun setChannelCount(count: Int) {
		channelCount = count
		channelAdapter.notifyDataSetChanged()
		programAdapter.notifyDataSetChanged()
	}

	fun updateGuideRange(start: LocalDateTime, end: LocalDateTime) {
		guideStart = start
		guideEnd = end
		previousProgramRow = null
		channelAdapter.notifyDataSetChanged()
		programAdapter.notifyDataSetChanged()
	}

	fun notifyProgramsChanged(range: IntRange) {
		for (i in range) {
			channelAdapter.notifyItemChanged(i)
			programAdapter.notifyItemChanged(i)
		}
	}

	fun getChannelHeaderAt(position: Int): GuideChannelHeader? =
		channelAdapter.getBoundHeader(position)

	fun getProgramRowAt(position: Int): LinearLayout? =
		programAdapter.getBoundRow(position)

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

			val programRow = programAdapter.getBoundRow(position)
			if (programRow != null && programRow.childCount > 0) {
				val firstCell = programRow.getChildAt(0) as? ProgramGridCell
				if (firstCell != null) {
					header.nextFocusRightId = firstCell.id
					firstCell.nextFocusLeftId = header.id
				}
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
			if (position >= TvManager.getChannelCount()) {
				holder.container.layoutParams.height = 0
				return
			}

			val channel = TvManager.getChannel(position)
			val channelId = channel.id ?: return
			val programs = TvManager.getProgramsForChannel(channelId, filters)
			val programRow = when {
				TvManager.hasProgramsForChannel(channelId) ->
					rowBuilder.buildProgramRow(programs, channelId, guideStart, guideEnd)
				filters.any() -> null
				else -> rowBuilder.buildProgramRow(emptyList(), channelId, guideStart, guideEnd)
			}

			if (programRow == null) {
				holder.container.layoutParams = RecyclerView.LayoutParams(
					ViewGroup.LayoutParams.WRAP_CONTENT,
					0,
				)
				rows.remove(position)
				return
			}

			holder.container.layoutParams = RecyclerView.LayoutParams(
				ViewGroup.LayoutParams.WRAP_CONTENT,
				rowHeightPx,
			)
			holder.container.addView(programRow)
			rows[position] = programRow

			previousProgramRow?.let { prev ->
				TvManager.setFocusParams(programRow, prev, true)
				TvManager.setFocusParams(prev, programRow, false)
			}
			previousProgramRow = programRow

			channelAdapter.getBoundHeader(position)?.let { header ->
				if (programRow.childCount > 0) {
					val firstCell = programRow.getChildAt(0) as ProgramGridCell
					header.nextFocusRightId = firstCell.id
					firstCell.nextFocusLeftId = header.id
				}
			}
		}

		override fun onViewRecycled(holder: GuideProgramViewHolder) {
			rows.entries.removeIf { it.value.parent == holder.container }
		}

		fun getBoundRow(position: Int): LinearLayout? = rows[position]
	}

	fun attachScrollListener(
		channelList: RecyclerView,
		programList: RecyclerView,
	) {
		val channelLm = channelList.layoutManager as LinearLayoutManager
		val programLm = programList.layoutManager as LinearLayoutManager

		val reportRange = {
			val first = minOf(
				channelLm.findFirstVisibleItemPosition().coerceAtLeast(0),
				programLm.findFirstVisibleItemPosition().coerceAtLeast(0),
			)
			val last = maxOf(
				channelLm.findLastVisibleItemPosition().coerceAtLeast(0),
				programLm.findLastVisibleItemPosition().coerceAtLeast(0),
			)
			if (first != RecyclerView.NO_POSITION && last != RecyclerView.NO_POSITION) {
				onVisibleRangeChanged(first, last)
			}
		}

		channelList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
				if (dy != 0) programList.scrollBy(0, dy)
				reportRange()
			}

			override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
				if (newState == RecyclerView.SCROLL_STATE_IDLE) reportRange()
			}
		})

		programList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
			override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
				if (dy != 0) channelList.scrollBy(0, dy)
				reportRange()
			}

			override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
				if (newState == RecyclerView.SCROLL_STATE_IDLE) reportRange()
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
