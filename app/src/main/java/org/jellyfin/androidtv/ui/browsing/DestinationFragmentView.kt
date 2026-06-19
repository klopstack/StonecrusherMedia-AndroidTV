package org.jellyfin.androidtv.ui.browsing

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.os.BundleCompat
import androidx.core.os.ParcelCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import org.jellyfin.androidtv.R
import org.jellyfin.androidtv.ui.navigation.NavigationAction
import timber.log.Timber
import java.util.Stack

private class HistoryEntry(
	val name: Class<out Fragment>,
	val arguments: Bundle = bundleOf(),

	var fragment: Fragment? = null,
	var savedState: Fragment.SavedState? = null,
) : Parcelable {
	override fun describeContents(): Int = 0

	override fun writeToParcel(dest: Parcel, flags: Int) {
		dest.writeString(name.name)
		dest.writeBundle(arguments)
		dest.writeParcelable(savedState, 0)
	}

	companion object CREATOR : Parcelable.Creator<HistoryEntry> {
		@Suppress("UNCHECKED_CAST")
		override fun createFromParcel(parcel: Parcel): HistoryEntry = HistoryEntry(
			name = Class.forName(parcel.readString()!!) as Class<out Fragment>,
			arguments = parcel.readBundle(this::class.java.classLoader)!!,
			fragment = null,
			savedState = ParcelCompat.readParcelable(parcel, this::class.java.classLoader, Fragment.SavedState::class.java)!!,
		)

		override fun newArray(size: Int): Array<HistoryEntry?> = arrayOfNulls(size)
	}
}

