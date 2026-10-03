package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.Launcher
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.engine.python.PythonEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import java.io.File
import java.util.zip.ZipFile

/** The real Python engines zip (built by Gradle) on the build host's python3: probe, then gallery-dl end to end. */
class GalleryDlMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private lateinit var zip: File
	private lateinit var engine: PythonEngine

	@Before fun setUp() {
		zip = File(System.getProperty("vidchain.pyzip") ?: "")
		val py = listOf("/usr/bin/python3", "/bin/python3").map(::File).firstOrNull { it.canExecute() }
		assumeTrue("python3 and the built zip", zip.isFile && py != null && Launcher.detect().setsid != null)
		engine = PythonEngine(ProcessEngineRunner(Launcher.detect()), EngineLayout(tmp.newFolder("lib"), tmp.newFolder("nb"), tmp.root), py!!, requireLibraryRuntime = false)
	}

	@Test fun theZipIsPureAndTheProbeImportsEverything() = runBlocking {
		ZipFile(zip).use { z -> assertTrue(z.entries().toList().none { it.name.endsWith(".so") || "__mypyc" in it.name }) }
		val r = engine.aio(listOf("probe"), zip)
		assertEquals(EngineOutcome.Success, r.outcome)
		val mods = JSONObject(r.lines.single()).getJSONObject("modules")
		for (m in listOf("gallery_dl", "requests", "urllib3", "idna", "certifi", "charset_normalizer")) assertTrue(m, !mods.getString(m).startsWith("missing"))
	}

	@Test fun galleryDlDownloadsADirectLink() = runBlocking {
		val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(3000) + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
		s.route("/media/photo.jpg") { ex, _ -> ex.send(200, jpeg, headers = mapOf("Content-Type" to "image/jpeg")) }
		val ctx = FallbackContext("31", s.url("/media/photo.jpg"), s.url("/media/photo.jpg"), UrlClass.DIRECT_FILE, destPath = File(tmp.root, "dl/x.jpg").path, expectMedia = false)
		val r = GalleryDlMethod({ engine }, { zip }).attempt(ctx)
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		assertTrue(File((r as MethodOutcome.Delivered).path).readBytes().contentEquals(jpeg))
	}

	@Test fun noExtractorIsUnsupported() = runBlocking {
		val ctx = FallbackContext("32", "https://nothing-gallery-dl-knows.example/p/1", "https://nothing-gallery-dl-knows.example/p/1", UrlClass.UNLISTED_PAGE, destPath = File(tmp.root, "dl/x").path)
		val r = GalleryDlMethod({ engine }, { zip }).attempt(ctx)
		assertTrue(r.toString(), r is MethodOutcome.Unsupported)
	}
}
