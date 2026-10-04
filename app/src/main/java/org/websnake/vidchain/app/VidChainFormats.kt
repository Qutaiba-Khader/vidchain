package org.websnake.vidchain.app

import app.core.AIOApp
import app.core.engines.video_parser.parsers.SupportedURLs.filterYoutubeUrlWithoutPlaylist
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.core.engines.video_parser.parsers.VideoFormat
import com.aio.R
import kotlinx.coroutines.withTimeoutOrNull
import lib.networks.DownloaderUtils.getHumanReadableFormat
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.YtDlpFormats

/**
 * T7.1, owner's phone test: for YouTube links the app lists formats through NewPipe; when NewPipe cannot read the
 * video it shows a fixed list of nine resolutions, every size "Unknown" (shown as "N/A"). Here the library's yt-dlp
 * fills in what this video really has: only its resolutions, each with its size (exact, or approximate marked "≈").
 * The rows keep the app's own format id and "720p" / "Audio" labels, so the app's own downloader is unchanged.
 * Called from one FALLBACK-SEAM line in SharedVideoURLIntercept; on any problem the list stays as the app made it.
 */
object VidChainFormats {
	private const val LOOKUP_MS = 45_000L

	@JvmStatic
	suspend fun refineYouTube(url: String, cookie: String?, formats: List<VideoFormat>) {
		try {
			if (!isYouTubeUrl(url)) return
			val unknown = AIOApp.INSTANCE.getString(R.string.title_unknown)
			if (formats.none { it.formatFileSize == unknown || it.formatFileSize.isBlank() }) return
			val list = formats as? MutableList<VideoFormat> ?: return
			val engine = FallbackRuntime.ytdlp?.takeIf { it.ready } ?: return
			val r = withTimeoutOrNull(LOOKUP_MS) { engine.info(filterYoutubeUrlWithoutPlaylist(url)) }
			val info = (r as? YtDlpEngine.Result.Ok)?.value ?: return
			val rows = YtDlpFormats.pickerRows(info).ifEmpty { return }
			fun text(row: YtDlpFormats.PickerRow): String {
				val bytes = row.bytes ?: return unknown
				return if (row.approximate) "≈ " + getHumanReadableFormat(bytes) else getHumanReadableFormat(bytes)
			}
			val pkg = AIOApp.INSTANCE.packageName
			if (list.all { it.formatFileSize == unknown || it.formatFileSize.isBlank() }) {
				// the app's fixed fallback list: replace it with what this video really has
				val fresh = rows.map { VideoFormat(formatId = pkg, formatResolution = it.label, formatFileSize = text(it)) }
				list.clear(); list.addAll(fresh)
			} else {
				// the app's own list from NewPipe: only fill the sizes it could not tell
				val byLabel = rows.associateBy { it.label }
				list.filter { it.formatFileSize == unknown || it.formatFileSize.isBlank() }
					.forEach { f -> byLabel[f.formatResolution]?.let { f.formatFileSize = text(it) } }
			}
			Trace.event { TraceEvent("picker.sizes", result = "filled", reason = "${rows.size} rows from yt-dlp") }
		} catch (e: kotlinx.coroutines.CancellationException) {
			throw e
		} catch (t: Throwable) {
			Trace.event { TraceEvent("picker.sizes", result = "error", reason = "${t.javaClass.simpleName}: ${t.message}") }
		}
	}
}