class DestinationFragmentView @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
	private companion object {
		private const val FRAGMENT_TAG_CONTENT = "content"
		private const val BUNDLE_SUPER = "super"
		private const val BUNDLE_HISTORY = "history"
	}

	private val fragmentManager by lazy {
		FragmentManager.findFragmentManager(this)
	}

	private val container by lazy {
		FragmentContainerView(context).also { view ->
			view.id = R.id.container
			addView(view)
		}
	}

	private val history = Stack<HistoryEntry>()

	fun navigate(action: NavigationAction.NavigateFragment) {
		if (isShowingDestination(action)) {
			if (action.clear) clearHistoryKeepCurrent()
			Timber.d("Skipping redundant navigation to ${action.destination.fragment.java.simpleName}")
			return
		}

		val entry = HistoryEntry(action.destination.fragment.java, action.destination.arguments)

		// Create the base transaction so we can mutate everything at once
		val transaction = fragmentManager.beginTransaction()

		if (action.clear) {
			// Clear all current fragments from the history before adding the new entry
			history.mapNotNull { it.fragment }.forEach { transaction.remove(it) }
			history.clear()
			history.push(entry)
		} else if (action.replace && action.addToBackStack && history.isNotEmpty()) {
			// Remove the top-most entry before replacing it with the next
			val currentFragment = history[history.size - 1].fragment
			if (currentFragment != null) transaction.remove(currentFragment)
			history[history.size - 1] = entry
		} else {
			// Add to the end of the history
			saveCurrentFragmentState()
			history.push(entry)
		}

		activateHistoryEntry(entry, transaction)
	}

	fun goBack(): Boolean {
		// Require at least 2 items (current & previous) to go back
		if (history.size < 2) return false

		// Create the base transaction so we can mutate everything at once
		val transaction = fragmentManager.beginTransaction()

		// Remove current entry
		val currentEntry = history.pop()

		// Make sure to remove the associated fragment
		val currentFragment = currentEntry.fragment
		if (currentFragment != null) transaction.remove(currentFragment)

		// Read & set previous entry
		val entry = history.last()
		activateHistoryEntry(entry, transaction)

		return true
	}

	private fun isShowingDestination(action: NavigationAction.NavigateFragment): Boolean {
		if (history.isEmpty()) return false

		val top = history.peek()
		val fragment = top.fragment ?: fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT)
		if (fragment == null || !fragment.isAdded || fragment.isDetached) return false

		return top.name == action.destination.fragment.java &&
			bundleContentsEqual(top.arguments, action.destination.arguments)
	}

	private fun bundleContentsEqual(a: Bundle, b: Bundle): Boolean {
		if (a.size() != b.size()) return false
		return a.keySet().all { key -> a.get(key) == b.get(key) }
	}

	/**
	 * Drop back-stack entries without re-attaching the visible fragment.
	 * Used when a clear-history navigation targets the screen already on top.
	 */
	@SuppressLint("CommitTransaction")
	private fun clearHistoryKeepCurrent() {
		if (history.isEmpty()) return

		val top = history.peek()
		val currentFragment = top.fragment ?: fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT)
		val transaction = fragmentManager.beginTransaction()

		history.mapNotNull { it.fragment }.distinct().forEach { fragment ->
			if (fragment != currentFragment) transaction.remove(fragment)
		}

		history.clear()
		if (currentFragment != null) {
			history.push(HistoryEntry(top.name, top.arguments, currentFragment, top.savedState))
		}

		if (fragmentManager.isDestroyed) return
		if (fragmentManager.isStateSaved) transaction.commitAllowingStateLoss()
		else transaction.commit()
	}

	private fun saveCurrentFragmentState() {
		if (history.isEmpty()) return

		// Update the top-most history entry with state from current fragment
		val fragment = requireNotNull(fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT))
		history[history.size - 1].savedState = fragmentManager.saveFragmentInstanceState(fragment)
	}

	@SuppressLint("CommitTransaction")
	private fun activateHistoryEntry(
		entry: HistoryEntry,
		transaction: FragmentTransaction,
	) {
		var fragment = entry.fragment

		// Recreate if the cached instance was removed from the FragmentManager (e.g. by replace)
		if (fragment != null && !fragment.isAdded && !fragment.isDetached) {
			fragment = null
			entry.fragment = null
		}

		// Create if there is no existing fragment
		if (fragment == null) {
			fragment = fragmentManager.fragmentFactory.instantiate(context.classLoader, entry.name.name).apply {
				setInitialSavedState(entry.savedState)
			}
			entry.fragment = fragment
		}

		// Update arguments
		fragment.arguments = entry.arguments

		val current = fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT)
		if (current === fragment && current.isAdded && !current.isDetached) return

		transaction.apply {
			// Set options
			setReorderingAllowed(true)
			setCustomAnimations(R.anim.fade_in, R.anim.fade_out, R.anim.fade_in, R.anim.fade_out)

			// Detach current fragment when switching to a different instance
			if (current != null && current !== fragment) detach(current)

			// Attach or replace so only one content fragment owns the container
			if (fragment.isDetached) attach(fragment)
			else replace(container.id, fragment, FRAGMENT_TAG_CONTENT)
		}

		if (fragmentManager.isDestroyed) {
			Timber.w("FragmentManager is already destroyed")
		} else if (fragmentManager.isStateSaved) {
			transaction.commitAllowingStateLoss()
		} else {
			transaction.commit()
		}
	}

	override fun onSaveInstanceState(): Parcelable {
		// Always retrieve current state before writing
		saveCurrentFragmentState()

		// Save state
		return bundleOf(
			BUNDLE_SUPER to super.onSaveInstanceState(),
			BUNDLE_HISTORY to ArrayList(history),
		)
	}

	override fun onRestoreInstanceState(state: Parcelable?) {
		// Ignore if not a bundle
		if (state !is Bundle) return super.onRestoreInstanceState(state)

		// Call parent
		@Suppress("DEPRECATION")
		val parent = state.getParcelable<Parcelable>(BUNDLE_SUPER)
		super.onRestoreInstanceState(parent)

		// Restore history
		val savedHistory = BundleCompat.getParcelableArrayList(state, BUNDLE_HISTORY, HistoryEntry::class.java)
		if (savedHistory != null) {
			history.clear()
			history.addAll(savedHistory)

			// FragmentManager may have already restored the content fragment; reusing it
			// avoids stacking a second copy on top of the restored view (ghost UI).
			val existing = fragmentManager.findFragmentByTag(FRAGMENT_TAG_CONTENT)
			val top = history.lastOrNull()
			if (existing != null && top != null && top.name.isInstance(existing)) {
				top.fragment = existing
				Timber.d("Reusing restored content fragment ${existing.javaClass.simpleName}")
			} else if (history.isNotEmpty()) {
				activateHistoryEntry(history.last(), fragmentManager.beginTransaction())
			}
		}
	}
}
