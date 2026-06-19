package org.jellyfin.androidtv.ui.startup.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.replace
import androidx.fragment.compose.content
import androidx.core.os.bundleOf
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.background.AppBackground
import org.jellyfin.androidtv.ui.base.StonecrusherTheme
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.button.Button
import org.jellyfin.androidtv.ui.composable.modifier.overscan
import org.jellyfin.androidtv.util.AccessScheduleEvaluator
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class AccessScheduleDeniedFragment : Fragment() {
	companion object {
		const val ARG_NEXT_ACCESS_EPOCH_MILLIS = "next_access_epoch_millis"
		const val ARG_SERVER_ID = "server_id"
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val nextAccessStart = arguments?.getLong(ARG_NEXT_ACCESS_EPOCH_MILLIS, -1L)
			?.takeIf { it >= 0 }
			?.let { millis ->
				LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
			}

		return content {
			StonecrusherTheme {
				AccessScheduleDeniedScreen(
					nextAccessStart = nextAccessStart,
					onBackToUsers = { navigateBackToUsers() },
				)
			}
		}
	}

	private fun navigateBackToUsers() {
		val serverId = arguments?.getString(ARG_SERVER_ID)
		if (serverId != null) {
			parentFragmentManager.commit {
				replace<ServerFragment>(
					R.id.content_view, null, bundleOf(ServerFragment.ARG_SERVER_ID to serverId),
				)
			}
		} else if (parentFragmentManager.backStackEntryCount > 0) {
			parentFragmentManager.popBackStack()
		} else {
			parentFragmentManager.commit {
				replace<SelectServerFragment>(R.id.content_view)
			}
		}
	}
}

@Composable
private fun AccessScheduleDeniedScreen(
	nextAccessStart: LocalDateTime?,
	onBackToUsers: () -> Unit,
) {
	val focusRequester = remember { FocusRequester() }
	val resumeMessage = AccessScheduleEvaluator.formatNextAccessMessage(
		context = androidx.compose.ui.platform.LocalContext.current,
		nextStart = nextAccessStart,
	)

	Column(
		modifier = Modifier
			.fillMaxSize()
			.overscan(),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Box(modifier = Modifier.fillMaxSize()) {
			AppBackground()

			Column(
				modifier = Modifier
					.align(Alignment.Center)
					.widthIn(max = 560.dp)
					.padding(horizontal = 32.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
			) {
			Text(
				text = stringResource(R.string.access_schedule_denied_title),
				fontSize = 32.sp,
				textAlign = TextAlign.Center,
			)

			Spacer(modifier = Modifier.height(16.dp))

			Text(
				text = stringResource(R.string.access_schedule_denied_message),
				fontSize = 18.sp,
				textAlign = TextAlign.Center,
			)

			if (resumeMessage != null) {
				Spacer(modifier = Modifier.height(12.dp))
				Text(
					text = resumeMessage,
					fontSize = 16.sp,
					textAlign = TextAlign.Center,
				)
			}

			Spacer(modifier = Modifier.height(32.dp))

			Button(
				modifier = Modifier.focusRequester(focusRequester),
				onClick = onBackToUsers,
			) {
				Text(stringResource(R.string.access_schedule_back_to_users))
			}
			}
		}
	}

	LaunchedEffect(Unit) {
		focusRequester.requestFocus()
	}
}
