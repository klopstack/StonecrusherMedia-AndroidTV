package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.AccessScheduleDeniedLoginState
import org.jellyfin.androidtv.auth.model.ApiClientErrorLoginState
import org.jellyfin.androidtv.auth.model.AuthenticatedState
import org.jellyfin.androidtv.auth.model.AuthenticatingState
import org.jellyfin.androidtv.auth.model.ConnectedQuickConnectState
import org.jellyfin.androidtv.auth.model.PendingQuickConnectState
import org.jellyfin.androidtv.auth.model.RequireSignInState
import org.jellyfin.androidtv.auth.model.ServerUnavailableState
import org.jellyfin.androidtv.auth.model.ServerTypeNotSupportedLoginState
import org.jellyfin.androidtv.auth.model.ServerVersionNotSupported
import org.jellyfin.androidtv.util.displayName
import org.jellyfin.androidtv.auth.model.UnavailableQuickConnectState
import org.jellyfin.androidtv.auth.model.UnknownQuickConnectState
import org.jellyfin.androidtv.auth.repository.ServerRepository
import org.jellyfin.androidtv.databinding.FragmentUserLoginQuickConnectBinding
import org.jellyfin.androidtv.ui.startup.UserLoginViewModel
import org.jellyfin.androidtv.util.QrCodeEncoder
import org.jellyfin.androidtv.util.buildQuickConnectAuthorizeUrl
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import java.time.ZoneId

class UserLoginQuickConnectFragment : Fragment() {
	private val userLoginViewModel: UserLoginViewModel by activityViewModel()
	private var _binding: FragmentUserLoginQuickConnectBinding? = null
	private val binding get() = _binding!!

	override fun onCreateView(
		inflater: LayoutInflater,
		container: ViewGroup?,
		savedInstanceState: Bundle?
	): View {
		_binding = FragmentUserLoginQuickConnectBinding.inflate(inflater, container, false)
		return binding.root
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)

		userLoginViewModel.clearLoginState()

		lifecycleScope.launch {
			viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
				userLoginViewModel.initiateQuickconnect()

				// React to Quick Connect specific state
				userLoginViewModel.quickConnectState.onEach { state ->
					when (state) {
						is PendingQuickConnectState -> {
							binding.quickConnectCode.text = state.code.formatCode()
							binding.loading.isVisible = false
							updateQuickConnectQr(state.code)
						}

						UnavailableQuickConnectState,
						UnknownQuickConnectState,
						ConnectedQuickConnectState -> {
							binding.loading.isVisible = true
							hideQuickConnectQr()
						}
					}
				}.launchIn(this)

				// React to login state
				userLoginViewModel.loginState.onEach { state ->
					when (state) {
						is ServerVersionNotSupported -> binding.error.setText(
							getString(
								R.string.server_issue_outdated_version,
								state.server.version,
								ServerRepository.recommendedServerVersion.toString()
							)
						)

						is ServerTypeNotSupportedLoginState -> binding.error.setText(
							getString(
								R.string.server_type_not_supported,
								state.server.serverType.displayName(requireContext()),
							)
						)

						AuthenticatingState -> binding.error.setText(R.string.login_authenticating)
						RequireSignInState -> binding.error.setText(R.string.login_invalid_credentials)
						is AccessScheduleDeniedLoginState -> navigateToAccessScheduleDenied(state.nextAccessStart)
						ServerUnavailableState,
						is ApiClientErrorLoginState -> binding.error.setText(R.string.login_server_unavailable)
						// Do nothing because the activity will respond to the new session
						AuthenticatedState -> Unit
						// Not initialized
						null -> Unit
					}
				}.launchIn(this)
			}
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()

		_binding = null
	}

	private fun navigateToAccessScheduleDenied(nextAccessStart: java.time.LocalDateTime?) {
		val serverId = userLoginViewModel.server.value?.id?.toString()
		val args = bundleOf()
		serverId?.let { args.putString(AccessScheduleDeniedFragment.ARG_SERVER_ID, it) }
		nextAccessStart?.let {
			args.putLong(
				AccessScheduleDeniedFragment.ARG_NEXT_ACCESS_EPOCH_MILLIS,
				it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
			)
		}
		requireActivity().supportFragmentManager.commit {
			replace<AccessScheduleDeniedFragment>(R.id.content_view, null, args)
			addToBackStack(null)
		}
	}

	private fun updateQuickConnectQr(code: String) {
		val server = userLoginViewModel.server.value
		if (server == null) {
			hideQuickConnectQr()
			return
		}

		val url = buildQuickConnectAuthorizeUrl(server.address, code)
		@Suppress("MagicNumber")
		val sizePx = (200 * resources.displayMetrics.density).toInt()
		val bitmap = QrCodeEncoder.encode(url, sizePx)
		if (bitmap != null) {
			binding.quickConnectQr.setImageBitmap(bitmap)
			binding.quickConnectQr.isVisible = true
		} else {
			hideQuickConnectQr()
		}
	}

	private fun hideQuickConnectQr() {
		binding.quickConnectQr.isVisible = false
		binding.quickConnectQr.setImageDrawable(null)
	}

	/**
	 * Add space after every 3 characters so "420420" becomes "420 420".
	 */
	private fun String.formatCode() = buildString {
		@Suppress("MagicNumber")
		val interval = 3
		this@formatCode.forEachIndexed { index, character ->
			if (index != 0 && index % interval == 0) append(" ")
			append(character)
		}
	}
}
