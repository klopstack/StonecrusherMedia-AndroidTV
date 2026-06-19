package org.jellyfin.androidtv.auth.model

import org.jellyfin.sdk.api.client.exception.ApiClientException
import java.time.LocalDateTime

sealed class LoginState
data object AuthenticatingState : LoginState()
data object RequireSignInState : LoginState()
data object ServerUnavailableState : LoginState()
data class ServerVersionNotSupported(val server: Server) : LoginState()
data class ServerTypeNotSupportedLoginState(val server: Server) : LoginState()
data class ApiClientErrorLoginState(val error: ApiClientException) : LoginState()
data class AccessScheduleDeniedLoginState(val nextAccessStart: LocalDateTime?) : LoginState()
data object AuthenticatedState : LoginState()
