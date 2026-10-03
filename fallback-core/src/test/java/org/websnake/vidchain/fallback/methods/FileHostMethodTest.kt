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
import org.websnake.vidchain.hosts.HostResult
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

class FileHostMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private fun box(t: String, n: Int) = byteArrayOf(0, 0, ((8 + n) shr 8).toByte(), (8 + n).toByte()) + t.toByteArray() + ByteArray(n)
	private val mp4 = box("ftyp", 8) + box("moov", 300) + box("mdat", 6000)
	private val ctx get() = FallbackContext("4", "https://www.dropbox.com/s/x/v.mp4?dl=0", "https://www.dropbox.com/s/x/v.mp4?dl=0", UrlClass.DIRECT_FILE,
		destPath = File(tmp.root, "d/v.mp4").path, userAgent = "UA")
	private fun method(r: HostResult, allowLocal: Boolean = false) = FileHostMethod(PlainGetFetcher(OkHttpClient(), backoffMs = { 0 }), DeliveryVerifier(), { _, _ -> r }, allowLocal)

	@Test fun resolvedLinkIsFetched() = runBlocking {
		s.route("/file") { ex, _ -> ex.send(200, mp4, headers = mapOf("Content-Type" to "video/mp4")) }
		val r = method(HostResult.Direct(s.url("/file"), mapOf("Cookie" to "never", "X-Test" to "1")), allowLocal = true).attempt(ctx)   // loopback test server
		assertTrue(r is MethodOutcome.Delivered)
		assertEquals(null, s.requests.single().second["cookie"])            // resolver headers never smuggle a Cookie
		assertEquals("1", s.requests.single().second["x-test"])
		assertEquals("UA", s.requests.single().second["user-agent"])
	}

	@Test fun aPublicShareCannotPointIntoTheLan() = runBlocking {
		val r = method(HostResult.Direct("http://192.168.1.1/admin.cgi")).attempt(ctx)
		assertTrue(r is MethodOutcome.Failed && r.reason.contains("local network"))
	}

	@Test fun signInAndFailuresAndForeignLinks() = runBlocking {
		assertTrue((method(HostResult.NeedsLogin("Google Drive: not public")).attempt(ctx) as MethodOutcome.Failed).reason.startsWith("needs sign-in"))
		assertEquals(MethodOutcome.Failed("MediaFire: file removed"), method(HostResult.Failed("MediaFire: file removed")).attempt(ctx))
		assertTrue(method(HostResult.NotMine).attempt(ctx) is MethodOutcome.Unsupported)
	}
}
