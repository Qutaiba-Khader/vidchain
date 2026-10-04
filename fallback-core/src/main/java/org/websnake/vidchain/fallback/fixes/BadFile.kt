package org.websnake.vidchain.fallback.fixes

import org.websnake.vidchain.fallback.verify.FileKind
import org.websnake.vidchain.fallback.verify.Sniffer
import java.io.File

/**
 * T8.7 b (inherited bug fix, owner INBOX #8): the original app's plain downloader called a download complete
 * without looking at what it saved: an HTML error page saved as .mp4, an empty file or a short file all ended as
 * "Completed", so nothing else could be tried. The fixed downloader asks this rule first; a bad file ends as
 * "Download Failed" (and VidChain's chain may start). Only files named as video or audio are judged, and only by
 * clear evidence (what the first bytes are, or fewer bytes than the server announced): an unusual but real media
 * file passes.
 */
object BadFile {
	private val MEDIA = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "3gp", "mpg", "mpeg", "wmv",
		"mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac", "wma")

	fun isMediaName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in MEDIA

	/** why [head] (the file's first bytes) of a file of [length] bytes named [name] is not the media it claims, or null */
	fun reason(name: String, head: ByteArray, length: Long, expectedLength: Long?): String? {
		if (!isMediaName(name)) return null
		if (length <= 0L) return "empty file"
		if (expectedLength != null && expectedLength > 0 && length < expectedLength) return "short file: $length of $expectedLength bytes"
		val kind = Sniffer.sniff(head)
		return if (kind.negative && kind != FileKind.EMPTY) "not media: ${kind.name.lowercase()}" else null
	}

	fun reason(file: File, expectedLength: Long?): String? {
		if (!isMediaName(file.name)) return null
		val length = if (file.exists()) file.length() else 0L
		val head = if (length > 0) file.inputStream().use { input -> ByteArray(4096).let { it.copyOf(maxOf(0, input.read(it))) } } else ByteArray(0)
		return reason(file.name, head, length, expectedLength)
	}
}
