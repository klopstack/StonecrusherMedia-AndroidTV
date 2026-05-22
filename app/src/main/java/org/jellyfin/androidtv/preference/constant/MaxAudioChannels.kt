package org.jellyfin.androidtv.preference.constant

import org.jellyfin.androidtv.R
import org.jellyfin.preference.PreferenceEnum

enum class MaxAudioChannels(
	override val nameRes: Int,
	val maxChannels: Int,
) : PreferenceEnum {
	AUTO(R.string.max_audio_channels_auto, 8),
	CHANNELS_2(R.string.max_audio_channels_2, 2),
	CHANNELS_6(R.string.max_audio_channels_6, 6),
	CHANNELS_8(R.string.max_audio_channels_8, 8),
}
