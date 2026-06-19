package org.jellyfin.androidtv.ui.startup

import android.app.AlertDialog
import android.content.Context
import androidx.annotation.StringRes
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.auth.model.ProfileProvisioningError
import org.jellyfin.androidtv.auth.model.ProfileProvisioningSummary
import org.jellyfin.androidtv.auth.repository.ProvisioningException

object ProfileSetupDialog {
	fun showProgress(context: Context): AlertDialog =
		AlertDialog.Builder(context)
			.setTitle(R.string.setup_all_profiles_progress_title)
			.setMessage(R.string.setup_all_profiles_progress_message)
			.setCancelable(false)
			.create()
			.also { it.show() }

	fun showResult(context: Context, summary: ProfileProvisioningSummary) {
		val message = buildString {
			if (summary.provisioned.isNotEmpty()) {
				append(context.getString(R.string.setup_all_profiles_result_provisioned, summary.provisioned.joinToString()))
				append("\n\n")
			}
			if (summary.skipped.isNotEmpty()) {
				append(context.getString(R.string.setup_all_profiles_result_skipped, summary.skipped.joinToString()))
				append("\n\n")
			}
			if (summary.failed.isNotEmpty()) {
				append(context.getString(R.string.setup_all_profiles_result_failed))
				append("\n")
				summary.failed.forEach { failure ->
					append(context.getString(R.string.setup_all_profiles_result_failed_item, failure.userName, failure.reason))
					append("\n")
				}
			}
			if (summary.provisioned.isEmpty() && summary.failed.isEmpty()) {
				append(context.getString(R.string.setup_all_profiles_result_none))
			}
		}.trim()

		AlertDialog.Builder(context)
			.setTitle(
				if (summary.isSuccess) R.string.setup_all_profiles_result_success_title
				else R.string.setup_all_profiles_result_partial_title
			)
			.setMessage(message)
			.setPositiveButton(R.string.lbl_ok, null)
			.show()
	}

	fun showError(context: Context, error: Throwable) {
		val messageRes = when (error) {
			is ProvisioningException -> when (error.error) {
				ProfileProvisioningError.NotJellyfinServer -> R.string.setup_all_profiles_error_not_jellyfin
				ProfileProvisioningError.NoAdminSession -> R.string.setup_all_profiles_error_no_admin
				ProfileProvisioningError.QuickConnectDisabled -> R.string.setup_all_profiles_error_qc_disabled
				ProfileProvisioningError.NotAdministrator -> R.string.setup_all_profiles_error_not_admin
			}
			else -> R.string.setup_all_profiles_error_generic
		}

		showMessage(context, R.string.setup_all_profiles_error_title, messageRes)
	}

	private fun showMessage(context: Context, @StringRes title: Int, @StringRes message: Int) {
		AlertDialog.Builder(context)
			.setTitle(title)
			.setMessage(message)
			.setPositiveButton(R.string.lbl_ok, null)
			.show()
	}
}
