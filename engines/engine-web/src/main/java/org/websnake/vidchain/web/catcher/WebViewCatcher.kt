package org.websnake.vidchain.web.catcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Method W's browser (T3.3): its own hidden WebView - never the app's browser or its cookie WebView - loads the page
 * with the in-app browser's cookie store (shared by every WebView of the app) and the browser's user agent, lets the
 * player start (muted), and records media requests and the URLs the page hands to its players. Bounded by a deadline;
 * destroyed afterwards.
 */
class WebViewCatcher(private val context: Context) {

	@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
	suspend fun catch(url: String, userAgent: String?, deadlineMs: Long = 25_000): CatchResult {
		val collector = CatchCollector(url, deadlineMs)
		var finalPage = url
		val rendererGone = java.util.concurrent.atomic.AtomicBoolean(false)
		val webView = withContext(Dispatchers.Main) {
			WebView(context.applicationContext).apply {
				settings.javaScriptEnabled = true
				settings.domStorageEnabled = true
				settings.mediaPlaybackRequiresUserGesture = false
				settings.blockNetworkImage = true
				userAgent?.takeIf { it.isNotBlank() }?.let { settings.userAgentString = it }
				CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
				// T8.11: the shared media probe, in every frame from document start where the WebView supports it
				val docStart = org.websnake.vidchain.web.probe.WebProbe.attach(this, autoplay = true) { f -> collector.offer(f.url, f.how) }
				val pageScript = if (docStart) null else org.websnake.vidchain.web.probe.WebProbe.script(autoplay = true)
				webViewClient = object : WebViewClient() {
					override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
						collector.offer(request.url?.toString(), "request", request.requestHeaders.orEmpty())
						return null                                       // observe only, never alter the page's traffic
					}
					override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { pageScript?.let { view.evaluateJavascript(it, null) } }
					override fun onPageFinished(view: WebView, u: String?) { u?.let { finalPage = it }; pageScript?.let { view.evaluateJavascript(it, null) } }
					// a lost renderer (Android reclaims memory) must not take the app down: end this catch with what it has
					override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
						rendererGone.set(true)
						runCatching { view.destroy() }
						return true
					}
				}
				val w = android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY)
				val h = android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY)
				measure(w, h); layout(0, 0, 1280, 720)
				loadUrl(url)
			}
		}
		try {
			while (!collector.done() && !rendererGone.get()) delay(250)
		} finally {
			withContext(Dispatchers.Main) {
				if (!rendererGone.get()) runCatching { webView.stopLoading(); webView.loadUrl("about:blank"); webView.destroy() }
			}
		}
		return CatchResult(collector.result().map { it.copy(page = finalPage) }, collector.sawBlob, finalPage)
	}
}
