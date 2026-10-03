package org.websnake.vidchain.fallback.verify

import java.io.File
import java.io.RandomAccessFile

/** Matroska / WebM: the EBML header, then the Segment element whose declared size must fit in the file. */
object Ebml {
	private const val SEGMENT = 0x18538067L

	/** true = the Segment declares more bytes than the file has (cut off); unknown sizes (live/streamed) are fine */
	fun segmentPastEnd(file: File): Boolean = runCatching {
		RandomAccessFile(file, "r").use { f ->
			val len = f.length()
			readId(f) ?: return false                          // EBML header id
			val (headerSize, _) = readSize(f) ?: return false
			f.seek(f.filePointer + headerSize)
			val id = readId(f) ?: return false
			if (id != SEGMENT) return false
			val (size, unknown) = readSize(f) ?: return false
			!unknown && f.filePointer + size > len
		}
	}.getOrDefault(false)

	private fun readId(f: RandomAccessFile): Long? {
		val first = f.read().takeIf { it >= 0 } ?: return null
		val n = Integer.numberOfLeadingZeros(first) - 24 + 1
		if (n !in 1..4) return null
		var v = first.toLong()
		repeat(n - 1) { v = (v shl 8) or (f.read().takeIf { it >= 0 } ?: return null).toLong() }
		return v
	}

	/** an EBML size vint: (value, all-ones "unknown size") */
	private fun readSize(f: RandomAccessFile): Pair<Long, Boolean>? {
		val first = f.read().takeIf { it >= 0 } ?: return null
		val n = Integer.numberOfLeadingZeros(first) - 24 + 1
		if (n !in 1..8) return null
		var v = (first and (0xFF shr n)).toLong()
		var allOnes = v == (0xFF shr n).toLong()
		repeat(n - 1) {
			val b = f.read().takeIf { it >= 0 } ?: return null
			v = (v shl 8) or b.toLong()
			allOnes = allOnes && b == 0xFF
		}
		return v to allOnes
	}
}
