package org.websnake.vidchain.fallback.fixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9 decision 2: a page's cookies never follow a redirect to another host */
class NetscapeCookiesTest {
	private val f = NetscapeCookies.file("sid=abc; lang=en", "https://www.example.com/watch/1")

	@Test fun linesAreHostOnlyForThePagesHost() {
		val lines = f.lines().filter { it.isNotBlank() && !it.startsWith("#") }
		assertEquals(2, lines.size)
		assertTrue(lines.all { it.startsWith("www.example.com\tFALSE\t/\tTRUE\t") })
	}

	@Test fun aRedirectToAnotherHostDropsTheCookies() {
		assertTrue(NetscapeCookies.sentTo(f, "www.example.com"))
		assertFalse(NetscapeCookies.sentTo(f, "cdn.example-video.net"))   // a CDN the page redirects to
		assertFalse(NetscapeCookies.sentTo(f, "example.com"))              // not even the parent domain
		assertFalse(NetscapeCookies.sentTo(f, "evil.www.example.com"))     // nor a subdomain
	}
}
