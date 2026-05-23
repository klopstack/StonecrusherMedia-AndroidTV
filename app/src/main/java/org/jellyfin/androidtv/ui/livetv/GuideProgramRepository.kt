package org.jellyfin.androidtv.ui.livetv

import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory program cache with incremental time-range loading and merge/dedupe.
 */
class GuideProgramRepository {
	private val programsByChannel = ConcurrentHashMap<UUID, MutableList<BaseItemDto>>()
	private val channelRanges = ConcurrentHashMap<UUID, ChannelTimeRange>()
	private val loadingChannels = ConcurrentHashMap.newKeySet<UUID>()

	var loadedStart: LocalDateTime? = null
		private set
	var loadedEnd: LocalDateTime? = null
		private set

	private data class ChannelTimeRange(
		var start: LocalDateTime,
		var end: LocalDateTime,
	)

	fun clear() {
		programsByChannel.clear()
		channelRanges.clear()
		loadingChannels.clear()
		loadedStart = null
		loadedEnd = null
	}

	fun markLoading(channelId: UUID, loading: Boolean) {
		if (loading) loadingChannels.add(channelId) else loadingChannels.remove(channelId)
	}

	fun isLoading(channelId: UUID): Boolean = loadingChannels.contains(channelId)

	fun hasProgramsForChannel(channelId: UUID): Boolean = channelRanges.containsKey(channelId)

	fun needsFetch(channelId: UUID, start: LocalDateTime, end: LocalDateTime): Boolean {
		if (isLoading(channelId)) return false
		val range = channelRanges[channelId] ?: return true
		return start.isBefore(range.start) || end.isAfter(range.end)
	}

	fun getFetchInterval(channelId: UUID, start: LocalDateTime, end: LocalDateTime): Pair<LocalDateTime, LocalDateTime>? {
		if (isLoading(channelId)) return null
		val range = channelRanges[channelId] ?: return start to end
		var fetchStart: LocalDateTime? = null
		var fetchEnd: LocalDateTime? = null
		if (start.isBefore(range.start)) {
			fetchStart = start
			fetchEnd = minOf(range.start, end)
		}
		if (end.isAfter(range.end)) {
			val rightStart = maxOf(range.end, start)
			if (fetchStart == null) {
				fetchStart = rightStart
				fetchEnd = end
			} else {
				fetchEnd = end
			}
		}
		if (fetchStart == null || fetchEnd == null || !fetchStart.isBefore(fetchEnd)) return null
		return fetchStart to fetchEnd
	}

	fun mergePrograms(
		programs: Collection<BaseItemDto>,
		fetchStart: LocalDateTime,
		fetchEnd: LocalDateTime,
		channelIdsInBatch: Collection<UUID>,
	) {
		updateGlobalBounds(fetchStart, fetchEnd)

		val channelsWithData = mutableSetOf<UUID>()
		for (program in programs) {
			val channelId = program.channelId ?: continue
			channelsWithData.add(channelId)
			if (program.endDate != null && !program.endDate!!.isAfter(fetchStart)) continue

			val list = programsByChannel.getOrPut(channelId) { mutableListOf() }
			if (!containsProgram(list, program)) {
				list.add(program)
			}
			extendChannelRange(channelId, fetchStart, fetchEnd)
		}

		for (channelId in channelIdsInBatch) {
			if (!channelsWithData.contains(channelId)) {
				programsByChannel.putIfAbsent(channelId, mutableListOf())
				extendChannelRange(channelId, fetchStart, fetchEnd)
			}
			programsByChannel[channelId]?.sortBy { it.startDate }
			loadingChannels.remove(channelId)
		}
	}

	fun hydrate(
		programsByChannelId: Map<UUID, List<BaseItemDto>>,
		windowStart: LocalDateTime,
		windowEnd: LocalDateTime,
	) {
		clear()
		for ((channelId, programs) in programsByChannelId) {
			val list = programsByChannel.getOrPut(channelId) { mutableListOf() }
			for (program in programs) {
				if (!containsProgram(list, program)) {
					list.add(program)
				}
			}
			extendChannelRange(channelId, windowStart, windowEnd)
			list.sortBy { it.startDate }
		}
		updateGlobalBounds(windowStart, windowEnd)
	}

	fun getProgramsForChannel(channelId: UUID, filters: GuideFilters): List<BaseItemDto> {
		if (!programsByChannel.containsKey(channelId)) return emptyList()
		val results = programsByChannel[channelId] ?: return emptyList()
		if (!filters.any()) return results.toList()

		var passes = false
		for (program in results) {
			if (filters.passesFilter(program)) {
				passes = true
				break
			}
		}
		return if (passes) results.toList() else emptyList()
	}

	fun getProgramsForChannel(channelId: UUID): List<BaseItemDto> =
		programsByChannel[channelId]?.toList() ?: emptyList()

	fun evictChannelsOutside(keepChannelIds: Set<UUID>) {
		val remove = programsByChannel.keys.filter { it !in keepChannelIds }
		for (id in remove) {
			programsByChannel.remove(id)
			channelRanges.remove(id)
			loadingChannels.remove(id)
		}
		recomputeGlobalBounds()
	}

	fun evictTimeBefore(before: LocalDateTime) {
		for ((channelId, list) in programsByChannel) {
			list.removeAll { program ->
				program.endDate != null && program.endDate!!.isBefore(before)
			}
			val range = channelRanges[channelId] ?: continue
			if (range.end.isBefore(before)) {
				programsByChannel.remove(channelId)
				channelRanges.remove(channelId)
			} else if (range.start.isBefore(before)) {
				range.start = before
			}
		}
		recomputeGlobalBounds()
	}

	fun snapshotPrograms(): Map<UUID, List<BaseItemDto>> =
		programsByChannel.mapValues { it.value.toList() }

	private fun extendChannelRange(channelId: UUID, start: LocalDateTime, end: LocalDateTime) {
		val existing = channelRanges[channelId]
		if (existing == null) {
			channelRanges[channelId] = ChannelTimeRange(start, end)
		} else {
			if (start.isBefore(existing.start)) existing.start = start
			if (end.isAfter(existing.end)) existing.end = end
		}
	}

	private fun updateGlobalBounds(start: LocalDateTime, end: LocalDateTime) {
		if (loadedStart == null || start.isBefore(loadedStart)) loadedStart = start
		if (loadedEnd == null || end.isAfter(loadedEnd)) loadedEnd = end
	}

	private fun recomputeGlobalBounds() {
		loadedStart = channelRanges.values.minOfOrNull { it.start }
		loadedEnd = channelRanges.values.maxOfOrNull { it.end }
	}

	private fun containsProgram(list: List<BaseItemDto>, program: BaseItemDto): Boolean {
		val id = program.id
		val start = program.startDate
		return list.any { existing ->
			existing.id == id && existing.startDate == start
		}
	}
}
