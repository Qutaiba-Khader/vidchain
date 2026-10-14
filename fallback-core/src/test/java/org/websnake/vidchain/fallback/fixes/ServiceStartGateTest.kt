package org.websnake.vidchain.fallback.fixes

import org.junit.Assert.assertEquals
import org.junit.Test
import org.websnake.vidchain.fallback.fixes.ServiceStartGate.Answer.ASK
import org.websnake.vidchain.fallback.fixes.ServiceStartGate.Answer.CAP
import org.websnake.vidchain.fallback.fixes.ServiceStartGate.Answer.REFUSED_PAUSE
import org.websnake.vidchain.fallback.fixes.ServiceStartGate.Answer.TOO_SOON

/** T9 priority 0b: the foreground-service start loop can never come back (simulated wall clock) */
class ServiceStartGateTest {
	@Test fun aTightLoopGetsOneAskNotThousands() {
		val g = ServiceStartGate()
		var asks = 0
		var t = 1_000_000L
		repeat(4_105) { if (g.ask(t) == ASK) asks++; t += 2 }       // the bug report: 4,105 calls, 0-3 ms apart
		assertEquals(1, asks)
	}

	@Test fun oneAskPerGap() {
		val g = ServiceStartGate(minGapMs = 30_000)
		assertEquals(ASK, g.ask(0))
		assertEquals(TOO_SOON, g.ask(29_999))
		assertEquals(ASK, g.ask(30_000))
	}

	@Test fun aRefusalPausesUntilTheAppIsVisible() {
		val g = ServiceStartGate(minGapMs = 1_000, refusedPauseMs = 600_000)
		assertEquals(ASK, g.ask(0))
		g.refused(10)
		assertEquals(REFUSED_PAUSE, g.ask(5_000))
		assertEquals(REFUSED_PAUSE, g.ask(599_000))
		assertEquals(ASK, g.ask(600_010))
		g.refused(600_020)
		g.appVisible()                                              // Android resets the dataSync budget
		assertEquals(ASK, g.ask(600_030))
	}

	@Test fun theCapHoldsEvenWhenTheAppKeepsBecomingVisible() {
		val g = ServiceStartGate(minGapMs = 0, windowMs = 600_000, maxPerWindow = 6)
		var asks = 0
		for (t in 0L until 600_000L step 1_000) { g.appVisible(); if (g.ask(t) == ASK) asks++ }
		assertEquals(6, asks)
		assertEquals(CAP, g.ask(599_999))
		assertEquals(ASK, g.ask(600_000))                          // the next window
	}

	@Test fun aClockMovedBackCountsAsLongAgo() {
		val g = ServiceStartGate()
		assertEquals(ASK, g.ask(1_000_000))
		g.refused(1_000_000)
		assertEquals(ASK, g.ask(500_000))
	}

	@Test fun theStateSurvivesAProcessRestart() {
		val first = ServiceStartGate()
		first.ask(0); first.refused(5)
		val second = ServiceStartGate().apply { restore(first.state) }
		assertEquals(REFUSED_PAUSE, second.ask(100))
	}
}
