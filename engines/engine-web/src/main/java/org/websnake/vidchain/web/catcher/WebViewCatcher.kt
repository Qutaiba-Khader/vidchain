package org.websnake.vidchain.web.catcher

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
		val webView = withContext(Dispatchers.Main) {
			WebView(context.applicationContext).apply {
				settings.javaScriptEnabled = true
				settings.domStorageEnabled = true
				settings.mediaPlaybackRequiresUserGesture = false
				settings.blockNetworkImage = true
				userAgent?.takeIf { it.isNotBlank() }?.let { settings.userAgentString = it }
				CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
				addJavascriptInterface(object {
					@JavascriptInterface fun report(u: String?, how: String?) = collector.offer(u, how ?: "js")
				}, CatcherScript.BRIDGE)
				webViewClient = object : WebViewClient() {
					override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
						collector.offer(request.url?.toString(), "request")
						return null                                       // observe only, never alter the page's traffic
					}
					override fun onPageStarted(view: WebView, u: String?, favicon: Bitmap?) { view.evaluateJavascript(CatcherScript.JS, null) }
					override fun onPageFinished(view: WebView, u: String?) { u?.let { finalPage = it }; view.evaluateJavascript(CatcherScript.JS, null) }
				}
				val w = android.view.View.MeasureSpec.makeMeasureSpec(1280, android.view.View.MeasureSpec.EXACTLY)
				val h = android.view.View.MeasureSpec.makeMeasureSpec(720, android.view.View.MeasureSpec.EXACTLY)
				measure(w, h); layout(0, 0, 1280, 720)
				loadUrl(url)
			}
		}
		try {
			while (!collector.done()) delay(250)
		} finally {
			withContext(Dispatchers.Main) {
				runCatching { webView.stopLoading(); webView.loadUrl("about:blank"); webView.removeJavascriptInterface(CatcherScript.BRIDGE); webView.destroy() }
			}
		}
		return CatchResult(collector.result().map { it.copy(page = finalPage) }, collector.sawBlob, finalPage)
	}
}
