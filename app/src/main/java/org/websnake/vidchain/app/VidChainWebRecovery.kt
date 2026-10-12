package org.websnake.vidchain.app

import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import app.core.AIOApp.Companion.aioSettings
import app.ui.main.fragments.browser.webengine.WebViewEngine

/**
 * WebView renderer loss (new file, not upstream code; T8.10, found on the test phone 2026-10-12).
 *
 * Android kills the WebView renderer process under memory pressure, typically while the app sits in the background.
 * A WebView that does not handle onRenderProcessGone takes the whole app down with it ("Render process ... kill (OOM
 * or update) wasn't handled by all associated webviews, killing application"), including running downloads. All
 * WebViews share one renderer, so every WebView of the app must handle it.
 */
object VidChainWebRecovery {
	private const val CHURN_MS = 30_000L
	@Volatile private var lastReopen = 0L

	/** Any WebView that is not a browser tab: detach and destroy it; the caller ends its own job. Always handled. */
	fun dispose(view: WebView): Boolean {
		runCatching { (view.parent as? ViewGroup)?.removeView(view) }
		runCatching { view.destroy() }
		return true
	}

	/**
	 * A browser tab: the dead WebView leaves the tab list; the current tab (or the last one) is opened again as a new
	 * tab at the same address. A second loss within 30 s opens it blank, so a phone short of memory is not put into a
	 * kill / reload loop.
	 */
	fun browserTabGone(engine: WebViewEngine, view: WebView): Boolean {
		val url = runCatching { view.url }.getOrNull()
		val drawer = runCatching { engine.safeMotherActivityRef.sideNavigation }.getOrNull()
		val wasCurrent = engine.currentWebView === view
		if (wasCurrent) engine.currentWebView = null
		drawer?.totalWebViews?.remove(view)
		dispose(view)
		if (drawer == null) return true
		if (wasCurrent || drawer.totalWebViews.isEmpty()) {
			val now = SystemClock.elapsedRealtime()
			val churn = now - lastReopen < CHURN_MS
			lastReopen = now
			val target = when {
				churn -> "about:blank"
				url.isNullOrBlank() || url == "about:blank" -> aioSettings.browserDefaultHomepage
				else -> url
			}
			runCatching { drawer.addNewBrowsingTab(target, engine) }
		} else {
			runCatching { drawer.webTabListAdapter.notifyDataSetChanged() }
		}
		return true
	}
}
