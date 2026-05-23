package org.jellyfin.androidtv.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.model.api.BaseItemDto
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID

@Serializable
data class GuideCacheSnapshot(
	val serverId: String,
	val userId: String,
	val fetchedAt: Long,
	val windowStart: String,
	val windowEnd: String,
	val programsByChannelId: Map<String, List<BaseItemDto>>,
)

/**
 * Disk cache for Live TV guide program data (all channels, short horizon).
 */
class GuideDiskCache(context: Context) {
	companion object {
		private const val PREFS_NAME = "live_tv_guide_cache"
		private const val KEY_SNAPSHOT = "guide_snapshot"
	}

	private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
	private val json = Json {
		ignoreUnknownKeys = true
		encodeDefaults = true
	}

	fun read(serverId: String, userId: String): GuideCacheSnapshot? {
		val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
		return try {
			val snapshot = json.decodeFromString<GuideCacheSnapshot>(raw)
			if (snapshot.serverId != serverId || snapshot.userId != userId) return null
			val ageMinutes = (System.currentTimeMillis() - snapshot.fetchedAt) / 60_000
			if (ageMinutes > org.jellyfin.androidtv.ui.livetv.GuideTimeWindow.DISK_CACHE_TTL_MINUTES) return null
			snapshot
		} catch (e: Exception) {
			Timber.e(e, "Failed to read guide disk cache")
			null
		}
	}

	fun write(snapshot: GuideCacheSnapshot) {
		try {
			prefs.edit().putString(KEY_SNAPSHOT, json.encodeToString(snapshot)).apply()
		} catch (e: Exception) {
			Timber.e(e, "Failed to write guide disk cache")
		}
	}

	fun clear() {
		prefs.edit().remove(KEY_SNAPSHOT).apply()
	}

	fun toRepositoryMap(snapshot: GuideCacheSnapshot): Map<UUID, List<BaseItemDto>> =
		snapshot.programsByChannelId.mapNotNull { (key, programs) ->
			runCatching { UUID.fromString(key) to programs }.getOrNull()
		}.toMap()

	fun windowStart(snapshot: GuideCacheSnapshot): LocalDateTime =
		LocalDateTime.parse(snapshot.windowStart)

	fun windowEnd(snapshot: GuideCacheSnapshot): LocalDateTime =
		LocalDateTime.parse(snapshot.windowEnd)
}
