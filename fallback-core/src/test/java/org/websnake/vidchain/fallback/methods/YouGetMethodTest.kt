package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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
import org.websnake.vidchain.engine.python.PythonEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import java.io.File

/** The dukpy shim against answers recorded with the real dukpy (Duktape), then you-get end to end. */
class YouGetMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private lateinit var zip: File
	private lateinit var py: File
	private lateinit var qjs: File

	@Before fun setUp() {
		zip = File(System.getProperty("vidchain.pyzip") ?: "")
		py = listOf("/usr/bin/python3", "/bin/python3").map(::File).firstOrNull { it.canExecute() } ?: File("")
		qjs = listOf("/usr/bin/qjs", "/bin/qjs").map(::File).firstOrNull { it.canExecute() } ?: File("")
		assumeTrue("python3, qjs and the built zip", zip.isFile && py.canExecute() && qjs.canExecute() && Launcher.detect().setsid != null)
	}

	@Test fun dukpyShimMatchesRealDukpyOnThePlayerGoldenSet() {
		val golden = File(System.getProperty("user.dir"), "../python/dukpy/golden.json").let { if (it.isFile) it else File("python/dukpy/golden.json") }
		val script = "import json,sys,dukpy\nfor g in json.load(open(sys.argv[1])):\n  r=dukpy.evaljs(g['code'], **g['kwargs'])\n  print(json.dumps([g['name'], r==g['result'], r], ensure_ascii=False))\n"
		val p = ProcessBuilder(py.path, "-c", script, golden.absolutePath).also { it.environment()["PYTHONPATH"] = zip.path; it.environment()["VIDCHAIN_QJS"] = qjs.path }.redirectErrorStream(true).start()
		val lines = p.inputStream.bufferedReader().readLines()
		assertEquals(lines.joinToString("\n"), 0, p.waitFor())
		assertEquals(5, lines.size)
		for (l in lines) { val a = JSONArray(l); assertTrue("${a.get(0)} differs from dukpy: ${a.get(2)}", a.getBoolean(1)) }
	}

	@Test fun youGetDownloadsADirectFile() = runBlocking {
		val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(5000)
		s.route("/media/clip.mkv") { ex, _ -> ex.send(200, mkv, headers = mapOf("Content-Type" to "video/x-matroska")) }
		val engine = PythonEngine(ProcessEngineRunner(Launcher.detect()), EngineLayout(tmp.newFolder("lib"), tmp.newFolder("nb"), tmp.root), py, requireLibraryRuntime = false)
		val ctx = FallbackContext("41", s.url("/media/clip.mkv"), s.url("/media/clip.mkv"), UrlClass.DIRECT_FILE, destPath = File(tmp.root, "dl/x.mkv").path)
		val r = YouGetMethod({ engine }, { zip }, extraEnv = mapOf("VIDCHAIN_QJS" to qjs.path)).attempt(ctx)
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		assertTrue(File((r as MethodOutcome.Delivered).path).readBytes().contentEquals(mkv))
	}
}
