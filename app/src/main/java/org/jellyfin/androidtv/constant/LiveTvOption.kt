package org.jellyfin.androidtv.constant

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import org.jellyfin.androidtv.R

object LiveTvOption {
	const val LIVE_TV_GUIDE_OPTION_ID = 1000
	const val LIVE_TV_RECORDINGS_OPTION_ID = 2000
	const val LIVE_TV_SCHEDULE_OPTION_ID = 4000
	const val LIVE_TV_SERIES_OPTION_ID = 5000

	/** Tile accent colors used for home-screen backdrop when a shortcut is focused. */
	@ColorRes
	fun backgroundColorRes(optionId: Int): Int? = when (optionId) {
		LIVE_TV_GUIDE_OPTION_ID -> R.color.spanish_blue
		LIVE_TV_RECORDINGS_OPTION_ID -> R.color.indigo_dye
		LIVE_TV_SCHEDULE_OPTION_ID -> R.color.spanish_blue
		LIVE_TV_SERIES_OPTION_ID -> R.color.midnight_blue
		else -> null
	}

	/** Tile icons composited over the accent gradient backdrop. */
	@DrawableRes
	fun backgroundIconRes(optionId: Int): Int? = when (optionId) {
		LIVE_TV_GUIDE_OPTION_ID -> R.drawable.ic_tv_guide
		LIVE_TV_RECORDINGS_OPTION_ID -> R.drawable.ic_tv_play
		LIVE_TV_SCHEDULE_OPTION_ID -> R.drawable.ic_time
		LIVE_TV_SERIES_OPTION_ID -> R.drawable.ic_tv_timer
		else -> null
	}
}
