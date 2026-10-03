package org.websnake.vidchain.fallback.verify

import java.io.File
import java.io.RandomAccessFile

/** Walks the top-level ISO-BMFF boxes (headers only; cheap even for large files). */
object Mp4Boxes {
	data class Result(val types: List<String>, val truncated: Boolean, val garbage: Boolean)

	fun walk(file: File, maxBoxes: Int = 10_000): Result {
		val types = ArrayList<String>()
		RandomAccessFile(file, "r").use { f ->
			val len = f.length()
			var pos = 0L
			val h = ByteArray(16)
			while (pos < len && types.size < maxBoxes) {
				if (len - pos < 8) return Result(types, truncated = true, garbage = false)
				f.seek(pos); f.readFully(h, 0, 8)
				var size = u32(h, 0)
				val type = String(h, 4, 4, Charsets.ISO_8859_1)
				if (!type.all { it.code in 32..126 }) return Result(types, truncated = false, garbage = true)
				var header = 8L
				if (size == 1L) {
					if (len - pos < 16) return Result(types, truncated = true, garbage = false)
					f.readFully(h, 8, 8); size = (u32(h, 8) shl 32) or u32(h, 12); header = 16
				} else if (size == 0L) {
					size = len - pos   // box runs to the end of the file
				}
				if (size < header) return Result(types, truncated = false, garbage = true)
				types += type
				if (pos + size > len) return Result(types, truncated = true, garbage = false)
				pos += size
			}
			return Result(types, truncated = false, garbage = false)
		}
	}

	private fun u32(b: ByteArray, o: Int) =
		((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}
