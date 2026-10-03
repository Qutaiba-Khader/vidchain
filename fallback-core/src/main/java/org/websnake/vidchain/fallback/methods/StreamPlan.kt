package org.websnake.vidchain.fallback.methods

/** What a stream source (NewPipe today) offers for one video, reduced to what method P needs. Pure, recordable. */
data class StreamSource(
	val title: String?,
	val durationMs: Long?,
	val progressive: List<Stream>,      // video + audio in one file
	val videoOnly: List<Stream>,
	val audio: List<Stream>,
	val hlsUrl: String?,
) {
	data class Stream(val url: String, val height: Int?, val bitrate: Int?, val ext: String?, val progressiveHttp: Boolean = true)
}

/** How P will get the file. */
sealed class StreamPlan {
	data class File(val stream: StreamSource.Stream) : StreamPlan()
	data class Join(val video: StreamSource.Stream, val audio: StreamSource.Stream) : StreamPlan()
	data class Hls(val url: String) : StreamPlan()
	object Nothing : StreamPlan()

	companion object {
		/**
		 * The user's resolution if available: a single progressive file when it reaches that height, otherwise the best
		 * video-only stream at or below it joined with the best audio (prefer MP4/M4A, they copy into MP4); HLS last.
		 */
		fun pick(s: StreamSource, height: Int?, audioOnly: Boolean): StreamPlan {
			val audio = s.audio.filter { it.progressiveHttp }.sortedWith(compareByDescending<StreamSource.Stream> { it.ext in MP4_AUDIO }.thenByDescending { it.bitrate ?: 0 })
			if (audioOnly) return audio.firstOrNull()?.let { File(it) } ?: s.hlsUrl?.let { Hls(it) } ?: Nothing
			fun fits(st: StreamSource.Stream) = height == null || (st.height ?: 0) <= height
			val prog = s.progressive.filter { it.progressiveHttp && fits(it) }.maxByOrNull { it.height ?: 0 }
			val video = s.videoOnly.filter { it.progressiveHttp && fits(it) }
				.sortedWith(compareByDescending<StreamSource.Stream> { it.height ?: 0 }.thenByDescending { it.ext in MP4_VIDEO }.thenByDescending { it.bitrate ?: 0 }).firstOrNull()
			val best = audio.firstOrNull()
			return when {
				video != null && best != null && (prog == null || (video.height ?: 0) > (prog.height ?: 0)) -> Join(video, best)
				prog != null -> File(prog)
				s.hlsUrl != null -> Hls(s.hlsUrl)
				s.progressive.isNotEmpty() -> File(s.progressive.filter { it.progressiveHttp }.minByOrNull { it.height ?: 0 } ?: return Nothing)
				else -> Nothing
			}
		}

		private val MP4_AUDIO = setOf("m4a", "mp4")
		private val MP4_VIDEO = setOf("mp4")
	}
}
