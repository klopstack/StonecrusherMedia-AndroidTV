package org.jellyfin.androidtv.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.compose.ui.graphics.ImageBitmap
import org.jellyfin.androidtv.R
import androidx.compose.ui.graphics.asImageBitmap
import coil3.ImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jellyfin.androidtv.auth.model.Server
import org.jellyfin.androidtv.preference.UserPreferences
import org.jellyfin.androidtv.preference.UserSettingPreferences
import org.jellyfin.androidtv.util.BitmapBlur
import org.jellyfin.androidtv.util.apiclient.getUrl
import org.jellyfin.androidtv.util.apiclient.itemBackdropImages
import org.jellyfin.androidtv.util.apiclient.parentBackdropImages
import org.jellyfin.androidtv.util.sdk.ApiClientFactory
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import timber.log.Timber
import java.util.UUID
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class BlurContext {
	DETAILS,
	BROWSING,
	NONE
}

class BackgroundService(
	private val context: Context,
	private val jellyfin: Jellyfin,
	private val api: ApiClient,
	private val userPreferences: UserPreferences,
	private val userSettingPreferences: UserSettingPreferences,
	private val imageLoader: ImageLoader,
	private val apiClientFactory: ApiClientFactory,
) {
	companion object {
		val SLIDESHOW_DURATION = 30.seconds
		val TRANSITION_DURATION = 800.milliseconds
	}

	// Async
	private val scope = MainScope()
	private var loadBackgroundsJob: Job? = null
	private var updateBackgroundTimerJob: Job? = null
	private var lastBackgroundTimerUpdate = 0L

	// Current background data
	private var _backgrounds = emptyList<ImageBitmap>()
	private var _currentIndex = 0
	private var _currentBackground = MutableStateFlow<ImageBitmap?>(null)
	private var _blurContext = MutableStateFlow(BlurContext.NONE)
	private var _enabled = MutableStateFlow(true)
	val currentBackground get() = _currentBackground.asStateFlow()
	val blurContext get() = _blurContext.asStateFlow()
	val enabled get() = _enabled.asStateFlow()
	
	/**
	 * Returns true if blur should be applied via Compose modifier (Android 12+),
	 * false if blur is pre-applied to bitmap (Android 11 and below).
	 */
	val useComposeBlur: Boolean
		get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

	/**
	 * Use all available backdrops from [baseItem] as background.
	 * @param blurContext The context to determine which blur amount preference to use
	 */
	@JvmOverloads
	fun setBackground(baseItem: BaseItemDto?, blurContext: BlurContext = BlurContext.DETAILS) {
		// Check if item is set and backgrounds are enabled
		if (baseItem == null || !userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Set blur context
		_blurContext.value = blurContext

		// Get the appropriate API client for this item's server
		val itemApi = apiClientFactory.getApiClientForItemOrFallback(baseItem, api)
		
		// Get all backdrop urls
		val backdropUrls = (baseItem.itemBackdropImages + baseItem.parentBackdropImages)
			.map { it.getUrl(itemApi) }
			.toSet()

		loadBackgrounds(backdropUrls)
	}

	/**
	 * Use splashscreen from [server] as background.
	 */
	fun setBackground(server: Server) {
		// Check if item is set and backgrounds are enabled
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Check if splashscreen is enabled in (cached) branding options
		if (!server.splashscreenEnabled)
			return clearBackgrounds()

		// No blur on splashscreen
		_blurContext.value = BlurContext.NONE

		// Manually grab the backdrop URL
		val api = jellyfin.createApi(baseUrl = server.address)
		val splashscreenUrl = api.imageApi.getSplashscreenUrl()

		loadBackgrounds(setOf(splashscreenUrl))
	}

	/**
	 * Use a direct image URL as background (e.g., TMDB images for Jellyseerr).
	 * @param blurContext The context to determine which blur amount preference to use
	 */
	fun setBackgroundUrl(imageUrl: String, blurContext: BlurContext = BlurContext.BROWSING) {
		// Check if backgrounds are enabled
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		// Set blur context
		_blurContext.value = blurContext

		loadBackgrounds(setOf(imageUrl))
	}

	fun setBackgroundFromLibrary(libraryId: UUID, blurContext: BlurContext = BlurContext.BROWSING) {
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		_blurContext.value = blurContext

		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			try {
				// Try to find an item with a backdrop image
				val response by api.itemsApi.getItems(
					parentId = libraryId,
					recursive = true,
					sortBy = listOf(ItemSortBy.RANDOM),
					limit = 1,
					imageTypes = listOf(ImageType.BACKDROP),
				)
				val item = response.items.firstOrNull()
				if (item != null) {
					val backdropUrls = (item.itemBackdropImages + item.parentBackdropImages)
						.map { it.getUrl(api) }
						.toSet()
					if (backdropUrls.isNotEmpty()) {
						loadBackgrounds(backdropUrls)
						return@launch
					}
				}

				// Fallback: fetch any item with a primary image
				val fallback by api.itemsApi.getItems(
					parentId = libraryId,
					recursive = true,
					sortBy = listOf(ItemSortBy.RANDOM),
					limit = 1,
					imageTypes = listOf(ImageType.PRIMARY),
				)
				val fallbackItem = fallback.items.firstOrNull()
				if (fallbackItem != null) {
					val primaryUrl = api.imageApi.getItemImageUrl(
						itemId = fallbackItem.id,
						imageType = ImageType.PRIMARY,
					)
					loadBackgrounds(setOf(primaryUrl))
					return@launch
				}
			} catch (e: Exception) {
				Timber.w(e, "Failed to fetch random backdrop from library $libraryId")
			}
			clearBackgrounds()
		}
	}

	private fun loadBackgrounds(backdropUrls: Set<String>) {
		if (backdropUrls.isEmpty()) return clearBackgrounds()

		_enabled.value = true
		
		val blurAmount = when (_blurContext.value) {
			BlurContext.DETAILS -> userSettingPreferences[UserSettingPreferences.detailsBackgroundBlurAmount]
			BlurContext.BROWSING -> userSettingPreferences[UserSettingPreferences.browsingBackgroundBlurAmount]
			BlurContext.NONE -> 0
		}

		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			_backgrounds = backdropUrls.mapNotNull { url ->
				val bitmap = imageLoader.execute(
					request = ImageRequest.Builder(context).data(url).build()
				).image?.toBitmap()
				
				if (bitmap != null && !useComposeBlur && blurAmount > 0) {
					BitmapBlur.blur(bitmap, blurAmount).asImageBitmap()
				} else {
					bitmap?.asImageBitmap()
				}
			}

			_currentIndex = 0
			update()
		}
	}

	/**
	 * Use a theme accent color as a blurred gradient background, optionally with a blurred icon overlay.
	 */
	fun setBackgroundFromColor(
		@ColorInt accentColor: Int,
		@DrawableRes iconRes: Int? = null,
		blurContext: BlurContext = BlurContext.BROWSING,
	) {
		if (!userPreferences[UserPreferences.backdropEnabled])
			return clearBackgrounds()

		_blurContext.value = blurContext
		_enabled.value = true

		loadBackgroundsJob?.cancel()
		loadBackgroundsJob = scope.launch(Dispatchers.IO) {
			var bitmap = createAccentGradientBitmap(accentColor)
			if (iconRes != null) {
				bitmap = compositeBlurredIcon(bitmap, iconRes)
			}

			val blurAmount = when (blurContext) {
				BlurContext.DETAILS -> userSettingPreferences[UserSettingPreferences.detailsBackgroundBlurAmount]
				BlurContext.BROWSING -> userSettingPreferences[UserSettingPreferences.browsingBackgroundBlurAmount]
				BlurContext.NONE -> 0
			}

			val imageBitmap = if (!useComposeBlur && blurAmount > 0) {
				BitmapBlur.blur(bitmap, blurAmount).asImageBitmap()
			} else {
				bitmap.asImageBitmap()
			}

			_backgrounds = listOf(imageBitmap)
			_currentIndex = 0
			update()
		}
	}

	private fun createAccentGradientBitmap(@ColorInt accentColor: Int): Bitmap {
		val width = 1280
		val height = 720
		val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)

		val midColor = blendColors(accentColor, ContextCompat.getColor(context, R.color.stonecrusher_soil), 0.55f)
		val endColor = blendColors(accentColor, ContextCompat.getColor(context, R.color.not_quite_black), 0.8f)

		val gradient = LinearGradient(
			0f,
			0f,
			width.toFloat(),
			height.toFloat(),
			intArrayOf(accentColor, midColor, endColor),
			floatArrayOf(0f, 0.45f, 1f),
			Shader.TileMode.CLAMP,
		)
		canvas.drawPaint(Paint().apply { shader = gradient })

		return bitmap
	}

	private fun compositeBlurredIcon(gradient: Bitmap, @DrawableRes iconRes: Int): Bitmap {
		val drawable = ContextCompat.getDrawable(context, iconRes) ?: return gradient
		val result = gradient.copy(Bitmap.Config.ARGB_8888, true)
		val canvas = Canvas(result)

		val iconSize = (gradient.width * 0.3f).toInt().coerceAtLeast(160)
		val iconBitmap = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888)
		Canvas(iconBitmap).apply {
			drawable.setBounds(0, 0, iconSize, iconSize)
			drawable.draw(this)
		}

		val blurredIcon = BitmapBlur.blur(iconBitmap, 20)
		if (blurredIcon != iconBitmap) iconBitmap.recycle()

		val left = gradient.width * 0.58f - iconSize / 2f
		val top = gradient.height * 0.36f - iconSize / 2f
		canvas.drawBitmap(
			blurredIcon,
			left,
			top,
			Paint().apply { alpha = 115 },
		)
		blurredIcon.recycle()

		return result
	}

	private fun blendColors(@ColorInt color1: Int, @ColorInt color2: Int, ratio: Float): Int {
		val inverse = 1f - ratio
		val a = (Color.alpha(color1) * inverse + Color.alpha(color2) * ratio).toInt().coerceIn(0, 255)
		val r = (Color.red(color1) * inverse + Color.red(color2) * ratio).toInt().coerceIn(0, 255)
		val g = (Color.green(color1) * inverse + Color.green(color2) * ratio).toInt().coerceIn(0, 255)
		val b = (Color.blue(color1) * inverse + Color.blue(color2) * ratio).toInt().coerceIn(0, 255)
		return Color.argb(a, r, g, b)
	}

	fun clearBackgrounds() {
		loadBackgroundsJob?.cancel()

		// Re-enable backgrounds if disabled
		_enabled.value = true

		if (_backgrounds.isEmpty()) return

		_backgrounds = emptyList()
		update()
	}

	/**
	 * Disable the showing of backgrounds until any function manipulating the backgrounds is called.
	 */
	fun disable() {
		_enabled.value = false
	}

	internal fun update() {
		val now = Instant.now().toEpochMilli()
		if (lastBackgroundTimerUpdate > now - TRANSITION_DURATION.inWholeMilliseconds)
			return setTimer((lastBackgroundTimerUpdate - now).milliseconds + TRANSITION_DURATION, false)

		lastBackgroundTimerUpdate = now

		// Get next background to show
		if (_currentIndex >= _backgrounds.size) _currentIndex = 0

		// Set background
		_currentBackground.value = _backgrounds.getOrNull(_currentIndex)

		// Set timer for next background
		if (_backgrounds.size > 1) setTimer()
		else updateBackgroundTimerJob?.cancel()
	}

	private fun setTimer(updateDelay: Duration = SLIDESHOW_DURATION, increaseIndex: Boolean = true) {
		updateBackgroundTimerJob?.cancel()
		updateBackgroundTimerJob = scope.launch {
			delay(updateDelay)

			if (increaseIndex) _currentIndex++

			update()
		}
	}
}
