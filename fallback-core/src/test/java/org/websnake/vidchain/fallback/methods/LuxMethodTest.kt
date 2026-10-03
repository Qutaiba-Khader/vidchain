package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.Launcher
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.engine.lux.LuxEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import java.io.File

/** lux played by a script on the real engine runner (the real binary is checked by natives.yml's ELF gate). */
class LuxMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var engine: LuxEngine
	private lateinit var args: File
	private lateinit var envFile: File
	private lateinit var lib: File

	@Before fun setUp() {
		val launcher = Launcher.detect()
		assumeTrue(launcher.setsid != null)
		lib = tmp.newFolder("lib"); val nb = tmp.newFolder("nb")
		args = File(tmp.root, "args.txt"); envFile = File(tmp.root, "env.txt")
		File(lib, "libffmpeg.so").apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }
		val d = '$'
		File(lib, "liblux.so").apply {
			writeText("""
				#!/bin/sh
				printf '%s\n' "$d@" > ${args.path}
				{ echo "SSL=${d}{SSL_CERT_FILE:-unset}"; [ -x ./ffmpeg ] && echo "ffmpeg=ok"; } > ${envFile.path}
				dir=""; name=""; url=""; prev=""; for a in "$d@"; do [ "${d}prev" = "-o" ] && dir="${d}a"; [ "${d}prev" = "-O" ] && name="${d}a"; [ "${d}prev" = "--" ] && url="${d}a"; prev="${d}a"; done
				case "${d}url" in *fail*) echo "Downloading ${d}url error:"; echo "bilibili: video not found"; echo "main.main"; exit 1;; esac
				printf '\000\000\000\030ftypisom' > "${d}dir/${d}name.mp4"; head -c 4000 /dev/zero >> "${d}dir/${d}name.mp4"
				printf 'part' > "${d}dir/${d}name.mp4.download"
			""".trimIndent())
			setExecutable(true)
		}
		engine = LuxEngine(ProcessEngineRunner(launcher), EngineLayout(lib, nb, tmp.newFolder("cache")))
	}

	private fun ctx(url: String) = FallbackContext("31", url, url, UrlClass.UNLISTED_PAGE, destPath = File(tmp.root, "dl/v.mp4").path, userAgent = "UA", referer = "https://site.example/")

	@Test fun downloadsThroughLuxWithItsOwnExtractor() = runBlocking {
		val r = LuxMethod({ engine }).attempt(ctx("https://www.bilibili.com/video/BV1xx411c7mD")) as MethodOutcome.Delivered
		assertTrue(r.path.endsWith("31-X/31-X.mp4")); assertTrue(r.extras.isEmpty())
		val a = args.readLines()
		assertTrue(a.containsAll(listOf("-s", "-O", "31-X", "-u", "UA", "-r", "https://site.example/")))
		assertEquals(listOf("--", "https://www.bilibili.com/video/BV1xx411c7mD"), a.takeLast(2))
		assertTrue("-c" !in a)
		assertEquals(listOf("SSL=unset", "ffmpeg=ok"), envFile.readLines())
	}

	@Test fun luxErrorsAreReadFromStdout() = runBlocking {
		val r = LuxMethod({ engine }).attempt(ctx("https://www.bilibili.com/video/fail"))
		assertTrue(r.toString(), r is MethodOutcome.Failed && r.reason == "lux (bilibili): bilibili: video not found")
		assertTrue(!File(tmp.root, "dl/31-X").exists())
	}

	@Test fun onlySitesLuxHasAnExtractorFor() = runBlocking {
		assertEquals("bilibili", LuxEngine.extractorFor("https://m.bilibili.com/video/x"))
		assertEquals("haokan", LuxEngine.extractorFor("https://haokan.baidu.com/v?vid=1"))
		assertEquals("youtube", LuxEngine.extractorFor("https://www.youtube.com/watch?v=x"))
		assertEquals("douyin", LuxEngine.extractorFor("https://v.douyin.com/abc/"))
		assertNull(LuxEngine.extractorFor("https://example.org/page"))
		assertNull(LuxEngine.extractorFor("not a url"))
		assertTrue(LuxMethod({ engine }).attempt(ctx("https://example.org/page")) is MethodOutcome.Unsupported)
		assertTrue(LuxMethod({ null }).attempt(ctx("https://vimeo.com/1")) is MethodOutcome.Unsupported)
		File(lib, "liblux.so").delete()
		assertTrue(LuxMethod({ engine }).attempt(ctx("https://vimeo.com/1")) is MethodOutcome.Unsupported)
	}

	@Test fun audioOnlyAsksLuxForAudio() = runBlocking {
		LuxMethod({ engine }).attempt(ctx("https://vimeo.com/1").copy(audioOnly = true))
		assertTrue("-ao" in args.readLines())
	}
}
