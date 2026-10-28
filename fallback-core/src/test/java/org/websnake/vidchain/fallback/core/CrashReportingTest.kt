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

	@Test fun reviewRun35Cases() {
		// a long line is cleaned before it is shortened (the cut used to fall inside the title)
		val long = "event=failure reason=\"" + "x".repeat(280) + " My Long Holiday Video Title In Paris 2026 final.mp4\" class=NETWORK"
		val l = CrashReporting.clean(long)
		assertFalse(l, "Holiday" in l); assertTrue(l, "class=NETWORK" in l)
		// an unquoted phone path whose file name holds spaces
		val p = CrashReporting.clean("open failed: /storage/emulated/0/Download/My Holiday In Paris.mp4: ENOENT")
		assertFalse(p, "Holiday" in p || "Paris" in p); assertTrue(p, "ENOENT" in p)
		// other file kinds
		for (n in listOf("Lecture.pdf", "Holiday.jpg", "Movie.srt")) assertFalse(n, n.substringBefore('.') in CrashReporting.clean("reason=\"$n\""))
		// the error text of a reason survives (only the name goes)
		val m = CrashReporting.clean("reason=\"merge failed: output.mp4\"")
		assertTrue(m, "merge failed" in m); assertFalse(m, "output" in m)
		// links behind userinfo and links without a scheme keep only their host
		val u = CrashReporting.clean("from https://user:pw@cdn.example.com/a/My.Video/v.mp4 and www.tiktok.com/@someone/video/123")
		for (leak in listOf("My.Video", "@someone", "video/123", "pw")) assertFalse(u, leak in u)
		assertTrue(u, "www.tiktok.com/<path>" in u)
	}

	@Test fun aLogLineIsCleaned() {
		assertEquals("Authorization: <redacted>", CrashReporting.clean("Authorization: Bearer abcdef123456"))
		assertEquals("", CrashReporting.clean(null))
	}
}
