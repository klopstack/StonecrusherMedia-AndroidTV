package org.jellyfin.androidtv.ui.startup

import android.Manifest
import android.app.AlertDialog
import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.StonecrusherApplication
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ConnectedState
import org.jellyfin.androidtv.auth.model.ConnectingState
import org.jellyfin.androidtv.auth.model.ServerTypeNotSupportedState
import org.jellyfin.androidtv.auth.model.UnableToConnectState
import org.jellyfin.androidtv.util.displayName
import org.jellyfin.androidtv.auth.repository.SessionRepository
import org.jellyfin.androidtv.auth.repository.SessionRepositoryState
import org.jellyfin.androidtv.auth.repository.UserRepository
import org.jellyfin.androidtv.data.repository.AccessScheduleRepository
import org.jellyfin.androidtv.data.service.pluginsync.PluginSyncService
import org.jellyfin.androidtv.databinding.ActivityStartupBinding
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.browsing.MainActivity
import org.jellyfin.androidtv.ui.itemhandling.ItemLauncher
import org.jellyfin.androidtv.ui.navigation.Destinations
import org.jellyfin.androidtv.ui.navigation.NavigationRepository
import org.jellyfin.androidtv.ui.playback.MediaManager
import org.jellyfin.androidtv.ui.startup.PinEntryDialog.Mode.SET
import org.jellyfin.androidtv.ui.startup.fragment.AccessScheduleDeniedFragment
import org.jellyfin.androidtv.ui.startup.fragment.SelectServerFragment
import org.jellyfin.androidtv.ui.startup.fragment.ServerFragment
import org.jellyfin.androidtv.ui.startup.fragment.SplashFragment
import org.jellyfin.androidtv.ui.startup.fragment.StartupToolbarFragment
import org.jellyfin.androidtv.util.PinCodeUtil
import org.jellyfin.androidtv.util.applyTheme
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import timber.log.Timber
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.resume

class StartupActivity : FragmentActivity() {
	companion object {
		const val EXTRA_ITEM_ID = "ItemId"
		const val EXTRA_ITEM_IS_USER_VIEW = "ItemIsUserView"
		const val EXTRA_HIDE_SPLASH = "HideSplash"
	}

	private val startupViewModel: StartupViewModel by viewModel()
	private val api: ApiClient by inject()
	private val mediaManager: MediaManager by inject()
	private val sessionRepository: SessionRepository by inject()
	private val userRepository: UserRepository by inject()
	private val navigationRepository: NavigationRepository by inject()
	private val itemLauncher: ItemLauncher by inject()
	private val pluginSyncService: PluginSyncService by inject()
	private val accessScheduleRepository: AccessScheduleRepository by inject()

	private lateinit var binding: ActivityStartupBinding
	private var hasHandledAdminPinPrompt = false

