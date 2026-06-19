enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "stonecrusher-media-androidtv"

val embyEnabled: Boolean = providers.gradleProperty("moonfin.emby.enabled")
	.orElse(providers.environmentVariable("MOONFIN_EMBY_ENABLED"))
	.map { it.toBooleanStrictOrNull() ?: false }
	.getOrElse(false)

pluginManagement {
	repositories {
		gradlePluginPortal()
		mavenCentral()
		google()
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

// Application
include(":app")

// Modules
include(":design")
include(":server:core")
include(":server:jellyfin")
include(":server:emby")
include(":playback:core")
include(":playback:jellyfin")
include(":playback:emby")

if (!embyEnabled) {
	project(":server:emby").projectDir = file("server/emby-stub")
	project(":playback:emby").projectDir = file("playback/emby-stub")
}
include(":playback:media3:exoplayer")
include(":playback:media3:session")
include(":preference")

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		google()

		mavenLocal {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "latest-SNAPSHOT")
			}
		}

		// Bundled local JARs (e.g. emby-client which is not on Maven Central)
		flatDir {
			dirs("libs")
		}
		maven("https://s01.oss.sonatype.org/content/repositories/snapshots/") {
			content {
				includeVersionByRegex("org.jellyfin.sdk", ".*", "master-SNAPSHOT")
				includeVersionByRegex("org.jellyfin.sdk", ".*", "openapi-unstable-SNAPSHOT")
			}
		}

		// NewPipe Extractor (YouTube stream URL resolution with n-parameter descrambling)
		maven("https://jitpack.io")
	}
}
