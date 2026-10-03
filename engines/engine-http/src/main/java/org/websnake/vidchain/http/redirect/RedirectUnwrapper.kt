package org.websnake.vidchain.http.redirect

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.websnake.vidchain.http.await
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

/**
 * Method R's engine: follows HTTP redirects one hop at a time, HTML meta refresh and simple location scripts, and
 * unwraps known link wrappers (Google, Facebook, YouTube redirect, ...) without a request. A visited set stops loops.
 * Each run has its own cookie jar (some shorteners set a cookie and redirect to themselves); nothing is kept.
 */
class RedirectUnwrapper(baseClient: OkHttpClient, private val maxHops: Int = 10, private val allowLocalTargets: Boolean = false) {
	private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

	sealed class Result {
		data class Unwrapped(val url: String, val hops: List<String>) : Result()
		data class Unchanged(val url: String) : Result()
		data class Failed(val reason: String, val hops: List<String>) : Result()
	}

	suspend fun unwrap(start: String, headers: Map<String, String> = emptyMap()): Result {
		val jar = MemoryJar()
		val c = client.newBuilder().cookieJar(jar).build()
		val hops = ArrayList<String>()
		val visited = HashSet<String>()
		var current = start
		val startLocal = isLocal(start)
		while (true) {
			// a public link must never bounce the phone into its own network (router pages, localhost services)
			if (!allowLocalTargets && !startLocal && isLocal(current)) return Result.Failed("redirect into the local network refused", hops)
			unwrapKnown(current)?.let { inner ->
				hops += current
				if (!visited.add(norm(current))) return Result.Failed("redirect loop at ${host(current)}", hops)
				current = inner
				continue
			}
			if (!visited.add(norm(current))) return Result.Failed("redirect loop at ${host(current)}", hops)
			if (hops.size >= maxHops) return Result.Failed("more than $maxHops redirects", hops)
			val url = current.toHttpUrlOrNull() ?: return finish(start, current, hops)
			val rb = Request.Builder().url(url).get()
			headers.forEach { (k, v) -> rb.header(k, v) }
			val next: String? = try {
				c.newCall(rb.build()).await().use { r ->
					when {
						r.code in 300..399 -> r.header("Location")?.let { url.resolve(it)?.toString() }
							?: return Result.Failed("HTTP ${r.code} without a usable Location", hops)
						r.code in 200..299 && isHtml(r.header("Content-Type")) -> {
							val head = r.body.source().let { src -> src.request(MAX_HTML); src.buffer.snapshot().utf8() }.take(MAX_HTML.toInt())
							pageRedirect(head)?.let { url.resolve(it)?.toString() }
						}
						r.code in 200..299 -> null                                   // a file or another resource: arrived
						else -> return if (hops.isEmpty()) Result.Failed("HTTP ${r.code}", hops) else finish(start, current, hops)
					}
				}
			} catch (e: IOException) {
				return if (hops.isEmpty()) Result.Failed("connection: ${e.javaClass.simpleName}", hops) else finish(start, current, hops)
			}
			if (next == null || norm(next) == norm(current)) return finish(start, current, hops)
			hops += current
			current = next
		}
	}

	private fun finish(start: String, end: String, hops: List<String>) =
		if (norm(end) == norm(start)) Result.Unchanged(start) else Result.Unwrapped(end, hops)

	private class MemoryJar : CookieJar {
		private val store = ArrayList<Cookie>()
		@Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
			for (c in cookies) { store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }; store += c }
		}
		@Synchronized override fun loadForRequest(url: HttpUrl) = store.filter { it.matches(url) }
	}

	companion object {
		private const val MAX_HTML = 64L * 1024

		/** wrappers that carry the target in a query parameter: host suffix -> path prefix -> parameter */
		private val WRAPPERS = listOf(
			Triple("google.com", "/url", listOf("q", "url")),
			Triple("l.facebook.com", "/l.php", listOf("u")),
			Triple("lm.facebook.com", "/l.php", listOf("u")),
			Triple("l.instagram.com", "/", listOf("u")),
			Triple("youtube.com", "/redirect", listOf("q")),
			Triple("out.reddit.com", "/", listOf("url")),
			Triple("href.li", "/", listOf()),
			Triple("vk.com", "/away.php", listOf("to")),
			Triple("steamcommunity.com", "/linkfilter", listOf("url", "u")),
			Triple("t.umblr.com", "/redirect", listOf("z")),
			Triple("slack-redir.net", "/link", listOf("url")),
		)

		/** the wrapped target, or null when [url] is not a known wrapper */
		fun unwrapKnown(url: String): String? {
			val u = url.toHttpUrlOrNull() ?: return null
			val host = u.host.lowercase(Locale.ROOT)
			for ((suffix, path, params) in WRAPPERS) {
				if (host != suffix && !host.endsWith(".$suffix")) continue
				if (!u.encodedPath.startsWith(path)) continue
				if (suffix == "href.li") return u.encodedQuery?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.startsWith("http") }
				for (p in params) u.queryParameter(p)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
			}
			return null
		}

		private val META = Regex("""<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["']?\s*\d*\s*;?\s*url\s*=\s*['"]?([^"'>\s]+)""", RegexOption.IGNORE_CASE)
		private val META_REV = Regex("""<meta[^>]+content\s*=\s*["']\s*\d*\s*;\s*url\s*=\s*['"]?([^"'>]+?)['"]?\s*["'][^>]*http-equiv\s*=\s*["']?refresh""", RegexOption.IGNORE_CASE)
		private val SCRIPT = Regex("""(?:window\.|document\.)?location(?:\.href)?\s*(?:=|\.replace\(|\.assign\()\s*["']([^"']+)["']""")

		/** the target of a meta refresh or a one-line location script in a short page */
		fun pageRedirect(html: String): String? {
			META.find(html)?.let { return it.groupValues[1].replace("&amp;", "&") }
			META_REV.find(html)?.let { return it.groupValues[1].replace("&amp;", "&") }
			if (html.length < 8_000) SCRIPT.find(html)?.let { return it.groupValues[1].replace("\\/", "/") }
			return null
		}

		/** loopback, private, link-local or .local hosts (literal addresses and names; no DNS lookup) */
		fun isLocal(url: String): Boolean {
			val h = url.toHttpUrlOrNull()?.host?.lowercase(Locale.ROOT) ?: return false
			if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
			val v4 = h.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 && h.count { c -> c == '.' } == 3 }
			if (v4 != null) {
				val (a, b) = v4
				return a == 10 || a == 127 || a == 0 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
			}
			if (h.contains(':')) return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
			return false
		}

		private fun isHtml(type: String?) = type == null || type.contains("html", ignoreCase = true)
		private fun norm(u: String) = u.trim().removeSuffix("/").lowercase(Locale.ROOT)
		private fun host(u: String) = u.toHttpUrlOrNull()?.host ?: u.take(40)
	}
}
