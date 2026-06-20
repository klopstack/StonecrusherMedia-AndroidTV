package org.jellyfin.androidtv.ui.playback

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Default Media3 processor chain (silence skipping, then speed/pitch) with [AudioDelayProcessor]
 * appended so lip-sync offsets stay in real time at any playback speed.
 */
@OptIn(UnstableApi::class)
class AudioDelayProcessorChain(
    private val audioDelayProcessor: AudioDelayProcessor,
) : DefaultAudioSink.AudioProcessorChain {
    private val defaultChain = DefaultAudioSink.DefaultAudioProcessorChain()

    override fun getAudioProcessors(): Array<AudioProcessor> {
        val defaultProcessors = defaultChain.getAudioProcessors()
        return arrayOf(*defaultProcessors, audioDelayProcessor)
    }

    override fun applyPlaybackParameters(playbackParameters: PlaybackParameters): PlaybackParameters =
        defaultChain.applyPlaybackParameters(playbackParameters)

    override fun applySkipSilenceEnabled(skipSilenceEnabled: Boolean): Boolean =
        defaultChain.applySkipSilenceEnabled(skipSilenceEnabled)

    override fun getMediaDuration(playoutDuration: Long): Long =
        defaultChain.getMediaDuration(playoutDuration)

    override fun getSkippedOutputFrameCount(): Long =
        defaultChain.getSkippedOutputFrameCount()
}
