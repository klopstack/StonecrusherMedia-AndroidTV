package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.ProgramGridCell
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class LiveTvGuideRowBuilder(
	private val context: Context,
	private val guide: LiveTvGuide,
	private val filters: GuideFilters,
	private val guideRowHeightPx: Int,
	private val guideRowWidthPerMinPx: Int,
) {
	private val cellIdSource = AtomicInteger(1)

	fun createChannelHeader(channel: BaseItemDto): GuideChannelHeader =
		GuideChannelHeader(context, guide, channel)

	fun buildProgramRow(
		programs: List<BaseItemDto>,
		channelId: UUID,
		guideStart: LocalDateTime,
		guideEnd: LocalDateTime,
	): LinearLayout? {
		if (programs.isEmpty()) {
			if (filters.any()) return null

			val minutes = (
				(guideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - guideStart.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000
				).toInt()
			val programRow = LinearLayout(context)
			var slot = 0
			do {
				val empty = createNoProgramDataBaseItem(
					context,
					channelId,
					guideStart.plusMinutes(30L * slot),
					guideEnd.plusMinutes(30L * (slot + 1)),
				)
				val cell = ProgramGridCell(context, guide, empty, false)
				cell.id = cellIdSource.getAndIncrement()
				cell.layoutParams = ViewGroup.LayoutParams(30 * guideRowWidthPerMinPx, guideRowHeightPx)
				programRow.addView(cell)
				if (slot == 0) cell.setFirst()
				if (slot == (minutes / 30) - 1) cell.setLast()
				slot++
			} while (30 * slot < minutes)
			return programRow
		}

		val programRow = LinearLayout(context)
		var prevEnd = guide.getCurrentLocalStartDate()

		for (item in programs) {
			var start = item.startDate ?: guide.getCurrentLocalStartDate()
			if (start.isBefore(guide.getCurrentLocalStartDate())) {
				start = guide.getCurrentLocalStartDate()
			}
			if (start.isBefore(prevEnd)) continue
			if (start.isAfter(prevEnd)) {
				val empty = createNoProgramDataBaseItem(context, channelId, prevEnd, start)
				val cell = ProgramGridCell(context, guide, empty, false)
				cell.id = cellIdSource.getAndIncrement()
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
				val program = ProgramGridCell(context, guide, item, false)
				program.id = cellIdSource.getAndIncrement()
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
			val cell = ProgramGridCell(context, guide, empty, false)
			cell.id = cellIdSource.getAndIncrement()
			cell.layoutParams = ViewGroup.LayoutParams(
				((guideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000).toInt() * guideRowWidthPerMinPx,
				guideRowHeightPx,
			)
			programRow.addView(cell)
		}

		return programRow
	}
}
