package org.jellyfin.androidtv.ui.livetv

import android.view.View
import android.widget.RelativeLayout
import org.jellyfin.androidtv.ui.GuideChannelHeader
import java.time.LocalDateTime
import java.util.UUID

interface LiveTvGuide {
	fun scrollToChannel(index: Int)
	fun getCurrentLocalStartDate(): LocalDateTime
	fun getCurrentLocalEndDate(): LocalDateTime
	fun showProgramOptions()
	fun setSelectedProgram(programView: RelativeLayout)
	fun refreshFavorite(channelId: UUID)
	fun redirectChannelHeaderFocus(header: GuideChannelHeader)
	fun findVerticalProgramFocusTarget(header: GuideChannelHeader, direction: Int): View?
	fun onGuideDisplayEndExtended(newEnd: LocalDateTime)
	fun extendTimeLineTo(newEnd: LocalDateTime)
}
