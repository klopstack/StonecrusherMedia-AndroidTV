package org.jellyfin.androidtv.data.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.util.AccessScheduleEvaluator
import org.jellyfin.sdk.api.client.exception.ApiClientException
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.UserPolicy
import org.moonfin.server.emby.EmbyApiException
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicBoolean

sealed class AccessScheduleStatus {
	data object Allowed : AccessScheduleStatus()
	data class Denied(val nextAccessStart: LocalDateTime?) : AccessScheduleStatus()
}

interface AccessScheduleRepository {
	val status: StateFlow<AccessScheduleStatus>
	val forceBlockOverlay: SharedFlow<Unit>
	val loginDeniedNextAccess: StateFlow<LocalDateTime?>

	fun evaluatePolicy(policy: UserPolicy?, now: LocalDateTime = LocalDateTime.now()): AccessScheduleStatus
	fun checkNow(): AccessScheduleStatus
	fun setLoginDenied(nextAccessStart: LocalDateTime?)
	fun consumeLoginDenied(): LocalDateTime?
	fun hasPendingLoginDenied(): Boolean
	fun clearPendingLoginDenied()
	fun requestBlockedOverlay()
	fun isScheduleRelatedApiError(error: Throwable): Boolean
	fun isCurrentlyDenied(): Boolean
}

class AccessScheduleRepositoryImpl(
	private val userRepository: UserRepository,
) : AccessScheduleRepository {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

	private val _status = MutableStateFlow<AccessScheduleStatus>(AccessScheduleStatus.Allowed)
	override val status = _status.asStateFlow()

	private val _forceBlockOverlay = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
	override val forceBlockOverlay = _forceBlockOverlay.asSharedFlow()

	private val _loginDeniedNextAccess = MutableStateFlow<LocalDateTime?>(null)
	override val loginDeniedNextAccess = _loginDeniedNextAccess.asStateFlow()
	private val loginDeniedPending = AtomicBoolean(false)

	init {
		userRepository.currentUser
			.map { user -> evaluatePolicy(user?.policy) }
			.distinctUntilChanged()
			.onEach { _status.value = it }
			.launchIn(scope)

		scope.launch {
			while (isActive) {
				val policy = userRepository.currentUser.value?.policy
				val now = LocalDateTime.now()
				val nextCheck = AccessScheduleEvaluator.getNextStatusChange(policy, now)
				val delayMs = nextCheck?.let {
					Duration.between(now, it).toMillis().coerceIn(1_000L, 86_400_000L)
				} ?: 60_000L
				delay(delayMs)
				_status.value = checkNow()
			}
		}
	}

	override fun evaluatePolicy(policy: UserPolicy?, now: LocalDateTime): AccessScheduleStatus {
		return if (AccessScheduleEvaluator.isAccessAllowed(policy, now)) {
			AccessScheduleStatus.Allowed
		} else {
			AccessScheduleStatus.Denied(AccessScheduleEvaluator.getNextAccessStart(policy, now))
		}
	}

	override fun checkNow(): AccessScheduleStatus = evaluatePolicy(userRepository.currentUser.value?.policy)

	override fun setLoginDenied(nextAccessStart: LocalDateTime?) {
		loginDeniedPending.set(true)
		_loginDeniedNextAccess.value = nextAccessStart
	}

	override fun hasPendingLoginDenied(): Boolean = loginDeniedPending.get()

	override fun clearPendingLoginDenied() {
		loginDeniedPending.set(false)
		_loginDeniedNextAccess.value = null
	}

	override fun consumeLoginDenied(): LocalDateTime? {
		if (!loginDeniedPending.getAndSet(false)) return null
		val value = _loginDeniedNextAccess.value
		_loginDeniedNextAccess.value = null
		return value
	}

	override fun isCurrentlyDenied(): Boolean = status.value is AccessScheduleStatus.Denied

	override fun requestBlockedOverlay() {
		_forceBlockOverlay.tryEmit(Unit)
	}

	override fun isScheduleRelatedApiError(error: Throwable): Boolean {
		val message = error.message.orEmpty()

		when (error) {
			is InvalidStatusException -> {
				if (error.status != 403) return false
				return message.contains("not allowed access", ignoreCase = true)
					|| message.contains("not allowed at this time", ignoreCase = true)
			}
			is EmbyApiException -> {
				if (error.statusCode != 403) return false
				return message.contains("not allowed", ignoreCase = true)
			}
			is ApiClientException -> {
				return message.contains("403") && message.contains("not allowed", ignoreCase = true)
			}
		}

		return message.contains("403")
			&& message.contains("not allowed", ignoreCase = true)
	}
}
