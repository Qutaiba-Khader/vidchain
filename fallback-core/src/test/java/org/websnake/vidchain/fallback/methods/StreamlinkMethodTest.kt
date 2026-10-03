package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import org.websnake.vidchain.engine.python.PythonEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.Check
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.fallback.verify.Expectation
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

/** Streamlink from the real engines zip (zipimport, stand-ins) resolves an HLS stream; the library-style ffmpeg saves it. */
class StreamlinkMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private lateinit var py: PythonEngine
	private lateinit var ff: FfmpegEngine
	private lateinit var zip: File
	private lateinit var cryptodome: File

	@Before fun setUp() {
		zip = File(System.getProperty("vidchain.pyzip") ?: "")
		val python = listOf("/usr/bin/python3", "/bin/python3").map(::File).firstOrNull { it.canExecute() }
		val ffmpeg = listOf("/usr/bin/ffmpeg", "/bin/ffmpeg").map(::File).firstOrNull { it.canExecute() }
		assumeTrue(zip.isFile && python != null && ffmpeg != null && Launcher.detect().setsid != null)
		val layout = EngineLayout(tmp.newFolder("lib"), tmp.newFolder("nb"), tmp.root)
		py = PythonEngine(ProcessEngineRunner(Launcher.detect()), layout, python!!, requireLibraryRuntime = false)
		ff = FfmpegEngine(ProcessEngineRunner(Launcher.detect()), layout, ffmpeg!!)
		val dir = tmp.newFolder("hls")
		val p = ProcessBuilder(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=160x120:rate=10", "-f", "lavfi", "-i", "sine",
			"-t", "3", "-c:v", "mpeg4", "-c:a", "aac", "-f", "hls", "-hls_time", "1", "-hls_playlist_type", "vod", File(dir, "index.m3u8").path).start()
		check(p.waitFor() == 0)
		dir.listFiles()!!.forEach { f -> s.route("/live/${f.name}") { ex, _ -> ex.send(200, f.readBytes()) } }
		// import-only placeholder for Cryptodome, which the phone's Python bundles natively (unencrypted HLS never calls it)
		cryptodome = tmp.newFolder("cryptodome")
		val any = "class _Any:\n    block_size = 16\n    def __getattr__(self, n): return _Any()\n    def __call__(self, *a, **k): return _Any()\n"
		mapOf("" to "", "Cipher" to any + "AES = PKCS1_v1_5 = _Any()\n", "Hash" to any + "MD5 = SHA256 = _Any()\n", "PublicKey" to any + "RSA = _Any()\n", "Util" to "")
			.forEach { (sub, body) -> File(cryptodome, "Cryptodome/$sub").apply { mkdirs() }.resolve("__init__.py").writeText(body) }
		File(cryptodome, "Cryptodome/Util/Padding.py").writeText("def pad(*a, **k): pass\ndef unpad(*a, **k): pass\n")
	}

	private fun method() = StreamlinkMethod({ py }, { zip }, { ff }, PlainGetFetcher(OkHttpClient()), DeliveryVerifier(),
		extraEnv = mapOf("PYTHONPATH" to "${zip.path}:${cryptodome.path}"))
	private fun ctx(url: String) = FallbackContext("51", url, url, UrlClass.HLS_DASH, destPath = File(tmp.root, "dl/v.mp4").path)

	@Test fun hlsPluginResolvesAndFfmpegSaves() = runBlocking {
		val r = method().attempt(ctx(s.url("/live/index.m3u8")))
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		assertEquals(Check.PASS, DeliveryVerifier().verify(File((r as MethodOutcome.Delivered).path), Expectation()).check)
	}

	@Test fun noPluginIsUnsupported() = runBlocking {
		val r = method().attempt(ctx("https://no-plugin-for-this.example/watch/1"))
		assertTrue(r.toString(), r is MethodOutcome.Unsupported)
	}
}
