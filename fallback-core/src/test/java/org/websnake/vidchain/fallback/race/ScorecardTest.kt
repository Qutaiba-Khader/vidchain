package org.websnake.vidchain.fallback.race

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.websnake.vidchain.fallback.race.Scorecard.Cause

/** T9 decision 1: the learned per-site order */
class ScorecardTest {
	private var now = 1_000_000L
	private val methods = listOf("Y", "W", "O")

	private fun card(seeds: Map<String, List<String>> = emptyMap()) = Scorecard(clock = { now }, seeds = seeds)

	@Test fun fastButFlakyLosesToSlowButReliable() {
		val s = card()
		// Y answers in 2 s but finds the video one time in three (about 7 s expected); W takes 5 s and always finds it
		repeat(4) { listOf(Cause.OK, Cause.EMPTY, Cause.EMPTY).forEach { c -> s.record("x.com", "Y", c, 2_000); now += 1 } }
		repeat(4) { s.record("x.com", "W", Cause.OK, 5_000); now += 1 }
		assertEquals(listOf("W", "Y", "O"), s.rank("x.com", methods))
	}

	@Test fun aRateLimitBurstPausesTheSiteAndBlamesNoMethod() {
		val s = card()
		repeat(6) { s.record("x.com", "Y", Cause.OK, 3_000); now += 1 }
		repeat(6) { s.record("x.com", "W", Cause.OK, 6_000); now += 1 }
		repeat(5) { s.record("x.com", "Y", Cause.RATE_LIMITED, 500); now += 1 }
		assertEquals(listOf("Y", "W", "O"), s.rank("x.com", methods))       // Y keeps its place
		assertTrue(s.pausedFor("x.com") > 0)
		now += Scorecard.PAUSE_MS
		assertEquals(0L, s.pausedFor("x.com"))
	}

	@Test fun threeFaultsInARowMoveAMethodBackUntilItsRestIsOver() {
		val s = card()
		repeat(4) { s.record("v.com", "Y", Cause.OK, 1_000); now += 1 }
		repeat(4) { s.record("v.com", "W", Cause.OK, 9_000); now += 1 }
		repeat(3) { s.record("v.com", "Y", Cause.NOT_UNDERSTOOD, 1_000); now += 1 }
		assertEquals(listOf("W", "O", "Y"), s.rank("v.com", methods))       // behind even the untried O
		now += 3 * 24 * 3_600_000L
		assertEquals("Y", s.rank("v.com", methods).first())                 // its new try, at its seed place
	}

	@Test fun theSeedRowDecidesUntilTheSiteHasSixResults() {
		val s = card(seeds = mapOf("tiktok.com" to listOf("O", "W", "Y")))
		assertEquals(listOf("O", "W", "Y"), s.rank("tiktok.com", methods))
		repeat(5) { s.record("tiktok.com", "Y", Cause.OK, 1_000); now += 1 }
		assertEquals(listOf("O", "W", "Y"), s.rank("tiktok.com", methods))  // 5 results: still the seed
		s.record("tiktok.com", "Y", Cause.OK, 1_000)
		assertEquals("Y", s.rank("tiktok.com", methods).first())            // the 6th: its own row takes over
	}

	@Test fun unknownSitesShareTheGenericRow() {
		val s = card()
		repeat(6) { s.record("a.example", "W", Cause.OK, 1_000); now += 1 }
		assertEquals("W", s.rank("b.example", methods).first())
	}

	@Test fun proofTimeIsTheP90OfSuccessesWithADefault() {
		val s = card()
		assertEquals(Scorecard.DEFAULT_PROOF_MS, s.proofTimeMs("x.com", "Y"))
		listOf(2_000L, 2_500L, 3_000L, 8_000L).forEach { s.record("x.com", "Y", Cause.OK, it) }
		assertEquals(8_000L, s.proofTimeMs("x.com", "Y"))      // T9 review: nearest-rank p90 (4 results: the slowest)
		listOf(2_000L, 2_100L, 2_200L, 2_300L, 2_400L, 2_500L).forEach { s.record("x.com", "Y", Cause.OK, it) }
		assertEquals(3_000L, s.proofTimeMs("x.com", "Y"))      // 10 results: the 9th of 10 (the one outlier is ignored)
	}

	@Test fun onlySiteMethodCauseAndTimeAreStored() {
		val s = card()
		s.record(Scorecard.siteOf("https://www.x.com/u/status/1?token=SECRET"), "Y", Cause.OK, 1_234)
		val json = s.toJson()
		assertTrue(json.contains("x.com") && !json.contains("SECRET") && !json.contains("status"))
		val t = card(); t.load(json)
		assertTrue(t.toJson().contains("1234") && t.toJson().contains("\"OK\""))
		assertEquals("x.com", Scorecard.siteOf("https://m.x.com/a"))
	}
}
