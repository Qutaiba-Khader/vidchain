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
import java.util.Collections
import java.util.WeakHashMap

/**
 * The in-app browser's video grabber, made stronger (new file, not upstream code; T8.11, owner checklist B).
 *
 * The upstream grabber only sees request addresses that end in a video extension. This adds the shared media probe
 * (document start, every frame incl. cross-origin player iframes): Content-Type and body of API responses, what the
 * <video> actually plays, common web players, Resource Timing. Finds go into the same per-tab list the upstream
 * grabber fills, so the upstream "videos found" button and links dialog show them; DASH manifests are left to the
 * upstream path (its downloader takes files and HLS). The first find on a page re-runs the upstream analysis once so
 * the button turns to "found" without a reload.
 */
object VidChainGrabber {
	private val attached: MutableSet<WebView> = Collections.newSetFromMap(WeakHashMap())
	private val main = Handler(Looper.getMainLooper())

	/** Seam grabber (WebViewEngine.updateEngineOfWebView): once per WebView. */
	@JvmStatic
	fun attach(engine: WebViewEngine, webView: WebView) {
		if (!attached.add(webView)) return
		runCatching { WebProbe.attach(webView, autoplay = false) { f -> main.post { found(engine, webView, f) } } }
	}

	private fun found(engine: WebViewEngine, view: WebView, f: WebProbe.Find) = runCatching {
		val kind = CatchCollector.classify(f.url, f.how) ?: return@runCatching
		if (kind == MediaCandidate.Kind.DASH || JUNK.containsMatchIn(f.url)) return@runCatching
		if (aioAdblocker.getAdBlockHosts().any { f.url.contains(it, ignoreCase = true) }) return@runCatching
		val library = engine.listOfWebVideosLibrary.find { it.webViewId == view.id } ?: return@runCatching
		val wasEmpty: Boolean
		synchronized(engine.listOfWebVideosLibrary) {
			wasEmpty = library.listOfAvailableVideoUrlInfos.isEmpty()
			library.addVideoUrlInfo(VideoUrlInfo(fileUrl = f.url, fileResolution = "",
				isM3U8 = kind == MediaCandidate.Kind.HLS || isM3U8Url(f.url), totalResolutions = 0, fileDuration = 0L))
		}
		if (wasEmpty && engine.currentWebView === view) main.postDelayed({
			runCatching { if (engine.currentWebView === view) view.url?.let { WebVideoParser.analyzeUrl(it, engine) } }
		}, 800)
	}

	/** thumbnails, previews, ads and tracking beacons are not the video */
	private val JUNK = Regex("""doubleclick|googlesyndication|imasdk|/ads?/|[?&]ad(?:_|=)|preroll|thumb|sprite|storyboard|\.vtt|\.srt|/beacon|/track""", RegexOption.IGNORE_CASE)
}
