package org.websnake.vidchain.web.probe

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject

/**
 * Puts [ProbeScript] into a WebView (T8.11). On current WebViews the script is a document-start script for every
 * origin, so it also runs inside cross-origin iframes (embedded players), before the page's own scripts; the results
 * come back through a WebMessageListener. Older WebViews get a JavaScript interface and the caller evaluates
 * [script] itself on page start / finish (main frame only).
 *
 * Whatever a page posts is treated as untrusted data: only an http(s) address, a short tag and the frame address are
 * read, and nothing is ever logged.
 */
object WebProbe {
	private const val MAX_FIELD = 6000

	/** One find: [url] (or "blob:"), [how] = the probe's tag, [frame] = the address of the frame that reported it. */
	data class Find(val url: String, val how: String, val frame: String?)

	/** true = the script runs by itself at document start; false = the caller must evaluate [script] on page events. */
	@SuppressLint("JavascriptInterface", "AddJavascriptInterface")
	fun attach(webView: WebView, autoplay: Boolean, onFind: (Find) -> Unit): Boolean {
		val origins = setOf("*")
		if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
			WebViewCompat.addWebMessageListener(webView, ProbeScript.NAME, origins) { _, message, _, _, _ ->
				// any frame can post here: only a text message is read (reading an ArrayBuffer message as text throws on
				// the main thread and took the app down - T8.11 review, reproduced on the test phone)
				try {
					if (message.type == WebMessageCompat.TYPE_STRING) parse(message.data)?.let(onFind)
				} catch (_: Throwable) {
				}
			}
		} else {
			webView.addJavascriptInterface(object {
				@JavascriptInterface fun report(m: String?) { try { parse(m)?.let(onFind) } catch (_: Throwable) {} }
			}, ProbeScript.NAME)
		}
		if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
			WebViewCompat.addDocumentStartJavaScript(webView, script(autoplay), origins)
			return true
		}
		return false
	}

	fun script(autoplay: Boolean): String = ProbeScript.build(autoplay)

	/** both WebView features the in-page probe needs (document-start script in every frame + message listener) */
	fun fullySupported(): Boolean = runCatching {
		WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
	}.getOrDefault(false)

	/** The probe's message: {"u": address, "h": tag, "p": frame}. Anything else is dropped. */
	fun parse(raw: String?): Find? = try {
		if (raw == null || raw.length > 3 * MAX_FIELD) null
		else JSONObject(raw).let { o ->
			val u = o.optString("u")
			val h = o.optString("h")
			val p = o.optString("p").take(MAX_FIELD).ifEmpty { null }
			if (u.length > MAX_FIELD || h.isEmpty() || h.length > 20) null                 // rejected, never cut to a different address
			else if (!(u == "blob:" || u.startsWith("http://", true) || u.startsWith("https://", true))) null
			else Find(u, h, p)
		}
	} catch (t: Throwable) {
		null
	}
}
