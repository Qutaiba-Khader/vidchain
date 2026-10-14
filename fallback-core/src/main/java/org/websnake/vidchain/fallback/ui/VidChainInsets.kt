package org.websnake.vidchain.fallback.ui

import android.app.Activity
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * T9 decision 11 (owner, 2026-10-14): content hidden under the title bar and the navigation bar.
 *
 * The app targets SDK 36, so on Android 15 and newer every screen is drawn edge to edge and an app can no longer turn
 * that off. A screen that never asked for room then draws under the status bar, its own title bar and the navigation
 * bar (found on "VidChain fallbacks": the first switches sat behind the title bar; API 36 emulator, 2026-10-14).
 *
 * [fit] pads a screen's content by what covers it, once Android reports it:
 * - left / right / bottom: the system bars, the display cutout and the keyboard;
 * - top: the status bar (and the cutout), plus the title bar when the window is drawn edge to edge (then AppCompat
 *   lays the content out from the very top, under its title bar).
 * Where Android still fits the window to the bars itself (Android 14 and older), the reported insets are 0 and
 * nothing changes. The content scrolls inside the padding (it is clipped there), so nothing is drawn under a bar.
 */
object VidChainInsets {
	@JvmStatic
	fun fit(activity: Activity) {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
		val root = content.getChildAt(0) ?: return
		val hasTitleBar = (activity as? AppCompatActivity)?.supportActionBar?.isShowing == true
		pad(root, titleBarPx = if (hasTitleBar) actionBarHeight(activity) else 0)
	}

	/** pads [view] by what covers it; [titleBarPx] = the screen's own title bar height, added when drawn edge to edge */
	@JvmStatic
	fun pad(view: View, titleBarPx: Int = 0) {
		val l = view.paddingLeft; val t = view.paddingTop; val r = view.paddingRight; val b = view.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
			val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
			val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
			val top = bars.top + if (bars.top > 0) titleBarPx else 0
			v.setPadding(l + bars.left, t + top, r + bars.right, b + maxOf(bars.bottom, ime.bottom))
			if (v is ViewGroup) v.clipToPadding = true
			insets
		}
		ViewCompat.requestApplyInsets(view)
	}

	private fun actionBarHeight(activity: Activity): Int {
		val tv = TypedValue()
		val attr = androidx.appcompat.R.attr.actionBarSize
		return if (activity.theme.resolveAttribute(attr, tv, true)) TypedValue.complexToDimensionPixelSize(tv.data, activity.resources.displayMetrics) else 0
	}
}
