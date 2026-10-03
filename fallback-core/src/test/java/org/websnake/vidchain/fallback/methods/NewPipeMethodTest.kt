package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

/** Recorded stream lists (shaped like NewPipe's StreamInfo) drive P; files come from a loopback server. */
class NewPipeMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private fun box(t: String, n: Int) = byteArrayOf(0, 0, ((8 + n) shr 8).toByte(), (8 + n).toByte()) + t.toByteArray() + ByteArray(n)
	private val mp4 = box("ftyp", 8) + box("moov", 300) + box("mdat", 6000)
	private fun st(path: String, h: Int?, ext: String, br: Int? = null, prog: Boolean = true) = StreamSource.Stream(s.url(path), h, br, ext, prog)
	private fun ctx(height: Int? = 720, audioOnly: Boolean = false) =
		FallbackContext("9", "https://www.youtube.com/watch?v=x", "https://rr.googlevideo.com/x", UrlClass.YOUTUBE, destPath = File(tmp.root, "d/v.mp4").path, preferredHeight = height, audioOnly = audioOnly)

	@Test fun planner() {
		val src = StreamSource("t", 60_000,
			progressive = listOf(st("/p360", 360, "mp4"), st("/p720", 720, "mp4")),
			videoOnly = listOf(st("/v1080", 1080, "webm"), st("/v720", 720, "mp4"), st("/v720w", 720, "webm", 5000), st("/v480", 480, "mp4")),
			audio = listOf(st("/aopus", null, "webm", 160), st("/am4a", null, "m4a", 128), st("/adash", null, "m4a", 256, prog = false)), hlsUrl = null)
		assertEquals(StreamPlan.File(src.progressive[1]), StreamPlan.pick(src, 720, false))                       // a 720p file exists: no join
		assertEquals(StreamPlan.Join(src.videoOnly[0], src.audio[1]), StreamPlan.pick(src, 1080, false))          // 1080 only as video-only: join with M4A
		assertEquals(StreamPlan.File(src.audio[1]), StreamPlan.pick(src, null, true))                             // audio only, MP4 audio first
		assertEquals(StreamPlan.Hls("https://h/m.m3u8"), StreamPlan.pick(StreamSource(null, null, emptyList(), emptyList(), emptyList(), "https://h/m.m3u8"), 720, false))
		assertEquals(StreamPlan.Nothing, StreamPlan.pick(StreamSource(null, null, emptyList(), emptyList(), emptyList(), null), 720, false))
		assertEquals(StreamPlan.File(src.progressive[0]), StreamPlan.pick(src.copy(videoOnly = emptyList()), 480, false))
	}

	@Test fun progressiveFileIsSaved() = runBlocking {
		s.route("/p720") { ex, _ -> ex.send(200, mp4, length = -1, headers = mapOf("Content-Type" to "video/mp4")) }
		val src = StreamSource("t", 3_000, listOf(st("/p720", 720, "mp4")), emptyList(), emptyList(), null)
		val r = NewPipeMethod(PlainGetFetcher(OkHttpClient(), backoffMs = { 0 }), DeliveryVerifier(), { src }, { null }).attempt(ctx())
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
	}

	@Test fun videoOnlyAndAudioAreJoinedByFfmpeg() = runBlocking {
		val ffmpeg = listOf("/usr/bin/ffmpeg", "/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() }
		assumeTrue(ffmpeg != null && Launcher.detect().setsid != null)
		val v = File(tmp.root, "v.mp4"); val a = File(tmp.root, "a.m4a")
		for (args in listOf(listOf("-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-t", "2", "-c:v", "mpeg4", v.path), listOf("-f", "lavfi", "-i", "sine", "-t", "2", "-c:a", "aac", a.path)))
			check(ProcessBuilder(listOf(ffmpeg!!.path, "-hide_banner", "-loglevel", "error", "-y") + args).start().waitFor() == 0)
		s.route("/v1080") { ex, _ -> ex.send(200, v.readBytes()) }
		s.route("/am4a") { ex, _ -> ex.send(200, a.readBytes()) }
		val src = StreamSource("t", 2_000, emptyList(), listOf(st("/v1080", 1080, "mp4")), listOf(st("/am4a", null, "m4a", 128)), null)
		val engine = FfmpegEngine(ProcessEngineRunner(Launcher.detect()), EngineLayout(tmp.newFolder("lib"), tmp.newFolder("nb"), tmp.root), ffmpeg!!)
		val r = NewPipeMethod(PlainGetFetcher(OkHttpClient()), DeliveryVerifier(), { src }, { engine }).attempt(ctx(height = 1080))
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		assertEquals(Check.PASS, DeliveryVerifier().verify(File((r as MethodOutcome.Delivered).path), Expectation()).check)
	}

	@Test fun notANewPipeServiceAndExtractorErrors() = runBlocking {
		val m = { f: suspend (String) -> StreamSource? -> NewPipeMethod(PlainGetFetcher(OkHttpClient()), DeliveryVerifier(), f, { null }) }
		assertTrue(m { null }.attempt(ctx()) is MethodOutcome.Unsupported)
		val r = m { throw IllegalStateException("Content unavailable") }.attempt(ctx())
		assertTrue(r is MethodOutcome.Failed && r.reason.contains("Content unavailable"))
		assertTrue(m { StreamSource(null, null, emptyList(), listOf(st("/v", 720, "mp4")), listOf(st("/a", null, "m4a")), null) }.attempt(ctx()) is MethodOutcome.Unsupported)   // join needs ffmpeg
	}
}
