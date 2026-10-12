package org.websnake.vidchain.web.headers

import org.junit.Assert.assertEquals
import org.junit.Test

class RequestHeadersTest {
	@Test fun keepsWhatAServerChecksAndDropsConnectionRangeCookieAndTheAppFingerprint() {
		val kept = RequestHeaders.keep(mapOf(
			"Referer" to "https://site.example/watch/1", "Origin" to "https://site.example", "X-Player-Token" to "t1",
			"Accept" to "*/*", "Range" to "bytes=0-", "Cookie" to "sid=S", "X-Requested-With" to "org.websnake.vidchain",
			"Accept-Encoding" to "gzip", "Host" to "cdn.example", "If-None-Match" to "\"e\"", "Bad" to "a\r\nInjected: 1"))
		assertEquals(listOf("Referer", "Origin", "X-Player-Token", "Accept"), kept.keys.toList())
	}

	@Test fun exactHeadersWinOverDefaultsWithoutCaseDuplicates() {
		val m = RequestHeaders.merge(mapOf("referer" to "https://site.example/watch/1"), mapOf("Referer" to "https://site.example/", "User-Agent" to "UA"))
		assertEquals(mapOf("referer" to "https://site.example/watch/1", "User-Agent" to "UA"), m)
	}
}
