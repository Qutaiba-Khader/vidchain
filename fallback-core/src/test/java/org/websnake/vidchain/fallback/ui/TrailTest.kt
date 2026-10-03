package org.websnake.vidchain.fallback.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.fallback.core.ChainSpec
import org.websnake.vidchain.fallback.ledger.AttemptRow
import org.websnake.vidchain.fallback.ledger.AttemptState

class TrailTest {
	@Test fun cardTexts() {
		val t = Trail("1", "B4", 2, 4, "O", Trail.State.RUNNING)
		assertEquals("Fallback: trying Plain download (2/4)", TrailText.card(t))
		assertEquals("Fallback: saved by aria2c", TrailText.card(t.copy(method = "A", state = Trail.State.DELIVERED)))
		assertEquals("Fallback: no other method worked (4 tried)", TrailText.card(t.copy(state = Trail.State.EXHAUSTED)))
		assertTrue(TrailText.childCard("Y").contains("yt-dlp"))
	}

	@Test fun everyChainMethodHasAName() {
		for (steps in ChainSpec.chains.values) for (m in steps) assertTrue(m, TrailText.name(m) != m)
		assertTrue(ChainSpec.STUB in TrailText.ALL_METHODS)
		assertEquals(TrailText.ALL_METHODS.size, TrailText.ALL_METHODS.toSet().size)
	}

	@Test fun methodsTriedSheet() {
		assertEquals("No fallback method has run for this download yet.", TrailText.methodsTried(emptyList(), null))
		val rows = listOf(
			AttemptRow("1", 1, "STUB", AttemptState.FAILED, reason = "stub method: always fails by design", startedMs = 1000, endedMs = 1200),
			AttemptRow("1", 2, "O", AttemptState.UNSURE, childId = "9", reason = "verify UNSURE UNKNOWN: unknown container", startedMs = 2000, endedMs = 7000),
			AttemptRow("1", 3, "A", AttemptState.RUNNING, startedMs = 8000),
		)
		val text = TrailText.methodsTried(rows, Trail("1", "B4", 3, 4, "A", Trail.State.RUNNING))
		assertEquals("""
			Fallback: trying aria2c (3/4)

			1. Self-check stub — failed (0.2 s)
			   stub method: always fails by design
			2. Plain download — unsure (5.0 s)
			   verify UNSURE UNKNOWN: unknown container
			3. aria2c — running
		""".trimIndent(), text)
	}
}
