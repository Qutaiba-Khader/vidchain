package org.websnake.vidchain.fallback.ui

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * T9 decision 11 (owner, 2026-10-14): content hidden under the title bar and the navigation bar.
 *
 * The app targets SDK 36, so on Android 15 and newer every screen is drawn edge to edge and an app can no longer turn
 * that off. A screen that never asked for room then draws under the status bar, its own title bar and the navigation
 * bar (found on "VidChain fallbacks": the first switches sat behind the title bar; API 36 emulator, 2026-10-14).
 *
 * [fit] pads a screen's content by what covers it, once Android reports it: the system bars, the display cutout
 * and the keyboard. Under edge to edge, AppCompat lays the content out from the very top but hands it insets whose
 * top already includes its title bar (API 35 emulator, 2026-10-14: adding the title bar again left a 56 px gap).
 * Where Android still fits the window to the bars itself (Android 14 and older), the reported insets are 0 and
 * nothing changes. The content scrolls inside the padding (it is clipped there), so nothing is drawn under a bar.
 */
object VidChainInsets {
	@JvmStatic
	fun fit(activity: Activity) {
		val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
		val root = content.getChildAt(0) ?: return
		pad(root)
	}

	/** pads [view] by what covers it (the insets it is given) */
	@JvmStatic
	fun pad(view: View) {
		val l = view.paddingLeft; val t = view.paddingTop; val r = view.paddingRight; val b = view.paddingBottom
		ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
			val types = WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
			val bars = insets.getInsets(types)
			// Android 15+ draws every window edge to edge (targetSdk 36) and the insets AppCompat hands its content may
			// lack the navigation bar (API 35 emulator, gesture navigation): the sides and the bottom come from the
			// window itself there. Older Android fits the window to the bars itself: only what the content is given.
			val root = if (Build.VERSION.SDK_INT >= 35) ViewCompat.getRootWindowInsets(v) else null
			val window = root?.getInsets(types)
			val ime = (root ?: insets).getInsets(WindowInsetsCompat.Type.ime())
			val left = maxOf(bars.left, window?.left ?: 0); val right = maxOf(bars.right, window?.right ?: 0)
			val bottom = maxOf(bars.bottom, window?.bottom ?: 0, ime.bottom)
			v.setPadding(l + left, t + bars.top, r + right, b + bottom)
			if (v is ViewGroup) v.clipToPadding = true
			insets
		}
		ViewCompat.requestApplyInsets(view)
	}
}
