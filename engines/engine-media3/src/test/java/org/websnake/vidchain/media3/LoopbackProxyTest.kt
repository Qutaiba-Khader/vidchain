package org.websnake.vidchain.media3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class LoopbackProxyTest {
	private val files = mapOf(
		"https://cdn.example/hls/index.m3u8" to "#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"https://keys.example/k.bin\"\n#EXTINF:1,\nseg0.ts\n#EXTINF:1,\nhttps://cdn2.example/seg1.ts\n",
		"https://cdn.example/hls/seg0.ts" to "TS0",
	)
	private val proxy = LoopbackProxy { u -> files[u]?.byteInputStream() }

	private fun get(u: String): Pair<Int, String> {
		val c = URL(u).openConnection() as HttpURLConnection
		val code = c.responseCode
		return code to (if (code == 200) c.inputStream.bufferedReader().readText() else "")
	}

	@Test fun relativeAndAbsoluteUrlsComeBackThroughTheProxy() {
		val manifest = get(proxy.proxied("https://cdn.example/hls/index.m3u8"))
		assertEquals(200, manifest.first)
		assertTrue(manifest.second.contains("URI=\"" + proxy.proxied("https://keys.example/k.bin") + "\""))
		assertTrue(manifest.second.contains(proxy.proxied("https://cdn2.example/seg1.ts")))
		assertTrue(manifest.second.contains("\nseg0.ts\n"))                                                    // relative: resolves under the proxy
		val seg = URL(URL(proxy.proxied("https://cdn.example/hls/index.m3u8")), "seg0.ts").toString()
		assertEquals(200 to "TS0", get(seg))
		proxy.close()
	}

	@Test fun wrongTokenAndUnknownFilesAre404() {
		val good = proxy.proxied("https://cdn.example/hls/seg0.ts")
		val bad = good.replaceFirst(Regex("""/[0-9a-f]{24}/"""), "/000000000000000000000000/")
		assertEquals(404, get(bad).first)
		assertEquals(404, get(proxy.proxied("https://cdn.example/missing.ts")).first)
		assertNull(proxy.original("/nope/https/x/y"))
		proxy.close()
	}

	@Test fun renditionChoice() {
		val o = listOf(RenditionChoice.Option("a", 360, 800), RenditionChoice.Option("b", 720, 2500), RenditionChoice.Option("c", 720, 3000), RenditionChoice.Option("d", 1080, 5000))
		assertEquals("c", RenditionChoice.pick(o, 720)!!.url)
		assertEquals("d", RenditionChoice.pick(o, null)!!.url)
		assertEquals("a", RenditionChoice.pick(o, 240)!!.url)                  // nothing fits: the smallest
		assertNull(RenditionChoice.pick(emptyList(), 720))
	}
}
