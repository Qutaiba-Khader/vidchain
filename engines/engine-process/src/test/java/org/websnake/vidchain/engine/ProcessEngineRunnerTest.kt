package org.websnake.vidchain.engine

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Runs real processes through `setsid sh` on the build host (Linux), like the app does on Android. */
class ProcessEngineRunnerTest {
	private lateinit var launcher: Launcher
	private val sh get() = launcher.sh

	@Before fun setUp() {
		launcher = Launcher.detect()
		assumeTrue("setsid needed for group tests", launcher.setsid != null)
	}

	private fun runner(slots: SlotLimiter = SlotLimiter(4)) = ProcessEngineRunner(launcher, slots, killGraceMs = 1_000)
	private fun shell(script: String, timeoutMs: Long = 20_000) = EngineSpec(listOf(sh, "-c", script), timeoutMs = timeoutMs)
	private fun alive(pid: Int) = File("/proc/$pid").exists() && !File("/proc/$pid/stat").readText().substringAfterLast(')').trim().startsWith("Z")

	private suspend fun waitFor(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
		val end = System.currentTimeMillis() + timeoutMs
		while (System.currentTimeMillis() < end) { if (cond()) return true; delay(50) }
		return cond()
	}

	@Test fun successStreamsLinesAndKeepsTails() = runBlocking {
		val lines = Collections.synchronizedList(ArrayList<String>())
		val r = runner().run(shell("for i in 1 2 3; do echo out\$i; done; echo warn >&2"), onStdout = { lines += it })
		assertEquals(EngineOutcome.Success, r.outcome)
		assertEquals(0, r.exitCode)
		assertEquals(listOf("out1", "out2", "out3"), lines)       // the launcher's own marker lines never leak
		assertEquals(listOf("warn"), r.stderrTail)
	}

	@Test fun exitCodesAreClassified() = runBlocking {
		val r = runner()
		assertEquals(EngineOutcome.Unsupported("not for me"), r.run(shell("echo 'not for me' >&2; exit 10")).outcome)
		assertEquals(EngineOutcome.Failed(15, "HTTP Error 403"), r.run(shell("echo 'HTTP Error 403' >&2; exit 15")).outcome)
		assertTrue(r.run(EngineSpec(listOf("/nonexistent/libnothing.so"))).outcome is EngineOutcome.Unsupported)
	}

	@Test fun crashIsCrashedNotOsKilled() = runBlocking {
		val r = runner().run(shell("kill -SEGV \$\$"))
		assertEquals(EngineOutcome.Crashed(ExitCodes.SIGSEGV), r.outcome)
	}

	@Test fun cancelKillsTheWholeProcessGroup() = runBlocking {
		val pidFile = File.createTempFile("grandchild", ".pid").apply { deleteOnExit() }
		val job = launch(Dispatchers.Default) {
			runner().run(shell("sleep 300 & echo \$! > ${pidFile.path}; sleep 300; wait"))
		}
		assertTrue(waitFor { pidFile.length() > 0 })
		val grandchild = pidFile.readText().trim().toInt()
		assertTrue(alive(grandchild))
		job.cancelAndJoin()
		assertTrue("background grandchild must die with the group", waitFor { !alive(grandchild) })
	}

	@Test fun leftoverChildrenAreKilledAfterANormalExit() = runBlocking {
		val pidFile = File.createTempFile("orphan", ".pid").apply { deleteOnExit() }
		val r = runner().run(shell("sleep 300 >/dev/null 2>&1 & echo \$! > ${pidFile.path}; exit 0"))
		assertEquals(EngineOutcome.Success, r.outcome)
		assertTrue(waitFor { !alive(pidFile.readText().trim().toInt()) })
	}

	@Test fun killFromOutsideIsOsKilled() = runBlocking {
		val pidFile = File.createTempFile("engine", ".pid").apply { deleteOnExit() }
		// the "engine" writes its own pid, then something outside kills it with SIGKILL (like the phantom-process killer)
		val d = async(Dispatchers.Default) { runner().run(shell("echo \$\$ > ${pidFile.path}; exec sleep 300")) }
		assertTrue(waitFor { pidFile.length() > 0 })
		Runtime.getRuntime().exec(arrayOf("kill", "-9", pidFile.readText().trim())).waitFor()
		val r = d.await()
		assertEquals(EngineOutcome.OsKilled("signal 9"), r.outcome)
	}

	@Test fun wholeGroupKilledFromOutsideIsOsKilled() = runBlocking {
		val pidFile = File.createTempFile("group", ".pid").apply { deleteOnExit() }
		val d = async(Dispatchers.Default) { runner().run(shell("ps -o pgid= -p \$\$ > ${pidFile.path}; sleep 300")) }
		assertTrue(waitFor { pidFile.length() > 0 })
		Runtime.getRuntime().exec(arrayOf("kill", "-9", "-" + pidFile.readText().trim())).waitFor()
		assertTrue(d.await().outcome is EngineOutcome.OsKilled)
	}

	@Test fun timeoutKillsAndReportsTimedOut() = runBlocking {
		val t0 = System.currentTimeMillis()
		val r = runner().run(shell("sleep 300", timeoutMs = 500))
		assertEquals(EngineOutcome.TimedOut, r.outcome)
		assertTrue(System.currentTimeMillis() - t0 < 10_000)
	}

	@Test fun slotLimiterCapsConcurrentEngines() = runBlocking {
		val slots = SlotLimiter(2)
		val r = runner(slots)
		val now = AtomicInteger(); val peak = AtomicInteger()
		(1..5).map {
			async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
				slots.withSlot { peak.accumulateAndGet(now.incrementAndGet(), ::maxOf); delay(100); now.decrementAndGet() }
				r.run(shell("sleep 0.2"))
			}
		}.awaitAll().forEach { assertEquals(EngineOutcome.Success, it.outcome) }
		assertEquals(2, peak.get())
		assertEquals(2, slots.available)
	}

	@Test fun probeIsCachedPerEngineVersionButNotWhenTransient() = runBlocking {
		val store = MemoryStore()
		val counter = File.createTempFile("probe", ".n").apply { deleteOnExit() }
		val probe = CapabilityProbe(runner(), store)
		val spec = shell("echo x >> ${counter.path}; echo 3.12.4")
		assertEquals(Capability(true, "3.12.4"), probe.check("python", "v1", spec))
		assertEquals(Capability(true, "3.12.4"), probe.check("python", "v1", spec))
		assertEquals(1, counter.readLines().size)
		probe.check("python", "v2", spec)                          // new engine version: probed again
		assertEquals(2, counter.readLines().size)
		val failing = probe.check("ffmpeg", "v1", shell("exit 3"))
		assertFalse(failing.ok)
		probe.check("slow", "v1", shell("sleep 300", timeoutMs = 300))   // timed out: not cached
		assertEquals(null, store.get("probe.slow.v1"))
	}
}
