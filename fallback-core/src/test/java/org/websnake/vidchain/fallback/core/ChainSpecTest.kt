package org.websnake.vidchain.fallback.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChainSpecTest {
	@Test fun urlClasses() {
		assertEquals(UrlClass.YOUTUBE, UrlClass.of("https://www.youtube.com/watch?v=abc"))
		assertEquals(UrlClass.YOUTUBE, UrlClass.of("https://youtu.be/abc"))
		assertEquals(UrlClass.LISTED_SITE, UrlClass.of("https://www.instagram.com/p/xyz/"))
		assertEquals(UrlClass.DIRECT_FILE, UrlClass.of("https://cdn.example.org/a/b/video.MP4?sig=1"))
		assertEquals(UrlClass.DIRECT_FILE, UrlClass.of("https://drive.google.com/file/d/1/view"))
		assertEquals(UrlClass.HLS_DASH, UrlClass.of("https://cdn.example.org/live/master.m3u8?token=x"))
		assertEquals(UrlClass.HLS_DASH, UrlClass.of("https://cdn.example.org/manifest.mpd"))
		assertEquals(UrlClass.MAGNET_TORRENT, UrlClass.of("magnet:?xt=urn:btih:abc"))
		assertEquals(UrlClass.MAGNET_TORRENT, UrlClass.of("https://example.org/file.torrent"))
		assertEquals(UrlClass.UNLISTED_PAGE, UrlClass.of("https://some-blog.example/post/42"))
		assertEquals(UrlClass.UNLISTED_PAGE, UrlClass.of("not a url"))
	}

	@Test fun everyClassHasAChainAndStubGoesFirst() {
		for (c in UrlClass.values()) {
			val steps = ChainSpec.stepsFor(c, includeStub = true)
			assertEquals(ChainSpec.STUB, steps.first())
			assertEquals(steps.size, steps.toSet().size)
		}
	}

	@Test fun nextMethodSkipsAttemptedAndUnavailable() {
		val steps = listOf("R", "L", "S", "O")
		assertEquals("R", ChainSpec.nextMethod(steps, emptyList()) { true })
		assertEquals("S", ChainSpec.nextMethod(steps, listOf("R")) { it != "L" })
		assertNull(ChainSpec.nextMethod(steps, listOf("R", "L", "S", "O")) { true })
		assertNull(ChainSpec.nextMethod(steps, emptyList()) { false })
	}
}
