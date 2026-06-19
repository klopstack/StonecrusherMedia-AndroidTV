package org.jellyfin.androidtv.data.service.pluginsync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PluginSyncStoreSelectorTests : FunSpec({
	test("uses per-user store for PIN hash preference") {
		PluginSyncStoreSelector.usePerUserUserSettingStore(
			PluginSyncConstants.USER_SETTING_PREFERENCES.first { it.preference == org.jellyfin.androidtv.preference.UserSettingPreferences.userPinHash }
		) shouldBe true
	}

	test("uses per-user store for PIN enabled preference") {
		PluginSyncStoreSelector.usePerUserUserSettingStore(
			PluginSyncConstants.USER_SETTING_PREFERENCES.first { it.preference == org.jellyfin.androidtv.preference.UserSettingPreferences.userPinEnabled }
		) shouldBe true
	}

	test("uses per-user store for PIN setup declined preference") {
		PluginSyncStoreSelector.usePerUserUserSettingStore(
			PluginSyncConstants.USER_SETTING_PREFERENCES.first { it.preference == org.jellyfin.androidtv.preference.UserSettingPreferences.userPinSetupDeclined }
		) shouldBe true
	}

	test("keeps media bar preferences on global user settings store") {
		PluginSyncStoreSelector.usePerUserUserSettingStore(
			PluginSyncConstants.USER_SETTING_PREFERENCES.first { it.preference == org.jellyfin.androidtv.preference.UserSettingPreferences.mediaBarEnabled }
		) shouldBe false
	}
})
