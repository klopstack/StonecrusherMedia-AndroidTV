package org.jellyfin.androidtv.data.service.pluginsync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain

class PluginSyncConstantsTests : FunSpec({
	test("excludes Jellyseerr API key from server sync allowlist") {
		PluginSyncConstants.ALL_SERVER_KEYS shouldNotContain "jellyseerrApiKey"
	}

	test("excludes other Moonfin-sensitive API keys from server sync allowlist") {
		val sensitiveApiKeys = listOf("mdblistApiKey", "tmdbApiKey")
		for (key in sensitiveApiKeys) {
			PluginSyncConstants.ALL_SERVER_KEYS shouldNotContain key
		}
	}
})
