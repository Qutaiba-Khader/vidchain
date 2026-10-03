package org.websnake.vidchain.fallback.verify

/** What the first bytes of a file say it is. */
enum class FileKind(val media: Boolean, val negative: Boolean = false) {
	MP4(true), MATROSKA(true), MPEG_TS(true), FLV(true), OGG(true), MP3_ID3(true), MPEG_AUDIO(true), AAC_ADTS(true),
	FLAC(true), WAV(true), AVI(true),
	JPEG(false), PNG(false), GIF(false), WEBP(false), ZIP(false), PDF(false), RAR(false), SEVEN_ZIP(false),
	HTML(false, negative = true), JSON(false, negative = true), XML(false, negative = true),
	M3U8_PLAYLIST(false, negative = true), MPD_MANIFEST(false, negative = true), TEXT(false, negative = true),
	EMPTY(false, negative = true), UNKNOWN(false),
}

/** Magic-byte sniffing of a file head (the first 4 KiB is plenty). Pure, no I/O. */
object Sniffer {
	const val HEAD_BYTES = 4096

	fun sniff(head: ByteArray): FileKind {
		if (head.isEmpty()) return FileKind.EMPTY
		binary(head)?.let { return it }
		return textual(head)
	}

	private fun binary(b: ByteArray): FileKind? {
		fun at(off: Int, vararg v: Int) = b.size >= off + v.size && v.indices.all { (b[off + it].toInt() and 0xFF) == v[it] }
		fun ascii(off: Int, s: String) = at(off, *s.map { it.code }.toIntArray())
		if (b.size >= 8 && (ascii(4, "ftyp") || ascii(4, "moov") || ascii(4, "styp") || ascii(4, "moof") ||
				(ascii(4, "mdat") || ascii(4, "free") || ascii(4, "wide") || ascii(4, "skip")) && boxSizeOk(b))) return FileKind.MP4
		if (at(0, 0x1A, 0x45, 0xDF, 0xA3)) return FileKind.MATROSKA
		if (isTs(b)) return FileKind.MPEG_TS
		if (ascii(0, "FLV") && at(3, 0x01)) return FileKind.FLV
		if (ascii(0, "OggS")) return FileKind.OGG
		if (ascii(0, "fLaC")) return FileKind.FLAC
		if (ascii(0, "RIFF") && ascii(8, "WAVE")) return FileKind.WAV
		if (ascii(0, "RIFF") && ascii(8, "AVI ")) return FileKind.AVI
		if (ascii(0, "RIFF") && ascii(8, "WEBP")) return FileKind.WEBP
		if (ascii(0, "ID3")) return FileKind.MP3_ID3
		if (at(0, 0xFF, 0xD8, 0xFF)) return FileKind.JPEG
		if (at(0, 0x89, 0x50, 0x4E, 0x47)) return FileKind.PNG
		if (ascii(0, "GIF8")) return FileKind.GIF
		if (at(0, 0x50, 0x4B, 0x03, 0x04)) return FileKind.ZIP
		if (ascii(0, "%PDF")) return FileKind.PDF
		if (ascii(0, "Rar!")) return FileKind.RAR
		if (at(0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return FileKind.SEVEN_ZIP
		if (b.size >= 2 && (b[0].toInt() and 0xFF) == 0xFF) {
			val b1 = b[1].toInt() and 0xFF
			if (b1 and 0xF6 == 0xF0) return FileKind.AAC_ADTS            // 1111 0xx0 / 1111 0xx1: ADTS (layer 00)
			if (b1 and 0xE0 == 0xE0 && b1 and 0x06 != 0) return FileKind.MPEG_AUDIO   // frame sync + a layer
		}
		return null
	}

	/** 0x47 sync byte every 188 bytes (also 192-byte M2TS packets): every packet start in the head, at least 2 */
	private fun isTs(b: ByteArray): Boolean {
		for ((start, step) in listOf(0 to 188, 4 to 192)) {
			val starts = (start until minOf(b.size, start + step * 8) step step).toList()
			if (starts.size >= 2 && starts.all { b[it].toInt() == 0x47 }) return true
		}
		return false
	}

	/** a top-level box header that looks sane (size 0, 1 = 64-bit, or >= 8) */
	private fun boxSizeOk(b: ByteArray): Boolean {
		val size = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
		return size == 0L || size == 1L || size >= 8
	}

	private fun textual(b: ByteArray): FileKind {
		var s = String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1)
		if (s.startsWith("ï»¿")) s = s.substring(3)
		val t = s.trimStart().lowercase()
		when {
			t.startsWith("#extm3u") -> return FileKind.M3U8_PLAYLIST
			t.startsWith("<!doctype html") || t.startsWith("<html") || t.startsWith("<head") || t.startsWith("<body") ||
				(t.startsWith("<") && t.contains("<html")) -> return FileKind.HTML
			t.startsWith("<?xml") || t.startsWith("<mpd") -> return if (t.contains("<mpd")) FileKind.MPD_MANIFEST else FileKind.XML
			t.startsWith("{") || t.startsWith("[") -> return FileKind.JSON
		}
		val sample = b.take(512)
		val printable = sample.count { val c = it.toInt() and 0xFF; c == 9 || c == 10 || c == 13 || c in 32..126 }
		return if (sample.isNotEmpty() && printable >= sample.size * 0.95) FileKind.TEXT else FileKind.UNKNOWN
	}
}