	private val networkPermissionsRequester = registerForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions()
	) { grants ->
		val anyRejected = grants.any { !it.value }

		if (anyRejected) {
			// Permission denied, exit the app.
			Toast.makeText(this, R.string.no_network_permissions, Toast.LENGTH_LONG).show()
			finish()
		} else {
			onPermissionsGranted()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		applyTheme()

		super.onCreate(savedInstanceState)

		binding = ActivityStartupBinding.inflate(layoutInflater)
		binding.background.setContent { AppBackground() }
		binding.screensaver.isVisible = false
		setContentView(binding.root)

		if (!intent.getBooleanExtra(EXTRA_HIDE_SPLASH, false)) showSplash()

		// Ensure basic permissions
		networkPermissionsRequester.launch(arrayOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE))
	}

	override fun onResume() {
		super.onResume()

		applyTheme()
	}

	private fun onPermissionsGranted() = sessionRepository.state
		.flowWithLifecycle(lifecycle, Lifecycle.State.RESUMED)
		.filter { it == SessionRepositoryState.READY }
		.map { sessionRepository.currentSession.value }
		.distinctUntilChanged()
		.onEach { session ->
			if (session != null) {
				Timber.i("Found a session in the session repository, waiting for the currentUser in the application class.")

				showSplash()

				val currentUser = userRepository.currentUser.first { it != null }
				Timber.i("CurrentUser changed to ${currentUser?.id} while waiting for startup.")

				lifecycleScope.launch {
					openNextActivity()
				}
			} else {
				// Clear audio queue in case left over from last run
				mediaManager.clearAudioQueue()

				if (accessScheduleRepository.hasPendingLoginDenied()) {
					val nextAccess = accessScheduleRepository.consumeLoginDenied()
					lifecycleScope.launch {
						val server = startupViewModel.getLastServer()
						showAccessScheduleDenied(nextAccess, server?.id)
					}
					return@onEach
				}

				val server = startupViewModel.getLastServer()
				when {
					server != null -> showServer(server.id)
					BuildConfig.DEFAULT_SERVER_URL.isNotBlank() -> connectDefaultServer()
					else -> showServerSelection()
				}
			}
		}.launchIn(lifecycleScope)

	private suspend fun openNextActivity() {
		maybePromptAdminPinSetup()

		val itemId = when {
			intent.action == Intent.ACTION_VIEW && intent.data != null -> intent.data.toString()
			else -> intent.getStringExtra(EXTRA_ITEM_ID)
		}?.toUUIDOrNull()
		val itemIsUserView = intent.getBooleanExtra(EXTRA_ITEM_IS_USER_VIEW, false)

		Timber.i("Determining next activity (action=${intent.action}, itemId=$itemId, itemIsUserView=$itemIsUserView)")

		// Start session
		(application as? StonecrusherApplication)?.onSessionStart()

		// Create destination
		val destination = when {
			// Search is requested
			intent.action === Intent.ACTION_SEARCH -> Destinations.search(
				query = intent.getStringExtra(SearchManager.QUERY)
			)
			// User view item is requested
			itemId != null && itemIsUserView -> runCatching {
				val item = withContext(Dispatchers.IO) {
					api.userLibraryApi.getItem(itemId = itemId).content
				}
				itemLauncher.getUserViewDestination(item)
			}.onFailure { throwable ->
				Timber.w(throwable, "Failed to retrieve item $itemId from server.")
			}.getOrNull()
			// Other item is requested
			itemId != null -> Destinations.itemDetails(itemId)
			// No destination requested, use default
			else -> null
		}

		navigationRepository.reset(destination, true)

		val intent = Intent(this, MainActivity::class.java)
		// Clear navigation history
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_TASK_ON_HOME)
		Timber.i("Opening next activity $intent")
		startActivity(intent)
		finishAfterTransition()
	}

	private suspend fun maybePromptAdminPinSetup() {
		if (hasHandledAdminPinPrompt) return

		val currentUser = userRepository.currentUser.value ?: return
		val userId = currentUser.id ?: return
		val userSettings = UserSettingPreferences(this, userId)

		val shouldPrompt = AdminPinSetupPromptPolicy.shouldPrompt(
			isAdministrator = currentUser.policy?.isAdministrator == true,
			userPinHash = userSettings[UserSettingPreferences.userPinHash],
			userPinEnabled = userSettings[UserSettingPreferences.userPinEnabled],
			userPinSetupDeclined = userSettings[UserSettingPreferences.userPinSetupDeclined],
		)

		hasHandledAdminPinPrompt = true
		if (!shouldPrompt) return

		when (showAdminPinSetupChoiceDialog()) {
			AdminPinSetupAction.SET_UP_PIN -> {
				val pin = showSetPinDialog()
				if (!pin.isNullOrEmpty()) {
					userSettings[UserSettingPreferences.userPinHash] = PinCodeUtil.hashPin(pin)
					userSettings[UserSettingPreferences.userPinEnabled] = true
					userSettings[UserSettingPreferences.userPinSetupDeclined] = false
					Toast.makeText(this, R.string.lbl_pin_code_set, Toast.LENGTH_SHORT).show()
					triggerPinSettingsSync()
				}
			}
			AdminPinSetupAction.NOT_NOW -> {
				userSettings[UserSettingPreferences.userPinSetupDeclined] = true
				triggerPinSettingsSync()
			}
			AdminPinSetupAction.DISMISSED -> Unit
		}
	}

	private fun triggerPinSettingsSync() {
		lifecycleScope.launch(Dispatchers.IO) {
			runCatching { pluginSyncService.syncOnStartup() }
				.onFailure { Timber.w(it, "Failed to sync PIN settings after login prompt action") }
		}
	}

	private suspend fun showSetPinDialog(): String? = suspendCancellableCoroutine { continuation ->
		PinEntryDialog.show(
			context = this,
			mode = SET,
			onComplete = { pin ->
				if (continuation.isActive) continuation.resume(pin)
			}
		)
	}

	private suspend fun showAdminPinSetupChoiceDialog(): AdminPinSetupAction = suspendCancellableCoroutine { continuation ->
		val dialog = AlertDialog.Builder(this)
			.setTitle(R.string.lbl_admin_pin_setup_title)
			.setMessage(R.string.lbl_admin_pin_setup_message)
			.setPositiveButton(R.string.lbl_set_pin_code) { _, _ ->
				if (continuation.isActive) continuation.resume(AdminPinSetupAction.SET_UP_PIN)
			}
			.setNegativeButton(R.string.lbl_not_now) { _, _ ->
				if (continuation.isActive) continuation.resume(AdminPinSetupAction.NOT_NOW)
			}
			.setOnCancelListener {
				if (continuation.isActive) continuation.resume(AdminPinSetupAction.DISMISSED)
			}
			.create()

		continuation.invokeOnCancellation { dialog.dismiss() }
		dialog.show()
	}

	private enum class AdminPinSetupAction {
		SET_UP_PIN,
		NOT_NOW,
		DISMISSED,
	}

	// Fragment switching
	private fun showSplash() {
		// Prevent progress bar flashing
		if (supportFragmentManager.findFragmentById(R.id.content_view) is SplashFragment) return

		supportFragmentManager.commit {
			replace<SplashFragment>(R.id.content_view)
		}
	}

	private fun showAccessScheduleDenied(nextAccessStart: java.time.LocalDateTime?, serverId: UUID?) = supportFragmentManager.commit {
		val args = bundleOf()
		serverId?.let { args.putString(AccessScheduleDeniedFragment.ARG_SERVER_ID, it.toString()) }
		nextAccessStart?.let {
			args.putLong(
				AccessScheduleDeniedFragment.ARG_NEXT_ACCESS_EPOCH_MILLIS,
				it.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
			)
		}
		replace<AccessScheduleDeniedFragment>(R.id.content_view, null, args)
		replace<StartupToolbarFragment>(R.id.toolbar_view)
	}

	private fun showServer(id: UUID) = supportFragmentManager.commit {
		replace<ServerFragment>(
			R.id.content_view, null, bundleOf(
				ServerFragment.ARG_SERVER_ID to id.toString()
			)
		)
		replace<StartupToolbarFragment>(R.id.toolbar_view)
	}

	private fun showServerSelection() = supportFragmentManager.commit {
		replace<SelectServerFragment>(R.id.content_view)
		replace<StartupToolbarFragment>(R.id.toolbar_view)
	}

	private fun connectDefaultServer() {
		showSplash()

		startupViewModel.addServer(BuildConfig.DEFAULT_SERVER_URL).onEach { state ->
			when (state) {
				is ConnectingState -> Unit
				is ConnectedState -> showServer(state.id)
				is UnableToConnectState -> {
					Toast.makeText(
						this@StartupActivity,
						R.string.server_connection_failed,
						Toast.LENGTH_LONG,
					).show()
					showServerSelection()
				}
				is ServerTypeNotSupportedState -> {
					Toast.makeText(
						this@StartupActivity,
						getString(R.string.server_type_not_supported, state.serverType.displayName(this@StartupActivity)),
						Toast.LENGTH_LONG,
					).show()
					showServerSelection()
				}
			}
		}.launchIn(lifecycleScope)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		setIntent(intent)
	}
}
