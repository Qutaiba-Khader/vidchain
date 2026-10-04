package org.websnake.vidchain.fallback.fixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoundedRetryTest {
	@Test fun alwaysFailingFetchIsBoundedWithBackoff() {
		val sleeps = ArrayList<Long>(); var calls = 0
		val r = BoundedRetry.run<String>(6, { sleeps += it }) { calls++; null to 503 }
		assertNull(r)
		assertEquals(4, calls)                         // asked for 6, capped at 4 (the original looped forever)
		assertEquals(listOf(500L, 1000L, 2000L), sleeps)
	}

	@Test fun notFoundStopsAtOnce() {
		var calls = 0
		assertNull(BoundedRetry.run<String>(4, {}) { calls++; null to 404 })
		assertEquals(1, calls)
	}

	@Test fun rateLimitAndTimeoutAreRetried() {
		var calls = 0
		assertEquals("ok", BoundedRetry.run(4, {}) { calls++; if (calls < 3) null to 429 else "ok" to 200 })
		assertEquals(3, calls)
		calls = 0
		assertNull(BoundedRetry.run<String>(2, {}) { calls++; null to 408 })
		assertEquals(2, calls)
	}

	@Test fun noRetryMeansOneAttempt() {
		var calls = 0
		BoundedRetry.run<String>(BoundedRetry.attempts(0), {}) { calls++; null to 0 }
		assertEquals(1, calls)
		assertEquals(4_000L, BoundedRetry.backoffMs(9))
	}
}
