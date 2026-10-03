package org.websnake.vidchain.fallback.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class DeliveryVerifierTest {
	@get:Rule val tmp = TemporaryFolder()

	private fun box(type: String, payload: ByteArray = ByteArray(0), declared: Int? = null): ByteArray {
		val size = declared ?: (8 + payload.size)
		return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) + type.toByteArray() + payload
	}
	private fun pad(n: Int, fill: Int = 0x11) = ByteArray(n) { fill.toByte() }
	private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }
	private fun bytes(vararg parts: ByteArray) = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()

	private val mp4 = bytes(box("ftyp", "isom".toByteArray() + pad(4)), box("moov", pad(200)), box("mdat", pad(4000)))
	private val mp4MoovAtEnd = bytes(box("ftyp", "isom".toByteArray() + pad(4)), box("mdat", pad(4000)), box("moov", pad(200)))
	private val fmp4Segment = bytes(box("styp", "msdh".toByteArray() + pad(4)), box("moof", pad(100)), box("mdat", pad(4000)))
	private val fmp4Full = bytes(box("ftyp", "iso6".toByteArray() + pad(4)), box("moov", pad(100)), box("moof", pad(100)), box("mdat", pad(4000)))
	private val mp4Truncated = bytes(box("ftyp", "isom".toByteArray() + pad(4)), box("moov", pad(200)), box("mdat", pad(1000), declared = 900_000))
	private val mp4NoMoov = bytes(box("ftyp", "isom".toByteArray() + pad(4)), box("mdat", pad(4000)))
	private val mkv = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + pad(3000)
	private val ts = ByteArray(188 * 20) { if (it % 188 == 0) 0x47 else 0x10 }
	private val flv = "FLV".toByteArray() + byteArrayOf(1, 5) + pad(3000)
	private val ogg = "OggS".toByteArray() + pad(3000)
	private val mp3 = "ID3".toByteArray() + pad(3000)
	private val mpegAudio = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + pad(3000)
	private val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte()) + pad(3000)
	private val rawAac = byteArrayOf(0x21, 0x1B, 0x94.toByte(), 0x05) + ByteArray(3000) { (it * 31 + 7).toByte() }

	private val v = DeliveryVerifier()
	private val media = Expectation()

	private fun check(name: String, b: ByteArray, exp: Expectation = media, verifier: DeliveryVerifier = v) = verifier.verify(file(name, b), exp)

	@Test fun validContainersPass() {
		for ((name, b) in listOf("a.mp4" to mp4, "b.mp4" to mp4MoovAtEnd, "c.mp4" to fmp4Full, "d.mkv" to mkv, "e.ts" to ts, "f.flv" to flv,
				"g.ogg" to ogg, "h.mp3" to mp3, "i.mp3" to mpegAudio, "j.aac" to adts)) {
			val r = check(name, b)
			assertEquals("$name: ${r.reason}", Check.PASS, r.check)
		}
	}

	@Test fun trapsAreBounced() {
		val html = "\n  <!DOCTYPE html><html><head><title>Access denied</title></head><body>403</body></html>".toByteArray()
		val traps = listOf(
			"html.mp4" to html,
			"bomhtml.mp4" to byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "<html><body>x</body></html>".toByteArray(),
			"json.mp4" to """{"error":"expired","code":410}""".toByteArray(),
			"list.mp4" to "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:10,\nseg0.ts\n".toByteArray(),
			"mpd.mp4" to """<?xml version="1.0"?><MPD xmlns="urn:mpeg:dash:schema:mpd:2011"></MPD>""".toByteArray(),
			"text.mp4" to "Too many requests, slow down please. ".repeat(20).toByteArray(),
			"trunc.mp4" to mp4Truncated,
			"nomoov.mp4" to mp4NoMoov,
			"tiny.mp4" to bytes(box("ftyp", "isom".toByteArray() + pad(4)), box("moov", pad(100))),
			"image.mp4" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + pad(3000),
			"empty.mp4" to ByteArray(0),
		)
		for ((name, b) in traps) {
			val r = check(name, b)
			assertEquals("$name: ${r.reason}", Check.FAIL, r.check)
		}
	}

	@Test fun fragmentedSegmentAndRawAacAreUnsureNotFailed() {
		assertEquals(Check.UNSURE, check("seg.m4s", fmp4Segment).check)
		assertEquals(Check.UNSURE, check("raw.aac", rawAac).check)
	}

	@Test fun sizeAgainstExpected() {
		assertEquals(Check.FAIL, check("s1.mkv", mkv, Expectation(expectedBytes = mkv.size + 1L)).check)
		assertEquals(Check.PASS, check("s2.mkv", mkv, Expectation(expectedBytes = mkv.size.toLong())).check)
		assertEquals(Check.PASS, check("s3.mkv", mkv, Expectation(expectedBytes = 0)).check)   // unknown
	}

	@Test fun durationAgainstExpected() {
		val probe = DeliveryVerifier { 59_000L }
		assertEquals(Check.PASS, check("d1.mkv", mkv, Expectation(expectedDurationMs = 60_000), probe).check)
		assertEquals(Check.FAIL, check("d2.mkv", mkv, Expectation(expectedDurationMs = 120_000), probe).check)
		assertEquals(Check.UNSURE, check("d3.mkv", mkv, Expectation(expectedDurationMs = 60_000), DeliveryVerifier { null }).check)
		assertEquals(Check.UNSURE, check("d4.mkv", mkv, Expectation(expectedDurationMs = 60_000), DeliveryVerifier { throw IllegalStateException() }).check)
		assertEquals(Check.PASS, check("d5.mkv", mkv, media, DeliveryVerifier { null }).check)   // nothing expected: no duration needed
	}

	@Test fun nonMediaDownloadsAreJudgedByTheirOwnType() {
		val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + pad(2000)
		assertEquals(Check.PASS, check("a.zip", zip, Expectation(expectMedia = false, fileName = "a.zip")).check)
		assertEquals(Check.FAIL, check("b.zip", "<html><body>login</body></html>".toByteArray(), Expectation(expectMedia = false, fileName = "b.zip")).check)
		assertEquals(Check.PASS, check("c.html", "<html><body>page</body></html>".toByteArray(), Expectation(expectMedia = false, fileName = "c.html")).check)
		assertEquals(Check.FAIL, check("d.mp4", zip).check)   // media expected, got an archive
	}

	@Test fun earlySniffAbortsOnlyOnCertainTrash() {
		assertNotNull(v.early("<!doctype html><html>".padEnd(200, ' ').toByteArray(), media))
		assertNull(v.early(mp4.copyOf(512), media))
		assertNull(v.early(rawAac.copyOf(512), media))
		assertNull(v.early("<html>".toByteArray(), media))   // too little to judge
	}

	@Test fun commitIsAtomicAndNeverOverwrites() {
		val dir = tmp.newFolder("out")
		val existing = File(dir, "video.mp4").apply { writeText("old") }
		val t1 = File(dir, "video.mp4.part").apply { writeText("new1") }
		val f1 = DeliveryVerifier.commit(t1, File(dir, "video.mp4"))
		assertEquals("video (1).mp4", f1.name)
		assertEquals("old", existing.readText())
		assertEquals("new1", f1.readText())
		assertTrue(!t1.exists())
		val f2 = DeliveryVerifier.commit(File(dir, "x.part").apply { writeText("new2") }, File(dir, "video.mp4"))
		assertEquals("video (2).mp4", f2.name)
	}

	@Test fun sniffKinds() {
		assertEquals(FileKind.MP4, Sniffer.sniff(mp4))
		assertEquals(FileKind.MPEG_TS, Sniffer.sniff(ts))
		assertEquals(FileKind.AAC_ADTS, Sniffer.sniff(adts))
		assertEquals(FileKind.MPEG_AUDIO, Sniffer.sniff(mpegAudio))
		assertEquals(FileKind.UNKNOWN, Sniffer.sniff(rawAac))
		assertEquals(FileKind.MPD_MANIFEST, Sniffer.sniff("<?xml version='1.0'?><MPD>".toByteArray()))
		assertEquals(FileKind.EMPTY, Sniffer.sniff(ByteArray(0)))
	}
}
