package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.androidtv.preference.LiveTvPreferences

/**
 * Snapshot of guide display preferences so each program cell does not hit Koin.
 */
data class GuideCellDisplayOptions(
	val colorCodeGuide: Boolean,
	val showNewIndicator: Boolean,
	val showPremiereIndicator: Boolean,
	val showRepeatIndicator: Boolean,
	val showHdIndicator: Boolean,
) {
	companion object {
		fun from(preferences: LiveTvPreferences): GuideCellDisplayOptions = GuideCellDisplayOptions(
			colorCodeGuide = preferences[LiveTvPreferences.colorCodeGuide],
			showNewIndicator = preferences[LiveTvPreferences.showNewIndicator],
			showPremiereIndicator = preferences[LiveTvPreferences.showPremiereIndicator],
			showRepeatIndicator = preferences[LiveTvPreferences.showRepeatIndicator],
			showHdIndicator = preferences[LiveTvPreferences.showHDIndicator],
		)
	}
}
