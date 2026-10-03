package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.io.File

/**
 * F end to end with a real ffmpeg on the build host: HLS, AES-128 HLS and DASH fixtures made by ffmpeg itself, served
 * over loopback HTTP, saved as MP4, checked by the DeliveryVerifier and ffprobe. Skipped where no ffmpeg exists.
 */
class FfmpegMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	private lateinit var ffmpeg: File
	private lateinit var engine: FfmpegEngine
	private lateinit var media: File

	@Before fun setUp() {
		ffmpeg = listOf("/usr/bin/ffmpeg", "/bin/ffmpeg", "/usr/local/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() } ?: File("")
		assumeTrue("ffmpeg on the build host", ffmpeg.canExecute() && Launcher.detect().setsid != null)
		media = tmp.newFolder("media")
		val src = listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "3", "-c:v", "mpeg4", "-c:a", "aac")
		run(src + listOf("-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", File(media, "plain/index.m3u8").also { it.parentFile.mkdirs() }.path))
		File(media, "aes").mkdirs()
		File(media, "aes/key.bin").writeBytes(ByteArray(16) { (it * 13 + 7).toByte() })
		File(tmp.root, "keyinfo.txt").writeText("${s.url("/aes/key.bin")}\n${File(media, "aes/key.bin").path}\n")
		run(src + listOf("-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", "-hls_key_info_file", File(tmp.root, "keyinfo.txt").path, File(media, "aes/index.m3u8").path))
		run(src + listOf("-f", "dash", "-seg_duration", "1", File(media, "dash/manifest.mpd").also { it.parentFile.mkdirs() }.path))
		media.walkTopDown().filter { it.isFile }.forEach { f ->
			val path = "/" + f.relativeTo(media).path
			s.route(path) { ex, _ -> ex.send(200, f.readBytes()) }
		}
		engine = FfmpegEngine(ProcessEngineRunner(Launcher.detect()), EngineLayout(tmp.newFolder("lib"), tmp.newFolder("nb"), tmp.root), ffmpeg)
	}

	@After fun stop() = s.close()

	private fun run(args: List<String>) {
		val p = ProcessBuilder(listOf(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-y") + args).redirectErrorStream(true).start()
		val out = p.inputStream.bufferedReader().readText()
		check(p.waitFor() == 0) { "fixture generation failed: $out" }
	}

	private fun streams(f: File): String {
		val probe = File(ffmpeg.parentFile, "ffprobe")
		// index,codec_type: ffprobe lists MPEG-TS streams a second time under their program
		val p = ProcessBuilder(probe.path, "-v", "error", "-show_entries", "stream=index,codec_type", "-of", "csv=p=0", f.path).start()
		return p.inputStream.bufferedReader().readText().lines().filter { it.isNotBlank() }.distinct().map { it.substringAfter(",") }.sorted().joinToString(",")
	}

	private fun ctx(path: String) = FallbackContext("8", s.url(path), s.url(path), UrlClass.HLS_DASH, destPath = File(tmp.root, "dl/v.mp4").path, userAgent = "UA", referer = "https://site.example/p")
	private val method get() = FfmpegMethod({ engine }, { engine.capabilities() })

	private fun assertGood(r: MethodOutcome, ext: String) {
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		val f = File((r as MethodOutcome.Delivered).path)
		assertTrue(f.name, f.name.endsWith(".$ext"))
		assertEquals(Check.PASS, DeliveryVerifier().verify(f, Expectation()).check)
		assertEquals("audio,video", streams(f))
	}

	private fun assertGoodMp4(r: MethodOutcome) {
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		val f = File((r as MethodOutcome.Delivered).path)
		assertEquals(Check.PASS, DeliveryVerifier().verify(f, Expectation()).check)
		assertEquals("audio,video", streams(f))
	}

	// MPEG-4 Part 2 in TS cannot be copied into MP4 (no global header): F keeps the streams in MPEG-TS instead
	@Test fun plainHls() = runBlocking { assertGood(method.attempt(ctx("/plain/index.m3u8")), "ts") }
	@Test fun aes128Hls() = runBlocking { assertGood(method.attempt(ctx("/aes/index.m3u8")), "ts") }

	@Test fun h264HlsBecomesMp4() = runBlocking {
		val enc = ProcessBuilder(ffmpeg.path, "-hide_banner", "-encoders").start().inputStream.bufferedReader().readText()
		assumeTrue("libx264 on the build host", "libx264" in enc)
		run(listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-f", "lavfi", "-i", "sine=frequency=440", "-t", "3", "-c:v", "libx264", "-preset", "ultrafast",
			"-c:a", "aac", "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", File(media, "h264/index.m3u8").also { it.parentFile.mkdirs() }.path))
		File(media, "h264").listFiles()!!.forEach { f -> s.route("/h264/${f.name}") { ex, _ -> ex.send(200, f.readBytes()) } }
		assertGood(method.attempt(ctx("/h264/index.m3u8")), "mp4")
	}
	@Test fun dash() = runBlocking { assertGoodMp4(method.attempt(ctx("/dash/manifest.mpd"))) }

	@Test fun capabilitiesAreRead() = runBlocking {
		val c = engine.capabilities()!!
		assertTrue(c.can(listOf("http", "hls", "crypto", "dash")))
	}

	@Test fun separateVideoAndAudioAreJoined() = runBlocking {
		run(listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-t", "2", "-c:v", "mpeg4", File(media, "v.mp4").path))
		run(listOf("-f", "lavfi", "-i", "sine=frequency=440", "-t", "2", "-c:a", "aac", File(media, "a.m4a").path))
		s.route("/v.mp4") { ex, _ -> ex.send(200, File(media, "v.mp4").readBytes()) }
		s.route("/a.m4a") { ex, _ -> ex.send(200, File(media, "a.m4a").readBytes()) }
		val out = File(tmp.root, "joined.mp4")
		val r = engine.save(listOf(FfmpegEngine.Input(s.url("/v.mp4")), FfmpegEngine.Input(s.url("/a.m4a"))), out)
		assertTrue(r.toString(), r is FfmpegEngine.Result.Ok)
		assertEquals("audio,video", streams(out))
	}

	@Test fun cancelLeavesNoFfmpegBehind() = runBlocking {
		s.route("/slow.m3u8") { ex, _ ->
			ex.send(200, ByteArray(0), length = -1)
			ex.out.write("#EXTM3U\n#EXT-X-TARGETDURATION:10\n".toByteArray()); ex.out.flush(); Thread.sleep(60_000)
		}
		val out = File(tmp.root, "slow-out.mp4")
		val job = launch(Dispatchers.Default) { engine.save(listOf(FfmpegEngine.Input(s.url("/slow.m3u8"))), out) }
		delay(1_500)
		job.cancelAndJoin()
		delay(500)
		val left = File("/proc").listFiles()!!.filter { it.name.all(Char::isDigit) }.any { runCatching { File(it, "cmdline").readText().contains(out.path) }.getOrDefault(false) }
		assertTrue("ffmpeg still running after cancel", !left)
	}

	@Test fun missingCapabilityIsUnsupported() = runBlocking {
		val noCrypto = FfmpegMethod({ engine }, { FfmpegEngine.Caps(setOf("http", "https", "file"), setOf("hls", "dash")) })
		assertTrue(noCrypto.attempt(ctx("/plain/index.m3u8")) is MethodOutcome.Unsupported)
	}
}
