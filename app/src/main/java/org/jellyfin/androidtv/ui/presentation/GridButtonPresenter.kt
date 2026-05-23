package org.jellyfin.androidtv.ui.presentation

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.findViewTreeCompositionContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.leanback.widget.Presenter
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.findViewTreeSavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.GridButton
import org.jellyfin.androidtv.ui.base.Text
import org.jellyfin.androidtv.ui.base.focusBorderColor
import org.jellyfin.androidtv.ui.itemhandling.GridButtonBaseRowItem

private class FocusAwareGridButtonContainer(context: Context) : FrameLayout(context) {
	var focusCallback: ((Boolean) -> Unit)? = null

	init {
		isFocusable = true
		isFocusableInTouchMode = true
		descendantFocusability = FOCUS_BLOCK_DESCENDANTS
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			defaultFocusHighlightEnabled = false
		}
	}

	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		if (isAttachedToWindow) super.onMeasure(widthMeasureSpec, heightMeasureSpec)
		else setMeasuredDimension(widthMeasureSpec, heightMeasureSpec)
	}

	override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
		super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
		focusCallback?.invoke(gainFocus)
	}
}

class GridButtonPresenter @JvmOverloads constructor(
	private val width: Int = 110,
	private val imageHeight: Int = 110,
) : Presenter() {
	private inner class ViewHolder(
		container: FocusAwareGridButtonContainer,
		private val composeView: ComposeView,
	) : Presenter.ViewHolder(container) {
		private val _gridButton = MutableStateFlow<GridButton?>(null)
		private val _focused = MutableStateFlow(false)

		init {
			composeView.isFocusable = false
			composeView.setContent {
				val gridButton by _gridButton.collectAsState()
				val focused by _focused.collectAsState()
				if (gridButton != null) {
					GridButtonContent(
						value = gridButton!!,
						focused = focused,
						width = width,
						imageHeight = imageHeight,
					)
				}
			}

			_focused.value = container.isFocused
			container.focusCallback = { focused -> _focused.value = focused }
		}

		fun bind(value: GridButton) {
			_gridButton.value = value
			_focused.value = view.isFocused
		}
	}

	override fun onCreateViewHolder(parent: ViewGroup): Presenter.ViewHolder {
		val container = FocusAwareGridButtonContainer(parent.context)
		val composeView = ComposeView(parent.context).apply {
			setParentCompositionContext(parent.findViewTreeCompositionContext())
		}

		container.setViewTreeLifecycleOwner(parent.findViewTreeLifecycleOwner())
		container.setViewTreeSavedStateRegistryOwner(parent.findViewTreeSavedStateRegistryOwner())
		container.addView(
			composeView,
			FrameLayout.LayoutParams(
				FrameLayout.LayoutParams.WRAP_CONTENT,
				FrameLayout.LayoutParams.WRAP_CONTENT,
			),
		)

		return ViewHolder(container, composeView)
	}

	override fun onBindViewHolder(viewHolder: Presenter.ViewHolder, item: Any?) {
		if (viewHolder !is ViewHolder) return

		when (item) {
			is GridButtonBaseRowItem -> viewHolder.bind(item.gridButton)
			is GridButton -> viewHolder.bind(item)
		}
	}

	override fun onUnbindViewHolder(viewHolder: Presenter.ViewHolder) = Unit
	override fun onViewAttachedToWindow(viewHolder: Presenter.ViewHolder) = Unit
}

@Composable
private fun GridButtonContent(
	value: GridButton,
	focused: Boolean,
	width: Int,
	imageHeight: Int,
) {
	val shape = RoundedCornerShape(4.dp)
	val borderColor = focusBorderColor()
	val cardHeight = if (value.imageRes != null) imageHeight else 0

	Box(
		modifier = Modifier
			.width(width.dp)
			.then(if (cardHeight > 0) Modifier.height(cardHeight.dp) else Modifier)
			.then(if (focused) Modifier.border(2.dp, borderColor, shape) else Modifier)
			.clip(shape)
			.background(colorResource(R.color.button_default_normal_background))
	) {
		value.imageRes?.let { imageRes ->
			GridButtonImage(
				imageRes = imageRes,
				contentDescription = value.text,
				modifier = Modifier
					.width(width.dp)
					.height(imageHeight.dp),
			)
		}

		Text(
			text = value.text,
			style = TextStyle(
				color = colorResource(R.color.button_default_normal_text),
				fontSize = 12.sp
			),
			modifier = Modifier
				.padding(15.dp, 10.dp)
				.align(Alignment.BottomStart)
		)
	}
}

@Composable
private fun GridButtonImage(
	imageRes: Int,
	contentDescription: String,
	modifier: Modifier = Modifier,
) {
	AndroidView(
		modifier = modifier,
		factory = { context ->
			ImageView(context).apply {
				scaleType = ImageView.ScaleType.CENTER_CROP
				this.contentDescription = contentDescription
			}
		},
		update = { imageView ->
			imageView.setImageResource(imageRes)
		},
	)
}
