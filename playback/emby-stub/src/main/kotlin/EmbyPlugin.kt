package org.moonfin.playback.emby

import org.jellyfin.playback.core.plugin.playbackPlugin
import org.jellyfin.sdk.model.api.DeviceProfile
import org.moonfin.server.emby.EmbyApiClient

fun embyPlugin(
	api: EmbyApiClient,
	deviceProfileBuilder: () -> DeviceProfile,
) = playbackPlugin { }
