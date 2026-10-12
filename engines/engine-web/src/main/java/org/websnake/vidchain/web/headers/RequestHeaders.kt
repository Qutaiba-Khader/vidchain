package org.websnake.vidchain.web.headers

import java.util.Locale

/**
 * The request headers the page's own player sent for a media address, kept so a fallback can replay them exactly
 * (T8.12, owner decision; idea from browser extensions that replay a captured request). Pure, JVM-tested.
 *
 * Kept: what a server may check (Referer, Origin, Accept, Accept-Language, User-Agent, custom X-... tokens a player
 * adds, Sec-Fetch-*). Dropped: what belongs to one connection or one range (Range, If-*, Host, Connection,
 * Content-Length, Accept-Encoding - the downloaders do not decompress), Cookie (cookies are always looked up per host
 * at download time, so they never follow a redirect to another host), and X-Requested-With (Android adds the app's
 * package name there: an app fingerprint, not something the page sent). Authorization is dropped too (T8.12 review):
 * a replay may reach another host (a redirect, a stream's pieces), and a credential must never follow it there. Only
 * plain printable values and short header names are kept, at most [MAX_TOTAL] characters per address, so a page cannot
 * make the app hold much, and an HTTP client never throws on a value.
 */
object RequestHeaders {
	private val DROP = setOf(
		"range", "if-range", "if-modified-since", "if-none-match", "if-match", "if-unmodified-since",
		"host", "connection", "keep-alive", "content-length", "content-type", "transfer-encoding", "te", "trailer", "upgrade",
		"accept-encoding", "cookie", "cookie2", "x-requested-with", "authorization", "proxy-authorization", "proxy-connection",
	)
	private const val MAX_HEADERS = 30
	private const val MAX_VALUE = 2000
	private const val MAX_TOTAL = 8_000
	private val NAME = Regex("""^[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}$""")

	fun keep(headers: Map<String, String>?): Map<String, String> {
		if (headers.isNullOrEmpty()) return emptyMap()
		val out = LinkedHashMap<String, String>()
		var total = 0
		for ((k, v) in headers) {
			val name = k.trim()
			if (!NAME.matches(name) || name.lowercase(Locale.ROOT) in DROP || v.length > MAX_VALUE || v.any { it != '\t' && (it < ' ' || it > '~') }) continue
			if (out.size >= MAX_HEADERS) break
			if (total + name.length + v.length > MAX_TOTAL) continue          // a big one is left out, the small ones after it kept
			out[name] = v
			total += name.length + v.length
		}
		return out
	}

	/** [exact] first, then the defaults for names it does not carry (names compared without case) */
	fun merge(exact: Map<String, String>, defaults: Map<String, String>): Map<String, String> {
		val have = exact.keys.map { it.lowercase(Locale.ROOT) }.toSet()
		return LinkedHashMap(exact).apply { defaults.forEach { (k, v) -> if (k.lowercase(Locale.ROOT) !in have) put(k, v) } }
	}
}
