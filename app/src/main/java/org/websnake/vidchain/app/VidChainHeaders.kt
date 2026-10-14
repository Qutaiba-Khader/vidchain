package org.websnake.vidchain.app

import android.webkit.WebResourceRequest
import org.websnake.vidchain.web.headers.RequestHeaders

/**
 * The request headers the in-app browser sent, per address (new file, not upstream code; T8.12, owner decision):
 * when a download from the browser fails, method S replays the exact headers the page's player used for that media
 * address. Memory only (lost with the process), at most [MAX] addresses, never logged, never a Cookie (cookies are
 * read per host when a fallback runs). Off with the fallbacks master switch.
 */
object VidChainHeaders {
	private const val MAX = 400
	private val byUrl = object : LinkedHashMap<String, Map<String, String>>(64, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Map<String, String>>?) = size > MAX
	}

	/** Seam headers (BrowserWebClient.shouldInterceptRequest, any thread): GET sub-requests only. */
	@JvmStatic
	fun seen(request: WebResourceRequest?) {
		runCatching {
			val r = request ?: return
			if (r.isForMainFrame || !r.method.equals("GET", ignoreCase = true)) return
			val url = r.url?.toString()?.takeIf { it.startsWith("http", ignoreCase = true) && it.length <= 6000 } ?: return
			if (STATIC.containsMatchIn(url.substringBefore('?'))) return
			val kept = RequestHeaders.keep(r.requestHeaders)
			if (kept.isEmpty()) return
			synchronized(byUrl) { byUrl[url] = kept }
		}
	}

	fun forUrl(url: String?): Map<String, String> = url?.let { synchronized(byUrl) { byUrl[it] } }.orEmpty()

	/** pictures, fonts, styles and scripts are never the video */
	private val STATIC = Regex("""\.(?:png|jpe?g|gif|webp|avif|svg|ico|woff2?|ttf|otf|eot|css|js|mjs|map)$""", RegexOption.IGNORE_CASE)

}
