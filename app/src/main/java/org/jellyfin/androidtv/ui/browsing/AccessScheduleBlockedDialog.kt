package org.jellyfin.androidtv.ui.browsing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.focusBorderColor

@Composable
fun AccessScheduleBlockedDialog(
	title: String,
	message: String,
	onConfirm: () -> Unit,
) {
	val initialFocusRequester = remember { FocusRequester() }
	val accentColor = focusBorderColor()

	Dialog(
		onDismissRequest = {},
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			dismissOnBackPress = false,
			dismissOnClickOutside = false,
		),
	) {
		Box(
			modifier = Modifier.fillMaxSize(),
			contentAlignment = Alignment.Center,
		) {
			Column(
				modifier = Modifier
					.widthIn(min = 340.dp, max = 520.dp)
					.clip(RoundedCornerShape(20.dp))
					.background(Color(0xE6141414))
					.border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(20.dp))
					.padding(vertical = 20.dp),
			) {
				Text(
					text = title,
					fontSize = 20.sp,
					fontWeight = FontWeight.W600,
					color = Color.White,
					modifier = Modifier
						.padding(horizontal = 24.dp)
						.padding(bottom = 12.dp),
				)

				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(1.dp)
						.background(Color.White.copy(alpha = 0.08f)),
				)

				Text(
					text = message,
					fontSize = 16.sp,
					color = Color.White.copy(alpha = 0.85f),
					textAlign = TextAlign.Center,
					modifier = Modifier
						.padding(horizontal = 24.dp)
						.padding(top = 16.dp, bottom = 20.dp),
				)

				AccessScheduleDialogButton(
					text = stringResource(R.string.access_schedule_back_to_users),
					focusRequester = initialFocusRequester,
					accentColor = accentColor,
					onClick = onConfirm,
				)
			}
		}
	}

	LaunchedEffect(Unit) {
		initialFocusRequester.requestFocus()
	}
}

@Composable
private fun AccessScheduleDialogButton(
	text: String,
	focusRequester: FocusRequester,
	accentColor: Color,
	onClick: () -> Unit,
) {
	val interactionSource = remember { MutableInteractionSource() }
	val isFocused by interactionSource.collectIsFocusedAsState()

	Box(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp)
			.clip(RoundedCornerShape(12.dp))
			.background(if (isFocused) accentColor.copy(alpha = 0.2f) else Color.Transparent)
			.border(
				width = if (isFocused) 2.dp else 0.dp,
				color = if (isFocused) accentColor else Color.Transparent,
				shape = RoundedCornerShape(12.dp),
			)
			.focusRequester(focusRequester)
			.focusable(interactionSource = interactionSource)
			.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
			.padding(vertical = 14.dp, horizontal = 16.dp),
		contentAlignment = Alignment.Center,
	) {
		Text(
			text = text,
			fontSize = 16.sp,
			fontWeight = FontWeight.W500,
			color = Color.White,
		)
	}
}
