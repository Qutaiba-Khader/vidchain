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
import org.websnake.vidchain.http.PlainGetFetcher
import org.websnake.vidchain.web.catcher.CatchResult
import org.websnake.vidchain.web.scrape.MediaCandidate
import java.io.File

class WebCatcherMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private fun box(t: String, n: Int) = byteArrayOf(0, 0, ((8 + n) shr 8).toByte(), (8 + n).toByte()) + t.toByteArray() + ByteArray(n)
	private val mp4 = box("ftyp", 8) + box("moov", 300) + box("mdat", 6000)
	private fun ctx() = FallbackContext("6", s.url("/page"), s.url("/page"), UrlClass.UNLISTED_PAGE, destPath = File(tmp.root, "d/v.mp4").path, userAgent = "UA")
	private fun method(result: CatchResult?) = WebCatcherMethod(PlainGetFetcher(OkHttpClient(), backoffMs = { 0 }), DeliveryVerifier(), { _, _ -> result })

	@Test fun whatThePlayerLoadedIsSaved() = runBlocking {
		s.route("/js-media.mp4") { ex, _ -> if (ex.header("Referer") == s.url("/page")) ex.send(200, mp4) else ex.send(403) }
		val caught = CatchResult(listOf(MediaCandidate(s.url("/js-media.mp4"), MediaCandidate.Kind.FILE, "webview:media.src", s.url("/page"), 90)), false, s.url("/page"))
		assertTrue(method(caught).attempt(ctx()) is MethodOutcome.Delivered)
	}

	@Test fun blobOnlyIsUnsupportedAndNothingIsAFailure() = runBlocking {
		assertTrue((method(CatchResult(emptyList(), true, s.url("/page"))).attempt(ctx()) as MethodOutcome.Unsupported).reason.contains("blob:"))
		assertEquals(MethodOutcome.Failed("the page loaded no media within the time limit"), method(CatchResult(emptyList(), false, s.url("/page"))).attempt(ctx()))
		assertTrue(method(null).attempt(ctx()) is MethodOutcome.Unsupported)
	}
}
