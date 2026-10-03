package org.websnake.vidchain.ytdlp

import kotlinx.coroutines.runBlocking
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
import org.websnake.vidchain.engine.SlotLimiter
import java.io.File

/** yt-dlp is played by a shell script on the real engine runner: argument order, JSON, file hand-off, error mapping. */
class YtDlpEngineTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var engine: YtDlpEngine
	private lateinit var argsLog: File

	private val json = """{"_type":"video","id":"abc","title":"A clip","webpage_url":"https://site.example/v/1","extractor_key":"Generic","duration":61.5,
		"formats":[{"format_id":"hls-360","ext":"mp4","width":640,"height":360,"vcodec":"avc1","acodec":"mp4a","tbr":800.5,"protocol":"m3u8_native","url":"https://cdn/x.m3u8","http_headers":{"Cookie":"secret"}},
		{"format_id":"a","ext":"m4a","vcodec":"none","acodec":"mp4a","filesize":1048576}]}""".replace("\n", "").replace("\t", "")

	@Before fun setUp() {
		val launcher = Launcher.detect()
		assumeTrue(launcher.setsid != null)
		val lib = tmp.newFolder("lib"); val nb = tmp.newFolder("nb"); val cache = tmp.newFolder("cache")
		File(lib, "libpython.so").apply { writeBytes(File(launcher.sh).readBytes()); setExecutable(true) }
		File(nb, "youtubedl-android/packages/python/usr/lib").mkdirs()
		argsLog = File(tmp.root, "args.txt")
		val jsonFile = File(tmp.root, "info.json").apply { writeText(json) }
		File(nb, "youtubedl-android/yt-dlp").mkdirs()
		File(nb, "youtubedl-android/yt-dlp/yt-dlp").writeText("""
			printf '%s\n' "${'$'}@" > ${argsLog.path}
			n=${'$'}#; i=0; prev=""; out=""; for a in "${'$'}@"; do last="${'$'}a"; i=${'$'}((i+1)); [ ${'$'}i -eq ${'$'}((n-1)) ] && prev="${'$'}a"; [ "${'$'}take" = 1 ] && out="${'$'}a"; take=0; [ "${'$'}a" = "-o" ] && take=1; done
			[ "${'$'}prev" = "--" ] || { echo "ERROR: URL not after --" >&2; exit 2; }
			case "${'$'}last" in *unsupported*) echo "ERROR: Unsupported URL: ${'$'}last" >&2; exit 1;; *private*) echo "ERROR: [generic] Private video. Sign in if you've been granted access" >&2; exit 1;; esac
			case " ${'$'}* " in *" --dump-single-json "*) cat ${jsonFile.path}; exit 0;; esac
			f=${'$'}(printf '%s' "${'$'}out" | sed 's/%(ext)s/mp4/'); echo "[download]  42.0% of 1.00MiB"; printf 'x' > "${'$'}f"; echo "__VIDCHAIN_FILE__${'$'}f"
		""".trimIndent())
		val layout = EngineLayout(lib, nb, cache)
		engine = YtDlpEngine(ProcessEngineRunner(launcher, SlotLimiter(2)), layout)
	}

	@Test fun infoParsesFormatsAndPassesTheUrlAfterDoubleDash() = runBlocking {
		val r = engine.info("https://site.example/v/1", YtDlpEngine.Options(userAgent = "UA", referer = "https://site.example/"))
		val info = (r as YtDlpEngine.Result.Ok).value
		assertEquals("A clip", info.title); assertEquals(2, info.formats.size)
		assertEquals("640x360", YtDlpFormats.resolution(info.formats[0])); assertEquals("audio only", YtDlpFormats.resolution(info.formats[1]))
		assertEquals("1.00MiB", YtDlpFormats.size(info.formats[1].fileSize)); assertEquals("800k", YtDlpFormats.tbr(info.formats[0]))
		val args = argsLog.readLines()
		assertEquals(listOf("--", "https://site.example/v/1"), args.takeLast(2))
		assertTrue(args.containsAll(listOf("--ignore-config", "--dump-single-json", "--no-playlist", "--user-agent", "UA", "--referer")))
		assertTrue(!info.toString().contains("secret"))                         // http_headers are never kept
	}

	@Test fun downloadReturnsTheFileAndReportsProgress() = runBlocking {
		val seen = ArrayList<Double>()
		val dir = File(tmp.root, "out")
		val r = engine.download("https://site.example/v/1", YtDlpFormats.selector(720, false), dir, "1-Y", onProgress = { seen += it })
		val f = (r as YtDlpEngine.Result.Ok).value
		assertEquals(File(dir, "1-Y.mp4"), f)
		assertEquals(listOf(42.0), seen)
		assertTrue(argsLog.readLines().containsAll(listOf("-f", "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b", "--ffmpeg-location")))
	}

	@Test fun errorsAreMapped() = runBlocking {
		assertEquals(YtDlpEngine.Problem.UNSUPPORTED, (engine.info("https://x/unsupported") as YtDlpEngine.Result.Failed).problem)
		assertEquals(YtDlpEngine.Problem.LOGIN, (engine.info("https://x/private") as YtDlpEngine.Result.Failed).problem)
	}

	@Test fun notReadyWithoutTheLibraryRuntime() = runBlocking {
		val bare = YtDlpEngine(ProcessEngineRunner(Launcher.detect()), EngineLayout(tmp.newFolder("l2"), tmp.newFolder("n2"), tmp.root))
		assertEquals(YtDlpEngine.Problem.NOT_READY, (bare.info("https://x") as YtDlpEngine.Result.Failed).problem)
	}

	@Test fun selectorsAndProgress() {
		assertEquals("ba/b", YtDlpFormats.selector(1080, audioOnly = true))
		assertEquals("bv*+ba/b", YtDlpFormats.selector(null, false))
		assertEquals(12.5, YtDlpEngine.progress("[download]  12.5% of ~ 3.00MiB at 1.00MiB/s ETA 00:02")!!, 0.0)
		assertEquals(null, YtDlpEngine.progress("[info] Downloading 1 format(s)"))
	}
}
