package org.websnake.vidchain.fallback.race

import org.junit.Assert.assertEquals
import org.junit.Test
import org.websnake.vidchain.fallback.race.ProofRule.Verdict

/** T9 decision 1: a candidate wins only when its first bytes prove it */
class ProofRuleTest {
	private val mp4 = byteArrayOf(0, 0, 0, 0x20, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(),
		'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte()) + ByteArray(600)
	private val html = "<!doctype html><html><body>Sign in</body></html>".toByteArray()

	@Test fun mediaBytesProve() = assertEquals(Verdict.PROVEN, ProofRule.judge(206, "video/mp4", mp4, 9_000_000))
	@Test fun aDecoyPageDoesNot() = assertEquals(Verdict.NOT_MEDIA, ProofRule.judge(200, "video/mp4", html, 9_000_000))
	@Test fun anErrorStatusDoesNot() = assertEquals(Verdict.NOT_MEDIA, ProofRule.judge(403, "video/mp4", mp4, null))
	@Test fun aTinyFileDoesNot() = assertEquals(Verdict.NOT_MEDIA, ProofRule.judge(200, "video/mp4", mp4, 2_000))
	@Test fun aPlaylistProvesAnHlsCandidate() =
		assertEquals(Verdict.PROVEN, ProofRule.judge(200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(), null, "hls"))
	@Test fun aPlaylistDoesNotProveAFile() =
		assertEquals(Verdict.NOT_MEDIA, ProofRule.judge(200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-VERSION:3\n".toByteArray(), null, "file"))
	@Test fun unknownBinaryIsUnsure() = assertEquals(Verdict.UNSURE, ProofRule.judge(200, "application/octet-stream", ByteArray(2048) { 7 }, null))
}
