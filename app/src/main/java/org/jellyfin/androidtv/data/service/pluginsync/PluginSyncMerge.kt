package org.jellyfin.androidtv.data.service.pluginsync

/**
 * Pure three-way merge for plugin sync settings.
 *
 * Extracted from [PluginSyncService] for unit testing.
 */
internal object PluginSyncMerge {

	/**
	 * Three-way merge using the last-synced snapshot as a common ancestor.
	 *
	 * For each syncable key:
	 * - If local changed from snapshot but server didn't → use local
	 * - If server changed from snapshot but local didn't → use server
	 * - If both changed (conflict) → local wins
	 * - If neither changed → use local (same value)
	 * - If no snapshot exists (first sync) → server wins for all keys
	 */
	fun mergeThreeWay(
		local: Map<String, Any?>,
		server: Map<String, Any?>,
		snapshot: Map<String, Any?>,
	): Map<String, Any?> {
		if (snapshot.isEmpty()) {
			return (local + server).filterKeys { it in PluginSyncConstants.ALL_SERVER_KEYS }
		}

		val allKeys = (local.keys + server.keys + snapshot.keys)
			.filter { it in PluginSyncConstants.ALL_SERVER_KEYS }
			.toSet()

		val merged = mutableMapOf<String, Any?>()
		for (key in allKeys) {
			val localVal = local[key]
			val serverVal = server[key]
			val snapshotVal = snapshot[key]

			val localChanged = normalizeForComparison(localVal) != normalizeForComparison(snapshotVal)
			val serverChanged = normalizeForComparison(serverVal) != normalizeForComparison(snapshotVal)

			val chosen = when {
				serverChanged && !localChanged -> serverVal
				localChanged && serverChanged -> localVal
				else -> localVal
			}
			merged[key] = chosen
		}
		return merged
	}

	/**
	 * Normalize a value to a comparable string form so that type mismatches
	 * (e.g. Int 1 vs String "1", Boolean true vs String "true") don't cause
	 * false "changed" detections during three-way merge.
	 */
	fun normalizeForComparison(value: Any?): String {
		return value?.toString() ?: ""
	}
}
