package org.websnake.vidchain.web.refetch

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Web Video Caster's re-fetch, done safely (T8.12, owner decision): for a page request that could be media in
 * disguise (no media extension, not a picture / style / script, or a static-looking address a disguise rule names),
 * VidChain asks the server itself, reads the first bytes and the Content-Type, reports a playlist / manifest / media
 * file, and hands the same answer to the page as it arrives (streamed, never kept whole).
 *
 * The page must not notice (ADHD design review, T8.12): every decision is taken before anything is sent
 * ([RefetchPolicy.decide]); only a clean 2xx is handed to the page - a redirect (WebView cannot take a 3xx from here),
 * an error, a bot check or a failure goes back to the WebView, which asks for itself, and that address shape is never
 * re-fetched again this session; signed / one-time links and range or media requests are never re-fetched.
 * Cookies: only a same-site request (same registrable domain as the page) carries the WebView's cookies for exactly
 * that address, and only its Set-Cookie answers are written back; a cross-site request that has cookies is left to
 * the WebView, so cookies never go anywhere the browser would not send them, and never follow a redirect. Nothing
 * is logged.
 */
class Refetcher(private val rules: DisguiseRules, private val never: RefetchPolicy.NeverList = RefetchPolicy.NeverList()) {

	/** [page]: the address of the page the request belongs to (its main frame); [report]: (url, probe tag) */
	fun intercept(request: WebResourceRequest, page: String?, gate: RefetchPolicy.PageGate, userAgent: String?,
		report: (String, String) -> Unit): WebResourceResponse? {
		val url = request.url?.toString() ?: return null
		val headers = request.requestHeaders.orEmpty()
		val verdict = RefetchPolicy.decide(RefetchPolicy.Facts(url, request.method, request.isForMainFrame, headers), gate, rules, never)
		val disguised = (verdict as? RefetchPolicy.Verdict.Fetch ?: return null).disguised
		val target = url.toHttpUrlOrNull() ?: return null
		val sameSite = page?.toHttpUrlOrNull()?.let { p -> p.topPrivateDomain() != null && p.topPrivateDomain() == target.topPrivateDomain() } ?: false
		val cookies = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
		if (!sameSite && cookies != null) return null                                 // the browser's own rules for it

		val b = Request.Builder().url(target)
		for ((k, v) in headers) if (k.lowercase(Locale.ROOT) !in DROP && '\n' !in v && '\r' !in v) runCatching { b.header(k, v) }
		if (headers.keys.none { it.equals("User-Agent", true) }) userAgent?.let { b.header("User-Agent", it) }
		if (sameSite && cookies != null) b.header("Cookie", cookies)

		val response: Response = try { CLIENT.newCall(b.build()).execute() } catch (e: Exception) { return handBack(url, gate, disguised) }
		try {
			val code = response.code
			if (code !in 200..299 || code == 206) { response.close(); return handBack(url, gate, disguised) }
			val body = response.body
			val source = body.source()
			source.request(1)                                             // the first bytes, never the whole answer
			val head = source.buffer.let { buf -> buf.copy().readByteArray(minOf(buf.size, HEAD.toLong())) }
			if (RefetchPolicy.challenge(head)) { response.close(); return handBack(url, gate, disguised) }
			if (sameSite) for (c in response.headers("Set-Cookie")) runCatching { CookieManager.getInstance().setCookie(url, c) }
			val how = RefetchPolicy.sniff(response.header("Content-Type"), head)
			when {
				how == "body-hls" || how == "body-dash" || how == "ct-hls" || how == "ct-dash" -> { rules.learn(url); gate.found(); report(url, how) }
				how != null -> { gate.found(); report(url, how) }
				disguised != null -> rules.miss(url)
			}
			return toWebView(response, body.byteStream())
		} catch (e: Exception) {
			response.close()
			return handBack(url, gate, disguised)
		}
	}

	/** the in-page probe's evidence: a playlist / manifest at a static-looking address teaches the disguise rules */
	fun observed(url: String, how: String) {
		if (how == "body-hls" || how == "body-dash" || how == "ct-hls" || how == "ct-dash") rules.learn(url)
	}

	private fun handBack(url: String, gate: RefetchPolicy.PageGate, disguised: String?): WebResourceResponse? {
		never.add(url); gate.handedBack()
		if (disguised != null) rules.miss(url)
		return null
	}

	private fun toWebView(r: Response, stream: InputStream): WebResourceResponse {
		val ct = r.header("Content-Type")
		val mime = ct?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
		val charset = ct?.let { Regex("""charset=([^;\s]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.trim('"') }
		val out = LinkedHashMap<String, String>()
		for (name in r.headers.names()) if (name.lowercase(Locale.ROOT) !in DROP_OUT) out[name] = r.headers(name).joinToString(", ")
		val closing = object : FilterInputStream(stream) { override fun close() { runCatching { super.close() }; r.close() } }
		return WebResourceResponse(mime, charset, r.code, r.message.takeIf { m -> m.isNotBlank() && m.all { it.code in 32..126 } } ?: "OK", out, closing)
	}

	companion object {
		private const val HEAD = 2048
		/** never forwarded: one connection's or range's headers, cookies (added per host above), compression (OkHttp's own) */
		private val DROP = setOf("host", "connection", "keep-alive", "content-length", "transfer-encoding", "te", "trailer", "upgrade",
			"accept-encoding", "cookie", "cookie2", "range", "if-range", "if-modified-since", "if-none-match", "if-match", "if-unmodified-since",
			"proxy-authorization", "proxy-connection")
		/** never handed to the page: the body is already decoded and its length may differ; cookies were stored above */
		private val DROP_OUT = setOf("content-encoding", "content-length", "transfer-encoding", "connection", "keep-alive", "set-cookie", "set-cookie2")

		private val CLIENT: OkHttpClient by lazy {
			OkHttpClient.Builder()
				.followRedirects(false).followSslRedirects(false)        // a redirect goes back to the WebView
				.retryOnConnectionFailure(false)                          // never a second request behind the page's back
				.connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
				.build()
		}

		@Volatile private var shared: Refetcher? = null

		/** one per process: the disguise rules (and what they learned) are shared by the browser and method W */
		fun get(context: Context): Refetcher = shared ?: synchronized(this) {
			shared ?: Refetcher(DisguiseRules(context.applicationContext.assets.open("vidchain-disguise-rules.json").use { String(it.readBytes()) })).also { shared = it }
		}
	}
}
