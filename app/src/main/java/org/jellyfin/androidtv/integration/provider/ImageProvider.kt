package org.jellyfin.androidtv.integration.provider

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.asDrawable
import coil3.request.ImageRequest
import coil3.request.error
import org.jellyfin.androidtv.BuildConfig
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.store.AuthenticationStore
import org.koin.android.ext.android.inject
import timber.log.Timber
import java.io.FileNotFoundException
import java.io.IOException

class ImageProvider : ContentProvider() {
	private val imageLoader by inject<ImageLoader>()
	private val authenticationStore by inject<AuthenticationStore>()

	override fun onCreate(): Boolean = true

	override fun getType(uri: Uri) = null
	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
	override fun insert(uri: Uri, values: ContentValues?) = null
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0

	override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
		val src = uri.getQueryParameter("src")?.trim()
			?: throw FileNotFoundException("Missing src parameter")

		if (!isAllowedImageUrl(src)) {
			Timber.w("Blocked ImageProvider request for untrusted URL")
			throw FileNotFoundException("URL not allowed")
		}

		val srcUri = src.toUri()

		val (read, write) = ParcelFileDescriptor.createPipe()
		val outputStream = ParcelFileDescriptor.AutoCloseOutputStream(write)

		imageLoader.enqueue(ImageRequest.Builder(context!!).apply {
			data(srcUri)
			error(R.drawable.placeholder_icon)
			target(
				onSuccess = { image -> writeDrawable(image.asDrawable(context!!.resources), outputStream) },
				onError = { image -> writeDrawable(requireNotNull(image?.asDrawable(context!!.resources)), outputStream) }
			)
		}.build())

		return read
	}

	private fun isAllowedImageUrl(src: String): Boolean {
		val srcUri = runCatching { src.toUri() }.getOrNull() ?: return false

		if (srcUri.scheme == ContentResolver.SCHEME_ANDROID_RESOURCE) {
			return srcUri.authority == context?.packageName
		}

		if (srcUri.scheme !in ALLOWED_REMOTE_SCHEMES) return false

		return authenticationStore.getServers().values.any { server ->
			matchesServerBaseUrl(srcUri, server.address)
		}
	}

	private fun matchesServerBaseUrl(srcUri: Uri, serverAddress: String): Boolean {
		val baseUri = runCatching { serverAddress.trim().trimEnd('/').toUri() }.getOrNull() ?: return false
		if (baseUri.scheme !in ALLOWED_REMOTE_SCHEMES) return false
		if (!srcUri.scheme.equals(baseUri.scheme, ignoreCase = true)) return false

		val srcHost = srcUri.host ?: return false
		val baseHost = baseUri.host ?: return false
		if (!srcHost.equals(baseHost, ignoreCase = true)) return false

		if (normalizedPort(srcUri) != normalizedPort(baseUri)) return false

		val basePath = baseUri.path?.trimEnd('/') ?: ""
		val srcPath = srcUri.path ?: ""
		return basePath.isEmpty() ||
			srcPath.equals(basePath, ignoreCase = true) ||
			srcPath.startsWith("$basePath/", ignoreCase = true)
	}

	private fun normalizedPort(uri: Uri): Int {
		if (uri.port != -1) return uri.port
		return when (uri.scheme?.lowercase()) {
			"https" -> 443
			"http" -> 80
			else -> -1
		}
	}

	private fun writeDrawable(
		drawable: Drawable,
		outputStream: ParcelFileDescriptor.AutoCloseOutputStream
	) {
		@Suppress("DEPRECATION")
		val format = when {
			Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
			else -> Bitmap.CompressFormat.WEBP
		}

		try {
			outputStream.use {
				drawable.toBitmap().compress(format, COMPRESSION_QUALITY, outputStream)
			}
		} catch (_: IOException) {
			// Ignore IOException as this is commonly thrown when the load request is cancelled
		}
	}

	companion object {
		private val ALLOWED_REMOTE_SCHEMES = setOf("http", "https")
		private const val COMPRESSION_QUALITY = 95

		/**
		 * Get a [Uri] that uses the [ImageProvider] to load an image. The input should be a valid
		 * Jellyfin image URL created using the SDK.
		 */
		fun getImageUri(src: String): Uri = Uri.Builder()
			.scheme("content")
			.authority("${BuildConfig.APPLICATION_ID}.integration.provider.ImageProvider")
			.appendQueryParameter("src", src)
			.appendQueryParameter("v", BuildConfig.VERSION_NAME)
			.build()
	}
}
