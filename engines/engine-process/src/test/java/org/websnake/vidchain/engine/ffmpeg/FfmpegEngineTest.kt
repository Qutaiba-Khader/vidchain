package org.websnake.vidchain.engine.ffmpeg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.engine.Launcher
import java.io.File

class FfmpegEngineTest {
	private val engine = FfmpegEngine(ProcessEngineRunner(Launcher("/bin/sh".takeIf { false }, "/bin/sh")), EngineLayout(File("/n/lib"), File("/n/nb"), File("/n/c")))

	@Test fun argvForOneStreamWithHeaders() {
		val a = engine.argv(listOf(FfmpegEngine.Input("https://cdn/x.m3u8", "UA", "https://site/p", mapOf("Origin" to "https://site", "Cookie" to "never"))), File("/o/v.mp4"))
		assertEquals("/n/lib/libffmpeg.so", a.first())
		assertTrue(a.containsAll(listOf("-user_agent", "UA", "-referer", "https://site/p", "-headers", "Origin: https://site\r\n")))
		assertFalse(a.any { "Cookie" in it })
		assertEquals(listOf("-i", "https://cdn/x.m3u8"), a.subList(a.indexOf("-i"), a.indexOf("-i") + 2))
		assertTrue(a.containsAll(listOf("-map", "0:v:0?", "0:a:0?", "-c", "copy")))
		assertEquals(listOf("-f", "mp4", "/o/v.mp4"), a.takeLast(3))
	}

	@Test fun argvJoinsVideoAndAudio() {
		val a = engine.argv(listOf(FfmpegEngine.Input("https://v"), FfmpegEngine.Input("https://a")), File("/o/j.mp4"))
		assertEquals(2, a.count { it == "-i" })
		assertTrue(a.containsAll(listOf("0:v:0", "1:a:0")))
	}

	@Test fun parsing() {
		assertEquals(setOf("file", "http", "https", "crypto", "hls"), FfmpegEngine.parseProtocols(listOf("Supported file protocols:", "Input:", "  file", "  http", "  https", "  crypto", "  hls", "Output:")) - setOf("Input", "Output"))
		assertEquals(setOf("hls", "dash", "mov", "mp4", "m4a"), FfmpegEngine.parseDemuxers(listOf("File formats:", " D. = Demuxing supported", " --", " D  hls             Apple HTTP Live Streaming", " D  dash            Dynamic Adaptive Streaming over HTTP", " D  mov,mp4,m4a      QuickTime / MOV")))
		assertEquals(1500L, FfmpegEngine.progressMs("out_time_us=1500000"))
		assertEquals(listOf("https", "hls", "crypto"), FfmpegEngine.needs("https://c/x.m3u8?t=1"))
		assertEquals(listOf("http", "dash"), FfmpegEngine.needs("http://c/m.mpd"))
	}
}
