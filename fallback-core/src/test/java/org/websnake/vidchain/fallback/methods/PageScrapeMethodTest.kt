package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.hosts.FileHosts
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

class PageScrapeMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private fun box(t: String, n: Int) = byteArrayOf(0, 0, ((8 + n) shr 8).toByte(), (8 + n).toByte()) + t.toByteArray() + ByteArray(n)
	private val mp4 = box("ftyp", 8) + box("moov", 300) + box("mdat", 6000)
	private val client = OkHttpClient()
	private fun method(stream: (suspend (FallbackContext, org.websnake.vidchain.web.scrape.MediaCandidate) -> MethodOutcome)? = null) =
		PageScrapeMethod(PlainGetFetcher(client, backoffMs = { 0 }), DeliveryVerifier(), { ua -> FileHosts.fetcher(client, ua) }, stream)
	private fun ctx(path: String) = FallbackContext("5", s.url(path), s.url(path), UrlClass.UNLISTED_PAGE, destPath = File(tmp.root, "d/v.mp4").path, userAgent = "UA")

	@Test fun ogVideoBehindAnEmbedIsSavedWithThePlayerPageAsReferer() = runBlocking {
		s.route("/post") { ex, _ -> ex.send(200, """<html><iframe src="${s.url("/embed/1")}"></iframe></html>""".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		s.route("/embed/1") { ex, _ -> ex.send(200, """<html><head><meta property="og:video" content="/media/v.mp4"></head></html>""".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		s.route("/media/v.mp4") { ex, _ ->
			if (ex.header("Referer") == s.url("/embed/1")) ex.send(200, mp4, headers = mapOf("Content-Type" to "video/mp4")) else ex.send(403)
		}
		assertTrue(method().attempt(ctx("/post")) is MethodOutcome.Delivered)
	}

	@Test fun streamsGoToTheStreamDownloader() = runBlocking {
		s.route("/live") { ex, _ -> ex.send(200, """<script>var s="https:\/\/cdn.example\/hls\/master.m3u8";</script>""".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		var handed: FallbackContext? = null
		val r = method { c, _ -> handed = c; MethodOutcome.Delivered("/tmp/x.mp4") }.attempt(ctx("/live"))
		assertTrue(r is MethodOutcome.Delivered)
		assertEquals("https://cdn.example/hls/master.m3u8", handed!!.url)
		assertEquals(UrlClass.HLS_DASH, handed!!.urlClass)
		assertEquals(s.url("/live"), handed!!.referer)
	}

	@Test fun aFacebookLikePageIsAskedAsABrowserAndAsAPhone() = runBlocking {
		// T9 (owner's Facebook reel, 2026-10-14): 400 without a browser's navigation headers; no video data for a
		// desktop User-Agent; for a phone's, the player's file under its own key, a CDN address without an extension
		s.route("/reel") { ex, _ ->
			when {
				ex.header("Sec-Fetch-Mode") != "navigate" || ex.header("Accept")?.startsWith("text/html") != true -> ex.send(400, "<title>Error</title>".toByteArray())
				ex.header("User-Agent")?.contains("Mobile") != true -> ex.send(200, "<html>log in to continue</html>".toByteArray(), headers = mapOf("Content-Type" to "text/html"))
				else -> ex.send(200, """<script>{is_reels_video:true,playable_url:"${s.url("/o1/v/HD1").replace("/", "\\/")}?efg=1"}</script>""".toByteArray(), headers = mapOf("Content-Type" to "text/html"))
			}
		}
		s.route("/o1/v/HD1") { ex, _ -> ex.send(200, mp4, headers = mapOf("Content-Type" to "video/mp4")) }
		assertTrue(method().attempt(ctx("/reel")) is MethodOutcome.Delivered)
	}

	@Test fun nothingOnThePage() = runBlocking {
		s.route("/empty") { ex, _ -> ex.send(200, "<html><p>text only</p></html>".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		assertEquals(MethodOutcome.Failed("no media on the page"), method().attempt(ctx("/empty")))
	}
}
