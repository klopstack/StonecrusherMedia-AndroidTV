package org.jellyfin.androidtv.ui.livetv

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.data.repository.GuideCacheSnapshot
import org.jellyfin.androidtv.data.repository.GuideDiskCache
import org.jellyfin.androidtv.preference.LiveTvPreferences
import org.jellyfin.androidtv.util.sdk.isUsable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.TimeoutException
import org.jellyfin.sdk.model.api.BaseItemDto
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Prefetches Live TV guide program data for all channels into disk cache.
 */
class LiveTvGuidePrefetchWorker(
	context: Context,
	workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams), KoinComponent {
	companion object {
		const val PERIODIC_REQUEST_NAME = "LiveTvGuidePrefetchPeriodic"
		const val ONE_SHOT_REQUEST_NAME = "LiveTvGuidePrefetchOneShot"

		suspend fun schedule(workManager: WorkManager) {
			workManager.enqueueUniquePeriodicWork(
				PERIODIC_REQUEST_NAME,
				androidx.work.ExistingPeriodicWorkPolicy.UPDATE,
				PeriodicWorkRequestBuilder<LiveTvGuidePrefetchWorker>(45, TimeUnit.MINUTES)
					.setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
					.build(),
			).await()

			workManager.enqueueUniqueWork(
				ONE_SHOT_REQUEST_NAME,
				androidx.work.ExistingWorkPolicy.REPLACE,
				OneTimeWorkRequestBuilder<LiveTvGuidePrefetchWorker>()
					.setInitialDelay(30, TimeUnit.SECONDS)
					.build(),
			).await()
		}
	}

	private val api by inject<ApiClient>()
	private val liveTvPreferences by inject<LiveTvPreferences>()
	private val sessionRepository by inject<SessionRepository>()
	private val diskCache by inject<GuideDiskCache>()

	override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
		if (!api.isUsable) return@withContext Result.retry()

		val session = sessionRepository.currentSession.value ?: return@withContext Result.retry()

		try {
			val channels = loadLiveTvChannelsSync(api, liveTvPreferences) ?: return@withContext Result.retry()
			if (channels.isEmpty()) return@withContext Result.success()

			val windowStart = GuideTimeWindow.roundGuideStart(LocalDateTime.now())
			val windowEnd = windowStart.plusHours(GuideTimeWindow.BACKGROUND_PREFETCH_HOURS)
			val channelIds = channels.mapNotNull { it.id }

			val allPrograms = mutableListOf<BaseItemDto>()
			var batchStart = 0
			while (batchStart < channelIds.size) {
				val batchEnd = minOf(batchStart + LiveTvGuideProgramLoader.BATCH_SIZE, channelIds.size)
				val batch = channelIds.subList(batchStart, batchEnd).toTypedArray()
				val programs = fetchProgramsSync(api, batch, windowStart, windowEnd) ?: emptyList()
				allPrograms.addAll(programs)
				batchStart = batchEnd
			}

			val byChannel = allPrograms.groupBy { it.channelId }
				.mapNotNull { (id, list) -> id?.toString()?.let { it to list } }
				.toMap()
				.toMutableMap()
			for (channel in channels) {
				val id = channel.id ?: continue
				byChannel.putIfAbsent(id.toString(), emptyList())
			}

			diskCache.write(
				GuideCacheSnapshot(
					serverId = session.serverId.toString(),
					userId = session.userId.toString(),
					fetchedAt = System.currentTimeMillis(),
					windowStart = windowStart.toString(),
					windowEnd = windowEnd.toString(),
					programsByChannelId = byChannel,
				),
			)

			Timber.d("Guide prefetch cached %d channels", byChannel.size)
			Result.success()
		} catch (err: TimeoutException) {
			Timber.w(err, "Guide prefetch timed out")
			Result.retry()
		} catch (err: ApiClientException) {
			Timber.w(err, "Guide prefetch API error")
			Result.retry()
		} catch (err: Exception) {
			Timber.e(err, "Guide prefetch failed")
			Result.failure()
		}
	}
}
