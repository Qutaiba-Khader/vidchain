package org.websnake.vidchain.web.po

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * YouTube PO tokens minted on the phone (T8.12, owner decision): a hidden WebView of VidChain's own - never the app's
 * browser - opens https://www.youtube.com/vidchain-po, a page the app serves itself (no YouTube page is loaded), so
 * BotGuard runs on YouTube's origin as it does on youtube.com. BgUtils (LuanRT, MIT; assets/vidchain-bgutils.js) fetches
 * the challenge, runs it, gets the integrity token and mints one token for the visitor and one for the video
 * (assets/vidchain-po-mint.js). Every request leaves without cookies: the user's YouTube login is never used or changed.
 *
 * One mint at a time, at most [TIMEOUT_MS]; a token is kept for [KEEP_MS] (or the integrity token's own lifetime if
 * shorter) per video, so a retry does not mint again. The tokens are never logged.
 */
class PoTokenMinter(private val context: Context) {

	class Minted(val visitorData: String, val gvs: String, val player: String) {
		override fun toString() = "Minted(<redacted>)"
	}

	/** what happened, for the trace: never the tokens */
	@Volatile var lastProblem: String? = null
		private set

	suspend fun mint(videoId: String): Minted? {
		if (!VIDEO_ID.matches(videoId)) return null
		cached(videoId)?.let { return it }
		return LOCK.withLock {
			cached(videoId)?.let { return@withLock it }
			lastProblem = null
			val e = withTimeoutOrNull(TIMEOUT_MS) { run(videoId) }
			if (e == null) { if (lastProblem == null) lastProblem = "timed out"; return@withLock null }
			synchronized(CACHE) {
				while (CACHE.size >= MAX_CACHED) CACHE.remove(CACHE.keys.first())
				CACHE[videoId] = e
			}
			e.minted
		}
	}

	private fun cached(videoId: String): Minted? = synchronized(CACHE) {
		val now = System.currentTimeMillis()
		CACHE.entries.removeAll { it.value.until <= now }
		CACHE[videoId]?.minted
	}

	@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
	private suspend fun run(videoId: String): Entry? {
		val result = CompletableDeferred<String>()
		val bg = runCatching { context.assets.open(BG_ASSET).use { it.readBytes() } }.getOrNull()
		val mint = runCatching { context.assets.open(MINT_ASSET).use { it.readBytes() } }.getOrNull()
		if (bg == null || mint == null) { lastProblem = "assets missing"; return null }
		val page = "$PAGE?v=$videoId"
		val webView = withContext(Dispatchers.Main) {
			WebView(context.applicationContext).apply {
				settings.javaScriptEnabled = true
				settings.domStorageEnabled = false
				settings.blockNetworkImage = true
				settings.allowFileAccess = false
				settings.allowContentAccess = false
				addJavascriptInterface(object {
					@JavascriptInterface fun done(json: String?) { result.complete(json.orEmpty().take(MAX_ANSWER)) }
				}, "VidChainPo")
				webViewClient = object : WebViewClient() {
					override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
						val u = request.url?.toString() ?: return null
						return when {
							u == page -> WebResourceResponse("text/html", "utf-8", HTML.byteInputStream())
							u == "$ORIGIN$BG_PATH" -> WebResourceResponse("text/javascript", "utf-8", bg.inputStream())
							u == "$ORIGIN$MINT_PATH" -> WebResourceResponse("text/javascript", "utf-8", mint.inputStream())
							else -> null                                          // the challenge and token requests go out as they are
						}
					}
					// the page never navigates anywhere
					override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
					override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
						result.complete("""{"ok":false,"error":"renderer gone"}""")
						runCatching { view.destroy() }
						return true
					}
				}
				loadUrl(page)
			}
		}
		try {
			val answer = runCatching { JSONObject(result.await()) }.getOrNull() ?: run { lastProblem = "bad answer"; return null }
			if (!answer.optBoolean("ok")) { lastProblem = answer.optString("error").take(160).ifBlank { "failed" }; return null }
			val m = Minted(answer.optString("visitorData"), answer.optString("gvs"), answer.optString("player"))
			val ttlMs = answer.optLong("ttl", 0L).takeIf { it > 60 }?.times(1000)?.minus(60_000) ?: KEEP_MS
			return Entry(m, System.currentTimeMillis() + minOf(KEEP_MS, ttlMs))
		} finally {
			withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
				runCatching { webView.stopLoading(); webView.removeJavascriptInterface("VidChainPo"); webView.destroy() }
			}
		}
	}

	private class Entry(val minted: Minted, val until: Long)

	companion object {
		private const val ORIGIN = "https://www.youtube.com"
		private const val PAGE = "$ORIGIN/vidchain-po"
		private const val BG_PATH = "/vidchain-po/bg.js"
		private const val MINT_PATH = "/vidchain-po/mint.js"
		private const val BG_ASSET = "vidchain-bgutils.js"
		private const val MINT_ASSET = "vidchain-po-mint.js"
		private const val HTML = "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>" +
			"<script src=\"$BG_PATH\"></script><script src=\"$MINT_PATH\"></script></body></html>"
		const val TIMEOUT_MS = 30_000L
		const val KEEP_MS = 6 * 60 * 60 * 1000L
		private const val MAX_CACHED = 8
		private const val MAX_ANSWER = 16_000
		private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{11}$")
		private val LOCK = Mutex()
		private val CACHE = LinkedHashMap<String, Entry>()
	}
}
