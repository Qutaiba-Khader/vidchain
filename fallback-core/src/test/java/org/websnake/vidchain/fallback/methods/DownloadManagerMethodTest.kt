package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import java.io.File

/** DownloadManager played by a script of states. */
class DownloadManagerMethodTest {
	@get:Rule val tmp = TemporaryFolder()

	private class Fake(val states: List<SystemDownloads.Status>) : SystemDownloads {
		var i = 0; val removed = ArrayList<Long>(); var headers: Map<String, String>? = null; var name: String? = null
		override fun enqueue(url: String, headers: Map<String, String>, fileName: String): Long { this.headers = headers; name = fileName; return 77 }
		override fun status(id: Long) = states[minOf(i++, states.size - 1)]
		override fun remove(id: Long) { removed += id }
	}

	private fun ctx() = FallbackContext("11", "https://site.example/p", "https://cdn.example/f.mp4", UrlClass.DIRECT_FILE,
		destPath = File(tmp.root, "d/f.mp4").path, userAgent = "UA", referer = "https://site.example/p", fileName = "f.mp4")

	@Test fun successfulDownloadIsCopiedAndTheRecordRemoved() = runBlocking {
		val dmFile = File(tmp.root, "dm/11-D-f.mp4").apply { parentFile.mkdirs(); writeText("payload") }
		val fake = Fake(listOf(SystemDownloads.Status(SystemDownloads.State.PENDING), SystemDownloads.Status(SystemDownloads.State.RUNNING, bytes = 3),
			SystemDownloads.Status(SystemDownloads.State.SUCCESSFUL, localFile = dmFile)))
		val r = DownloadManagerMethod({ fake }, pollMs = 1).attempt(ctx())
		val f = File((r as MethodOutcome.Delivered).path)
		assertEquals("payload", f.readText())
		assertEquals(listOf(77L), fake.removed)
		assertEquals(mapOf("User-Agent" to "UA", "Referer" to "https://site.example/p"), fake.headers)   // never a Cookie
		assertEquals("11-D-f.mp4", fake.name)
	}

	@Test fun failuresCarryTheReason() = runBlocking {
		val r = DownloadManagerMethod({ Fake(listOf(SystemDownloads.Status(SystemDownloads.State.FAILED, reason = 404))) }, pollMs = 1).attempt(ctx())
		assertEquals(MethodOutcome.Failed("DownloadManager: HTTP 404"), r)
		assertEquals("not enough space", DownloadManagerMethod.reason(1006))
	}

	@Test fun stalledDownloadsAreGivenUp() = runBlocking {
		val fake = Fake(listOf(SystemDownloads.Status(SystemDownloads.State.PAUSED, bytes = 5)))
		val r = DownloadManagerMethod({ fake }, pollMs = 5, stallMs = 30).attempt(ctx())
		assertTrue(r is MethodOutcome.Failed && r.reason.contains("no progress"))
		assertEquals(listOf(77L), fake.removed)
	}

	@Test fun cancelRemovesTheSystemDownload() = runBlocking {
		val fake = Fake(listOf(SystemDownloads.Status(SystemDownloads.State.RUNNING, bytes = 1)))
		val job = launch(Dispatchers.Default) { DownloadManagerMethod({ fake }, pollMs = 10).attempt(ctx()) }
		delay(100); job.cancelAndJoin()
		assertEquals(listOf(77L), fake.removed)
	}

	@Test fun streamsAndMissingServiceAreUnsupported() = runBlocking {
		assertTrue(DownloadManagerMethod({ null }).attempt(ctx()) is MethodOutcome.Unsupported)
		assertTrue(DownloadManagerMethod({ Fake(emptyList()) }).attempt(ctx().copy(urlClass = UrlClass.HLS_DASH)) is MethodOutcome.Unsupported)
		assertFalse(File(tmp.root, "d/.vidchain-partial/11-D.part").exists())
	}
}
