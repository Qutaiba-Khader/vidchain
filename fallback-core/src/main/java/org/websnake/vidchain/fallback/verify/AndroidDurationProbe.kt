package org.websnake.vidchain.fallback.verify

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** Duration from the container's track formats (longest track). null when no track declares one. */
object AndroidDurationProbe : DurationProbe {
	override fun durationMs(file: File): Long? {
		val ex = MediaExtractor()
		return try {
			ex.setDataSource(file.absolutePath)
			(0 until ex.trackCount).mapNotNull { i ->
				val f = ex.getTrackFormat(i)
				if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else null
			}.maxOrNull()
		} catch (e: Exception) {
			null
		} finally {
			ex.release()
		}
	}
}
