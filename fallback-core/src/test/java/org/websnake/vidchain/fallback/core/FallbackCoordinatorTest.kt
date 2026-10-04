package org.websnake.vidchain.fallback.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.CLOSE
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.COMPLETE
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.DOWNLOADING
import org.websnake.vidchain.fallback.classifier.Engine
import org.websnake.vidchain.fallback.classifier.StatusKey
import org.websnake.vidchain.fallback.classifier.UserIntent
import org.websnake.vidchain.fallback.ledger.AttemptState
import org.websnake.vidchain.fallback.ledger.InMemoryLedger
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FallbackCoordinatorTest {
	private val fileUrl = "https://files.example/video.mp4"   // B4: R, L, S, O, A, D

	private class FakeHost : FallbackHost {
		val list = LinkedHashMap<String, HostDownload>()
		val queued = ArrayList<Pair<String, Candidate>>()
		val delivered = ArrayList<File>()
		var lists = true
		private var nextId = 1000
		override fun downloads() = list.values.toList()
		override fun registerDelivered(parent: HostDownload, file: File, method: String): String? =
			if (!lists) null else { delivered += file; "d${nextId++}" }
		override fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String {
			val id = (nextId++).toString()
			queued += parent.id to candidate
			list[id] = HostDownload(id, candidate.url, candidate.url, DownloadSnapshot(Engine.REGULAR, CLOSE))
			return id
		}
		fun set(id: String, snap: DownloadSnapshot, bytes: Long = 0, url: String = list[id]?.url ?: "", path: String? = list[id]?.filePath) {
			list[id] = HostDownload(id, url, url, snap, bytes, filePath = path)
		}
	}

	private class Counting(override val id: String, private val outcome: () -> MethodOutcome) : FallbackMethod {
		val calls = AtomicInteger()
		override val kind = FallbackMethod.Kind.RESOLVER
		override suspend fun attempt(ctx: FallbackContext): MethodOutcome { calls.incrementAndGet(); return outcome() }
	}

	private fun running() = DownloadSnapshot(Engine.REGULAR, DOWNLOADING, isRunning = true)
	private fun failed(key: StatusKey = StatusKey.DOWNLOAD_FAILED) = DownloadSnapshot(Engine.REGULAR, CLOSE, statusKey = key)
	private fun paused() = DownloadSnapshot(Engine.REGULAR, CLOSE, statusKey = StatusKey.PAUSED)

	private class World(scope: CoroutineScope, methods: List<FallbackMethod>, enabled: Boolean = true, stub: Boolean = true) {
		val host = FakeHost()
		val ledger = InMemoryLedger()
		var now = 1_000_000L
		val c = FallbackCoordinator(host, ledger, methods, scope, FallbackCoordinator.Config(stub, enabled = { enabled }), clock = { now })
		suspend fun tick() { c.tick(); c.drain() }
	}

	private val stub = StubAlwaysFailMethod
	private val done = DownloadSnapshot(Engine.REGULAR, COMPLETE, isComplete = true)

	/** a minimal valid MP4 (ftyp, moov, mdat) and an HTML error page saved as .mp4 */
	private fun goodMp4(): String = File.createTempFile("good", ".mp4").apply {
		deleteOnExit()
		fun box(t: String, n: Int) = byteArrayOf(0, 0, ((8 + n) shr 8).toByte(), (8 + n).toByte()) + t.toByteArray() + ByteArray(n)
		writeBytes(box("ftyp", 8) + box("moov", 200) + box("mdat", 3000))
	}.path
	private fun htmlAsMp4(): String = File.createTempFile("bad", ".mp4").apply { deleteOnExit(); writeText("<!doctype html><html><body>denied</body></html>") }.path

	@Test fun stateFoundAtStartupNeverStartsAChain() = runBlocking {
		val w = World(this, listOf(stub))
		w.host.set("1", paused(), url = fileUrl)
		w.host.set("2", failed(), url = fileUrl)
		repeat(3) { w.tick() }
		assertTrue(w.ledger.attempts("1").isEmpty())
		assertTrue(w.ledger.attempts("2").isEmpty())
	}

	@Test fun observedFailureReachesStubExactlyOnce() = runBlocking {
		val w = World(this, listOf(stub))
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); repeat(5) { w.tick() }
		val rows = w.ledger.attempts("1")
		assertEquals(1, rows.size)
		assertEquals(ChainSpec.STUB, rows[0].method)
		assertEquals(AttemptState.FAILED, rows[0].state)
	}

	@Test fun userPauseNeverStartsAChain() = runBlocking {
		val w = World(this, listOf(stub))
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.ledger.recordIntent("1", UserIntent.PAUSE, 0)
		w.host.set("1", paused()); repeat(3) { w.tick() }
		assertTrue(w.ledger.attempts("1").isEmpty())
	}

	@Test fun storageFailureIsNotEligible() = runBlocking {
		val w = World(this, listOf(stub))
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed(StatusKey.FILE_IO_FAILED)); w.tick()
		assertTrue(w.ledger.attempts("1").isEmpty())
	}

	@Test fun switchedOffDoesNothing() = runBlocking {
		val w = World(this, listOf(stub), enabled = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		assertTrue(w.ledger.attempts("1").isEmpty())
	}

	@Test fun stallAfterTwoMinutesWithoutProgress() = runBlocking {
		val w = World(this, listOf(stub))
		w.host.set("1", running(), bytes = 10, url = fileUrl); w.tick()
		w.now += 60_000; w.host.set("1", running(), bytes = 20); w.tick()
		w.now += 119_000; w.tick()
		assertTrue(w.ledger.attempts("1").isEmpty())
		w.now += 2_000; w.tick(); w.tick()
		assertEquals(1, w.ledger.attempts("1").size)
	}

	@Test fun resolverChildFailureContinuesParentChainAndChildSuccessEndsIt() = runBlocking {
		val r = Counting("R") { MethodOutcome.Failed("no redirect") }
		val o = Counting("O") { MethodOutcome.Resolved(Candidate("https://cdn.example/v.mp4")) }
		val a = Counting("A") { MethodOutcome.Resolved(Candidate("https://cdn2.example/v.mp4")) }
		val w = World(this, listOf(r, o, a), stub = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(listOf("R", "O"), w.ledger.attempts("1").map { it.method })
		assertEquals(AttemptState.CHILD, w.ledger.attempts("1")[1].state)
		val child = w.ledger.attempts("1")[1].childId!!
		assertEquals("1", w.ledger.parentOf(child))
		// the child runs, then fails: the parent's chain moves on to A, which queues a second child
		w.host.set(child, running()); w.tick()
		w.host.set(child, failed()); w.tick(); w.tick()
		assertEquals(listOf("R", "O", "A"), w.ledger.attempts("1").map { it.method })
		assertEquals(AttemptState.FAILED, w.ledger.attempts("1")[1].state)
		val child2 = w.ledger.attempts("1")[2].childId!!
		w.host.set(child2, running()); w.tick()
		w.host.set(child2, done, path = goodMp4()); w.tick(); w.tick()
		assertEquals(AttemptState.DELIVERED, w.ledger.attempts("1")[2].state)
		assertEquals(1, r.calls.get()); assertEquals(1, o.calls.get()); assertEquals(1, a.calls.get())
		assertTrue(w.ledger.attempts(child).isEmpty())   // a child never starts a chain of its own
	}

	@Test fun deletedParentStopsTheChain() = runBlocking {
		val o = Counting("O") { MethodOutcome.Resolved(Candidate("https://cdn.example/v.mp4")) }
		val a = Counting("A") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(o, a), stub = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		val child = w.ledger.attempts("1")[0].childId!!
		w.ledger.recordIntent("1", UserIntent.DELETE, 0); w.host.list.remove("1")
		w.host.set(child, running()); w.tick()
		w.host.set(child, failed()); w.tick()
		assertEquals(0, a.calls.get())
	}

	@Test fun resumeAfterFailureMayFallBackAgainButNeverRepeatsAMethod() = runBlocking {
		val r = Counting("R") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(r), stub = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		w.ledger.recordIntent("1", UserIntent.RESUME, 0)
		w.host.set("1", running()); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(1, r.calls.get())   // the chain was exhausted; resuming does not re-run R
	}

	@Test fun downloadCreatedAndFailedBeforeTheFirstLookStillCounts() = runBlocking {
		// pass 11: the app creates a download and fails it while its lists are still loading (no baseline yet)
		val w = World(this, listOf(stub))
		w.host.list["9"] = HostDownload("9", fileUrl, fileUrl, failed(), startedAtMs = w.now + 5_000)       // after VidChain started
		w.host.list["10"] = HostDownload("10", fileUrl, fileUrl, failed(), startedAtMs = w.now - 60_000)    // before: old state
		w.tick()
		assertEquals(1, w.ledger.attempts("9").size)
		assertTrue(w.ledger.attempts("10").isEmpty())
	}

	@Test fun newDownloadAfterStartupWithExplicitFailureCounts() = runBlocking {
		val w = World(this, listOf(stub))
		w.tick()                                   // startup baseline (empty)
		w.host.set("7", failed(StatusKey.LINK_EXPIRED), url = fileUrl)
		w.host.set("8", DownloadSnapshot(Engine.REGULAR, CLOSE), url = fileUrl)   // queued, never started
		w.tick()
		assertEquals(1, w.ledger.attempts("7").size)
		assertTrue(w.ledger.attempts("8").isEmpty())
	}

	@Test fun resumeThatFailsBeforeItIsSeenRunningStillCounts() = runBlocking {
		val w = World(this, listOf(stub))
		w.host.set("1", paused(), url = fileUrl); w.tick()            // found paused at startup
		w.ledger.recordIntent("1", UserIntent.RESUME, 0)
		w.host.set("1", failed(StatusKey.LINK_EXPIRED)); w.tick()      // failed between two ticks
		assertEquals(1, w.ledger.attempts("1").size)
	}

	@Test fun tickReportsIdleWhenNothingRuns() = runBlocking {
		val w = World(this, listOf(stub))
		w.host.set("1", paused(), url = fileUrl)
		assertTrue(!w.c.tick())
		w.host.set("1", running()); assertTrue(w.c.tick())
		w.c.drain()
	}

	@Test fun crashingMethodCountsAsFailedAndChainContinues() = runBlocking {
		val boom = Counting("R") { throw IllegalStateException("boom") }
		val o = Counting("O") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(boom, o), stub = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(listOf(AttemptState.FAILED, AttemptState.FAILED), w.ledger.attempts("1").map { it.state })
		assertTrue(w.ledger.attempts("1")[0].reason!!.contains("boom"))
	}

	@Test fun staleRunningRowFromADeadProcessIsClosed() = runBlocking {
		val w = World(this, listOf(stub))
		w.ledger.claim("1", 1, "R", 0)             // left RUNNING by a killed process
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		val rows = w.ledger.attempts("1")
		assertEquals(AttemptState.FAILED, rows[0].state)
		assertEquals(ChainSpec.STUB, rows[1].method)
	}

	@Test fun childThatDeliversTrashFailsVerificationAndTheChainMovesOn() = runBlocking {
		val o = Counting("O") { MethodOutcome.Resolved(Candidate("https://cdn.example/v.mp4")) }
		val a = Counting("A") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(o, a), stub = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		val child = w.ledger.attempts("1")[0].childId!!
		w.host.set(child, running()); w.tick()
		w.host.set(child, done, path = htmlAsMp4()); w.tick(); w.tick()
		val rows = w.ledger.attempts("1")
		assertEquals(AttemptState.FAILED, rows[0].state)
		assertTrue(rows[0].reason!!.contains("HTML"))
		assertEquals(1, a.calls.get())
	}

	@Test fun unsureChildIsKeptAsBestSoFarWhileTheChainContinues() = runBlocking {
		val o = Counting("O") { MethodOutcome.Resolved(Candidate("https://cdn.example/v.mp4")) }
		val a = Counting("A") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(o, a), stub = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		val child = w.ledger.attempts("1")[0].childId!!
		w.host.set(child, running()); w.tick()
		w.host.set(child, done, path = null); w.tick(); w.tick()     // no file path: cannot verify
		assertEquals(listOf(AttemptState.UNSURE, AttemptState.FAILED), w.ledger.attempts("1").map { it.state })
	}

	@Test fun executorFileIsVerified() = runBlocking {
		val good = goodMp4(); val bad = htmlAsMp4()
		val r = Counting("R") { MethodOutcome.Delivered(bad) }
		val l = Counting("L") { MethodOutcome.Delivered(good) }
		val s = Counting("S") { MethodOutcome.Failed("never reached") }
		val w = World(this, listOf(r, l, s), stub = false)
		val dest = File(File(good).parentFile, "vidchain-test-${System.nanoTime()}.mp4").apply { deleteOnExit() }
		w.host.set("1", running(), url = fileUrl, path = dest.path); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(listOf(AttemptState.FAILED, AttemptState.DELIVERED), w.ledger.attempts("1").map { it.state })
		assertEquals(0, s.calls.get())
		assertTrue(!File(bad).exists())                      // FAIL: the method's temp file is removed
		assertTrue(!File(good).exists())                     // PASS: moved to its final name
		assertTrue(dest.isFile && dest.length() > 3000)
	}

	@Test fun boardFollowsTheChain() = runBlocking {
		org.websnake.vidchain.fallback.ui.TrailBoard.clear()
		val r = Counting("R") { MethodOutcome.Failed("x") }
		val o = Counting("O") { MethodOutcome.Resolved(Candidate("https://cdn.example/v.mp4")) }
		val w = World(this, listOf(r, o), stub = false)
		w.host.set("1", running(), url = fileUrl); w.tick()
		w.host.set("1", failed()); w.tick()
		val t = org.websnake.vidchain.fallback.ui.TrailBoard.of("1")!!
		assertEquals("O", t.method); assertEquals(2, t.step); assertEquals(2, t.total)
		assertEquals(org.websnake.vidchain.fallback.ui.Trail.State.WAITING_CHILD, t.state)
		val child = w.ledger.attempts("1")[1].childId!!
		assertEquals("1", org.websnake.vidchain.fallback.ui.TrailBoard.parentOf(child))
		assertEquals("O", org.websnake.vidchain.fallback.ui.TrailBoard.methodOfChild(child))
		w.host.set(child, running()); w.tick()
		w.host.set(child, done, path = goodMp4()); w.tick(); w.tick()
		assertEquals(org.websnake.vidchain.fallback.ui.Trail.State.DELIVERED, org.websnake.vidchain.fallback.ui.TrailBoard.of("1")!!.state)
	}

	@Test fun tryAnotherRunsTheNextMethodWithoutTouchingTheCurrentDownload() = runBlocking {
		val r = Counting("R") { MethodOutcome.Failed("x") }
		val o = Counting("O") { MethodOutcome.Resolved(Candidate("https://cdn.example/v.mp4")) }
		val a = Counting("A") { MethodOutcome.Failed("y") }
		val w = World(this, listOf(r, o, a), enabled = false, stub = false)   // automatic fallbacks off
		w.host.set("1", running(), url = fileUrl); w.tick()
		assertEquals(FallbackCoordinator.Manual.STARTED, w.c.tryAnother("1")); w.c.drain()
		assertEquals(listOf("R", "O"), w.ledger.attempts("1").map { it.method })
		assertEquals(running(), w.host.list["1"]!!.snapshot)                   // the current download is untouched
		// asking again gives up the queued child and moves on
		assertEquals(FallbackCoordinator.Manual.STARTED, w.c.tryAnother(w.ledger.attempts("1")[1].childId!!)); w.c.drain()
		assertEquals(listOf(AttemptState.FAILED, AttemptState.FAILED, AttemptState.FAILED), w.ledger.attempts("1").map { it.state })
		assertEquals(FallbackCoordinator.Manual.NOTHING_LEFT, w.c.tryAnother("1"))
		assertEquals(FallbackCoordinator.Manual.NOT_FOUND, w.c.tryAnother("404"))
	}

	@Test fun boardIsRestoredFromTheLedgerAfterARestart() = runBlocking {
		org.websnake.vidchain.fallback.ui.TrailBoard.clear()
		val w = World(this, listOf(stub))
		w.ledger.claim("5", 1, "O", 10); w.ledger.update("5", 1, AttemptState.FAILED, null, "x", 11)
		w.ledger.claim("5", 2, "A", 20); w.ledger.update("5", 2, AttemptState.CHILD, "77", null, 21)
		w.ledger.claim("6", 1, "R", 30); w.ledger.update("6", 1, AttemptState.FAILED, null, "x", 31)
		w.c.restoreBoard()
		assertEquals(org.websnake.vidchain.fallback.ui.Trail.State.WAITING_CHILD, org.websnake.vidchain.fallback.ui.TrailBoard.of("5")!!.state)
		assertEquals("5", org.websnake.vidchain.fallback.ui.TrailBoard.parentOf("77"))
		assertEquals(org.websnake.vidchain.fallback.ui.Trail.State.EXHAUSTED, org.websnake.vidchain.fallback.ui.TrailBoard.of("6")!!.state)
		assertEquals(listOf("6", "5"), w.ledger.recentParents(10))
	}

	@Test fun redirectMovesTheChainToTheUnwrappedUrlsClass() = runBlocking {
		val r = Counting("R") { MethodOutcome.Redirected("https://youtu.be/abc", "1 hop") }
		val seen = ArrayList<FallbackContext>()
		val y = object : FallbackMethod {
			override val id = "Y"; override val kind = FallbackMethod.Kind.RESOLVER
			override suspend fun attempt(ctx: FallbackContext): MethodOutcome { seen += ctx; return MethodOutcome.Failed("no formats") }
		}
		val w = World(this, listOf(r, y), stub = false)
		w.host.set("1", running(), url = "https://t.co/xyz.mp4"); w.tick()          // B4 (looks like a file) -> R -> YouTube (B1)
		w.host.set("1", failed()); w.tick()
		val rows = w.ledger.attempts("1")
		assertEquals(listOf("R", "Y"), rows.map { it.method })
		assertEquals(AttemptState.REDIRECTED, rows[0].state)
		assertTrue(rows[0].reason!!.contains("B4 -> B1"))
		assertEquals("https://youtu.be/abc", seen.single().url)
		assertEquals(UrlClass.YOUTUBE, seen.single().urlClass)
		assertEquals("https://youtu.be/abc", w.ledger.effectiveUrl("1"))
		assertEquals(1, r.calls.get())
	}

	@Test fun deliveredFileKeepsItsRealContainerExtension() = runBlocking {
		val dir = java.nio.file.Files.createTempDirectory("ext").toFile()
		val webm = File(dir, ".vidchain-partial/1-Y.webm").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(4000)) }
		val y = Counting("Y") { MethodOutcome.Delivered(webm.path) }
		val w = World(this, listOf(y), stub = false)
		w.host.set("1", running(), url = "https://site.example/page", path = File(dir, "clip.mp4").path); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(AttemptState.DELIVERED, w.ledger.attempts("1").single().state)
		assertTrue(File(dir, "clip.webm").isFile)
		assertFalse(File(dir, "clip.mp4").exists())
	}

	@Test fun anOsKillIsRetriedOnceInsideTheSameAttempt() = runBlocking {
		val n = java.util.concurrent.atomic.AtomicInteger()
		val y = Counting("Y") { if (n.incrementAndGet() == 1) MethodOutcome.Failed("stopped by the system", retryable = true) else MethodOutcome.Failed("real") }
		val w = World(this, listOf(y), stub = false)
		w.host.set("1", running(), url = "https://site.example/page"); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(2, y.calls.get())
		assertEquals("real", w.ledger.attempts("1").single().reason)
	}

	@Test fun claimIsAtomicUnderConcurrency() {
		val ledger = InMemoryLedger()
		val pool = Executors.newFixedThreadPool(16)
		val start = CountDownLatch(1)
		val wins = AtomicInteger()
		repeat(64) { pool.execute { start.await(); if (ledger.claim("p", 1, "R", 0)) wins.incrementAndGet() } }
		start.countDown(); pool.shutdown(); pool.awaitTermination(10, TimeUnit.SECONDS)
		assertEquals(1, wins.get())
		assertTrue(ledger.markHandled("p", "s", 0)); assertTrue(!ledger.markHandled("p", "s", 0))
	}

	// --- T6.2 review fixes ---

	@Test fun aFileTheAppCouldNotListIsNotADelivery() = runBlocking {
		val y = Counting("Y") { MethodOutcome.Delivered(goodMp4()) }
		val n = Counting("N") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(y, n), stub = false)
		w.host.lists = false
		w.host.set("1", running(), url = "https://site.example/page", path = File(File(goodMp4()).parentFile, "l-${System.nanoTime()}.mp4").path); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(AttemptState.FAILED, w.ledger.attempts("1").first().state)
		assertTrue(w.ledger.attempts("1").first().reason!!.startsWith("could not list"))
		assertEquals(1, n.calls.get())                                   // the chain went on
	}

	@Test fun aDeletedDownloadIsForgottenAndItsReusedIdGetsFallbacksAgain() = runBlocking {
		val y = Counting("Y") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(y), stub = false)
		w.host.set("7", running(), url = "https://site.example/a"); w.tick()
		w.host.set("7", failed()); w.tick()
		assertEquals(1, w.ledger.attempts("7").size)
		w.ledger.recordIntent("7", UserIntent.DELETE, w.now)
		w.host.list.remove("7"); w.tick()                              // gone after the delete: forgotten
		assertEquals(0, w.ledger.attempts("7").size)
		assertEquals(UserIntent.NONE, w.ledger.intentOf("7"))
		w.host.set("7", running(), url = "https://site.example/b"); w.tick()   // the app hands id 7 to a new download
		w.host.set("7", failed()); w.tick()
		assertEquals(2, y.calls.get())                                   // it gets its own chain
	}

	@Test fun aReusedIdStillMarkedDeletedIsForgottenWhenItAppears() = runBlocking {
		val y = Counting("Y") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(y), stub = false)
		w.ledger.recordIntent("9", UserIntent.DELETE, w.now)            // left over from an older app run
		w.tick()
		w.host.set("9", running(), url = "https://site.example/c"); w.tick()
		w.host.set("9", failed()); w.tick()
		assertEquals(1, y.calls.get())
	}

	@Test fun deletingTheDownloadStopsItsChainAndNothingIsSaved() = runBlocking {
		val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
		val started = kotlinx.coroutines.CompletableDeferred<Unit>()
		val good = goodMp4()
		val y = object : FallbackMethod {
			override val id = "Y"; override val kind = FallbackMethod.Kind.EXECUTOR
			override suspend fun attempt(ctx: FallbackContext): MethodOutcome { started.complete(Unit); gate.await(); return MethodOutcome.Delivered(good) }
		}
		val n = Counting("N") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(y, n), stub = false)
		w.host.set("1", running(), url = "https://site.example/page"); w.c.tick()
		w.host.set("1", failed()); w.c.tick()
		started.await()
		w.ledger.recordIntent("1", UserIntent.DELETE, w.now)
		w.c.cancelChain("1")
		w.c.drain()
		assertTrue(w.host.delivered.isEmpty())
		assertEquals(0, n.calls.get())
	}

	@Test fun aResumedDownloadWaitingForASlotIsNotAFailure() = runBlocking {
		val y = Counting("Y") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(y), stub = false)
		w.host.set("1", paused(), url = "https://site.example/p"); w.tick()
		w.ledger.recordIntent("1", UserIntent.RESUME, w.now)
		w.host.set("1", DownloadSnapshot(Engine.REGULAR, CLOSE, statusKey = StatusKey.QUEUED)); w.tick()
		w.host.set("1", DownloadSnapshot(Engine.REGULAR, CLOSE, statusKey = StatusKey.OTHER)); w.now += 5_000; w.tick()
		assertEquals(0, y.calls.get())                                   // not started yet: inside the grace time
		w.host.set("1", running()); w.now += 5_000; w.tick()
		w.host.set("1", failed()); w.now += 5_000; w.tick()
		assertEquals(1, y.calls.get())                                   // a real failure after it ran
	}

	@Test fun anInterruptedChainResumesWithItsNextMethodOnlyTwice() = runBlocking {
		val y = Counting("Y") { MethodOutcome.Failed("x") }
		val n = Counting("N") { MethodOutcome.Failed("x") }
		val w = World(this, listOf(y, n), stub = false)
		w.host.set("1", failed(), url = "https://site.example/page")
		assertTrue(w.ledger.claim("1", 1, "Y", 0))                     // the process died while Y ran
		w.c.restoreBoard()
		assertEquals(org.websnake.vidchain.fallback.ui.Trail.State.INTERRUPTED, org.websnake.vidchain.fallback.ui.TrailBoard.of("1")!!.state)
		assertEquals(1, w.c.resumeInterrupted()); w.c.drain()
		assertEquals(0, y.calls.get()); assertEquals(1, n.calls.get())  // Y is not run again, N is next
		assertTrue(w.ledger.claim("1", 3, "N", 0))                     // killed again, a second time
		w.c.restoreBoard()
		assertEquals(0, w.c.resumeInterrupted())                        // two interruptions: it stays stopped
	}

	@Test fun aTorrentOfTextFilesIsKept() = runBlocking {
		val dir = java.nio.file.Files.createTempDirectory("tor").toFile()
		val a = File(dir, ".vidchain-partial/x-A/book.txt").apply { parentFile!!.mkdirs(); writeText("chapter one ".repeat(500)) }
		val b = File(dir, ".vidchain-partial/x-A/notes.srt").apply { writeText("1\n00:00:01,000 --> 00:00:02,000\nhi\n") }
		val aria = Counting("A") { MethodOutcome.Delivered(a.path, extras = listOf(b.path)) }
		val w = World(this, listOf(aria), stub = false)
		val root = HostDownload("share-x", "magnet:?xt=urn:btih:abc", "magnet:?xt=urn:btih:abc", DownloadSnapshot(Engine.REGULAR, CLOSE),
			filePath = File(dir, "torrent").path, expectMedia = false, keepNames = true)
		assertTrue(w.c.startStandalone(root)); w.c.drain()
		assertEquals(AttemptState.DELIVERED, w.ledger.attempts("share-x").single().state)
		assertTrue(File(dir, "book.txt").isFile && File(dir, "notes.srt").isFile)
		assertEquals(2, w.host.delivered.size)
	}

	@Test fun aFailedFirstFileGivesWayToAGoodFileOfTheSameDelivery() = runBlocking {
		val g = Counting("G") { MethodOutcome.Delivered(htmlAsMp4(), extras = listOf(goodMp4())) }
		val w = World(this, listOf(g), stub = false)
		w.host.set("1", running(), url = "https://site.example/page", path = File(File(goodMp4()).parentFile, "g-${System.nanoTime()}.mp4").path); w.tick()
		w.host.set("1", failed()); w.tick()
		assertEquals(AttemptState.DELIVERED, w.ledger.attempts("1").single().state)
		assertEquals(1, w.host.delivered.size)
	}

	@Test fun aYtDlpDownloadThatIsStillRunningIsNeverStalled() {
		val snap = DownloadSnapshot(Engine.M3U8, DOWNLOADING, isRunning = true, msSinceLastProgress = 600_000)
		assertEquals(org.websnake.vidchain.fallback.classifier.Verdict.InProgress, org.websnake.vidchain.fallback.classifier.TerminalClassifier.classify(snap))
		val gone = snap.copy(isRunning = false)
		assertTrue(org.websnake.vidchain.fallback.classifier.TerminalClassifier.classify(gone) is org.websnake.vidchain.fallback.classifier.Verdict.Failure)
		val paused = DownloadSnapshot(Engine.REGULAR, DOWNLOADING, userIntent = UserIntent.PAUSE, msSinceLastProgress = 600_000)
		assertTrue(org.websnake.vidchain.fallback.classifier.TerminalClassifier.classify(paused) is org.websnake.vidchain.fallback.classifier.Verdict.UserStopped)
	}
}
