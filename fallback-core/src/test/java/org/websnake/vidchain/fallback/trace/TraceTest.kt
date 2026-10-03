package org.websnake.vidchain.fallback.trace

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TraceTest {
	@get:Rule val tmp = TemporaryFolder()
	private val lines = mutableListOf<String>()
	private val sink = TraceSink { lines.add(it) }

	@Before fun setUp() { Trace.clearSinks(); Trace.addSink(sink); Trace.setEnabled(true) }
	@After fun tearDown() { Trace.clearSinks(); Trace.setEnabled(TraceConfig.DEFAULT_ENABLED) }

	@Test fun `master switch off - nothing built, nothing written, no file`() {
		Trace.setEnabled(false)
		val file = TraceFileSink(tmp.root.resolve("trace"))
		Trace.addSink(file)
		var built = false
		Trace.event { built = true; TraceEvent("decision") }
		Trace.emit(TraceEvent("decision"))
		assertFalse("message lambda must not run when off", built)
		assertTrue(lines.isEmpty())
		assertFalse("no trace file when off", file.current.exists())
	}

	@Test fun `on - one line per event with the stable fields`() {
		Trace.event { TraceEvent("attempt.end", downloadId = "42", chain = "B4", step = 3, method = "O", result = "failed",
			failureClass = "HTTP_OR_SERVER", reason = "HTTP 503", durationMs = 1234, timeMs = 0) }
		assertEquals(1, lines.size)
		assertEquals("1970-01-01T00:00:00.000Z event=attempt.end id=42 chain=B4 step=3 method=O result=failed class=HTTP_OR_SERVER dur=1234ms reason=\"HTTP 503\"", lines[0])
	}

	@Test fun `redaction - cookies, auth headers, bearer tokens, URL secrets never reach a sink`() {
		Trace.event { TraceEvent("decision", reason = "Cookie: sid=abc123; theme=dark Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.x.y " +
			"url=https://cdn.example.com/v.mp4?token=SECRET1&sig=SECRET2&quality=720 Set-Cookie: a=b") }
		val l = lines.single()
		for (secret in listOf("abc123", "eyJhbGciOiJIUzI1NiJ9", "SECRET1", "SECRET2", "a=b", "theme=dark"))
			assertFalse("leaked $secret in: $l", l.contains(secret))
		assertTrue(l.contains("https://cdn.example.com/v.mp4?token=<redacted>&sig=<redacted>&quality=<redacted>"))   // every URL value goes; the link stays readable
		assertTrue(l.contains("<redacted>"))
	}

	@Test fun `redaction - every string field, newlines removed, length capped`() {
		Trace.event { TraceEvent("x\ny", downloadId = "id?key=K1", chain = "B1", method = "m?session=S1", reason = "r".repeat(1000)) }
		val l = lines.single()
		assertFalse(l.contains("K1")); assertFalse(l.contains("S1")); assertFalse(l.contains("\n"))
		assertTrue(l.length < 600)
	}

	@Test fun `file sink rotates at the cap and keeps at most maxFiles files`() {
		val dir = tmp.root.resolve("trace")
		val file = TraceFileSink(dir, maxBytes = 200, maxFiles = 3)
		repeat(50) { file.write("line $it " + "x".repeat(40)) }
		val files = file.files()
		assertEquals(3, files.size)
		assertTrue(files.all { it.length() <= 200 })
		assertTrue(file.current.readText().contains("line 49"))
		assertFalse(files.any { it.readText().contains("line 0 ") })   // oldest rotated away
	}

	@Test fun `a failing sink never breaks the caller or the other sinks`() {
		Trace.addSink { throw IllegalStateException("disk full") }
		Trace.emit(TraceEvent("decision"))
		assertEquals(1, lines.size)
	}

	@Test fun `redaction - signed CDN links, fragments, userinfo, nested and JSON forms`() {
		for (s in listOf("URI=https://cdn.x/v.mp4?X-Amz-Security-Token=S1",
				"https://cdn.example/v/index.m3u8?hdnts=exp=1~acl=/*~hmac=S2: Server returned 403",
				"https://a.example/p#access_token=S3", "https://a.example/p;jsessionid=S4",
				"https://l.facebook.com/l.php?u=https%3A%2F%2Fcdn.x%2Fv.mp4%3Ftoken%3DS5", "https://user:S6@host.example/v.mp4",
				"{\"Cookie\":\"sid=S7\"}", "Cookie: a=1, sid=S8", "auth_token=S9 lsig=S10")) {
			val c = Redactor.clean(s)!!
			for (k in 1..10) assertFalse("leaked S$k in: $c", Regex("S$k\\b").containsMatchIn(c))
		}
		assertEquals(Redactor.clean("https://x.example/a?b=1"), Redactor.clean(Redactor.clean("https://x.example/a?b=1")))   // idempotent
	}
}
