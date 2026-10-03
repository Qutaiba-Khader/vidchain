package org.websnake.vidchain.ytdlp.nightly

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.engine.MemoryStore
import java.io.File
import java.security.MessageDigest

class NightlyToolStoreTest {
	@get:Rule val tmp = TemporaryFolder()
	private val s = TestHttpServer()
	@After fun stop() = s.close()
	private var now = 1_000_000L
	private val kv = MemoryStore()
	private var latest = "2026.10.01.232112"
	private val assets = HashMap<String, ByteArray>()
	private val bundled by lazy { tmp.newFile("bundled-yt-dlp").apply { writeText("bundled stable") } }

	private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
	private fun publish(version: String, body: String, sumsFor: String = body) {
		assets["$version/yt-dlp"] = body.toByteArray()
		assets["$version/SHA2-256SUMS"] = "${sha(sumsFor.toByteArray())}  yt-dlp\n${sha("x".toByteArray())}  yt-dlp.exe\n".toByteArray()
		s.route("/rel/download/$version/yt-dlp") { ex, _ -> ex.send(200, assets["$version/yt-dlp"]!!) }
		s.route("/rel/download/$version/SHA2-256SUMS") { ex, _ -> ex.send(200, assets["$version/SHA2-256SUMS"]!!) }
	}
	private fun store() = NightlyToolStore(File(tmp.root, "tools"), OkHttpClient(), kv, base = s.url("/rel"), clock = { now })

	init {
		s.route("/rel/latest") { ex, _ -> ex.send(302, headers = mapOf("Location" to "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/tag/$latest")) }
	}

	@Test fun verifiedNightlyIsInstalledAndCheckedOnlyDaily() = runBlocking {
		val before = sha(bundled.readBytes())
		publish(latest, "#!/usr/bin/env python3\nnightly-1")
		val r = store().current() as NightlyToolStore.Result.Ready
		assertTrue(r.fresh); assertEquals(latest, r.version); assertEquals("#!/usr/bin/env python3\nnightly-1", r.file.readText())
		val calls = s.requests.size
		now += 3_600_000
		assertFalse((store().current() as NightlyToolStore.Result.Ready).fresh)
		assertEquals(calls, s.requests.size)                                          // within a day: no network
		assertEquals(before, sha(bundled.readBytes()))                               // the bundled yt-dlp is never touched
	}

	@Test fun checksumMismatchIsRefusedAndTheCurrentOneStays() = runBlocking {
		publish(latest, "good")
		store().current()
		latest = "2026.10.02.010101"
		publish(latest, "tampered", sumsFor = "what the release says")
		now += 25 * 3_600_000L
		val r = store().update()
		assertTrue(r is NightlyToolStore.Result.Failed && r.reason.contains("checksum mismatch"))
		assertEquals("2026.10.01.232112", (store().current() as NightlyToolStore.Result.Ready).version)
		assertFalse(File(tmp.root, "tools/2026.10.02.010101/yt-dlp").exists())
	}

	@Test fun previousIsKeptForRollbackAndOlderOnesPruned() = runBlocking {
		val versions = listOf("2026.09.30.1", "2026.10.01.1", "2026.10.02.1")
		for (v in versions) { latest = v; publish(v, "build $v"); now += 25 * 3_600_000L; store().current() }
		assertEquals(setOf("2026.10.01.1", "2026.10.02.1"), File(tmp.root, "tools").list()!!.toSet())
		assertTrue(store().rollback())
		assertEquals("2026.10.01.1", store().installed()!!.first)
	}

	@Test fun oddVersionNamesAreRefused() = runBlocking {
		latest = "../../evil"
		assertTrue(store().update() is NightlyToolStore.Result.Failed)
	}
}
