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

	@Test fun theScriptOnlyReportsAndGuardsAgainstDoubleInjection() {
		val js = CatcherScript.JS
		assertTrue(js.contains("window.__vidchainCatcher") && js.contains("${CatcherScript.BRIDGE}.report"))
		for (hook in listOf("window.fetch", "XMLHttpRequest.prototype.open", "HTMLMediaElement.prototype", "URL.createObjectURL")) assertTrue(hook, js.contains(hook))
		assertTrue(js.contains("return of.apply(this, arguments)") && js.contains("return oo.apply(this, arguments)"))   // originals still run
	}
}
