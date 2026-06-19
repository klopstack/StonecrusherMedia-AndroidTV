package org.moonfin.server.emby.socket

import android.media.AudioManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.moonfin.server.core.api.ServerWebSocketApi
import org.moonfin.server.core.model.EmbyConnectionState
import org.moonfin.server.core.model.ServerWebSocketMessage
import org.moonfin.server.emby.EmbyApiClient

class EmbyWebSocketClient(
	private val api: EmbyApiClient,
	private val audioManager: AudioManager,
) : ServerWebSocketApi {
	private val _connectionState = MutableStateFlow<EmbyConnectionState>(EmbyConnectionState.Disconnected)
	val connectionState: StateFlow<EmbyConnectionState> = _connectionState.asStateFlow()

	override val messages: Flow<ServerWebSocketMessage> = emptyFlow()

	override suspend fun connect() = Unit

	override suspend fun disconnect() = Unit

	fun forceDisconnect() = Unit
}
