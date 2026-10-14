package org.websnake.vidchain.web.scrape

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.hosts.Page

class PageScraperTest {
	private val base = "https://site.example/watch/9"

	@Test fun openGraphVideo() {
		val c = PageScraper.scan("""<html><head><meta property="og:video:secure_url" content="https://cdn.site.example/v/9.mp4?sig=1"></head></html>""", base)
		assertEquals(listOf("https://cdn.site.example/v/9.mp4?sig=1"), c.map { it.url })
		assertEquals("og:video", c[0].source); assertEquals(MediaCandidate.Kind.FILE, c[0].kind); assertEquals(base, c[0].page)
	}

	@Test fun videoSourcesBestQualityFirstAndRelativeUrlsResolved() {
		val c = PageScraper.scan("""<video poster="p.jpg"><source src="/m/360.mp4" label="360p"><source src="/m/720.mp4" label="720p"><track src="/sub.vtt"></video>""", base)
		assertEquals(listOf("https://site.example/m/720.mp4", "https://site.example/m/360.mp4"), c.map { it.url })
	}

	@Test fun jsonLdGraph() {
		val ld = """{"@context":"https://schema.org","@graph":[{"@type":"WebPage"},{"@type":"VideoObject","name":"x","contentUrl":"https://media.site.example/a.webm","embedUrl":"https://site.example/embed/1"}]}"""
		val c = PageScraper.scan("""<script type="application/ld+json">$ld</script>""", base)
		assertEquals(listOf("https://media.site.example/a.webm"), c.map { it.url })
	}

	@Test fun escapedStreamInScript() {
		val c = PageScraper.scan("""<script>var cfg = {"hls":"https:\/\/cdn.site.example\/live\/master.m3u8?t=5&x=1","poster":"https:\/\/cdn\/p.jpg"};</script>""", base)
		assertEquals(MediaCandidate.Kind.HLS, c.single().kind)
		assertEquals("https://cdn.site.example/live/master.m3u8?t=5&x=1", c.single().url)
	}

	@Test fun facebookStylePlayerFilesWithoutExtensionHdFirst() {
		// T9 (owner's Facebook reel, 2026-10-14): CDN addresses without a file extension, under the player's own keys
		// (an unquoted JS key, and a JSON-escaped one), and a typed og:video (the SD copy)
		val html = """<html><head><meta property="og:video" content="https://video.cdn.example/o1/v/t2/f2/m367/SD1?efg=a&amp;oh=1">
			<meta property="og:video:type" content="video/mp4"></head><body>
			<script>{player_origin:"video_home",is_reels_video:true,playable_url:"https://video.cdn.example/o1/v/t2/f2/m367/HD1?efg=b\u0025253D&oh=2",x:1}</script>
			<script>{"other":{\"playable_url_quality_hd\":\"https:\/\/video.cdn.example\/o1\/v\/NEXT?x=1\"}}</script></body></html>"""
		val c = PageScraper.scan(html, "https://social.example/reel/1/")
		assertEquals("https://video.cdn.example/o1/v/t2/f2/m367/HD1?efg=b%253D&oh=2", c[0].url)   // the video shown first
		assertEquals("https://video.cdn.example/o1/v/NEXT?x=1", c[1].url)          // a later one (a feed preloads more)
		assertEquals("https://video.cdn.example/o1/v/t2/f2/m367/SD1?efg=a&oh=1", c[2].url)
		assertTrue(c.all { it.kind == MediaCandidate.Kind.FILE })
	}

	@Test fun anUntypedOgVideoWithoutExtensionIsNotAFile() {
		// an og:video that is a player page (no type, no extension) stays out
		assertTrue(PageScraper.scan("""<meta property="og:video" content="https://player.example/embed/9">""", base).isEmpty())
		assertTrue(PageScraper.scan("""<meta property="og:video" content="https://player.example/embed/9"><meta property="og:video:type" content="text/html">""", base).isEmpty())
	}

	@Test fun junkIsIgnored() {
		val c = PageScraper.scan("""<video src="blob:https://site.example/123"></video><meta property="og:video" content="https://ads.doubleclick.net/x.mp4">
			<script>"https://cdn/thumb/preview.mp4"</script>""", base)
		assertTrue(c.isEmpty())
	}

	@Test fun embeddedPlayerIsFollowedButBounded() = runBlocking {
		val fetched = ArrayList<String>()
		val pages = mapOf(
			base to """<iframe src="https://player.example/e/1"></iframe><iframe src="$base"></iframe>""",
			"https://player.example/e/1" to """<iframe src="https://player.example/e/2"></iframe>""",
			"https://player.example/e/2" to """<iframe src="https://player.example/e/3"></iframe>""",
			"https://player.example/e/3" to """<video src="https://cdn.example/deep.mp4"></video>""",
		)
		val fetch: suspend (String) -> Page = { u -> fetched += u; Page(200, u, pages[u] ?: "", "text/html") }
		assertTrue(PageScraper.discover(base, fetch, maxDepth = 2).isEmpty())                     // e/3 is level 3: never fetched
		assertEquals(listOf(base, "https://player.example/e/1", "https://player.example/e/2"), fetched)  // the page itself is not fetched twice
		fetched.clear()
		val deep = PageScraper.discover(base, fetch, maxDepth = 3)
		assertEquals("https://cdn.example/deep.mp4", deep.single().url)
		assertEquals("https://player.example/e/3", deep.single().page)                               // Referer = the player page
		fetched.clear()
		assertTrue(PageScraper.discover(base, fetch, maxDepth = 9, maxPages = 2).isEmpty())
		assertEquals(2, fetched.size)
	}
}
