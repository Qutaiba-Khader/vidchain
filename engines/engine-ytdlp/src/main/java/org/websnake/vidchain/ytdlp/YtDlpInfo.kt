package org.websnake.vidchain.ytdlp

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class YtDlpFormat(
	val id: String,
	val ext: String,
	val width: Int?,
	val height: Int?,
	val vcodec: String?,
	val acodec: String?,
	val tbrKbps: Double?,
	val fileSize: Long?,
	val protocol: String?,
	val url: String?,
	val note: String?,
) {
	val hasVideo get() = vcodec != null && vcodec != "none"
	val hasAudio get() = acodec != null && acodec != "none"
	val audioOnly get() = hasAudio && !hasVideo
}

/** The parts of `yt-dlp --dump-single-json` the fallbacks use. Never keeps http_headers or cookies. */
data class YtDlpInfo(
	val id: String?,
	val title: String?,
	val webpageUrl: String?,
	val extractor: String?,
	val durationSec: Double?,
	val thumbnail: String?,
	val formats: List<YtDlpFormat>,
	val directUrl: String?,           // a single-format result carries its URL at the top level
) {
	companion object {
		fun parse(json: String): YtDlpInfo {
			val o = JSONObject(json)
			// a playlist answer: take its first entry (we always pass --no-playlist, but generic pages may still answer so)
			val root = if (o.optString("_type") == "playlist") o.optJSONArray("entries")?.optJSONObject(0) ?: o else o
			val formats = root.optJSONArray("formats")?.let(::formats).orEmpty().ifEmpty {
				// no formats list: the info itself is the only format
				if (root.has("url")) listOf(format(root)) else emptyList()
			}
			return YtDlpInfo(
				id = root.str("id"), title = root.str("title"), webpageUrl = root.str("webpage_url"), extractor = root.str("extractor_key") ?: root.str("extractor"),
				durationSec = root.optDouble("duration").takeIf { !it.isNaN() && it > 0 }, thumbnail = root.str("thumbnail"),
				formats = formats, directUrl = root.str("url"),
			)
		}

		private fun formats(a: JSONArray) = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map(::format).filter { it.id.isNotEmpty() }

		private fun format(f: JSONObject) = YtDlpFormat(
			id = f.str("format_id") ?: "", ext = f.str("ext") ?: "", width = f.int("width"), height = f.int("height"),
			vcodec = f.str("vcodec"), acodec = f.str("acodec"), tbrKbps = f.optDouble("tbr").takeIf { !it.isNaN() },
			fileSize = (f.long("filesize") ?: f.long("filesize_approx")), protocol = f.str("protocol"), url = f.str("url"), note = f.str("format_note"),
		)

		private fun JSONObject.str(k: String) = if (has(k) && !isNull(k)) optString(k).takeIf { it.isNotEmpty() } else null
		private fun JSONObject.int(k: String) = if (has(k) && !isNull(k)) optInt(k).takeIf { it > 0 } else null
		private fun JSONObject.long(k: String) = if (has(k) && !isNull(k)) optLong(k).takeIf { it > 0 } else null
	}
}

/** Format selection and the strings the upstream picker shows (it parses `yt-dlp -F` tables into these). */
object YtDlpFormats {
	/** best video at or below [height] with the best audio; plain best when nothing fits */
	fun selector(height: Int?, audioOnly: Boolean): String = when {
		audioOnly -> "ba/b"
		height != null && height > 0 -> "bv*[height<=$height]+ba/b[height<=$height]/bv*+ba/b"
		else -> "bv*+ba/b"
	}

	fun resolution(f: YtDlpFormat): String = when {
		f.width != null && f.height != null -> "${f.width}x${f.height}"
		f.audioOnly -> "audio only"
		f.height != null -> "${f.height}p"
		else -> f.note ?: "unknown"
	}

	fun size(bytes: Long?): String = when {
		bytes == null -> ""
		bytes >= 1L shl 30 -> String.format(Locale.US, "%.2fGiB", bytes / (1L shl 30).toDouble())
		bytes >= 1L shl 20 -> String.format(Locale.US, "%.2fMiB", bytes / (1L shl 20).toDouble())
		else -> String.format(Locale.US, "%.2fKiB", bytes / 1024.0)
	}

	fun tbr(f: YtDlpFormat): String = f.tbrKbps?.let { "${it.toInt()}k" } ?: ""
}
