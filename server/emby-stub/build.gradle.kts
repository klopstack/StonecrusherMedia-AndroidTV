plugins {
	alias(libs.plugins.android.library)
}

android {
	namespace = "org.moonfin.server.emby"
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
	api(projects.server.core)
	implementation(libs.kotlinx.coroutines)
	coreLibraryDesugaring(libs.android.desugar)
}
