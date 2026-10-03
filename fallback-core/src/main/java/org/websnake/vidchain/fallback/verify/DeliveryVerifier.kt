package org.websnake.vidchain.fallback.verify

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class Check { PASS, FAIL, UNSURE }

data class Delivery(val check: Check, val kind: FileKind, val reason: String, val durationMs: Long? = null)

/** What the fallback expected to get. Everything is optional; unknown values are not checked. */
data class Expectation(
	val expectMedia: Boolean = true,
	val expectedBytes: Long? = null,
	val expectedDurationMs: Long? = null,
	val fileName: String? = null,
)

/** Reads a media file's duration (Android: MediaExtractor). null = could not tell. */
fun interface DurationProbe {
	fun durationMs(file: File): Long?
}

/**
 * The delivery gate for files a FALLBACK produced (never the current method's success path).
 * PASS = keep it; FAIL = certainly not the wanted file (an HTML/JSON/playlist answer, empty, truncated, wrong container,
 * far too short); UNSURE = could not prove it either way (unknown container, no duration): the coordinator keeps it as
 * the best so far and keeps looking for a PASS.
 */
class DeliveryVerifier(private val probe: DurationProbe? = null) {

	/** Early abort while a fallback streams a file: FAIL as soon as the head proves it is not media. null = keep going. */
	fun early(head: ByteArray, exp: Expectation): Delivery? {
		if (head.size < 64) return null
		val kind = Sniffer.sniff(head)
		return if (kind.negative && !negativeExpected(kind, exp)) Delivery(Check.FAIL, kind, "early sniff: ${kind.name} instead of the file") else null
	}

	fun verify(file: File, exp: Expectation): Delivery {
		if (!file.isFile) return Delivery(Check.FAIL, FileKind.EMPTY, "file missing")
		val size = file.length()
		if (size == 0L) return Delivery(Check.FAIL, FileKind.EMPTY, "empty file")
		val head = ByteArray(minOf(size, Sniffer.HEAD_BYTES.toLong()).toInt())
		file.inputStream().use { input -> var n = 0; while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r } }
		val kind = Sniffer.sniff(head)

		if (kind.negative && !negativeExpected(kind, exp)) return Delivery(Check.FAIL, kind, "${kind.name} instead of the file")
		exp.expectedBytes?.takeIf { it > 0 }?.let { if (size < it) return Delivery(Check.FAIL, kind, "truncated: $size of $it bytes") }
		if (!exp.expectMedia) return Delivery(if (kind == FileKind.UNKNOWN) Check.UNSURE else Check.PASS, kind, "not media: size ok")
		if (!kind.media) {
			return if (kind == FileKind.UNKNOWN) Delivery(Check.UNSURE, kind, "unknown container (raw stream?)")
			else Delivery(Check.FAIL, kind, "${kind.name} is not a media file")
		}
		if (size < MIN_MEDIA_BYTES) return Delivery(Check.FAIL, kind, "media file too small ($size bytes)")
		if (kind == FileKind.MP4) {
			val boxes = Mp4Boxes.walk(file)
			if (boxes.garbage) return Delivery(Check.FAIL, kind, "MP4 box structure broken")
			if (boxes.truncated) return Delivery(Check.FAIL, kind, "MP4 truncated (box past end of file)")
			if ("moov" !in boxes.types) {
				// fragmented MP4 segments (styp/moof, no moov) are playable only with their init segment: cannot tell
				return if ("moof" in boxes.types) Delivery(Check.UNSURE, kind, "fragmented MP4 without moov")
				else Delivery(Check.FAIL, kind, "MP4 without moov (no index)")
			}
		}
		val duration = runCatching { probe?.durationMs(file) }.getOrNull()?.takeIf { it > 0 }
		val expected = exp.expectedDurationMs?.takeIf { it > 0 }
		if (expected != null) {
			if (duration == null) return Delivery(Check.UNSURE, kind, "duration unreadable", null)
			if (duration < expected * MIN_DURATION_SHARE) return Delivery(Check.FAIL, kind, "too short: ${duration}ms of ${expected}ms", duration)
		}
		return Delivery(Check.PASS, kind, "ok", duration)
	}

	private fun negativeExpected(kind: FileKind, exp: Expectation): Boolean {
		if (exp.expectMedia) return false
		val ext = exp.fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
		return when (kind) {
			FileKind.HTML -> ext in setOf("html", "htm")
			FileKind.JSON -> ext == "json"
			FileKind.XML -> ext in setOf("xml", "svg")
			FileKind.M3U8_PLAYLIST -> ext in setOf("m3u8", "m3u")
			FileKind.MPD_MANIFEST -> ext == "mpd"
			FileKind.TEXT -> ext in setOf("txt", "srt", "vtt", "csv", "md", "log")
			else -> false
		}
	}

	companion object {
		const val MIN_MEDIA_BYTES = 1024L
		const val MIN_DURATION_SHARE = 0.9

		/**
		 * Atomic commit: move a verified temp file to its final name in the same folder, never overwriting an existing
		 * file ("name (1).ext", ...). Returns the final file.
		 */
		fun commit(temp: File, dest: File): File {
			if (temp.absoluteFile == dest.absoluteFile) return temp
			val target = unique(dest)
			try {
				Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
			} catch (e: IOException) {
				// AtomicMoveNotSupportedException or another mount: rename, else copy and delete
				if (!temp.renameTo(target)) {
					temp.copyTo(target, overwrite = false)
					temp.delete()
				}
			}
			return target
		}

		fun unique(dest: File): File {
			if (!dest.exists()) return dest
			val name = dest.name
			val dot = name.lastIndexOf('.')
			val base = if (dot > 0) name.substring(0, dot) else name
			val ext = if (dot > 0) name.substring(dot) else ""
			var i = 1
			while (true) {
				val f = File(dest.parentFile, "$base ($i)$ext")
				if (!f.exists()) return f
				i++
			}
		}
	}
}
