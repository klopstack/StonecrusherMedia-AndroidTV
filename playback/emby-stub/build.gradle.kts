plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "org.moonfin.playback.emby"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	compileOptions {
		isCoreLibraryDesugaringEnabled = true
	}

	lint {
		lintConfig = file("$rootDir/android-lint.xml")
		abortOnError = false
	}
}

dependencies {
	api(projects.playback.core)
	api(projects.server.emby)
	implementation(libs.jellyfin.sdk)
	coreLibraryDesugaring(libs.android.desugar)
}
