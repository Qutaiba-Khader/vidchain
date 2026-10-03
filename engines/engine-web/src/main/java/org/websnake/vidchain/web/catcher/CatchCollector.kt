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
		val kind = kindOf(url) ?: return
		if (JUNK.containsMatchIn(url)) return
		val score = when (how) { "media.src", "dom" -> 90; "request" -> 80; "fetch", "xhr" -> 70; else -> 60 } + if (kind == MediaCandidate.Kind.FILE) 0 else 5
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

/** The hooks injected into the hidden page. They only report URLs; the page keeps working unchanged. */
object CatcherScript {
	const val BRIDGE = "VidChainCatcher"

	val JS = """
		(function(){
		  if (window.__vidchainCatcher) return; window.__vidchainCatcher = 1;
		  function rep(u, how){ try { if (u) $BRIDGE.report(String(u), how); } catch(e){} }
		  try { var of = window.fetch; if (of) window.fetch = function(i, o){ try { rep((i && i.url) || i, 'fetch'); } catch(e){} return of.apply(this, arguments); }; } catch(e){}
		  try { var oo = XMLHttpRequest.prototype.open; XMLHttpRequest.prototype.open = function(m, u){ rep(u, 'xhr'); return oo.apply(this, arguments); }; } catch(e){}
		  try { var d = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
		    if (d && d.set) Object.defineProperty(HTMLMediaElement.prototype, 'src', { set: function(v){ rep(v, 'media.src'); return d.set.call(this, v); }, get: d.get, configurable: true }); } catch(e){}
		  try { var oc = URL.createObjectURL; URL.createObjectURL = function(o){ var u = oc.apply(this, arguments); try { if (o && o.constructor && o.constructor.name === 'MediaSource') rep(u, 'mediasource'); } catch(e){} return u; }; } catch(e){}
		  function scan(){ try { document.querySelectorAll('video,audio,source').forEach(function(e){ rep(e.currentSrc, 'dom'); rep(e.src, 'dom'); }); } catch(e){} }
		  function play(){ try { document.querySelectorAll('video').forEach(function(v){ v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function(){}); }); } catch(e){} }
		  setInterval(scan, 1000); setTimeout(play, 1500); setTimeout(play, 5000);
		})();
	""".trimIndent()
}
