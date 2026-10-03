package org.websnake.vidchain.app

import android.widget.Toast
import app.core.AIOApp
import app.core.bases.BaseActivity
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoInfo
import app.ui.main.fragments.downloads.intercepter.VideoResolutionPicker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.websnake.vidchain.fallback.context.SessionContext
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.core.FallbackSettings
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.YtDlpFormats
import java.io.File
import java.lang.ref.WeakReference
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Method Y before a download exists (T2.4). When the app turns a shared link away (not on its site list, or its own
 * analysis found no formats), VidChain asks the library's yt-dlp - generic extractor included - and, if it finds a
 * video, opens the app's own resolution picker with those formats. The app's own reaction (toast, browser) is untouched.
 * New file inside the upstream source tree; called only from the FALLBACK-SEAM lines in SharedVideoURLIntercept.
 */
object VidChainShareRescue {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private val busy = AtomicBoolean(false)

	/** the first http(s) link inside shared text; null when there is none or the text already is that link */
	@JvmStatic
	fun extractUrl(text: String?): String? = org.websnake.vidchain.fallback.context.ShareText.firstUrl(text)

	/** a shared magnet link or torrent/metalink file: VidChain runs chain B6 (aria2c) for it; true = taken */
	@JvmStatic
	fun torrent(activity: BaseActivity?, uri: String?): Boolean {
		try {
			val u = uri?.trim() ?: return false
			if (activity == null || UrlClass.of(u) != UrlClass.MAGNET_TORRENT) return false
			if (!FallbackSettings.methodOn(activity, "A")) return false
			val c = FallbackRuntime.coordinator ?: return false
			val dir = app.core.engines.downloader.DownloadDataModel().fileDirectory.takeIf { it.isNotEmpty() } ?: return false
			val root = org.websnake.vidchain.fallback.core.HostDownload(
				id = "share-${System.currentTimeMillis()}", url = u, mediaUrl = u,
				snapshot = org.websnake.vidchain.fallback.classifier.DownloadSnapshot(org.websnake.vidchain.fallback.classifier.Engine.REGULAR, org.websnake.vidchain.fallback.classifier.DownloadSnapshot.CLOSE),
				filePath = File(dir, "torrent").path, expectMedia = false, keepNames = true,
			)
			val app = activity.applicationContext
			scope.launch {
				val started = withContext(Dispatchers.Default) { c.startStandalone(root) }
				Toast.makeText(app, if (started) "VidChain: downloading with aria2c - the files appear in Finished downloads" else "VidChain: this torrent is already downloading", Toast.LENGTH_LONG).show()
			}
			return true
		} catch (t: Throwable) {
			return false
		}
	}

	@JvmStatic
	fun rescue(activity: BaseActivity?, url: String?, cookie: String?) {
		try {
			if (url != null && UrlClass.of(url) == UrlClass.MAGNET_TORRENT) { torrent(activity, url); return }
			if (activity == null || url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return
			if (UrlClass.of(url) == UrlClass.YOUTUBE) return                       // YouTube has its own methods (C, P)
			if (!FallbackSettings.enabled(activity) || !FallbackSettings.methodOn(activity, "Y")) return
			val engine = FallbackRuntime.ytdlp ?: return
			if (!engine.ready) return
			if (!busy.compareAndSet(false, true)) return
			val ref = WeakReference(activity)
			Toast.makeText(activity.applicationContext, "VidChain: checking this link with yt-dlp…", Toast.LENGTH_SHORT).show()
			Trace.event { TraceEvent("share.rescue", chain = UrlClass.of(url).chain, method = "Y", result = "start") }
			scope.launch {
				try {
					val found = withContext(Dispatchers.Default) { lookUp(engine, url, cookie) }
					val a = ref.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@launch
					if (found == null || found.videoFormats.isEmpty()) {
						Toast.makeText(a.applicationContext, "VidChain: no downloadable video found on this page", Toast.LENGTH_SHORT).show()
						return@launch
					}
					VideoResolutionPicker(baseActivity = a, videoInfo = found).show()
				} catch (t: Throwable) {
					Trace.event { TraceEvent("share.rescue", method = "Y", result = "error", reason = "${t.javaClass.simpleName}: ${t.message}") }
				} finally {
					busy.set(false)
				}
			}
		} catch (t: Throwable) {
			busy.set(false)
		}
	}

	private suspend fun lookUp(engine: YtDlpEngine, url: String, cookie: String?): VideoInfo? {
		val host = runCatching { URI(url).host }.getOrNull()
		val cookieFile = if (!cookie.isNullOrBlank() && host != null) {
			SessionContext(SessionContext.parseHeader(cookie, host), null, null)
				.writeNetscape(File(AIOApp.INSTANCE.cacheDir, "vidchain-cookies/share-${System.nanoTime()}.txt"))
		} else null
		try {
			val options = YtDlpEngine.Options(userAgent = AIOApp.aioSettings.browserHttpUserAgent.ifEmpty { null }, cookiesFile = cookieFile)
			return when (val r = engine.info(url, options)) {
				is YtDlpEngine.Result.Ok -> {
					val info = r.value
					Trace.event { TraceEvent("share.rescue", method = "Y", result = "found", reason = "${info.formats.size} formats via ${info.extractor}") }
					VideoInfo(
						videoUrl = info.webpageUrl ?: url,
						videoTitle = info.title,
						videoThumbnailUrl = info.thumbnail,
						videoCookie = cookie,
						videoDuration = info.durationSec?.let { (it * 1000).toLong() } ?: 0L,
						videoFormats = info.formats.filter { it.hasVideo || it.hasAudio || (it.vcodec == null && it.acodec == null) }.map { f ->
							VideoFormat(
								formatId = f.id, formatExtension = f.ext, formatResolution = YtDlpFormats.resolution(f),
								formatFileSize = YtDlpFormats.size(f.fileSize), formatTBR = YtDlpFormats.tbr(f),
								formatVcodec = f.vcodec ?: "", formatAcodec = f.acodec ?: "", formatProtocol = f.protocol ?: "",
							)
						},
					)
				}
				is YtDlpEngine.Result.Failed -> {
					Trace.event { TraceEvent("share.rescue", method = "Y", result = "failed", reason = "${r.problem}: ${r.reason}") }
					null
				}
			}
		} finally {
			cookieFile?.delete()
		}
	}
}
