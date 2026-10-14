package org.websnake.vidchain.fallback.fixes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class SmallFixesTest {
	@Test fun hostMatchesTheDomainOrItsSubdomainsOnly() {
		assertTrue(HostMatch.isHost("x.com", "x.com"))
		assertTrue(HostMatch.isHost("mobile.x.com", "x.com"))
		assertTrue(HostMatch.isHost("WWW.X.COM.", "x.com"))
		assertFalse(HostMatch.isHost("dropbox.com", "x.com"))        // the carried issue: "dropbox.com" contains "x.com"
		assertFalse(HostMatch.isHost("reddit.com", "t.co"))           // and "reddit.com" contains "t.co"
		assertFalse(HostMatch.isHost("notx.com", "x.com"))
		assertFalse(HostMatch.isHost(null, "x.com"))
		assertTrue(HostMatch.isAnyHost("vm.tiktok.com", "tiktok.com", "x.com"))
	}

	@Test fun socketTimeoutIsPassedInSeconds() {
		assertEquals(30, YtDlpTimeout.seconds(30_000))                // was passed as 30000 s (about 8 hours)
		assertEquals(5, YtDlpTimeout.seconds(100))
		assertEquals(300, YtDlpTimeout.seconds(Int.MAX_VALUE / 2))
		assertEquals(2, (1500 + 999) / 1000)
	}

	@Test fun retryStopsOnceTheCallerStoppedTheWork() {
		var calls = 0
		var stopped = false
		val r = StoppableRetry.run(limit = 5, stopped = { stopped }, empty = { it: List<Int> -> it.isEmpty() }) { calls++; stopped = true; emptyList() }
		assertEquals(emptyList<Int>(), r)
		assertEquals(1, calls)
		calls = 0
		StoppableRetry.run(limit = 3, stopped = { false }, empty = { it: List<Int> -> it.isEmpty() }) { calls++; emptyList() }
		assertEquals(3, calls)
		calls = 0
		assertEquals(listOf(1), StoppableRetry.run(limit = 3, stopped = { false }, empty = { it: List<Int> -> it.isEmpty() }) { calls++; listOf(1) })
		assertEquals(1, calls)
	}

	@Test fun cancellationIsNotSwallowed() {
		CancelAware.rethrowCancel(IllegalStateException("plain"))
		try {
			CancelAware.rethrowCancel(CancellationException("stop"))
			fail("cancellation swallowed")
		} catch (e: CancellationException) {
			assertEquals("stop", e.message)
		}
	}
}
