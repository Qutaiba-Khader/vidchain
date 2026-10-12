package org.websnake.vidchain.app

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import app.core.AIOApp.Companion.aioAdblocker
import app.core.engines.video_parser.parsers.SupportedURLs.isM3U8Url
import app.ui.main.fragments.browser.webengine.VideoUrlInfo
import app.ui.main.fragments.browser.webengine.WebVideoParser
import app.ui.main.fragments.browser.webengine.WebViewEngine
import org.websnake.vidchain.web.catcher.CatchCollector
import org.websnake.vidchain.web.probe.WebProbe
import org.websnake.vidchain.web.scrape.MediaCandidate
import java.net.InetAddress
import java.net.URI
import java.util.WeakHashMap

/**
 * The in-app browser's video grabber, made stronger (new file, not upstream code; T8.11, owner checklist B).
 *
 * The upstream grabber only sees request addresses that end in a video extension. This adds the shared media probe
 * (document start, every frame incl. cross-origin player iframes): what the <video> actually plays, playlists proven
 * by Content-Type or body, common web players, og:video. Finds go into the same per-tab list the upstream grabber
 * fills, so the upstream "videos found" button and links dialog show them. The first find on a page re-runs the
 * upstream analysis once so the button turns to "found" without a reload.
 *
 * Every page can post to the probe object, so a find is untrusted input (T8.11 review): only strong evidence is
 * listed (no addresses merely named in data, no stream pieces), only addresses the upstream dialog can download as
 * what they are (HLS only when the address says m3u8; DASH left to the upstream path), never a local-network or
 * loopback address, and at most [PER_PAGE] per page; the rest is dropped before it reaches the main thread's list.
 */
object VidChainGrabber {
	private const val PER_PAGE = 60
	private val TAGS = setOf("play", "media.src", "dom", "player", "meta", "ct", "ct-hls", "body-hls", "net")

	private class PageState(var page: String?, var count: Int = 0, val seen: HashSet<String> = HashSet())

	private val states = WeakHashMap<WebView, PageState>()
	private val main = Handler(Looper.getMainLooper())

	/** Seam grabber (WebViewEngine.updateEngineOfWebView): once per WebView, and only where the probe fully works. */
	@JvmStatic
	fun attach(engine: WebViewEngine, webView: WebView) {
		if (states.containsKey(webView) || !WebProbe.fullySupported()) return
		states[webView] = PageState(null)
		runCatching { WebProbe.attach(webView, autoplay = false) { f -> accept(engine, webView, f) } }
	}

	/** runs on the UI thread (WebMessageListener); cheap checks first, the list is touched only for accepted finds */
	private fun accept(engine: WebViewEngine, view: WebView, f: WebProbe.Find) {
		if (f.how !in TAGS) return
		val state = states[view] ?: return
		val page = runCatching { view.url }.getOrNull()
		if (page != state.page) { state.page = page; state.count = 0; state.seen.clear() }
		if (state.count >= PER_PAGE || !state.seen.add(f.url)) return
		val kind = CatchCollector.classify(f.url, f.how) ?: return
		if (kind == MediaCandidate.Kind.DASH || (kind == MediaCandidate.Kind.HLS && !isM3U8Url(f.url))) return
		if (JUNK.containsMatchIn(f.url) || isLocal(f.url)) return
		if (aioAdblocker.getAdBlockHosts().any { f.url.contains(it, ignoreCase = true) }) return
		state.count++
		main.post { add(engine, view, f.url, kind) }
	}

	private fun add(engine: WebViewEngine, view: WebView, url: String, kind: MediaCandidate.Kind) = runCatching {
		val library = engine.listOfWebVideosLibrary.find { it.webViewId == view.id } ?: return@runCatching
		val wasEmpty: Boolean
		synchronized(engine.listOfWebVideosLibrary) {
			wasEmpty = library.listOfAvailableVideoUrlInfos.isEmpty()
			library.addVideoUrlInfo(VideoUrlInfo(fileUrl = url, fileResolution = "",
				isM3U8 = kind == MediaCandidate.Kind.HLS, totalResolutions = 0, fileDuration = 0L))
		}
		if (wasEmpty && engine.currentWebView === view) main.postDelayed({
			runCatching { if (engine.currentWebView === view) view.url?.let { WebVideoParser.analyzeUrl(it, engine) } }
		}, 800)
	}

	/** loopback, private, link-local and .local hosts: a page must not make the app fetch the user's own network */
	internal fun isLocal(url: String): Boolean = runCatching {
		val host = URI(url).host?.trim('[', ']')?.lowercase() ?: return true
		if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || '.' !in host && ':' !in host) return true
		if (!Regex("""^[0-9.]+$|:""").containsMatchIn(host)) return false        // a name: not resolved here
		val a = InetAddress.getByName(host)                                       // a literal address: no lookup
		a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress || a.isAnyLocalAddress ||
			(a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)         // IPv6 unique-local fc00::/7
	}.getOrDefault(true)

	/** thumbnails, previews, ads and tracking beacons are not the video */
	private val JUNK = Regex("""doubleclick|googlesyndication|imasdk|/ads?/|[?&]ad(?:_|=)|preroll|thumb|sprite|storyboard|\.vtt|\.srt|/beacon|/track""", RegexOption.IGNORE_CASE)
}
