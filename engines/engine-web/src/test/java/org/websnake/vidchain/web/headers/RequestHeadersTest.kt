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

	@Test fun credentialsOddValuesAndOversizeAreDropped() {
		val kept = RequestHeaders.keep(linkedMapOf("Authorization" to "Bearer x", "X-Ok" to "a\tb", "X-Accent" to "tok\u00E9n",
			"X-Nul" to "a\u0000b", "x".repeat(65) to "long name", "Bad Name" to "v", "X-Big" to "y".repeat(1999), "X-Big2" to "y".repeat(1999),
			"X-Big3" to "y".repeat(1999), "X-Big4" to "y".repeat(1999), "X-Last" to "z"))
		assertEquals(listOf("X-Ok", "X-Big", "X-Big2", "X-Big3"), kept.keys.toList())          // the 4th big one passes 8 000 characters
	}

	@Test fun exactHeadersWinOverDefaultsWithoutCaseDuplicates() {
		val m = RequestHeaders.merge(mapOf("referer" to "https://site.example/watch/1"), mapOf("Referer" to "https://site.example/", "User-Agent" to "UA"))
		assertEquals(mapOf("referer" to "https://site.example/watch/1", "User-Agent" to "UA"), m)
	}
}
