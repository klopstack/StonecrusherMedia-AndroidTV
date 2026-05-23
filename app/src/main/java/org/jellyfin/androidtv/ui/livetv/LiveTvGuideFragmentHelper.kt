package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.model.DataRefreshService
import org.jellyfin.androidtv.data.repository.GuideCacheSnapshot
import org.jellyfin.androidtv.data.repository.GuideDiskCache
import org.jellyfin.androidtv.data.repository.ItemMutationRepository
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding
import org.jellyfin.androidtv.ui.GuideChannelHeader
import org.jellyfin.androidtv.ui.base.StonecrusherTheme
import org.jellyfin.androidtv.ui.navigation.ProvideRouter
import org.jellyfin.androidtv.ui.settings.Routes
import org.jellyfin.androidtv.ui.settings.composable.SettingsDialog
import org.jellyfin.androidtv.ui.settings.composable.SettingsRouterContent
import org.jellyfin.androidtv.ui.settings.routes
import org.jellyfin.androidtv.util.Utils
import org.jellyfin.androidtv.util.getTimeFormatter
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID

fun createNoProgramDataBaseItem(
	context: Context,
	channelId: UUID?,
	startDate: LocalDateTime?,
	endDate: LocalDateTime?,
) = BaseItemDto(
	id = UUID.randomUUID(),
	type = BaseItemKind.FOLDER,
	mediaType = MediaType.UNKNOWN,
	name = context.getString(R.string.no_program_data),
	channelId = channelId,
	startDate = startDate,
	endDate = endDate,
)

fun LiveTvGuideFragment.toggleFavorite() {
	val header = mSelectedProgramView as? GuideChannelHeader
	val channel = header?.channel ?: return

	val itemMutationRepository by inject<ItemMutationRepository>()
	val dataRefreshService by inject<DataRefreshService>()

	lifecycleScope.launch {
		runCatching {
			val userData = itemMutationRepository.setFavorite(
				item = header.channel.id,
				favorite = !(channel.userData?.isFavorite ?: false),
			)

			header.channel = header.channel.copy(userData = userData)
			header.findViewById<View>(R.id.favImage).isVisible = userData.isFavorite
			dataRefreshService.lastFavoriteUpdate = Instant.now()
		}
	}
}

fun LiveTvGuideFragment.refreshSelectedProgram() {
	val api by inject<ApiClient>()

	lifecycleScope.launch {
		runCatching {
			val item = withContext(Dispatchers.IO) {
				api.userLibraryApi.getItem(mSelectedProgram.id).content
			}
			mSelectedProgram = item
		}.onFailure { error ->
			Timber.e(error, "Unable to get program details")
		}

		detailUpdateInternal()
	}
}

fun LiveTvGuideFragment.addSettingsOptions(binding: LiveTvGuideBinding): MutableStateFlow<Boolean> {
	val visible = MutableStateFlow(false)

	binding.settingsOptions.setContent {
		val isVisible by visible.collectAsState(false)

		StonecrusherTheme {
			ProvideRouter(
				routes,
				Routes.LIVETV_GUIDE_OPTIONS,
			) {
				SettingsDialog(
					visible = isVisible,
					onDismissRequest = {
						visible.value = false
						TvManager.forceReload()
						doLoad()
					},
				) {
					SettingsRouterContent()
				}
			}
		}
	}

	return visible
}

fun LiveTvGuideFragment.addSettingsFilters(binding: LiveTvGuideBinding): MutableStateFlow<Boolean> {
	val visible = MutableStateFlow(false)

	binding.settingsFilters.setContent {
		val isVisible by visible.collectAsState(false)

		StonecrusherTheme {
			ProvideRouter(
				routes,
				Routes.LIVETV_GUIDE_FILTERS,
			) {
				SettingsDialog(
					visible = isVisible,
					onDismissRequest = {
						visible.value = false
						TvManager.forceReload()
						doLoad()
					},
				) {
					SettingsRouterContent()
				}
			}
		}
	}

	return visible
}

fun readGuideDiskSnapshot(fragment: Fragment, diskCache: GuideDiskCache): GuideCacheSnapshot? {
	val sessionRepository by fragment.inject<SessionRepository>()
	val session = sessionRepository.currentSession.value ?: return null
	return diskCache.read(session.serverId.toString(), session.userId.toString())
}

fun fillLinearTimeLine(
	timeline: LinearLayout,
	context: Context,
	start: LocalDateTime,
	end: LocalDateTime,
) {
	val guideRowWidthPerMinPx = Utils.convertDpToPixel(
		context,
		LiveTvGuideFragment.GUIDE_ROW_WIDTH_PER_MIN_DP,
	)
	val oneHour = 60 * guideRowWidthPerMinPx
	timeline.removeAllViews()
	var current = start
	while (current.isBefore(end)) {
		val time = TextView(context)
		time.text = context.getTimeFormatter().format(current)
		val segmentEnd = current.plusMinutes(60)
		val segmentMinutes = ChronoUnit.MINUTES.between(current, minOf(segmentEnd, end)).toInt()
		time.width = if (segmentMinutes >= 60) oneHour else segmentMinutes * guideRowWidthPerMinPx
		timeline.addView(time)
		current = segmentEnd
	}
}

fun LiveTvGuideFragment.fillLinearTimeLine(start: LocalDateTime, end: LocalDateTime) {
	fillLinearTimeLine(mTimeline, requireContext(), start, end)
}

fun LiveTvGuideFragment.buildInitialTimeLine(start: LocalDateTime) {
	val rounded = GuideTimeWindow.roundGuideStart(start)
	fillTimeLine(rounded, getDisplayHours())
}

fun appendLinearTimeLineSegments(
	timeline: LinearLayout,
	context: Context,
	from: LocalDateTime,
	to: LocalDateTime,
) {
	val guideRowWidthPerMinPx = Utils.convertDpToPixel(
		context,
		LiveTvGuideFragment.GUIDE_ROW_WIDTH_PER_MIN_DP,
	)
	val oneHour = 60 * guideRowWidthPerMinPx
	var current = from
	while (current.isBefore(to)) {
		val time = TextView(context)
		time.text = context.getTimeFormatter().format(current)
		val segmentEnd = current.plusMinutes(60)
		val segmentMinutes = ChronoUnit.MINUTES.between(current, minOf(segmentEnd, to)).toInt()
		time.width = if (segmentMinutes >= 60) oneHour else segmentMinutes * guideRowWidthPerMinPx
		timeline.addView(time)
		current = segmentEnd
	}
}

fun LiveTvGuideFragment.extendTimeLineTo(newEnd: LocalDateTime) {
	if (!newEnd.isAfter(mCurrentGuideEnd)) return
	appendLinearTimeLineSegments(mTimeline, requireContext(), mCurrentGuideEnd, newEnd)
	mCurrentGuideEnd = newEnd
	updateChannelStatus()
}

fun LiveTvGuideFragment.getDisplayHours(): Int =
	if (mFilters.any()) GuideTimeWindow.FILTERED_LOAD_HOURS.toInt()
	else GuideTimeWindow.INITIAL_LOAD_HOURS.toInt()
