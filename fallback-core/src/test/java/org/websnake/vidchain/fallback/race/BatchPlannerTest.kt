package org.websnake.vidchain.fallback.race

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.fallback.race.BatchPlanner.Action
import org.websnake.vidchain.fallback.race.BatchPlanner.Step
import org.websnake.vidchain.fallback.race.BatchPlanner.Weight.HEAVY
import org.websnake.vidchain.fallback.race.BatchPlanner.Weight.LIGHT

/** T9 decision 1: batches by weight, staggered start, cancel only after a proven success, losers killed (simulated time) */
class BatchPlannerTest {
	private fun starts(a: List<Action>) = a.filterIsInstance<Action.Start>().map { it.id }

	@Test fun aHeavyMethodNeverStartsBesideAnotherHeavyOne() {
		val p = BatchPlanner(listOf(Step("Y", HEAVY), Step("W", HEAVY), Step("H", LIGHT), Step("O", LIGHT)), startMs = 0)
		assertEquals(listOf("Y", "H", "O"), p.batchIds())        // W (a WebView) waits for the next batch
		var t = 0L
		val started = ArrayList<String>()
		repeat(5) { started += starts(p.tick(t)); t += 400 }
		assertEquals(listOf("Y", "H", "O"), started)
		val next = listOf("Y", "H", "O").flatMap { p.onEnded(it, t) }
		assertEquals(listOf("W"), p.batchIds())
		assertEquals(listOf("W"), starts(next))                     // the next batch starts as soon as this one ended
	}

	@Test fun upToFiveLightMethodsShareABatch() {
		val p = BatchPlanner(listOf("R", "L", "H", "O", "D", "G").map { Step(it, LIGHT) }, startMs = 0)
		assertEquals(listOf("R", "L", "H", "O", "D"), p.batchIds())
	}

	@Test fun startsAreStaggeredAndStopWhenACandidateArrives() {
		val p = BatchPlanner(listOf(Step("C", HEAVY), Step("H", LIGHT), Step("O", LIGHT)), startMs = 1_000)
		assertEquals(listOf("C"), starts(p.tick(1_000)))           // the best-ranked method starts alone
		assertEquals(emptyList<String>(), starts(p.tick(1_399)))
		assertEquals(listOf("H"), starts(p.tick(1_400)))           // the next one 400 ms later
		val a = p.onCandidate("C", 1_500)
		assertTrue(a.contains(Action.Probe("C")))
		assertEquals(emptyList<String>(), starts(p.tick(1_800)))   // O does not join while C's candidate is probed
	}

	@Test fun othersAreCancelledOnlyAfterAProvenSuccess() {
		val p = BatchPlanner(listOf(Step("A1", LIGHT), Step("A2", LIGHT), Step("A3", LIGHT)), startMs = 0)
		p.tick(0); p.tick(400); p.tick(800)
		p.onCandidate("A1", 900)
		// a decoy: A1's candidate is an HTML page; nothing is cancelled and the batch goes on
		val decoy = p.onProof("A1", proven = false, now = 1_000)
		assertTrue(decoy.none { it is Action.Cancel })
		assertEquals(listOf("A2", "A3"), p.running())
		p.onCandidate("A3", 1_100)
		val win = p.onProof("A3", proven = true, now = 1_200)
		assertEquals(Action.Win("A3"), win.first())
		assertEquals(listOf(Action.Cancel("A2", "another method won")), win.drop(1))
	}

	@Test fun aLoserThatIgnoresTheCancelIsKilled() {
		val p = BatchPlanner(listOf(Step("Y", HEAVY), Step("O", LIGHT)), BatchPlanner.Config(killGraceMs = 5_000), startMs = 0)
		p.tick(0); p.tick(400)
		p.onCandidate("O", 500)
		p.onProof("O", proven = true, now = 600)                    // Y is cancelled now
		assertEquals(emptyList<Action>(), p.reap(5_000))
		assertEquals(listOf(Action.Kill("Y")), p.reap(5_600))      // still running 5 s later: killed
		assertEquals(emptyList<Action>(), p.reap(9_000))
	}

	@Test fun aMethodPastItsLimitIsCancelledThenKilledAndTheNextBatchStarts() {
		val p = BatchPlanner(listOf(Step("W", HEAVY, limitMs = 10_000), Step("Y", HEAVY)), BatchPlanner.Config(killGraceMs = 2_000), startMs = 0)
		assertEquals(listOf("W"), starts(p.tick(0)))
		assertEquals(listOf(Action.Cancel("W", "time limit")), p.tick(10_000))
		val a = p.tick(12_000)
		assertEquals(Action.Kill("W"), a.first())
		assertEquals(listOf("Y"), starts(a))
	}

	@Test fun everythingFailedIsExhaustedOnce() {
		val p = BatchPlanner(listOf(Step("R", LIGHT), Step("O", LIGHT)), startMs = 0)
		p.tick(0); p.tick(400)
		p.onEnded("R", 500)
		val a = p.onEnded("O", 600)
		assertEquals(listOf(Action.Exhausted), a)
		assertEquals(emptyList<Action>(), p.tick(700))
		assertEquals(listOf(Action.Exhausted), BatchPlanner(emptyList(), startMs = 0).tick(0))
	}
}
