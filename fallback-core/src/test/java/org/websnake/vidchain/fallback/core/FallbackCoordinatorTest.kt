package org.websnake.vidchain.fallback.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FallbackCoordinatorTest {
	private val fileUrl = "https://files.example/video.mp4"   // B4: R, L, S, O, A, D

	private class FakeHost : FallbackHost {
		val list = LinkedHashMap<String, HostDownload>()
		val queued = ArrayList<Pair<String, Candidate>>()
		private var nextId = 1000
		override fun downloads() = list.values.toList()
		override fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String {
			val id = (nextId++).toString()
			queued += parent.id to candidate
			list[id] = HostDownload(id, candidate.url, candidate.url, DownloadSnapshot(Engine.REGULAR, CLOSE))
			return id
		}
		fun set(id: String, snap: DownloadSnapshot, bytes: Long = 0, url: String = list[id]?.url ?: "") {
			list[id] = HostDownload(id, url, url, snap, bytes)
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
		w.host.set(child2, DownloadSnapshot(Engine.REGULAR, COMPLETE, isComplete = true)); w.tick(); w.tick()
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
}
