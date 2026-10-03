package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.Launcher
import org.websnake.vidchain.engine.MemoryStore
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.nightly.NightlyToolStore
import java.io.File

class NightlyYtDlpMethodTest {
	@get:Rule val tmp = TemporaryFolder()

	@Test fun theInstalledNightlyRunsWithQuickJs() = runBlocking {
		val launcher = Launcher.detect()
		assumeTrue(launcher.setsid != null)
		val lib = tmp.newFolder("lib"); val nb = tmp.newFolder("nb")
		File(lib, "libpython.so").apply { writeBytes(File(launcher.sh).readBytes()); setExecutable(true) }
		File(lib, "libqjs.so").writeText("qjs")
		File(nb, "youtubedl-android/packages/python/usr/lib").mkdirs(); File(nb, "youtubedl-android/yt-dlp").mkdirs()
		File(nb, "youtubedl-android/yt-dlp/yt-dlp").writeText("echo bundled-must-not-run >&2; exit 9")
		val args = File(tmp.root, "args.txt")
		val d = '$'
		val kv = MemoryStore().apply { put(NightlyToolStore.KEY_CURRENT, "2026.10.02.1"); put(NightlyToolStore.KEY_CHECKED, System.currentTimeMillis().toString()) }
		File(tmp.root, "tools/2026.10.02.1").mkdirs()
		File(tmp.root, "tools/2026.10.02.1/yt-dlp").writeText("""
			printf '%s\n' "$d@" > ${args.path}
			out=""; take=0; for a in "$d@"; do [ "${d}take" = 1 ] && out="${d}a"; take=0; [ "${d}a" = "-o" ] && take=1; done
			f=${d}(printf '%s' "${d}out" | sed 's/%(ext)s/mp4/'); printf 'x' > "${d}f"; echo "__VIDCHAIN_FILE__${d}f"
		""".trimIndent())
		val layout = EngineLayout(lib, nb, tmp.root)
		val store = NightlyToolStore(File(tmp.root, "tools"), OkHttpClient(), kv, base = "http://127.0.0.1:9/unused")
		val m = NightlyYtDlpMethod({ store }, { f -> YtDlpEngine(ProcessEngineRunner(launcher), layout, ytdlp = f) }, { layout.quickJs }, { tmp.newFolder("c") })
		val r = m.attempt(FallbackContext("12", "https://site.example/v", "https://site.example/v", UrlClass.UNLISTED_PAGE, destPath = File(tmp.root, "dl/v.mp4").path))
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		assertTrue((r as MethodOutcome.Delivered).path.endsWith("/12-N.mp4"))
		val a = args.readLines()
		assertEquals("quickjs:${layout.quickJs.absolutePath}", a[a.indexOf("--js-runtimes") + 1])
	}

	@Test fun noNightlyIsUnsupported() = runBlocking {
		val store = NightlyToolStore(File(tmp.root, "t2"), OkHttpClient(), MemoryStore(), base = "http://127.0.0.1:9/unused")
		val m = NightlyYtDlpMethod({ store }, { null }, { null }, { null })
		assertTrue(m.attempt(FallbackContext("1", "https://x/v", "https://x/v", UrlClass.UNLISTED_PAGE)) is MethodOutcome.Unsupported)
	}
}
