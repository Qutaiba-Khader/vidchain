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
	@Volatile private var front: WeakReference<BaseActivity>? = null
	private val tracking = AtomicBoolean(false)

	/** remembers the app's screen in front (the yt-dlp answer may arrive after the share screen closed) */
	private fun track(activity: BaseActivity) {
		if (!tracking.compareAndSet(false, true)) return
		activity.application.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
			override fun onActivityResumed(a: android.app.Activity) { if (a is BaseActivity) front = WeakReference(a) }
			override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
			override fun onActivityStarted(a: android.app.Activity) {}
			override fun onActivityPaused(a: android.app.Activity) {}
			override fun onActivityStopped(a: android.app.Activity) {}
			override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
			override fun onActivityDestroyed(a: android.app.Activity) {}
		})
	}

	/** the first http(s) link inside shared text; null when there is none or the text already is that link */
	@JvmStatic
	fun extractUrl(text: String?): String? = org.websnake.vidchain.fallback.context.ShareText.firstUrl(text)

	/**
	 * Seam share-entry: a share the app would reject as "Invalid URL" (it only takes a bare http(s) link). A magnet /
	 * torrent link runs chain B6; a text with a link in it is shared again as that link, so the app's own analysis runs
	 * on it. true = taken (the share screen closes). A valid link is never touched.
	 */
	@JvmStatic
	fun shared(activity: BaseActivity?, text: String?): Boolean = try {
		if (activity == null || text.isNullOrBlank() || lib.networks.URLUtility.isValidURL(text)) false
		else if (torrent(activity, text)) true
		else extractUrl(text)?.let { url ->
			activity.startActivity(android.content.Intent(activity, activity.javaClass).setAction(android.content.Intent.ACTION_SEND)
				.setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, url))
			true
		} ?: false
	} catch (t: Throwable) {
		false
	}

	/** Seam paste: the same for text pasted into the "download a link" dialog. true = taken (a torrent); else [replace] gets the link in it. */
	@JvmStatic
	fun pasted(activity: BaseActivity?, text: String?, replace: (String) -> Unit): Boolean = try {
		if (activity == null || text.isNullOrBlank() || lib.networks.URLUtility.isValidURL(text)) false
		else if (torrent(activity, text)) true
		else { extractUrl(text)?.let(replace); false }
	} catch (t: Throwable) {
		false
	}

	private fun aria2Ready(): Boolean =
		FallbackRuntime.engines?.let { org.websnake.vidchain.engine.aria2.Aria2Engine(it.runner, it.layout).ready } ?: false

	/** a shared magnet link or torrent/metalink file: VidChain runs chain B6 (aria2c) for it; true = taken */
	@JvmStatic
	fun torrent(activity: BaseActivity?, uri: String?): Boolean {
		try {
			val u = uri?.trim() ?: return false
			if (activity == null || UrlClass.of(u) != UrlClass.MAGNET_TORRENT) return false
			if (!FallbackSettings.enabled(activity) || !FallbackSettings.methodOn(activity, "A")) return false
			val c = FallbackRuntime.coordinator ?: return false
			if (!aria2Ready()) {
				// 32-bit phones carry no aria2c (Q8), and the first start unpacks it a moment after the app opens
				Toast.makeText(activity.applicationContext, "VidChain: torrents need aria2c, which is not available on this phone", Toast.LENGTH_LONG).show()
				return true
			}
			val dir = app.core.engines.downloader.DownloadDataModel().fileDirectory.takeIf { it.isNotEmpty() } ?: return false
			// the same torrent shared twice is one download (its info hash, else the link)
			val key = Regex("""(?i)btih:([a-z0-9]+)""").find(u)?.groupValues?.get(1)?.lowercase() ?: u
			val hash = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
			val root = org.websnake.vidchain.fallback.core.HostDownload(
				id = "share-$hash", url = u, mediaUrl = u,
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
			track(activity)
			val ref = WeakReference(activity)
			val sharedAt = System.currentTimeMillis()
			val known = downloadIds()
			Trace.event { TraceEvent("share.rescue", chain = UrlClass.of(url).chain, method = "Y", result = "start") }
			scope.launch {
				try {
					val found = withContext(Dispatchers.Default) { lookUp(engine, url, cookie) }
					// the app's own way (its browser offers the file) goes first: wait, and stay away if it started a download
					kotlinx.coroutines.delay((sharedAt + OWN_PATH_GRACE_MS - System.currentTimeMillis()).coerceAtLeast(0))
					if (ownDownloadStarted(url, known)) {
						Trace.event { TraceEvent("share.rescue", method = "Y", result = "not-needed", reason = "the app started its own download") }
						return@launch
					}
					// a share from another app has closed its screen by now: show the result on the app's screen in front
					val a = ref.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
						?: front?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@launch
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

	/** how long the app's own path (browser sniffing, its picker) has before VidChain offers yt-dlp's formats */
	private const val OWN_PATH_GRACE_MS = 30_000L      // 12 s was too short: a slow phone's browser offered a redirected file later

	private fun models() = AIOApp.downloadSystem.let { s -> ArrayList(s.activeDownloadDataModels) + ArrayList(s.finishedDownloadDataModels) }

	private fun downloadIds(): Set<Int> = runCatching { models().map { it.downloadId }.toSet() }.getOrDefault(emptySet())

	/**
	 * the app's own path worked: a download appeared since the share (its browser found the file, also after a
	 * redirect, when the file's URL is not the shared one), or the app already has one for this link
	 */
	private fun ownDownloadStarted(url: String, before: Set<Int>): Boolean = runCatching {
		models().any { m -> m.downloadId !in before || m.fileURL == url || m.siteReferrer == url || m.videoInfo?.videoUrl == url }
	}.getOrDefault(false)

	private suspend fun lookUp(engine: YtDlpEngine, url: String, cookie: String?): VideoInfo? {
		val host = runCatching { URI(url).host }.getOrNull()
		val cookieFile = if (!cookie.isNullOrBlank() && host != null) {
			SessionContext(SessionContext.parseHeader(cookie, host, secure = url.startsWith("https:", ignoreCase = true)), null, null)
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
