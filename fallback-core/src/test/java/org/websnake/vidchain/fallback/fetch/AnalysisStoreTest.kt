package org.websnake.vidchain.fallback.fetch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** T9 decision 2: one analysis per link, reused by the download */
class AnalysisStoreTest {
	private var now = 1_000L
	private val dropped = ArrayList<String>()
	private fun store(cap: Int = 16) = AnalysisStore<String>(cap, ttlMs = 600_000, clock = { now }, onDrop = { dropped += it })
	private val k = AnalysisStore.Key("https://x.com/a/status/1", "c0")

	@Test fun manyCallersMakeOneAnalysis() = runBlocking {
		val s = store()
		var runs = 0
		val gate = CompletableDeferred<Unit>()
		val a = async { s.analyse(k) { runs++; gate.await(); "json-1" } }
		val b = async { s.analyse(k) { runs++; "json-2" } }
		val c = async { s.analyse(k) { runs++; "json-3" } }
		yield(); yield()
		gate.complete(Unit)
		assertEquals(listOf("json-1", "json-1", "json-1"), listOf(a.await(), b.await(), c.await()))
		assertEquals(1, runs)
		// and a later caller gets the kept record without a new analysis
		assertEquals("json-1", s.analyse(k) { runs++; "json-4" })
		assertEquals(1, runs)
	}

	@Test fun aCookieChangeForcesANewAnalysis() = runBlocking {
		val s = store()
		s.analyse(k) { "logged-out" }
		val loggedIn = k.copy(cookieGeneration = "c1")
		assertNull(s.get(loggedIn))
		assertEquals("logged-in", s.analyse(loggedIn) { "logged-in" })
		assertEquals("logged-out", s.get(k))
	}

	@Test fun anOldRecordIsDroppedWithItsFile() = runBlocking {
		val s = store()
		s.analyse(k) { "r1" }
		now += 600_000
		assertNull(s.get(k))
		assertEquals(listOf("r1"), dropped)
		assertEquals("r2", s.analyse(k) { "r2" })
	}

	@Test fun anExpiredRecordIsAnalysedAgainExactlyOnce() {
		val s = store()
		s.put(k, "r1")
		assertTrue(s.invalidate(k))          // first refusal: analyse again
		assertNull(s.get(k))
		s.put(k, "r2")
		assertFalse(s.invalidate(k))         // second refusal: the method race takes over
		assertEquals(listOf("r1", "r2"), dropped)
	}

	@Test fun capacityDropsTheLeastRecentlyUsed() {
		val s = store(cap = 2)
		val k2 = k.copy(url = "u2"); val k3 = k.copy(url = "u3")
		s.put(k, "a"); s.put(k2, "b")
		assertEquals("a", s.get(k))          // k used again: k2 is now the oldest
		s.put(k3, "c")
		assertNull(s.get(k2))
		assertSame("a", s.get(k))
		assertEquals(listOf("b"), dropped)
		assertEquals(2, s.size())
	}

	@Test fun aFailedAnalysisIsNotKept() = runBlocking {
		val s = store()
		assertNull(s.analyse(k) { null })
		assertNull(s.analyse(k) { throw IllegalStateException("site down") })
		assertEquals("ok", s.analyse(k) { "ok" })
	}
}
