package org.websnake.vidchain.fallback.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner 2026-10-28: what a crash report may carry off the phone. */
class CrashReportingTest {
	@Test fun aReportCarriesNoSecretNoAddressAndNoPath() {
		val cause = IllegalStateException("cookie: sid=abc123; token=def456 for a@b.com")
		val t = java.io.IOException("GET https://cdn.example/v.mp4?sig=s3cr3t&exp=99 failed, file /storage/emulated/0/Download/My Video.mp4", cause)
		val r = CrashReporting.redact(t)
		val all = generateSequence(r) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
		for (secret in listOf("s3cr3t", "abc123", "def456", "a@b.com", "My Video")) assertFalse(all, secret in all)
		assertTrue(r.message!!.startsWith("java.io.IOException: "))
		assertTrue(r.cause!!.message!!.startsWith("java.lang.IllegalStateException: "))
		assertArrayEquals(t.stackTrace, r.stackTrace)                                   // grouping stays
	}

	@Test fun aCycleOrADeepChainNeverLoops() {
		val a = RuntimeException("a"); val b = RuntimeException("b", a)
		a.initCause(b)
		val r = CrashReporting.redact(a)
		assertEquals("java.lang.RuntimeException: a", r.message)
		var deep: Throwable = RuntimeException("0")
		repeat(50) { deep = RuntimeException("$it", deep) }
		assertTrue(generateSequence(CrashReporting.redact(deep)) { it.cause }.count() <= 9)
	}

	@Test fun aLinkKeepsOnlyItsHostAndAFileNameIsDropped() {
		// review run 34: a breadcrumb names neither the video's address nor its title
		val c = CrashReporting.clean("event=commit reason=\"My Holiday Video (1).mp4\" url=https://www.tiktok.com/@someone/video/123?x=1")
		for (leak in listOf("Holiday", "@someone", "video/123")) assertFalse(c, leak in c)
		assertTrue(c, "https://www.tiktok.com/<path>" in c)
		assertTrue(c, "<file>.mp4" in c)
		assertEquals("plain words stay", CrashReporting.clean("plain words stay"))
	}

	@Test fun aLogLineIsCleaned() {
		assertEquals("Authorization: <redacted>", CrashReporting.clean("Authorization: Bearer abcdef123456"))
		assertEquals("", CrashReporting.clean(null))
	}
}
