package org.websnake.vidchain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EngineUnitsTest {
	@Test fun exitCodeTable() {
		assertEquals(EngineOutcome.Success, ExitCodes.classify(0, false, false))
		assertEquals(EngineOutcome.TimedOut, ExitCodes.classify(137, cancelledByUs = false, timedOut = true))
		assertEquals(EngineOutcome.Cancelled, ExitCodes.classify(null, cancelledByUs = true, timedOut = false))
		assertEquals(EngineOutcome.OsKilled("signal 9"), ExitCodes.classify(137, false, false))
		assertEquals(EngineOutcome.OsKilled("signal 15"), ExitCodes.classify(143, false, false))
		assertTrue(ExitCodes.classify(null, false, false) is EngineOutcome.OsKilled)
		assertEquals(EngineOutcome.Crashed(11), ExitCodes.classify(139, false, false))
		assertEquals(EngineOutcome.Crashed(6), ExitCodes.classify(134, false, false))
		assertEquals(EngineOutcome.Unsupported("engine binary not found"), ExitCodes.classify(127, false, false))
		assertEquals(EngineOutcome.Failed(1, "ERROR: Unsupported URL"), ExitCodes.classify(1, false, false, "x\nERROR: Unsupported URL\n"))
		assertEquals(EngineOutcome.Failed(14, "exit 14"), ExitCodes.classify(ExitCodes.NETWORK, false, false))
	}

	@Test fun layoutMatchesTheLibrary() {
		val l = EngineLayout(File("/data/app/x/lib/arm64"), File("/data/user/0/p/no_backup"), File("/data/user/0/p/cache"))
		assertEquals("/data/app/x/lib/arm64/libpython.so", l.python.path)
		assertEquals("/data/app/x/lib/arm64/libffmpeg.so", l.ffmpeg.path)
		assertEquals("/data/user/0/p/no_backup/youtubedl-android/yt-dlp/yt-dlp", l.ytdlp.path)
		val env = l.env(systemPath = "/system/bin")
		assertEquals("/data/user/0/p/no_backup/youtubedl-android/packages/python/usr/lib:" +
			"/data/user/0/p/no_backup/youtubedl-android/packages/ffmpeg/usr/lib:" +
			"/data/user/0/p/no_backup/youtubedl-android/packages/aria2c/usr/lib", env["LD_LIBRARY_PATH"])
		assertEquals("/data/user/0/p/no_backup/youtubedl-android/packages/python/usr/etc/tls/cert.pem", env["SSL_CERT_FILE"])
		assertEquals("/data/user/0/p/no_backup/youtubedl-android/packages/python/usr", env["PYTHONHOME"])
		assertEquals(env["PYTHONHOME"], env["HOME"])
		assertEquals("/data/user/0/p/cache", env["TMPDIR"])
		assertEquals("/system/bin:/data/app/x/lib/arm64", env["PATH"])
		assertFalse("PYTHONPATH" in env)
		assertEquals("/z/engine.zip", l.env(extraPythonPath = listOf(File("/z/engine.zip")))["PYTHONPATH"])
		assertFalse(l.pythonReady)
	}

	@Test fun quarantineCountsOnlyCrashes() {
		var now = 0L
		val q = Quarantine(MemoryStore(), clock = { now }, threshold = 3, windowMs = 1_000, banMs = 10_000)
		repeat(5) { q.record("G", "h1", "arm64-v8a", EngineOutcome.OsKilled("signal 9")) }
		repeat(5) { q.record("G", "h1", "arm64-v8a", EngineOutcome.Failed(1, "x")) }
		assertFalse(q.isQuarantined("G", "h1", "arm64-v8a"))
		q.record("G", "h1", "arm64-v8a", EngineOutcome.Crashed(11)); now += 400
		q.record("G", "h1", "arm64-v8a", EngineOutcome.Crashed(11)); now += 400
		assertFalse(q.isQuarantined("G", "h1", "arm64-v8a"))
		q.record("G", "h1", "arm64-v8a", EngineOutcome.Crashed(11))
		assertTrue(q.isQuarantined("G", "h1", "arm64-v8a"))
		assertFalse(q.isQuarantined("G", "h2", "arm64-v8a"))     // new engine version starts clean
		assertFalse(q.isQuarantined("G", "h1", "x86_64"))
		now += 10_001
		assertFalse(q.isQuarantined("G", "h1", "arm64-v8a"))     // ban expired
	}

	@Test fun crashesOutsideTheWindowDoNotAddUp() {
		var now = 0L
		val q = Quarantine(MemoryStore(), clock = { now }, threshold = 3, windowMs = 1_000)
		repeat(3) { q.record("T", "h", "a", EngineOutcome.Crashed(6)); now += 600 }
		assertFalse(q.isQuarantined("T", "h", "a"))
		q.record("T", "h", "a", EngineOutcome.Success)
	}
}
