package org.jellyfin.androidtv.ui.livetv

import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.RecyclerView
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Caches built program rows so [RecyclerView] bind is a cheap re-attach, not a full re-inflate.
 * Rows are built in small batches on the main thread; priority rows can build while scrolling.
 */
class LiveTvGuideRowCache(
	private val rowBuilder: LiveTvGuideRowBuilder,
	private val filters: GuideFilters,
) {
	private val rows = ConcurrentHashMap<UUID, LinearLayout>()
	private val pendingPositions = LinkedHashSet<Int>()
	private var priorityPosition: Int? = null
	private var buildScheduled = false
	private var programList: RecyclerView? = null
	private var onRowBuilt: ((Int) -> Unit)? = null

	fun attachProgramList(list: RecyclerView, onRowBuilt: (Int) -> Unit) {
		programList = list
		this.onRowBuilt = onRowBuilt
	}

	fun clear() {
		rows.clear()
		pendingPositions.clear()
		priorityPosition = null
		buildScheduled = false
	}

	fun invalidateChannel(channelId: UUID) {
		rows.remove(channelId)
	}

	fun invalidateChannels(channelIds: Collection<UUID>) {
		for (id in channelIds) {
			rows.remove(id)
		}
	}

	fun getRow(channelId: UUID): LinearLayout? = rows[channelId]

	fun attachRow(container: FrameLayout, row: LinearLayout) {
		(row.parent as? ViewGroup)?.removeView(row)
		container.addView(row)
	}

	fun scheduleBuild(position: Int, priority: Boolean = false) {
		pendingPositions.add(position)
		if (priority) priorityPosition = position
		scheduleFlush(urgent = priority)
	}

	fun scheduleBuild(indices: Collection<Int>, priorityIndex: Int? = null) {
		if (priorityIndex != null) {
			priorityPosition = priorityIndex
			pendingPositions.add(priorityIndex)
		}
		pendingPositions.addAll(indices)
		scheduleFlush(urgent = priorityIndex != null)
	}

	private fun scheduleFlush(urgent: Boolean = false) {
		if (buildScheduled) return
		buildScheduled = true
		programList?.post { flushPendingBuilds(urgent) } ?: run {
			buildScheduled = false
		}
	}

	private fun flushPendingBuilds(urgent: Boolean = false) {
		val list = programList ?: run {
			buildScheduled = false
			return
		}

		if (!urgent && list.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
			list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
				override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
					if (newState == RecyclerView.SCROLL_STATE_IDLE) {
						recyclerView.removeOnScrollListener(this)
						flushPendingBuilds()
					}
				}
			})
			return
		}

		buildScheduled = false
		if (pendingPositions.isEmpty()) return

		val priority = priorityPosition
		val batch = pendingPositions
			.sortedBy { if (priority == null) it else abs(it - priority) }
			.take(BUILD_BATCH_PER_FRAME)
		pendingPositions.removeAll(batch.toSet())

		for (position in batch) {
			if (position >= TvManager.getChannelCount()) continue
			val channel = TvManager.getChannel(position)
			val channelId = channel.id ?: continue
			if (rows.containsKey(channelId)) continue
			if (!TvManager.hasProgramsForChannel(channelId) && !filters.any()) continue

			val programs = TvManager.getProgramsForChannel(channelId, filters)
			val row = rowBuilder.buildProgramRow(programs, channelId) ?: continue

			rows[channelId] = row
			onRowBuilt?.invoke(position)
		}

		if (pendingPositions.isEmpty()) {
			priorityPosition = null
		}

		if (pendingPositions.isNotEmpty()) {
			buildScheduled = true
			list.post { flushPendingBuilds(urgent) }
		}
	}

	companion object {
		private const val BUILD_BATCH_PER_FRAME = 10
	}
}
