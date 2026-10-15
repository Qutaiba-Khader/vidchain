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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.websnake.vidchain.fallback.context.SessionContext
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.core.FallbackSettings
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.http.redirect.RedirectUnwrapper
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
	 * torrent link runs chain B6 and the share screen closes; a text with a link in it continues in the same share
	 * screen as that bare link, so the app's own analysis runs on it. true = handled here (the caller returns).
	 * A valid link is never touched.
	 */
	@JvmStatic
	fun shared(activity: BaseActivity?, text: String?): Boolean = try {
		if (activity == null || text.isNullOrBlank() || lib.networks.URLUtility.isValidURL(text)) false
		else if (torrent(activity, text)) { activity.onBackPressActivity(); true }
		else if (activity.intent?.action != android.content.Intent.ACTION_SEND) false     // the re-entry reads EXTRA_TEXT
		else extractUrl(text)?.takeIf { lib.networks.URLUtility.isValidURL(it) }?.let { url ->
			// T8.10: relaunching the share screen with the link (T8.9) lost it on a real phone (the new instance closed
			// without a trace); the same screen now goes on with the link in its intent
			activity.intent = android.content.Intent(activity.intent).putExtra(android.content.Intent.EXTRA_TEXT, url)
			activity.onAfterLayoutRender()
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
			if (!FallbackSettings.methodOn(activity, "A")) {
				// decision 10: aria2c is the only method for torrents; say so instead of doing nothing
				Toast.makeText(activity.applicationContext, org.websnake.vidchain.fallback.ui.TrailText.NO_METHOD, Toast.LENGTH_LONG).show()
				return true
			}
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
				val started = withContext(Dispatchers.Default) { c.startStandalone(root) } == org.websnake.vidchain.fallback.core.FallbackCoordinator.Start.STARTED
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
			// review run 25: with yt-dlp off or not ready the page chain still runs (it needs no yt-dlp)
			val engine = FallbackRuntime.ytdlp?.takeIf { it.ready && FallbackSettings.methodOn(activity, "Y") }
			if (!busy.compareAndSet(false, true)) return
			track(activity)
			val ref = WeakReference(activity)
			val sharedAt = System.currentTimeMillis()
			Trace.event { TraceEvent("share.rescue", chain = UrlClass.of(url).chain, method = "Y", result = "start") }
			scope.launch {
				try {
					// review run 28: the download lists loaded first (a cold start from a share), so old downloads are not "new"
					val loadedBy = System.currentTimeMillis() + 20_000
					while (runCatching { AIOApp.downloadSystem.isInitializing }.getOrDefault(false) && System.currentTimeMillis() < loadedBy)
						kotlinx.coroutines.delay(250)
					val known = downloadIds()
					// review run 28: never while yt-dlp is being replaced (its update waits for this share in turn)
					val updatedBy = System.currentTimeMillis() + 120_000
					while (VidChainYtDlpUpdate.updating() && System.currentTimeMillis() < updatedBy) kotlinx.coroutines.delay(1_000)
					// review run 28: where the link leads (a short link or a wrapper opens another site in the app's browser)
					val sites = async(Dispatchers.IO) { shareSites(url) }
					val tried = LinkedHashMap<String, String>()                   // what the page chain need not run again
					val found = engine?.let { e -> withContext(Dispatchers.Default) { lookUp(e, url, cookie, tried) } }
					// the app's own way (its browser offers the file) goes first: wait, and stay away if it started a download
					kotlinx.coroutines.delay((sharedAt + OWN_PATH_GRACE_MS - System.currentTimeMillis()).coerceAtLeast(0))
					val s = sites.await()
					if (withContext(Dispatchers.Default) { ownDownloadStarted(url, s, known, sharedAt) }) {   // review run 31: off the main thread
						Trace.event { TraceEvent("share.rescue", method = "Y", result = "not-needed", reason = "the app started its own download") }
						return@launch
					}

					// a share from another app has closed its screen by now: show the result on the app's screen in front
					val a = ref.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
						?: front?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return@launch
					if (found == null || found.videoFormats.isEmpty()) {
						// T9 (owner, 2026-10-15: "if there's a stream link, there's a download"): yt-dlp found nothing, so
						// VidChain's other methods look for the video on the page (its hidden browser W, as Web Video
						// Caster's in-page detection does, the page scan H, the other downloaders)
						if (!pageChain(a.applicationContext, url, tried))
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

	/**
	 * T9: VidChain's method chain on a shared page nothing else could read; the file appears in Finished downloads.
	 * The same link shared again while it runs is one chain (its id is the link's hash). false = could not start.
	 * Review run 28: returns once the chain is marked busy (the share's own busy flag holds until then, so yt-dlp's
	 * update never finds a gap between the two).
	 */
	private suspend fun pageChain(context: android.content.Context, url: String, tried: Map<String, String>): Boolean {
		val c = FallbackRuntime.coordinator ?: return false
		val dir = app.core.engines.downloader.DownloadDataModel().fileDirectory.takeIf { it.isNotEmpty() } ?: return false
		val id = shareId(url)
		val root = org.websnake.vidchain.fallback.core.HostDownload(
			id = id, url = url, mediaUrl = url,
			snapshot = org.websnake.vidchain.fallback.classifier.DownloadSnapshot(org.websnake.vidchain.fallback.classifier.Engine.REGULAR, org.websnake.vidchain.fallback.classifier.DownloadSnapshot.CLOSE),
			filePath = File(dir, "${shareFilePrefix(url)}.mp4").path, expectMedia = true,
		)
		Trace.event { TraceEvent("share.rescue", downloadId = id, chain = UrlClass.of(url).chain, result = "page-chain") }
		val start = withContext(Dispatchers.Default) { c.startStandalone(root, fresh = true, alreadyTried = tried) }
		Trace.event { TraceEvent("share.rescue", downloadId = id, result = "page-chain-${start.name.lowercase()}") }   // corpus run 5
		scope.launch {
			runCatching { FallbackRuntime.wake() }                            // its observer keeps the service while it runs
			Toast.makeText(context, when (start) {
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Start.STARTED -> "VidChain: looking for the video with its other methods - it will appear in Finished downloads"
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Start.ALREADY_DELIVERED -> "VidChain: this video is already in Finished downloads"
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Start.ALREADY_UNSURE -> "VidChain: a possible copy of this video is in Finished downloads - delete it and share again to look once more"
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Start.RUNNING -> "VidChain: already looking for this video"
			}, Toast.LENGTH_LONG).show()
			if (start != org.websnake.vidchain.fallback.core.FallbackCoordinator.Start.STARTED) return@launch
			var state = org.websnake.vidchain.fallback.core.FallbackCoordinator.Standalone.RUNNING
			val until = System.currentTimeMillis() + 30 * 60_000L
			while (state == org.websnake.vidchain.fallback.core.FallbackCoordinator.Standalone.RUNNING && System.currentTimeMillis() < until) {
				// (review runs 25-27: no "the app downloads it too" cancel any more: telling the chain's own files, a redirected
				// page and other shares apart reopened a defect each time; the start message says where the file appears)
				kotlinx.coroutines.delay(3_000)
				state = c.standalone(id)
			}
			val text = when (state) {
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Standalone.DELIVERED -> "VidChain: the video is in Finished downloads"
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Standalone.BEST_SO_FAR -> "VidChain: a possible copy of the video is in Finished downloads"
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Standalone.NOTHING -> "VidChain: no method found a video on this page"
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Standalone.DRM -> "VidChain: this video is DRM-protected (encrypted) - it cannot be saved"
				org.websnake.vidchain.fallback.core.FallbackCoordinator.Standalone.DRM_COPY -> "VidChain: this video seems DRM-protected (encrypted) - the copy in Finished downloads may not play"
				else -> null
			}
			text?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
		}
		return true
	}

	/** how long the app's own path (browser sniffing, its picker) has before VidChain offers yt-dlp's formats */
	private const val OWN_PATH_GRACE_MS = 30_000L      // 12 s was too short: a slow phone's browser offered a redirected file later

	private fun models() = AIOApp.downloadSystem.let { s -> ArrayList(s.activeDownloadDataModels) + ArrayList(s.finishedDownloadDataModels) }

	private fun downloadIds(): Set<Int> = runCatching { models().map { it.downloadId }.toSet() }.getOrDefault(emptySet())

	/** review run 26: a look-up or a page chain runs yt-dlp now (its update must wait) */
	@JvmStatic
	fun busy(): Boolean = busy.get()

	/** the site of a page: its host without www. / m. / mobile. */
	private fun site(u: String?): String? = runCatching { URI(u ?: return null).host?.lowercase() }.getOrNull()
		?.removePrefix("www.")?.removePrefix("m.")?.removePrefix("mobile.")

	/** the name a page chain gives the file of [url] (its host and the link's hash) */
	private fun shareFilePrefix(url: String): String {
		val hash = java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
		val host = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()?.replace(Regex("[^A-Za-z0-9.-]"), "_") ?: "video"
		return "$host-${hash.take(6)}"
	}

	/**
	 * review run 28: the sites [url] stands for - its own, the page yt-dlp reads, and every site its redirects pass
	 * through (a short link or a wrapper opens another site in the app's browser). Only the link's host is asked: no
	 * cookies, no other headers.
	 */
	private suspend fun shareSites(url: String): Set<String> {
		val out = LinkedHashSet<String>()
		listOf(url, VidChainAnalysis.normalise(url), RedirectUnwrapper.unwrapKnown(url)).forEach { u -> site(u)?.let { out += it } }
		val r = kotlinx.coroutines.withTimeoutOrNull(15_000) {
			runCatching { RedirectUnwrapper(FallbackRuntime.http).unwrap(url) }.getOrNull()
		}
		when (r) {
			is RedirectUnwrapper.Result.Unwrapped -> (r.hops + r.url).forEach { u -> site(u)?.let { out += it } }
			is RedirectUnwrapper.Result.Failed -> r.hops.forEach { u -> site(u)?.let { out += it } }
			else -> {}
		}
		return out
	}

	/**
	 * the app's own path worked: a download of this link (or of a page of one of its [sites], e.g. after a redirect)
	 * appeared since the share. Review runs 26-28: never "any new download" (a second link shared meanwhile is not
	 * this one), never a file VidChain listed itself (a page chain's, for this share or another: [VidChainFallbackHost.issued],
	 * the chain's ledger, its name), never a download older than the share.
	 */
	private fun ownDownloadStarted(url: String, sites: Set<String>, before: Set<Int>, sharedAt: Long): Boolean = runCatching {
		val prefix = shareFilePrefix(url)
		val chainFile = FallbackRuntime.coordinator?.chainFiles(shareId(url))
		fun vidChains(m: app.core.engines.downloader.DownloadDataModel) =
			VidChainFallbackHost.issued(m.downloadId) || m.fileName.startsWith(prefix) ||
				chainFile?.invoke(org.websnake.vidchain.fallback.core.HostDownload(m.downloadId.toString(), m.fileURL, m.fileURL,
					org.websnake.vidchain.fallback.classifier.DownloadSnapshot(org.websnake.vidchain.fallback.classifier.Engine.REGULAR, org.websnake.vidchain.fallback.classifier.DownloadSnapshot.CLOSE),
					filePath = if (m.fileName.isNotEmpty()) File(m.fileDirectory, m.fileName).path else null,
					startedAtMs = m.startTimeDate.takeIf { it > 0 })) == true
		models().any { m ->
			m.downloadId !in before && !vidChains(m) && (m.startTimeDate == 0L || m.startTimeDate >= sharedAt - 1_000) &&
				(m.fileURL == url || m.siteReferrer == url || m.videoInfo?.videoUrl == url ||
					site(m.siteReferrer) in sites || site(m.videoInfo?.videoUrl) in sites)
		} || models().any { m -> m.downloadId in before && (m.fileURL == url || m.videoInfo?.videoUrl == url) && !vidChains(m) }
	}.getOrDefault(false)

	/** the id of [url]'s page chain (the same link shared again is the same chain) */
	private fun shareId(url: String) =
		"share-" + java.security.MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

	private fun sameHost(a: String, b: String): Boolean {
		val ha = runCatching { URI(a).host?.lowercase() }.getOrNull() ?: return false
		return ha == runCatching { URI(b).host?.lowercase() }.getOrNull()
	}

	private suspend fun lookUp(engine: YtDlpEngine, url: String, cookie: String?, tried: MutableMap<String, String>): VideoInfo? {
		val host = runCatching { URI(url).host }.getOrNull()
		val cookieFile = if (!cookie.isNullOrBlank() && host != null) {
			SessionContext(SessionContext.parseHeader(cookie, host, secure = url.startsWith("https:", ignoreCase = true)), null, null)
				.writeNetscape(File(AIOApp.INSTANCE.cacheDir, "vidchain-cookies/share-${System.nanoTime()}.txt"))
		} else null
		try {
			// (the browser's User-Agent: the cookies may be tied to it; YtDlpEngine gives Facebook yt-dlp's own - T9)
			val options = YtDlpEngine.Options(userAgent = AIOApp.aioSettings.browserHttpUserAgent.ifEmpty { null }, cookiesFile = cookieFile)
			// review run 26: the address yt-dlp can read (vimeo.com/<id> -> its public player), as the app's analysis does
			val page = VidChainAnalysis.normalise(url)
			val pageCookie = cookie.takeIf { page == url }                  // review run 27: never another host's cookies
			val stable = engine.info(page, options)
			if (stable is YtDlpEngine.Result.Failed) tried["Y"] = "the share's look-up: ${stable.problem}"
			// T9 (corpus test): an out-of-date extractor -> the nightly yt-dlp (method N's) tries once
			val nightly = if (stable is YtDlpEngine.Result.Failed && (stable.problem == YtDlpEngine.Problem.FORMAT || stable.problem == YtDlpEngine.Problem.OTHER))
				runCatching { FallbackRuntime.nightlyYtdlp() }.getOrNull()?.info(page, options) else null
			if (nightly is YtDlpEngine.Result.Failed) tried["N"] = "the share's look-up: ${nightly.problem}"
			val r = nightly?.takeIf { it is YtDlpEngine.Result.Ok } ?: stable
			return when (r) {
				is YtDlpEngine.Result.Ok -> {
					val info = r.value
					Trace.event { TraceEvent("share.rescue", method = "Y", result = "found", reason = "${info.formats.size} formats via ${info.extractor}") }
					VideoInfo(
						videoUrl = info.webpageUrl ?: url,
						videoTitle = info.title,
						videoThumbnailUrl = info.thumbnail,
						videoCookie = pageCookie?.takeIf { sameHost(info.webpageUrl ?: url, url) },   // review run 28: nor the page yt-dlp names
						videoDuration = info.durationSec?.let { (it * 1000).toLong() } ?: 0L,
						videoFormats = info.formats.filter { it.hasVideo || it.hasAudio || (it.vcodec == null && it.acodec == null) }.map { f ->
							VideoFormat(
								formatId = f.id, formatExtension = f.ext, formatResolution = YtDlpFormats.resolution(f),
								formatFileSize = YtDlpFormats.size(f.fileSize), formatTBR = YtDlpFormats.tbr(f),
								formatVcodec = f.vcodec ?: "", formatAcodec = YtDlpFormats.appAcodec(f.acodec), formatProtocol = f.protocol ?: "",
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
