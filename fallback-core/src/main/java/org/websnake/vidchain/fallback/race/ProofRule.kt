package org.websnake.vidchain.fallback.race

import org.websnake.vidchain.fallback.verify.FileKind
import org.websnake.vidchain.fallback.verify.Sniffer

/**
 * T9 decision 1: a method's candidate counts as a success only when its first bytes prove it (adhd 2026-10-14: a
 * 200 decoy page must neither cancel the method that would have worked nor spoil the order). Pure, JVM-tested.
 *
 * The probe is one ranged read of the first [PROBE_BYTES] with the candidate's own headers; this judges what came
 * back: the HTTP status, the content type, the first bytes and the announced size.
 */
object ProofRule {
	const val PROBE_BYTES = 65_536L
	/** a real video file is bigger than this; a smaller "file" is an error, a pixel or a placeholder */
	const val MIN_MEDIA_BYTES = 16_384L

	enum class Verdict { PROVEN, NOT_MEDIA, UNSURE }

	/**
	 * [kind] = the candidate's kind ("file", "hls", "dash", "magnet"); [total] = the full size when the server told it
	 * (Content-Range total or Content-Length of a 200), null when unknown.
	 */
	fun judge(status: Int, contentType: String?, head: ByteArray, total: Long?, kind: String = "file"): Verdict {
		if (kind == "magnet") return Verdict.UNSURE                      // nothing to probe over HTTP
		if (status !in 200..299) return Verdict.NOT_MEDIA
		val ct = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
		val sniffed = Sniffer.sniff(head)
		return when (kind) {
			"hls" -> if (sniffed == FileKind.M3U8_PLAYLIST || head.decodeToString().trimStart().startsWith("#EXTM3U")) Verdict.PROVEN
				else if (sniffed.negative) Verdict.NOT_MEDIA else Verdict.UNSURE
			"dash" -> if (sniffed == FileKind.MPD_MANIFEST || head.decodeToString().contains("<MPD")) Verdict.PROVEN
				else if (sniffed.negative) Verdict.NOT_MEDIA else Verdict.UNSURE
			else -> when {
				sniffed.negative -> Verdict.NOT_MEDIA                            // an HTML / JSON / text "file"
				total != null && total in 1 until MIN_MEDIA_BYTES -> Verdict.NOT_MEDIA
				sniffed.media -> Verdict.PROVEN
				ct.startsWith("text/") || ct.contains("html") || ct.contains("json") -> Verdict.NOT_MEDIA
				(ct.startsWith("video/") || ct.startsWith("audio/")) && head.size >= 512 -> Verdict.UNSURE  // a type we do not sniff
				else -> Verdict.UNSURE
			}
		}
	}
}
