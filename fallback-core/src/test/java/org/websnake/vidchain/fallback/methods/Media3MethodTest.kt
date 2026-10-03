package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.Launcher
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.engine.ffmpeg.FfmpegEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.Check
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.fallback.verify.Expectation
import org.websnake.vidchain.media3.ContentStore
import org.websnake.vidchain.media3.Downloaded
import org.websnake.vidchain.media3.StreamDownloader
import java.io.File

/** M's export path for real: what "Media3 downloaded" (a file store keyed by the original URLs) goes through the proxy into ffmpeg. */
class Media3MethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var ffmpeg: File
	private lateinit var engine: FfmpegEngine
	private val store = HashMap<String, File>()
	private var closed = 0

	@Before fun setUp() {
		ffmpeg = listOf("/usr/bin/ffmpeg", "/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() } ?: File("")
		assumeTrue(ffmpeg.canExecute() && Launcher.detect().setsid != null)
		engine = FfmpegEngine(ProcessEngineRunner(Launcher.detect()), EngineLayout(tmp.newFolder("lib"), tmp.newFolder("nb"), tmp.root), ffmpeg)
		val src = listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-f", "lavfi", "-i", "sine", "-t", "3", "-c:v", "mpeg4", "-c:a", "aac")
		gen("hls", src + listOf("-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod"), "index.m3u8")
		val aes = tmp.newFolder("aes-src")
		File(aes, "key.bin").writeBytes(ByteArray(16) { it.toByte() })
		File(tmp.root, "ki.txt").writeText("https://keys.example/k.bin\n${File(aes, "key.bin").path}\n")
		gen("hls-aes", src + listOf("-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", "-hls_key_info_file", File(tmp.root, "ki.txt").path), "index.m3u8")
		store["https://keys.example/k.bin"] = File(aes, "key.bin")
		gen("dash", src + listOf("-f", "dash", "-seg_duration", "1"), "manifest.mpd")
	}

	private fun gen(name: String, args: List<String>, main: String) {
		val dir = File(tmp.root, name).apply { mkdirs() }
		val p = ProcessBuilder(listOf(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-y") + args + File(dir, main).path).redirectErrorStream(true).start()
		check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }
		dir.listFiles()!!.forEach { store["https://cdn.example/$name/${it.name}"] = it }
	}

	private val fake = StreamDownloader { url, _, _, dir, _ ->
		dir.mkdirs()
		Downloaded(ContentStore { u -> store[u]?.inputStream() }, listOf(url)) { closed++; dir.deleteRecursively() }
	}
	private fun ctx(url: String) = FallbackContext("10", url, url, UrlClass.HLS_DASH, destPath = File(tmp.root, "dl/v.mp4").path)
	private val method get() = Media3Method({ fake }, { engine }, { tmp.root })

	private fun good(r: MethodOutcome) {
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		assertEquals(Check.PASS, DeliveryVerifier().verify(File((r as MethodOutcome.Delivered).path), Expectation()).check)
	}

	@Test fun hls() = runBlocking { good(method.attempt(ctx("https://cdn.example/hls/index.m3u8"))); assertEquals(1, closed) }
	@Test fun aes128HlsWithAnAbsoluteKeyUrl() = runBlocking { good(method.attempt(ctx("https://cdn.example/hls-aes/index.m3u8"))) }
	@Test fun dash() = runBlocking { good(method.attempt(ctx("https://cdn.example/dash/manifest.mpd"))) }

	@Test fun downloaderErrorsAndNonStreams() = runBlocking {
		val failing = Media3Method({ StreamDownloader { _, _, _, _, _ -> throw java.io.IOException("403 Forbidden") } }, { engine }, { tmp.root })
		val r = failing.attempt(ctx("https://cdn.example/hls/index.m3u8"))
		assertTrue(r is MethodOutcome.Failed && r.reason.contains("403"))
		assertTrue(method.attempt(ctx("https://cdn.example/v.mp4").copy(urlClass = UrlClass.DIRECT_FILE)) is MethodOutcome.Unsupported)
		assertFalse(File(tmp.root, "media3-10").exists())
	}
}
