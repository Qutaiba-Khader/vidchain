package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.fallback.context.SessionContext
import org.websnake.vidchain.fallback.context.SessionCookie
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

class SessionMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()

	private fun box(t: String, n: Int) = byteArrayOf(0, 0, ((8 + n) shr 8).toByte(), (8 + n).toByte()) + t.toByteArray() + ByteArray(n)
	private val mp4 = box("ftyp", 8) + box("moov", 300) + box("mdat", 6000)
	private val fetcher = PlainGetFetcher(OkHttpClient(), backoffMs = { 0 })
	private val page get() = s.url("/watch/page?id=5")

	private fun ctx() = FallbackContext("1", page, s.url("/media/v.mp4"), UrlClass.DIRECT_FILE, destPath = File(tmp.root, "d/v.mp4").path)

	@Test fun cookieAndRefererGatedFilePassesOnlyWithTheSession() = runBlocking {
		s.route("/media/v.mp4") { ex, _ ->
			val ok = ex.header("Cookie") == "sess=ok" && ex.header("Referer") == page && ex.header("User-Agent") == "BrowserUA"
			if (ok) ex.send(200, mp4, length = -1, headers = mapOf("Content-Type" to "video/mp4")) else ex.send(403, "denied".toByteArray())
		}
		val session = SessionContext(listOf(SessionCookie("127.0.0.1", "sess", "ok"), SessionCookie("other.example", "leak", "x")), page, "BrowserUA")
		val withSession = SessionMethod(fetcher, DeliveryVerifier(), { _, _ -> session }).attempt(ctx())
		assertTrue(withSession.toString(), withSession is MethodOutcome.Delivered)
		assertFalse(s.requests.any { it.second["cookie"].orEmpty().contains("leak") })   // another host's cookie never leaves
		val plain = PlainGetMethod(fetcher, DeliveryVerifier()).attempt(ctx())
		assertEquals(MethodOutcome.Failed("HTTP 403"), plain)                                // the current method's view fails
	}

	@Test fun cookiesNeverFollowARedirectToAnotherHost() = runBlocking {
		s.route("/hop") { ex, _ -> ex.send(302, headers = mapOf("Location" to s.url("/media/v.mp4").replace("127.0.0.1", "localhost"))) }
		s.route("/media/v.mp4") { ex, _ -> ex.send(200, mp4, headers = mapOf("Content-Type" to "video/mp4")) }
		val session = SessionContext(listOf(SessionCookie("127.0.0.1", "sess", "ok")), page, "UA")
		val c = FallbackContext("1", page, s.url("/hop"), UrlClass.DIRECT_FILE, destPath = File(tmp.root, "r/v.mp4").path)
		assertTrue(SessionMethod(fetcher, DeliveryVerifier(), { _, _ -> session }).attempt(c) is MethodOutcome.Delivered)
		assertEquals("sess=ok", s.requests.first { it.first == "/hop" }.second["cookie"])
		assertNull(s.requests.first { it.first == "/media/v.mp4" }.second["cookie"])         // the other host got no cookie
	}

	@Test fun noSessionOrEmptySessionIsUnsupported() = runBlocking {
		assertTrue(SessionMethod(fetcher, DeliveryVerifier(), { _, _ -> null }).attempt(ctx()) is MethodOutcome.Unsupported)
		assertTrue(SessionMethod(fetcher, DeliveryVerifier(), { _, _ -> SessionContext(emptyList(), null, "UA") }).attempt(ctx()) is MethodOutcome.Unsupported)
	}

	@Test fun pagesWaitForTheExtractor() = runBlocking {
		val session = SessionContext(listOf(SessionCookie("youtube.com", "SID", "1")), "https://www.youtube.com/watch?v=x", "UA")
		val page = FallbackContext("1", "https://www.youtube.com/watch?v=x", "https://www.youtube.com/watch?v=x", UrlClass.YOUTUBE, destPath = File(tmp.root, "y.mp4").path)
		assertTrue(SessionMethod(fetcher, DeliveryVerifier(), { _, _ -> session }).attempt(page) is MethodOutcome.Unsupported)
		var handed: SessionContext? = null
		SessionMethod(fetcher, DeliveryVerifier(), { _, _ -> session }, withExtractor = { _, sc -> handed = sc; MethodOutcome.Failed("x") }).attempt(page)
		assertEquals(session, handed)
	}

	@Test fun scopingOriginAndNetscape() {
		val sc = SessionContext(listOf(
			SessionCookie("example.com", "a", "1"), SessionCookie("cdn.example.com", "b", "2"),
			SessionCookie("example.com", "sec", "3", secure = true), SessionCookie("example.com", "adm", "4", path = "/admin"),
		), "https://www.site.org/watch/9?x=1", "UA")
		assertEquals("a=1; sec=3", sc.cookieHeader("https://example.com/v.mp4"))
		assertEquals("a=1", sc.cookieHeader("http://example.com/v.mp4"))                  // secure cookie only over https
		assertEquals("b=2", sc.cookieHeader("https://cdn.example.com/v.mp4"))   // exact host only: the store cannot tell domain cookies apart
		assertNull(sc.cookieHeader("https://evil-example.com/v.mp4"))
		assertEquals("https://www.site.org", sc.originFor("https://example.com/v.mp4"))
		assertNull(SessionContext(emptyList(), "https://example.com/p", null).originFor("https://example.com/v.mp4"))
		val text = SessionContext.netscape(sc.cookies)
		assertTrue(text.lines().drop(1).filter { it.isNotEmpty() }.all { it.split('\t').size == 7 && it.split('\t')[0].isNotEmpty() })
		assertFalse(sc.toString().contains("=1")); assertFalse(sc.toString().contains("sec"))
		val f = sc.writeNetscape(File(tmp.root, "c/cookies.txt"))
		assertTrue(f.readText().contains("example.com\tFALSE\t/\tFALSE\t2147483647\ta\t1"))
	}
}
