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
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.engine.aria2.Aria2Engine
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.Engine
import org.websnake.vidchain.fallback.core.Candidate
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackCoordinator
import org.websnake.vidchain.fallback.core.FallbackHost
import org.websnake.vidchain.fallback.core.HostDownload
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.ledger.AttemptState
import org.websnake.vidchain.fallback.ledger.InMemoryLedger
import java.io.File

/** aria2c played by a script on the real engine runner; the coordinator commits and lists every file of a torrent. */
class Aria2MethodTest {
	@get:Rule val tmp = TemporaryFolder()
	private lateinit var engine: Aria2Engine
	private lateinit var args: File

	@Before fun setUp() {
		val launcher = Launcher.detect()
		assumeTrue(launcher.setsid != null)
		val lib = tmp.newFolder("lib"); val nb = tmp.newFolder("nb")
		File(nb, "youtubedl-android/packages/aria2c/usr/lib").mkdirs()
		args = File(tmp.root, "args.txt")
		val d = '$'
		File(lib, "libaria2c.so").apply {
			writeText("""
				#!/bin/sh
				printf '%s\n' "$d@" > ${args.path}
				dir=""; out=""; uri=""; prev=""; for a in "$d@"; do case "${d}a" in --dir=*) dir="${d}{a#--dir=}";; --out=*) out="${d}{a#--out=}";; esac; [ "${d}prev" = "--" ] && uri="${d}a"; prev="${d}a"; done
				case "${d}uri" in *fail*) echo "Exception: 404 Not Found" >&2; exit 3;; esac
				echo "[#2089b0 12MiB/48MiB(25%) CN:4 DL:1.2MiB ETA:30s]"
				mkv() { printf '\032\105\337\243' > "${d}1"; head -c ${d}2 /dev/zero >> "${d}1"; }
				case "${d}uri" in
				  magnet:*) mkdir -p "${d}dir/Show"; mkv "${d}dir/Show/ep1.mkv" 9000; mkv "${d}dir/Show/ep2.mkv" 5000; printf x > "${d}dir/Show/ep1.mkv.aria2";;
				  *) mkv "${d}dir/${d}out" 3000;;
				esac
			""".trimIndent())
			setExecutable(true)
		}
		engine = Aria2Engine(ProcessEngineRunner(launcher), EngineLayout(lib, nb, tmp.root))
	}

	@Test fun plainFileWithSeveralConnections() = runBlocking {
		val ctx = FallbackContext("21", "https://cdn.example/v.mkv", "https://cdn.example/v.mkv", UrlClass.DIRECT_FILE, destPath = File(tmp.root, "dl/v.mkv").path, userAgent = "UA", referer = "https://site.example/")
		val r = Aria2Method({ engine }).attempt(ctx) as MethodOutcome.Delivered
		assertTrue(r.path.endsWith("21-A/21-A.bin")); assertTrue(r.extras.isEmpty())
		val a = args.readLines()
		assertTrue(a.containsAll(listOf("--async-dns=false", "--check-integrity=true", "--seed-time=0", "--user-agent=UA", "--header=Referer: https://site.example/", "--out=21-A.bin")))
		assertTrue(a.any { it.startsWith("--ca-certificate=") && it.endsWith("usr/etc/tls/cert.pem") })
		assertEquals(listOf("--", "https://cdn.example/v.mkv"), a.takeLast(2))
	}

	@Test fun failureCodesAreNamed() = runBlocking {
		val ctx = FallbackContext("22", "https://cdn.example/fail.mkv", "https://cdn.example/fail.mkv", UrlClass.DIRECT_FILE, destPath = File(tmp.root, "dl/v.mkv").path)
		val r = Aria2Method({ engine }).attempt(ctx)
		assertTrue(r is MethodOutcome.Failed && r.reason.startsWith("resource not found"))
		assertTrue(Aria2Method({ engine }).attempt(ctx.copy(urlClass = UrlClass.HLS_DASH)) is MethodOutcome.Unsupported)
	}

	@Test fun aSharedMagnetRunsStandaloneAndEveryFileIsCommittedAndListed() = runBlocking {
		val listed = ArrayList<String>()
		val host = object : FallbackHost {
			override fun downloads() = emptyList<HostDownload>()
			override fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String? = null
			override fun registerDelivered(parent: HostDownload, file: File, method: String): String { listed += file.name; return "id-${file.name}" }
		}
		val ledger = InMemoryLedger()
		val c = FallbackCoordinator(host, ledger, listOf(Aria2Method({ engine })), this, FallbackCoordinator.Config(includeStub = false))
		val dir = tmp.newFolder("Downloads")
		val root = HostDownload("share-1", "magnet:?xt=urn:btih:abc", "magnet:?xt=urn:btih:abc", DownloadSnapshot(Engine.REGULAR, DownloadSnapshot.CLOSE),
			filePath = File(dir, "torrent").path, expectMedia = false, keepNames = true)
		assertTrue(c.startStandalone(root)); c.drain()
		assertEquals(AttemptState.DELIVERED, ledger.attempts("share-1").single().state)
		assertEquals(listOf("ep1.mkv", "ep2.mkv"), listed)                                   // largest first, names kept
		assertTrue(File(dir, "ep1.mkv").isFile && File(dir, "ep2.mkv").isFile)
		assertTrue(File(dir, ".vidchain-partial").listFiles()!!.none { it.name.endsWith(".mkv") })
	}

	@Test fun argvAndProgress() {
		assertEquals(25.0, Aria2Engine.progress("[#2089b0 12MiB/48MiB(25%) CN:4 DL:1.2MiB ETA:30s]")!!, 0.0)
		assertEquals("not enough disk space", Aria2Engine.meaning(9))
		val a = engine.argv(Aria2Engine.Job("magnet:?xt=1", File("/d"), headers = mapOf("Cookie" to "x", "Origin" to "o")))
		assertTrue(a.none { "Cookie" in it } && a.contains("--header=Origin: o") && a.none { it.startsWith("--out=") })
	}
}
