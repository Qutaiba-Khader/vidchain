package org.websnake.vidchain.fallback.context

import java.io.File
import java.net.URI
import java.util.Locale

/** One cookie with the host it belongs to (the in-app browser's cookie store answers per URL, so the host is exact). */
data class SessionCookie(val host: String, val name: String, val value: String, val path: String = "/", val secure: Boolean = false) {
	override fun toString() = "SessionCookie($host, $name=…)"
}

/**
 * Method S's view of the browsing session: cookies scoped to their host, the FULL Referer (the current method cuts it
 * to the host), Origin and the browser's User-Agent. It never reaches the ledger, the trace or a FallbackContext:
 * toString() shows no values, and cookie files are private, temporary and deleted after use.
 */
class SessionContext(
	val cookies: List<SessionCookie>,
	val referer: String?,
	val userAgent: String?,
) {
	/**
	 * the Cookie header for [url]: only cookies of that exact host (the browser store cannot say which cookies are
	 * domain cookies, so none is widened to subdomains), a matching path, and secure cookies only over https
	 */
	fun cookieHeader(url: String): String? {
		val u = runCatching { URI(url) }.getOrNull() ?: return null
		val host = u.host?.lowercase(Locale.ROOT) ?: return null
		val path = u.rawPath.orEmpty().ifEmpty { "/" }
		val https = u.scheme.equals("https", true)
		return cookies.filter { c -> host == c.host && path.startsWith(c.path) && (!c.secure || https) }
			.distinctBy { it.name }.joinToString("; ") { "${it.name}=${it.value}" }.ifEmpty { null }
	}

	/** Origin of the page, sent only when the media lives on another site (like a cross-origin player request) */
	fun originFor(mediaUrl: String): String? {
		val r = referer?.let { runCatching { URI(it) }.getOrNull() } ?: return null
		val m = runCatching { URI(mediaUrl) }.getOrNull() ?: return null
		if (r.host == null || r.host.equals(m.host, ignoreCase = true)) return null
		return "${r.scheme}://${r.host}" + if (r.port > 0) ":${r.port}" else ""
	}

	/** request headers for [url] (Cookie only for its host; leave it out when the client scopes cookies per redirect hop) */
	fun headersFor(url: String, withCookie: Boolean = true): Map<String, String> = buildMap {
		userAgent?.takeIf { it.isNotBlank() }?.let { put("User-Agent", it) }
		referer?.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
		originFor(url)?.let { put("Origin", it) }
		if (withCookie) cookieHeader(url)?.let { put("Cookie", it) }
	}

	/**
	 * Netscape cookie file with a real domain on every line (the upstream writer leaves the domain empty, so yt-dlp and
	 * aria2c ignore those cookies). Owner-only permissions; the caller deletes it after use.
	 */
	fun writeNetscape(file: File): File {
		file.parentFile?.mkdirs()
		file.writeText(netscape(cookies))
		file.setReadable(false, false); file.setReadable(true, true)
		file.setWritable(false, false); file.setWritable(true, true)
		return file
	}

	override fun toString() = "SessionContext(cookies=${cookies.size} on ${cookies.map { it.host }.distinct()}, referer=${referer?.let { runCatching { URI(it).host }.getOrNull() }}, ua=${userAgent != null})"

	companion object {
		/** "a=1; b=2" as cookies of [host]; [secure] = they were read for an https URL (then they never go over plain http) */
		fun parseHeader(header: String?, host: String, secure: Boolean = false): List<SessionCookie> =
			header.orEmpty().split(';').mapNotNull { part ->
				val kv = part.trim().split('=', limit = 2)
				if (kv.size == 2 && kv[0].isNotBlank()) SessionCookie(host.lowercase(Locale.ROOT), kv[0].trim(), kv[1].trim(), secure = secure) else null
			}

		fun netscape(cookies: List<SessionCookie>): String = buildString {
			append("# Netscape HTTP Cookie File\n")
			for (c in cookies) {
				// exact host, no subdomains: the browser store answered for this host only
				append(c.host).append('\t').append("FALSE").append('\t').append(c.path).append('\t')
					.append(if (c.secure) "TRUE" else "FALSE").append('\t').append("2147483647").append('\t')
					.append(c.name).append('\t').append(c.value).append('\n')
			}
		}
	}
}
