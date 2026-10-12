package org.websnake.vidchain.web.catcher

import org.websnake.vidchain.web.scrape.MediaCandidate
import org.websnake.vidchain.web.scrape.PageScraper
import java.util.Locale

/**
 * Method W's bookkeeping (pure, JVM-tested): which requests and DOM sources are media, and when to stop.
 * Stops at the deadline, or [graceMs] after the first strong find (a stream manifest or a media file) so a better
 * rendition that loads right after can still be caught.
 */
class CatchCollector(
	private val page: String,
	private val deadlineMs: Long,
	private val graceMs: Long = 2_500,
	private val clock: () -> Long = System::currentTimeMillis,
) {
	private val started = clock()
	private val found = LinkedHashMap<String, MediaCandidate>()
	private var firstStrongAt: Long? = null
	var sawBlob = false
		private set

	@Synchronized
	fun offer(rawUrl: String?, how: String) {
		val url = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return
		if (url.startsWith("blob:", ignoreCase = true)) { sawBlob = true; return }
		if (!url.startsWith("http", ignoreCase = true)) return
		val kind = classify(url, how) ?: return
		if (JUNK.containsMatchIn(url)) return
		val score = when (how) {
			"ct-hls", "ct-dash", "body-hls", "body-dash", "play" -> 95
			"media.src", "dom", "ct", "player" -> 90
			"request", "net" -> 80
			"perf", "meta" -> 75
			"fetch", "xhr" -> 70
			else -> 60                                                  // "body": an address named in an API response
		} + if (kind == MediaCandidate.Kind.FILE) 0 else 5
		val prev = found[url]
		if (prev == null || prev.score < score) found[url] = MediaCandidate(url, kind, "webview:$how", page, score)
		if (firstStrongAt == null) firstStrongAt = clock()
	}

	@Synchronized
	fun done(): Boolean {
		val now = clock()
		if (now - started >= deadlineMs) return true
		return firstStrongAt?.let { now - it >= graceMs } ?: false
	}

	@Synchronized
	fun result(): List<MediaCandidate> = found.values.sortedWith(compareByDescending<MediaCandidate> { it.score }.thenBy { it.url.length })

	companion object {
		private val JUNK = Regex("""doubleclick|googlesyndication|imasdk|/ads?/|[?&]ad(?:_|=)|preroll|thumb|sprite|\.vtt|\.srt|/beacon|/track""", RegexOption.IGNORE_CASE)

		/**
		 * The probe's proof outranks the address (T8.11): a response that WAS a playlist or media (by Content-Type or
		 * body), or what a media element actually played, is media whatever its address looks like.
		 */
		fun classify(url: String, how: String): MediaCandidate.Kind? = when (how) {
			"ct-hls", "body-hls" -> MediaCandidate.Kind.HLS
			"ct-dash", "body-dash" -> MediaCandidate.Kind.DASH
			"ct", "play" -> kindOf(url) ?: MediaCandidate.Kind.FILE
			else -> kindOf(url)
		}

		/** files and manifests by extension; extension-less player requests by their mime= query (e.g. googlevideo) */
		fun kindOf(url: String): MediaCandidate.Kind? {
			PageScraper.kindOf(url)?.let { return it }
			val q = url.substringAfter('?', "").lowercase(Locale.ROOT)
			return if (Regex("""(?:^|&)mime=(?:video|audio)%2f""").containsMatchIn(q) || Regex("""(?:^|&)mime=(?:video|audio)/""").containsMatchIn(q)) MediaCandidate.Kind.FILE else null
		}
	}
}

/** What W found. [sawBlob]: the page played from a blob: (MediaSource) URL, which cannot be fetched from outside. */
data class CatchResult(val candidates: List<MediaCandidate>, val sawBlob: Boolean, val finalPage: String)
