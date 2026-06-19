package org.jellyfin.androidtv.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.data.repository.AccessScheduleRepository
import org.jellyfin.androidtv.data.repository.AccessScheduleStatus
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.playback.PlaybackControllerContainer
import org.jellyfin.androidtv.util.AccessScheduleEvaluator
import org.jellyfin.androidtv.auth.repository.UserRepository
import java.time.LocalDateTime

class AccessScheduleViewModel(
	private val context: Context,
	private val accessScheduleRepository: AccessScheduleRepository,
	private val userRepository: UserRepository,
	private val playbackControllerContainer: PlaybackControllerContainer,
	private val mediaManager: MediaManager,
) : ViewModel() {
	private val _showBlockedOverlay = MutableStateFlow(false)
	val showBlockedOverlay: StateFlow<Boolean> = _showBlockedOverlay.asStateFlow()

	private val _blockedTitle = MutableStateFlow("")
	val blockedTitle: StateFlow<String> = _blockedTitle.asStateFlow()

	private val _blockedMessage = MutableStateFlow("")
	val blockedMessage: StateFlow<String> = _blockedMessage.asStateFlow()

	private var pendingBlock = false

	init {
		accessScheduleRepository.status
			.onEach { status ->
				when (status) {
					AccessScheduleStatus.Allowed -> pendingBlock = false
					is AccessScheduleStatus.Denied -> {
						if (isPlaybackActive()) {
							pendingBlock = true
						} else {
							showBlocked(status.nextAccessStart)
						}
					}
				}
			}
			.launchIn(viewModelScope)

		accessScheduleRepository.forceBlockOverlay
			.onEach { showBlocked(accessScheduleRepository.status.value.let {
				if (it is AccessScheduleStatus.Denied) it.nextAccessStart else null
			}) }
			.launchIn(viewModelScope)

		viewModelScope.launch {
			while (true) {
				kotlinx.coroutines.delay(5_000)
				if (pendingBlock && !isPlaybackActive()) {
					val status = accessScheduleRepository.status.value
					if (status is AccessScheduleStatus.Denied) {
						pendingBlock = false
						showBlocked(status.nextAccessStart)
					}
				}
			}
		}
	}

	fun showBlocked(nextAccessStart: LocalDateTime? = null) {
		val policy = userRepository.currentUser.value?.policy
		val resumeAt = nextAccessStart ?: AccessScheduleEvaluator.getNextAccessStart(policy)
		_blockedTitle.value = context.getString(org.jellyfin.androidtv.R.string.access_schedule_denied_title)
		_blockedMessage.value = buildString {
			append(context.getString(org.jellyfin.androidtv.R.string.access_schedule_denied_message))
			AccessScheduleEvaluator.formatNextAccessMessage(context, resumeAt)?.let { resumeMessage ->
				append("\n\n")
				append(resumeMessage)
			}
		}
		_showBlockedOverlay.value = true
	}

	fun dismissBlockedOverlay() {
		_showBlockedOverlay.value = false
	}

	fun isPlaybackActive(): Boolean {
		val controller = playbackControllerContainer.playbackController
		if (controller != null && (controller.isPlaying || controller.isPaused)) return true
		if (mediaManager.isPlayingAudio) return true
		return false
	}
}
