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

	@Test fun aServerThatNeverSendsAByteIsGivenUpLikeTheAppsOwnDownload() = runBlocking {
		// T9 (full pass, hang fault): DownloadManager keeps retrying a hanging server; no first byte -> the next method
		val fake = Fake(listOf(SystemDownloads.Status(SystemDownloads.State.RUNNING, bytes = 0)))
		val r = DownloadManagerMethod({ fake }, pollMs = 5, noStartMs = 40).attempt(ctx())
		assertTrue(r.toString(), r is MethodOutcome.Failed && r.reason.contains("no data"))
		assertEquals(listOf(77L), fake.removed)
	}

	@Test fun waitingForTheNetworkIsNotAServerThatNeverAnswers() = runBlocking {
		// review run 25: queued or waiting for a network / Wi-Fi (PAUSED reason 2/3) does not count; reason 1 (retry) does
		val waits = List(30) { SystemDownloads.Status(SystemDownloads.State.PAUSED, reason = 2, bytes = 0) } +
			SystemDownloads.Status(SystemDownloads.State.FAILED, reason = 404)
		val r = DownloadManagerMethod({ Fake(waits) }, pollMs = 5, noStartMs = 40).attempt(ctx())
		assertEquals(MethodOutcome.Failed("DownloadManager: HTTP 404"), r)               // not "no data"
		val retry = Fake(listOf(SystemDownloads.Status(SystemDownloads.State.PAUSED, reason = 1, bytes = 0)))
		val r2 = DownloadManagerMethod({ retry }, pollMs = 5, noStartMs = 40).attempt(ctx())
		assertTrue(r2.toString(), r2 is MethodOutcome.Failed && r2.reason.contains("no data"))
	}

	@Test fun pendingStopsOnlyTheNoDataClock() = runBlocking {
		// review run 28: Android keeps an offline download PENDING; past noStartMs it is still not "no data" ...
		val pending = List(30) { SystemDownloads.Status(SystemDownloads.State.PENDING, bytes = 0) } +
			SystemDownloads.Status(SystemDownloads.State.FAILED, reason = 404)
		val r = DownloadManagerMethod({ Fake(pending) }, pollMs = 5, noStartMs = 40).attempt(ctx())
		assertEquals(MethodOutcome.Failed("DownloadManager: HTTP 404"), r)
		// ... but the stall clock still runs: a download PENDING for ever is given up
		val stuck = Fake(listOf(SystemDownloads.Status(SystemDownloads.State.PENDING, bytes = 0)))
		val r2 = DownloadManagerMethod({ stuck }, pollMs = 5, noStartMs = 40, stallMs = 30).attempt(ctx())
		assertTrue(r2.toString(), r2 is MethodOutcome.Failed && r2.reason.contains("no progress") && r2.reason.contains("pending"))
		assertEquals(listOf(77L), stuck.removed)
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
