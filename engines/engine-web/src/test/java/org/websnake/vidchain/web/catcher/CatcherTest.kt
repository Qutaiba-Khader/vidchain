package org.websnake.vidchain.web.catcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.web.scrape.MediaCandidate

class CatcherTest {
	@Test fun requestsAreSortedIntoMediaJunkAndBlob() {
		var now = 0L
		val c = CatchCollector("https://site.example/p", deadlineMs = 25_000, clock = { now })
		c.offer("https://site.example/app.js", "request")
		c.offer("https://imasdk.googleapis.com/ads/preroll.mp4", "request")
		c.offer("blob:https://site.example/1234", "media.src")
		c.offer("https://cdn.example/hls/master.m3u8?t=1", "request")
		c.offer("https://rr3.googlevideo.com/videoplayback?itag=18&mime=video%2Fmp4&x=1", "request")
		c.offer("https://cdn.example/hls/master.m3u8?t=1", "media.src")       // same URL, stronger evidence
		val r = c.result()
		assertTrue(c.sawBlob)
		assertEquals(listOf("https://cdn.example/hls/master.m3u8?t=1", "https://rr3.googlevideo.com/videoplayback?itag=18&mime=video%2Fmp4&x=1"), r.map { it.url })
		assertEquals("webview:media.src", r[0].source); assertEquals(MediaCandidate.Kind.HLS, r[0].kind); assertEquals(MediaCandidate.Kind.FILE, r[1].kind)
	}

	@Test fun stopsAfterGraceOrAtTheDeadline() {
		var now = 0L
		val c = CatchCollector("https://site.example/p", deadlineMs = 25_000, graceMs = 2_500, clock = { now })
		now = 10_000; assertFalse(c.done())
		c.offer("https://cdn.example/v.mp4", "dom")
		now = 12_000; assertFalse(c.done())
		now = 12_500; assertTrue(c.done())                                    // 2.5 s after the first find
		val empty = CatchCollector("https://site.example/p", deadlineMs = 25_000, clock = { now })
		now += 24_999; assertFalse(empty.done())
		now += 1; assertTrue(empty.done())                                    // the deadline holds
	}

	@Test fun theProbesProofOutranksTheAddress() {
		val c = CatchCollector("https://site.example/p", deadlineMs = 25_000, clock = { 0L })
		c.offer("https://api.example/stream?id=7", "ct-hls")                 // a response that WAS a playlist
		c.offer("https://api.example/manifest?id=7", "body-dash")            // a response that WAS an MPD
		c.offer("https://cdn.example/media/7", "play")                       // what the <video> actually played
		c.offer("https://cdn.example/page/7", "body")                        // a plain address in a response: not media
		c.offer("https://cdn.example/v/7.mp4?sig=1", "body")                 // a media address in an API response
		c.offer("blob:", "blob")
		val r = c.result().associateBy { it.url }
		assertEquals(MediaCandidate.Kind.HLS, r["https://api.example/stream?id=7"]?.kind)
		assertEquals(MediaCandidate.Kind.DASH, r["https://api.example/manifest?id=7"]?.kind)
		assertEquals(MediaCandidate.Kind.FILE, r["https://cdn.example/media/7"]?.kind)
		assertEquals(MediaCandidate.Kind.FILE, r["https://cdn.example/v/7.mp4?sig=1"]?.kind)
		assertFalse(r.containsKey("https://cdn.example/page/7"))
		assertTrue(c.sawBlob)
		assertTrue(r.getValue("https://api.example/stream?id=7").score > r.getValue("https://cdn.example/v/7.mp4?sig=1").score)
	}

	@Test fun streamPiecesAreNeverTheVideoAndWeakFindsDoNotStopTheCatch() {
		var now = 0L
		val c = CatchCollector("https://site.example/p", deadlineMs = 25_000, graceMs = 2_500, clock = { now })
		c.offer("https://cdn.example/hls/seg1.ts", "ct")
		c.offer("https://cdn.example/dash/chunk-1.m4s", "ct")
		c.offer("https://rr1.googlevideo.com/videoplayback?mime=video%2Fmp4&range=0-1000", "request")
		c.offer("https://video.fbcdn.example/v.mp4?bytestart=0&byteend=900", "net")
		assertTrue(c.result().isEmpty())
		c.offer("https://cdn.example/preview.mp4", "body")                    // named in page data: weak
		now = 5_000; assertFalse(c.done())                                    // a weak find does not start the 2.5 s stop timer
		c.offer("https://cdn.example/main.m3u8", "ct-hls")
		now = 7_500; assertTrue(c.done())
		assertEquals("https://cdn.example/main.m3u8", c.result().first().url)
	}

	@Test fun theProbeOnlyReportsKeepsOriginalsAndGuardsAgainstDoubleInjection() {
		val js = org.websnake.vidchain.web.probe.ProbeScript.build(autoplay = false)
		assertTrue(js.contains("window.__vcProbe") && js.contains("window.VidChainProbe"))
		for (hook in listOf("window.fetch", "XMLHttpRequest.prototype.open", "XMLHttpRequest.prototype.send", "JSON.parse", "HTMLMediaElement.prototype", "URL.createObjectURL", "MutationObserver", "performance.getEntriesByType"))
			assertTrue(hook, js.contains(hook))
		assertTrue(js.contains("var p = of.apply(this, arguments)") && js.contains("return oo.apply(this, arguments)") && js.contains("return os.apply(this, arguments)") && js.contains("var r = jp.apply(this, arguments)"))
		assertTrue(js.contains("var AUTOPLAY = false") && org.websnake.vidchain.web.probe.ProbeScript.build(true).contains("var AUTOPLAY = true"))
		assertFalse(js.contains("__AUTOPLAY__") || js.contains("\$NAME"))
	}
}
