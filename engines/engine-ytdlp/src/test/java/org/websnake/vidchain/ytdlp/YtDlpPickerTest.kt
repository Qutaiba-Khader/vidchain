package org.websnake.vidchain.ytdlp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The quality picker's rows from yt-dlp's JSON (T7.1). */
class YtDlpPickerTest {
	private fun f(id: String, h: Int?, v: String?, a: String?, tbr: Double?, size: String) =
		"""{"format_id":"$id","ext":"mp4","height":${h ?: "null"},"vcodec":"${v ?: "none"}","acodec":"${a ?: "none"}","tbr":${tbr ?: "null"}$size}"""

	@Test fun sizesPerHeightOnlyWhatTheVideoHas() {
		val json = """{"id":"x","title":"t","duration":100,"formats":[
			${f("140", null, null, "mp4a", 128.0, ",\"filesize\":1600000")},
			${f("251", null, null, "opus", 160.0, ",\"filesize_approx\":2000000")},
			${f("137", 1080, "avc1", null, 4000.0, ",\"filesize\":50000000")},
			${f("248", 1080, "vp9", null, 3000.0, ",\"filesize\":40000000")},
			${f("136", 720, "avc1", null, 2000.0, ",\"filesize_approx\":25000000")},
			${f("18", 360, "avc1", "mp4a", 600.0, "")}
		]}"""
		val rows = YtDlpFormats.pickerRows(YtDlpInfo.parse(json))
		assertEquals(listOf("Audio", "1080p", "720p", "360p"), rows.map { it.label })   // no 2160p / 1440p / 480p / 240p / 144p
		val audio = rows[0]; assertEquals(2000000L, audio.bytes); assertTrue(audio.approximate)  // best audio (opus 160k), approx
		assertEquals(52000000L, rows[1].bytes); assertTrue(rows[1].approximate)                 // 50 MB exact + approx audio
		assertEquals(27000000L, rows[2].bytes); assertTrue(rows[2].approximate)
		assertEquals(7500000L, rows[3].bytes); assertTrue(rows[3].approximate)                  // 600k x 100 s / 8, has audio
	}

	@Test fun exactWhenEverythingIsExactAndNullWhenNothingIsKnown() {
		val exact = """{"id":"x","duration":10,"formats":[${f("a", null, null, "mp4a", 128.0, ",\"filesize\":100")},${f("v", 720, "avc1", null, 1000.0, ",\"filesize\":900")}]}"""
		val r = YtDlpFormats.pickerRows(YtDlpInfo.parse(exact))
		assertEquals(1000L, r[1].bytes); assertTrue(!r[1].approximate)
		val unknown = """{"id":"x","formats":[${f("v", 480, "avc1", "mp4a", null, "")}]}"""
		val u = YtDlpFormats.pickerRows(YtDlpInfo.parse(unknown))
		assertEquals(listOf("480p"), u.map { it.label }); assertNull(u[0].bytes)
	}

	@Test fun audioCodecForTheAppsPicker() {
		assertEquals("unknown", YtDlpFormats.appAcodec(null))   // Generic single file: download the format itself
		assertEquals("unknown", YtDlpFormats.appAcodec(""))
		assertEquals("", YtDlpFormats.appAcodec("none"))        // silent video: the app adds the best audio
		assertEquals("mp4a.40.2", YtDlpFormats.appAcodec("mp4a.40.2"))
	}
}
