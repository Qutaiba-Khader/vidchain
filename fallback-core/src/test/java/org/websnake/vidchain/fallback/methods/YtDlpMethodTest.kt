package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import org.websnake.vidchain.fallback.context.SessionContext
import org.websnake.vidchain.fallback.context.SessionCookie
import org.websnake.vidchain.fallback.context.ShareText
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.ytdlp.YtDlpEngine
import java.io.File

class YtDlpMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var engine: YtDlpEngine
	private lateinit var argsLog: File
	private lateinit var cookieSeen: File
	private lateinit var cookieDir: File

	@Before fun setUp() {
		val launcher = Launcher.detect()
		assumeTrue(launcher.setsid != null)
		val lib = tmp.newFolder("lib"); val nb = tmp.newFolder("nb"); cookieDir = tmp.newFolder("cookies")
		File(lib, "libpython.so").apply { writeBytes(File(launcher.sh).readBytes()); setExecutable(true) }
		File(nb, "youtubedl-android/packages/python/usr/lib").mkdirs(); File(nb, "youtubedl-android/yt-dlp").mkdirs()
		argsLog = File(tmp.root, "args.txt"); cookieSeen = File(tmp.root, "cookie-copy.txt")
		val d = '$'
		File(nb, "youtubedl-android/yt-dlp/yt-dlp").writeText("""
			printf '%s\n' "$d@" > ${argsLog.path}
			out=""; take=0; ck=""; tc=0; for a in "$d@"; do [ "${d}take" = 1 ] && out="${d}a"; take=0; [ "${d}a" = "-o" ] && take=1; [ "${d}tc" = 1 ] && ck="${d}a"; tc=0; [ "${d}a" = "--cookies" ] && tc=1; last2="${d}a"; done
			[ -n "${d}ck" ] && cp "${d}ck" ${cookieSeen.path}
			case "${d}last2" in *unsupported*) echo "ERROR: Unsupported URL" >&2; exit 1;; *members*) echo "ERROR: This video is members-only content" >&2; exit 1;; esac
			f=${d}(printf '%s' "${d}out" | sed 's/%(ext)s/webm/'); printf 'x' > "${d}f"; echo "__VIDCHAIN_FILE__${d}f"
		""".trimIndent())
		engine = YtDlpEngine(ProcessEngineRunner(launcher), EngineLayout(lib, nb, tmp.root))
	}

	private fun ctx(url: String) = FallbackContext("7", url, url, UrlClass.of(url), destPath = File(tmp.root, "dl/clip.mp4").path, preferredHeight = 480)
	private val method get() = YtDlpMethod({ engine }, { cookieDir })

	@Test fun downloadsThePageWithTheUsersResolution() = runBlocking {
		val r = method.attempt(ctx("https://site.example/v/1"))
		assertEquals(File(tmp.root, "dl/.vidchain-partial/7-Y.webm").path, (r as MethodOutcome.Delivered).path)
		val args = argsLog.readLines()
		assertTrue(args.contains("bv*[height<=480]+ba/b[height<=480]/bv*+ba/b"))
		assertFalse(args.contains("--cookies"))
	}

	@Test fun sessionCookiesGoThroughAPrivateFileThatIsDeletedAfterwards() = runBlocking {
		val s = SessionContext(listOf(SessionCookie("site.example", "sid", "42")), "https://site.example/v/1", "BrowserUA")
		assertTrue(method.run(ctx("https://site.example/v/1"), s) is MethodOutcome.Delivered)
		assertTrue(cookieSeen.readText().contains("site.example\tFALSE\t/\tFALSE\t2147483647\tsid\t42"))
		assertTrue(argsLog.readLines().containsAll(listOf("--user-agent", "BrowserUA", "--referer", "https://site.example/v/1")))
		assertEquals(0, cookieDir.listFiles()!!.size)
	}

	@Test fun failuresMapToTheChain() = runBlocking {
		assertTrue(method.attempt(ctx("https://site.example/unsupported")) is MethodOutcome.Unsupported)
		val r = method.attempt(ctx("https://site.example/members"))
		assertTrue(r is MethodOutcome.Failed && r.reason.startsWith("login:"))
		assertTrue(File(tmp.root, "dl/.vidchain-partial").listFiles()!!.none { it.name.startsWith("7-Y.") })
		assertTrue(YtDlpMethod({ null }, { cookieDir }).attempt(ctx("https://site.example/v")) is MethodOutcome.Unsupported)
	}

	@Test fun crashesAreBenched() = runBlocking {
		val recorded = ArrayList<String>()
		var benched = false
		val bench = object : YtDlpMethod.Bench {
			override fun benched(method: String) = benched
			override fun record(method: String, outcome: org.websnake.vidchain.engine.EngineOutcome?) { recorded += "$method:$outcome" }
		}
		val m = YtDlpMethod({ engine }, { cookieDir }, bench)
		assertTrue(m.attempt(ctx("https://site.example/v/1")) is MethodOutcome.Delivered)
		assertEquals(listOf("Y:success"), recorded)
		benched = true
		assertTrue(m.attempt(ctx("https://site.example/v/1")) is MethodOutcome.Unsupported)
	}

	@Test fun linksInSharedText() {
		assertEquals("https://site.example/v/1", ShareText.firstUrl("Look at this! https://site.example/v/1 #fun"))
		assertEquals("https://site.example/a?b=1", ShareText.firstUrl("(see https://site.example/a?b=1)."))
		assertNull(ShareText.firstUrl("https://site.example/v/1"))          // already a link: nothing to re-enter
		assertNull(ShareText.firstUrl("no link here"))
		assertNull(ShareText.firstUrl(null))
	}
}
