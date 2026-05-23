package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class LiveTvGuideRowBuilder(
	private val context: Context,
	private val guide: LiveTvGuide,
	private val filters: GuideFilters,
	private var guideStart: LocalDateTime,
	private var guideEnd: LocalDateTime,
	private val guideRowHeightPx: Int,
	private val guideRowWidthPerMinPx: Int,
	private val displayOptions: GuideCellDisplayOptions,
) {
	fun updateGuideRange(start: LocalDateTime, end: LocalDateTime) {
		guideStart = start
		guideEnd = end
	}

	fun createChannelHeader(channel: BaseItemDto): GuideChannelHeader =
		GuideChannelHeader(context, guide, channel)

	fun buildProgramRow(
		programs: List<BaseItemDto>,
		channelId: UUID,
	): LinearLayout? {
		if (programs.isEmpty()) {
			if (filters.any()) return null

			val minutes = (
				(guideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - guideStart.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000
				).toInt()
			val programRow = LinearLayout(context)
			val empty = createNoProgramDataBaseItem(context, channelId, guideStart, guideEnd)
			val cell = ProgramGridCell(context, guide, empty, false, displayOptions)
			cell.id = View.generateViewId()
			cell.layoutParams = ViewGroup.LayoutParams(minutes * guideRowWidthPerMinPx, guideRowHeightPx)
			cell.setFirst()
			cell.setLast()
			programRow.addView(cell)
			return programRow
		}

		val programRow = LinearLayout(context)
		var prevEnd = guide.getCurrentLocalStartDate()

		val sortedPrograms = programs.sortedBy { it.startDate ?: guide.getCurrentLocalStartDate() }
		for (item in sortedPrograms) {
			var start = item.startDate ?: guide.getCurrentLocalStartDate()
			if (start.isBefore(guide.getCurrentLocalStartDate())) {
				start = guide.getCurrentLocalStartDate()
			}
			if (start.isBefore(prevEnd)) {
				val itemEnd = item.endDate ?: prevEnd
				if (itemEnd.isAfter(prevEnd)) prevEnd = itemEnd
				continue
			}
			if (start.isAfter(prevEnd)) {
				val empty = createNoProgramDataBaseItem(context, channelId, prevEnd, start)
				val cell = ProgramGridCell(context, guide, empty, false, displayOptions)
				cell.id = View.generateViewId()
				cell.layoutParams = ViewGroup.LayoutParams(
					((start.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000).toInt() * guideRowWidthPerMinPx,
					guideRowHeightPx,
				)
				if (prevEnd == guideStart) cell.setFirst()
				programRow.addView(cell)
			}
			var end = item.endDate ?: guide.getCurrentLocalEndDate()
			if (end.isAfter(guide.getCurrentLocalEndDate())) end = guide.getCurrentLocalEndDate()
			prevEnd = end
			val duration = (end.toInstant(ZoneOffset.UTC).toEpochMilli() - start.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000
			if (duration > 0) {
				val program = ProgramGridCell(context, guide, item, false, displayOptions)
				program.id = View.generateViewId()
				program.layoutParams = ViewGroup.LayoutParams(
					duration.toInt() * guideRowWidthPerMinPx,
					guideRowHeightPx,
				)
				if (start == guideStart) program.setFirst()
				if (end == guideEnd) program.setLast()
				programRow.addView(program)
			}
		}

		if (prevEnd.isBefore(guideEnd)) {
			val empty = createNoProgramDataBaseItem(context, channelId, prevEnd, guideEnd)
			val cell = ProgramGridCell(context, guide, empty, false, displayOptions)
			cell.id = View.generateViewId()
			cell.layoutParams = ViewGroup.LayoutParams(
				((guideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000).toInt() * guideRowWidthPerMinPx,
				guideRowHeightPx,
			)
			programRow.addView(cell)
		}

		return programRow
	}
}
