package org.websnake.vidchain.http.redirect

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.http.TestHttpServer

class RedirectUnwrapperTest {
	private val s = TestHttpServer()
	private val r = RedirectUnwrapper(OkHttpClient())
	@After fun stop() = s.close()

	@Test fun followsAChainOfRedirectsHopByHop() = runBlocking {
		s.route("/a") { ex, _ -> ex.send(301, headers = mapOf("Location" to s.url("/b"))) }
		s.route("/b") { ex, _ -> ex.send(302, headers = mapOf("Location" to "/files/c.mp4")) }      // relative
		s.route("/files/c.mp4") { ex, _ -> ex.send(200, ByteArray(10), headers = mapOf("Content-Type" to "video/mp4")) }
		val res = r.unwrap(s.url("/a"))
		assertEquals(RedirectUnwrapper.Result.Unwrapped(s.url("/files/c.mp4"), listOf(s.url("/a"), s.url("/b"))), res)
	}

	@Test fun metaRefreshAndLocationScript() = runBlocking {
		s.route("/m") { ex, _ -> ex.send(200, """<html><head><META http-equiv="refresh" content="0;URL='${s.url("/js")}'"></head></html>""".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		s.route("/js") { ex, _ -> ex.send(200, """<script>window.location.replace("${s.url("/end").replace("/", "\\/")}")</script>""".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		s.route("/end") { ex, _ -> ex.send(200, "<html><body>a real page</body></html>".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		val res = r.unwrap(s.url("/m")) as RedirectUnwrapper.Result.Unwrapped
		assertEquals(s.url("/end"), res.url)
	}

	@Test fun loopsAreStopped() = runBlocking {
		s.route("/x") { ex, _ -> ex.send(302, headers = mapOf("Location" to s.url("/y"))) }
		s.route("/y") { ex, _ -> ex.send(302, headers = mapOf("Location" to s.url("/x"))) }
		val res = r.unwrap(s.url("/x"))
		assertTrue(res is RedirectUnwrapper.Result.Failed && res.reason.startsWith("redirect loop"))
		assertTrue(s.requests.size <= 3)
	}

	@Test fun cookieGateIsNotALoop() = runBlocking {
		s.route("/gate") { ex, _ ->
			if (ex.header("Cookie")?.contains("ok=1") == true) ex.send(302, headers = mapOf("Location" to s.url("/target")))
			else ex.send(302, headers = mapOf("Location" to s.url("/gate?c=1"), "Set-Cookie" to "ok=1; Path=/"))
		}
		s.route("/target") { ex, _ -> ex.send(200, ByteArray(5), headers = mapOf("Content-Type" to "video/mp4")) }
		assertEquals(s.url("/target"), (r.unwrap(s.url("/gate")) as RedirectUnwrapper.Result.Unwrapped).url)
	}

	@Test fun plainPageIsUnchangedAndErrorsFail() = runBlocking {
		s.route("/page") { ex, _ -> ex.send(200, "<html>hi</html>".toByteArray(), headers = mapOf("Content-Type" to "text/html")) }
		assertEquals(RedirectUnwrapper.Result.Unchanged(s.url("/page")), r.unwrap(s.url("/page")))
		assertEquals(RedirectUnwrapper.Result.Failed("HTTP 404", emptyList()), r.unwrap(s.url("/missing")))
	}

	@Test fun tooManyHopsFail() = runBlocking {
		s.route("/n") { ex, n -> ex.send(302, headers = mapOf("Location" to s.url("/n?i=$n"))) }
		val res = RedirectUnwrapper(OkHttpClient(), maxHops = 4).unwrap(s.url("/n"))
		assertTrue(res is RedirectUnwrapper.Result.Failed)
	}

	@Test fun publicLinksCannotBounceIntoTheLocalNetwork() = runBlocking {
		val res = r.unwrap("https://www.google.com/url?q=http%3A%2F%2F192.168.1.1%2Fadmin")
		assertTrue(res is RedirectUnwrapper.Result.Failed && res.reason.contains("local network"))
		assertTrue(RedirectUnwrapper.isLocal("http://10.0.0.5/x")); assertTrue(RedirectUnwrapper.isLocal("http://[::1]/"))
		assertTrue(RedirectUnwrapper.isLocal("http://printer.local/")); assertTrue(RedirectUnwrapper.isLocal("http://172.20.1.1/"))
		assertTrue(!RedirectUnwrapper.isLocal("https://172.32.0.1/")); assertTrue(!RedirectUnwrapper.isLocal("https://example.org/"))
	}

	@Test fun knownWrappersNeedNoRequest() {
		assertEquals("https://example.org/v.mp4", RedirectUnwrapper.unwrapKnown("https://www.google.com/url?sa=t&url=https%3A%2F%2Fexample.org%2Fv.mp4&ved=x"))
		assertEquals("https://vimeo.com/1", RedirectUnwrapper.unwrapKnown("https://l.facebook.com/l.php?u=https%3A%2F%2Fvimeo.com%2F1&h=AT0"))
		assertEquals("https://a.b/c", RedirectUnwrapper.unwrapKnown("https://www.youtube.com/redirect?event=x&q=https%3A%2F%2Fa.b%2Fc"))
		assertEquals("https://a.b/c", RedirectUnwrapper.unwrapKnown("https://href.li/?https://a.b/c"))
		assertNull(RedirectUnwrapper.unwrapKnown("https://www.google.com/search?q=https://a.b"))
		assertNull(RedirectUnwrapper.unwrapKnown("https://notgoogle.com/url?q=https://a.b"))
		assertNull(RedirectUnwrapper.unwrapKnown("https://www.google.com/url?q=javascript:alert(1)"))
	}

	@Test fun knownWrapperThenNetworkHop() = runBlocking {
		s.route("/short") { ex, _ -> ex.send(301, headers = mapOf("Location" to s.url("/final.mp4"))) }
		s.route("/final.mp4") { ex, _ -> ex.send(200, ByteArray(3), headers = mapOf("Content-Type" to "video/mp4")) }
		val wrapped = "https://www.google.com/url?q=" + java.net.URLEncoder.encode(s.url("/short"), "UTF-8")
		// the test server is on loopback; a real public wrapper pointing there would be refused (see the test above)
		val res = RedirectUnwrapper(OkHttpClient(), allowLocalTargets = true).unwrap(wrapped) as RedirectUnwrapper.Result.Unwrapped
		assertEquals(s.url("/final.mp4"), res.url)
		assertEquals(listOf(wrapped, s.url("/short")), res.hops)
	}

	@Test fun everyLocalAddressSpellingIsLocal() {
		for (u in listOf("http://127.1/", "http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://3232235777/",
				"http://10.1/", "http://[::]/", "http://[::ffff:127.0.0.1]/", "http://[fd00::1]/", "http://[fe80::1]/", "http://localhost./",
				"http://127.0.0.1./", "http://0.0.0.0/", "http://224.0.0.1/", "http://100.64.1.1/"))
			assertTrue(u, RedirectUnwrapper.isLocal(u))
		for (u in listOf("https://example.org/", "https://8.8.8.8/", "https://[2606:4700::1111]/", "https://123.com/", "https://1.1/"))
			assertTrue(u, !RedirectUnwrapper.isLocal(u))
	}
}
