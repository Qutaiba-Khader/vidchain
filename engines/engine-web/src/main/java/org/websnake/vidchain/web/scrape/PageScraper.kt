package org.websnake.vidchain.web.scrape

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.websnake.vidchain.hosts.Page
import java.util.Locale

/** A media URL found on a page. [page] is the page it came from (the Referer to send). */
data class MediaCandidate(val url: String, val kind: Kind, val source: String, val page: String, val score: Int) {
	enum class Kind { FILE, HLS, DASH }
}

/**
 * Method H's scanner (T3.2): <video>/<audio>/<source>, Open Graph and Twitter player streams, JSON-LD VideoObject
 * contentUrl, and stream URLs written into scripts (also JSON-escaped). Iframes are followed, bounded by depth and
 * page count, never back to a page already seen.
 */
object PageScraper {
	private val MEDIA_EXT = Regex("""\.(mp4|m4v|webm|mov|mkv|m4a|mp3|ogg|ogv|ts|flv|3gp)(?:$|[?#])""", RegexOption.IGNORE_CASE)
	private val SCRIPT_URL = Regex("""https?:(?://|\\/\\/)[^"'\s<>\\]*(?:\\/[^"'\s<>\\]*)*?\.(?:m3u8|mpd|mp4|webm|m4v)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
	private val JUNK = Regex("""doubleclick|googlesyndication|/ads?/|[?&]ad=|preroll|sprite|thumb|preview\.mp4|\.vtt|\.srt|placeholder""", RegexOption.IGNORE_CASE)

	fun scan(html: String, pageUrl: String): List<MediaCandidate> {
		val doc = Jsoup.parse(html, pageUrl)
		val out = LinkedHashMap<String, MediaCandidate>()
		fun add(raw: String?, source: String, score: Int) {
			val url = absolute(unescape(raw ?: return).trim(), pageUrl) ?: return
			if (JUNK.containsMatchIn(url) || url.startsWith("blob:") || url.startsWith("data:")) return
			val kind = kindOf(url) ?: return
			val prev = out[url]
			if (prev == null || prev.score < score) out[url] = MediaCandidate(url, kind, source, pageUrl, score)
		}
		for (v in doc.select("video[src], audio[src]")) add(v.attr("src"), v.tagName(), 90)
		for (v in doc.select("video[data-src], video source[data-src]")) add(v.attr("data-src"), "data-src", 85)
		for (s in doc.select("video source[src], audio source[src]")) add(s.attr("src"), "source", 90 + qualityBonus(s.attr("label") + s.attr("res") + s.attr("size")))
		for (m in doc.select("meta[property~=^og:video(:secure_url|:url)?$], meta[name~=^og:video(:secure_url|:url)?$]")) add(m.attr("content"), "og:video", 80)
		for (m in doc.select("meta[name=twitter:player:stream], meta[property=twitter:player:stream]")) add(m.attr("content"), "twitter:stream", 75)
		for (l in doc.select("link[rel=video_src], link[itemprop=contentUrl]")) add(l.attr("href"), "link", 70)
		for (m in doc.select("[itemprop=contentUrl]")) add(m.attr("content").ifEmpty { m.attr("href") }, "microdata", 70)
		for (s in doc.select("script[type=application/ld+json]")) jsonLdUrls(s.data()).forEach { add(it, "json-ld", 85) }
		for (s in doc.select("script")) SCRIPT_URL.findAll(s.data()).forEach { add(it.value, "script", 50) }
		return out.values.sortedWith(compareByDescending<MediaCandidate> { it.score + kindBonus(it.kind) }.thenBy { it.url.length })
	}

	/** iframes worth following (players), same order as on the page */
	fun iframes(html: String, pageUrl: String): List<String> =
		Jsoup.parse(html, pageUrl).select("iframe[src], iframe[data-src]").mapNotNull { absolute(it.attr("src").ifEmpty { it.attr("data-src") }, pageUrl) }
			.filter { it.startsWith("http") && !JUNK.containsMatchIn(it) }.distinct()

	/**
	 * Scans [start] and, when it shows no media, its iframes, at most [maxDepth] levels and [maxPages] pages, never a
	 * page twice. Candidates keep the page they were found on.
	 */
	suspend fun discover(start: String, fetch: suspend (String) -> Page, maxDepth: Int = 2, maxPages: Int = 6): List<MediaCandidate> {
		val seen = HashSet<String>()
		var frontier = listOf(start)
		var depth = 0
		val found = ArrayList<MediaCandidate>()
		while (frontier.isNotEmpty() && depth <= maxDepth && seen.size < maxPages) {
			val next = ArrayList<String>()
			for (u in frontier) {
				if (seen.size >= maxPages || !seen.add(u.substringBefore('#'))) continue
				val p = runCatching { fetch(u) }.getOrNull() ?: continue
				if (p.code !in 200..299) continue
				val type = p.contentType
				if (type != null && !type.contains("html", true) && !type.contains("xml", true)) {
					kindOf(p.url)?.let { found += MediaCandidate(p.url, it, "direct", start, 60) }
					continue
				}
				found += scan(p.body, p.url)
				if (found.isEmpty()) next += iframes(p.body, p.url)
			}
			if (found.isNotEmpty()) break
			frontier = next
			depth++
		}
		return found.distinctBy { it.url }.sortedWith(compareByDescending<MediaCandidate> { it.score + kindBonus(it.kind) }.thenBy { it.url.length })
	}

	fun kindOf(url: String): MediaCandidate.Kind? {
		val path = url.substringBefore('#').lowercase(Locale.ROOT)
		val p = path.substringBefore('?')
		return when {
			p.endsWith(".m3u8") || "m3u8" in path && "format=" in path -> MediaCandidate.Kind.HLS
			p.endsWith(".mpd") -> MediaCandidate.Kind.DASH
			MEDIA_EXT.containsMatchIn(p) -> MediaCandidate.Kind.FILE
			else -> null
		}
	}

	private fun kindBonus(k: MediaCandidate.Kind) = when (k) { MediaCandidate.Kind.HLS, MediaCandidate.Kind.DASH -> 5; else -> 0 }

	private fun qualityBonus(label: String): Int = Regex("""(\d{3,4})""").find(label)?.groupValues?.get(1)?.toIntOrNull()?.let { (it / 120).coerceAtMost(9) } ?: 0

	private fun jsonLdUrls(json: String): List<String> {
		val out = ArrayList<String>()
		fun walk(x: Any?) {
			when (x) {
				is JSONObject -> {
					val type = x.opt("@type")?.toString().orEmpty()
					if ("VideoObject" in type || "AudioObject" in type || "MediaObject" in type) x.optString("contentUrl").takeIf { it.isNotEmpty() }?.let(out::add)
					x.keys().forEach { walk(x.opt(it)) }
				}
				is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
			}
		}
		runCatching { walk(if (json.trim().startsWith("[")) JSONArray(json.trim()) else JSONObject(json.trim())) }
		return out
	}

	private fun unescape(s: String) = s.replace("\\/", "/").replace("\\u002F", "/").replace("\\u002f", "/").replace("\\u0026", "&").replace("&amp;", "&")

	private fun absolute(u: String, base: String): String? {
		if (u.isEmpty()) return null
		if (u.startsWith("//")) return (base.substringBefore(':').ifEmpty { "https" }) + ":" + u
		return base.toHttpUrlOrNull()?.resolve(u)?.toString() ?: u.takeIf { it.startsWith("http") }
	}

	/** jsoup's Document, for callers that already parsed the page */
	fun parse(html: String, base: String): Document = Jsoup.parse(html, base)
}
