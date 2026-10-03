package org.websnake.vidchain.fallback.methods

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
import org.websnake.vidchain.engine.MemoryStore
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.youtube.YouTubeClients
import java.io.File

/** Recorded client behaviour played by a fake yt-dlp: bot check, 403 on download, PO token demand, success. */
class YouTubeClientMethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var engine: YtDlpEngine
	private lateinit var calls: File
	private lateinit var behaviour: File

	@Before fun setUp() {
		val launcher = Launcher.detect()
		assumeTrue(launcher.setsid != null)
		val lib = tmp.newFolder("lib"); val nb = tmp.newFolder("nb")
		File(lib, "libpython.so").apply { writeBytes(File(launcher.sh).readBytes()); setExecutable(true) }
		File(nb, "youtubedl-android/packages/python/usr/lib").mkdirs(); File(nb, "youtubedl-android/yt-dlp").mkdirs()
		calls = File(tmp.root, "calls.txt"); behaviour = File(tmp.root, "behaviour.txt")
		val d = '$'
		// behaviour.txt lines: <client> <info|download> <ok|bot|403|po|private>
		File(nb, "youtubedl-android/yt-dlp/yt-dlp").writeText("""
			client=""; mode=download; out=""; take=0; for a in "$d@"; do [ "${d}take" = 1 ] && out="${d}a"; take=0; [ "${d}a" = "-o" ] && take=1
			  case "${d}a" in youtube:player_client=*) client="${d}{a#youtube:player_client=}";; --dump-single-json) mode=info;; esac; done
			echo "${d}client ${d}mode" >> ${calls.path}
			r=${d}(grep "^${d}client ${d}mode " ${behaviour.path} | awk '{print ${d}3}'); [ -z "${d}r" ] && r=ok
			case "${d}r" in
			  bot) echo "ERROR: [youtube] x: Sign in to confirm you’re not a bot" >&2; exit 1;;
			  403) echo "ERROR: unable to download video data: HTTP Error 403: Forbidden" >&2; exit 1;;
			  po) echo "ERROR: [youtube] x: This client requires a GVS PO Token" >&2; exit 1;;
			  private) echo "ERROR: [youtube] x: Private video. Sign in if you've been granted access" >&2; exit 1;;
			esac
			if [ "${d}mode" = info ]; then echo '{"id":"x","title":"t","formats":[{"format_id":"18","ext":"mp4","height":360,"vcodec":"avc1","acodec":"mp4a"}]}'; exit 0; fi
			f=${d}(printf '%s' "${d}out" | sed 's/%(ext)s/mp4/'); printf 'x' > "${d}f"; echo "__VIDCHAIN_FILE__${d}f"
		""".trimIndent())
		engine = YtDlpEngine(ProcessEngineRunner(launcher), EngineLayout(lib, nb, tmp.root))
	}

	private val store = MemoryStore()
	private val ctx get() = FallbackContext("3", "https://www.youtube.com/watch?v=x", "https://rr1.googlevideo.com/x", UrlClass.YOUTUBE, destPath = File(tmp.root, "dl/v.mp4").path)
	private fun method(hash: String = "b1") = YouTubeClientMethod({ engine }, { YouTubeClients(store, hash) })

	@Test fun rotatesPastBotCheckAnd403AndRemembersTheWinner() = runBlocking {
		behaviour.writeText("android_vr info bot\nweb_safari download 403\n")
		val r = method().attempt(ctx)
		assertTrue(r.toString(), r is MethodOutcome.Delivered)
		assertEquals(listOf("android_vr info", "web_safari info", "web_safari download", "web_embedded info", "web_embedded download"), calls.readLines())
		assertEquals("web_embedded", YouTubeClients(store, "b1").plan().first())
		calls.delete()
		assertTrue(method().attempt(ctx) is MethodOutcome.Delivered)
		assertEquals(listOf("web_embedded info", "web_embedded download"), calls.readLines())   // the winner goes first
	}

	@Test fun poTokenClientsAreSkippedUntilYtDlpChanges() = runBlocking {
		behaviour.writeText("android_vr info po\nweb_safari info bot\nweb_embedded info bot\ntv info bot\n")
		val r = method().attempt(ctx)
		assertTrue(r is MethodOutcome.Failed)
		assertEquals(listOf("web_safari", "web_embedded", "tv"), YouTubeClients(store, "b1").plan())
		assertEquals("android_vr", YouTubeClients(store, "b2").plan().first())                       // a new yt-dlp build starts clean
	}

	@Test fun aPrivateVideoStopsTheRotation() = runBlocking {
		behaviour.writeText("android_vr info private\n")
		val r = method().attempt(ctx)
		assertTrue(r is MethodOutcome.Failed && r.reason.contains("Private video"))
		assertEquals(listOf("android_vr info"), calls.readLines())
	}

	@Test fun onlyYouTube() = runBlocking {
		assertTrue(method().attempt(ctx.copy(urlClass = UrlClass.UNLISTED_PAGE)) is MethodOutcome.Unsupported)
	}

	@Test fun judging() {
		assertEquals(YouTubeClients.Verdict.NEEDS_PO_TOKEN, YouTubeClients.judge("ERROR: [youtube] abc: This client requires a GVS PO Token"))
		assertEquals(YouTubeClients.Verdict.NEXT_CLIENT, YouTubeClients.judge("Sign in to confirm you’re not a bot"))
		assertEquals(YouTubeClients.Verdict.GIVE_UP, YouTubeClients.judge("ERROR: [youtube] x: Video unavailable. This video has been removed by the uploader"))
		assertEquals(listOf("--extractor-args", "youtube:player_client=tv"), YouTubeClients.args("tv"))
	}
}
