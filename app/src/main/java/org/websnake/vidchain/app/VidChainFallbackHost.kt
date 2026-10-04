package org.websnake.vidchain.app

import app.core.AIOApp
import app.core.engines.downloader.DownloadDataModel
import com.aio.R
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.Engine
import org.websnake.vidchain.fallback.classifier.StatusKey
import org.websnake.vidchain.fallback.core.Candidate
import org.websnake.vidchain.fallback.core.FallbackHost
import org.websnake.vidchain.fallback.core.HostDownload
import android.media.MediaScannerConnection
import app.core.engines.downloader.DownloadStatus
import java.io.File
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSizeFormatter.humanReadableSizeOf
import java.net.URI
import java.util.Locale

/**
 * VidChain's adapter onto the upstream download system (new file, not upstream code). It only READS the upstream
 * download models and ADDS new downloads through the public DownloadSystem.addDownload; it never changes an existing one.
 * Called on the main thread (the coordinator's host context).
 */
object VidChainFallbackHost : FallbackHost {
	// created by the start seam (right after AIOApp.INSTANCE is set): the fresh-install defaults (T7.3)
	init { runCatching { VidChainDefaults.applyWhenReady(AIOApp.INSTANCE) } }


	override fun downloads(): List<HostDownload> {
		val system = AIOApp.downloadSystem
		val models = ArrayList(system.activeDownloadDataModels) + ArrayList(system.finishedDownloadDataModels)
		val keys = statusKeys()
		return models.map { toHost(it, keys) }
	}

	override fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String? {
		// hls / dash / magnet candidates are queued by their own methods (P2-P5); P1 only queues plain files
		if (candidate.kind != "file") return null
		val p = findModel(parent.id) ?: return null
		val child = DownloadDataModel()
		child.fileName = childName(candidate.fileName ?: p.fileName, attemptNo)
		child.fileURL = candidate.url
		child.fileDirectory = p.fileDirectory
		child.fileCategoryName = p.fileCategoryName
		child.siteReferrer = candidate.headers["Referer"] ?: p.siteReferrer
		// cookies only go back to the host they came from
		if (sameHost(candidate.url, p.fileURL)) child.siteCookieString = p.siteCookieString
		child.isUnknownFileSize = true
		child.globalSettings.downloadHttpUserAgent = candidate.headers["User-Agent"] ?: p.globalSettings.downloadHttpUserAgent
		child.additionalWebHeaders = candidate.headers.filterKeys { it !in RESERVED_HEADERS }.ifEmpty { null }
		AIOApp.downloadSystem.addDownload(child)
		return child.downloadId.toString()
	}

	/** ids handed out this session: a new model takes max(id on disk)+1, but its file is written in the background */
	private val issuedIds = HashSet<Int>()

	@Volatile private var sawLoading = false
	private val createdAt = android.os.SystemClock.elapsedRealtime()

	override fun ready(): Boolean {
		if (AIOApp.downloadSystem.isInitializing) { sawLoading = true; return false }
		return sawLoading || android.os.SystemClock.elapsedRealtime() - createdAt > 15_000
	}

	override fun refresh(downloadId: String) {
		val system = AIOApp.downloadSystem
		val m = ArrayList(system.activeDownloadDataModels).firstOrNull { it.downloadId.toString() == downloadId } ?: return
		system.downloadsUIManager.updateActiveUI(m)
	}

	override fun registerDelivered(parent: HostDownload, file: File, method: String): String? {
		val dir = file.parent ?: return null
		val p = findModel(parent.id)
		val now = System.currentTimeMillis()
		val m = DownloadDataModel()
		synchronized(issuedIds) {
			val taken = issuedIds + (ArrayList(AIOApp.downloadSystem.activeDownloadDataModels) + ArrayList(AIOApp.downloadSystem.finishedDownloadDataModels)).map { it.downloadId }
			while (m.downloadId in taken) m.downloadId++
			issuedIds += m.downloadId
		}
		m.fileName = file.name
		m.fileDirectory = dir
		m.fileCategoryName = p?.fileCategoryName.orEmpty()
		m.fileURL = parent.mediaUrl
		m.siteReferrer = p?.siteReferrer.orEmpty()
		m.fileMimeType = p?.fileMimeType.orEmpty()
		m.fileSize = file.length()
		m.fileSizeInFormat = humanReadableSizeOf(m.fileSize)
		m.isUnknownFileSize = false
		m.downloadedByte = m.fileSize
		m.startTimeDate = now
		m.startTimeDateInFormat = millisToDateTimeString(now)
		m.lastModifiedTimeDate = now
		m.lastModifiedTimeDateInFormat = millisToDateTimeString(now)
		m.status = DownloadStatus.COMPLETE
		m.isComplete = true
		m.isRunning = false
		m.statusInfo = AIOApp.INSTANCE.getString(R.string.title_completed)
		m.updateInStorage()
		val system = AIOApp.downloadSystem
		system.addAndSortFinishedDownloadDataModels(m)
		system.downloadsUIManager.finishedTasksFragment?.finishedTasksListAdapter?.notifyDataSetChangedOnSort(false)
		MediaScannerConnection.scanFile(AIOApp.INSTANCE, arrayOf(file.path), null, null)
		return m.downloadId.toString()
	}

	override fun session(parent: HostDownload, url: String): org.websnake.vidchain.fallback.context.SessionContext? {
		val p = findModel(parent.id) ?: return null
		val page = p.siteReferrer.takeIf { it.startsWith("http") }
		val cookies = ArrayList<org.websnake.vidchain.fallback.context.SessionCookie>()
		val store = runCatching { android.webkit.CookieManager.getInstance() }.getOrNull()
		for (u in listOfNotNull(url, page).distinct()) {
			val host = runCatching { URI(u).host }.getOrNull() ?: continue
			cookies += org.websnake.vidchain.fallback.context.SessionContext.parseHeader(store?.getCookie(u), host, secure = u.startsWith("https:", ignoreCase = true))
		}
		// cookies the app captured with the download belong to the page's host; they are used only for that host
		page?.let { runCatching { URI(it).host }.getOrNull() }?.let { pageHost ->
			val known = cookies.filter { it.host == pageHost.lowercase(Locale.ROOT) }.map { it.name }.toSet()
			cookies += org.websnake.vidchain.fallback.context.SessionContext.parseHeader(p.siteCookieString, pageHost, secure = page.startsWith("https:", ignoreCase = true)).filter { it.name !in known }
		}
		// the browser's UA when the session came from the browser (servers may tie a session to it)
		val ua = if (p.isDownloadFromBrowser || cookies.isNotEmpty()) AIOApp.aioSettings.browserHttpUserAgent.ifEmpty { null } else null
		return org.websnake.vidchain.fallback.context.SessionContext(cookies, page, ua ?: p.globalSettings.downloadHttpUserAgent.ifEmpty { null })
	}

	private fun findModel(id: String): DownloadDataModel? {
		val system = AIOApp.downloadSystem
		return (ArrayList(system.activeDownloadDataModels) + ArrayList(system.finishedDownloadDataModels))
			.firstOrNull { it.downloadId.toString() == id }
	}

	private fun toHost(m: DownloadDataModel, keys: StatusKeys): HostDownload {
		val viaExtractor = m.videoInfo != null && m.videoFormat != null
		val sourceUrl = m.videoInfo?.videoUrl?.takeIf { viaExtractor && it.isNotEmpty() } ?: m.fileURL
		return HostDownload(
			id = m.downloadId.toString(),
			url = sourceUrl,
			mediaUrl = m.fileURL,
			bytes = m.downloadedByte,
			referer = m.siteReferrer.ifEmpty { null },
			userAgent = m.globalSettings.downloadHttpUserAgent.ifEmpty { null },
			fileName = m.fileName.ifEmpty { null },
			// same path as DownloadDataModel.getDestinationFile(), built here so the observer does not trigger its log line
			filePath = if (m.fileName.isNotEmpty()) File(m.fileDirectory, m.fileName).path else null,
			expectedBytes = m.fileSize.takeIf { !m.isUnknownFileSize && it > 0 },
			expectedDurationMs = m.videoInfo?.videoDuration?.takeIf { viaExtractor && it > 0 },
			expectMedia = viaExtractor || isMediaName(m.fileName, m.fileMimeType),
			preferredHeight = m.videoFormat?.formatResolution?.let(::heightOf),
			audioOnly = m.videoFormat?.let { it.formatVcodec == "none" || it.formatResolution.contains("audio", ignoreCase = true) } ?: false,
			snapshot = DownloadSnapshot(
				engine = if (viaExtractor) Engine.M3U8 else Engine.REGULAR,
				status = m.status,
				isRunning = m.isRunning,
				isComplete = m.isComplete,
				isDeleted = m.isDeleted,
				isRemoved = m.isRemoved,
				isFileUrlExpired = m.isFileUrlExpired,
				isYtdlpHavingProblem = m.isYtdlpHavingProblem,
				ytdlpProblem = keys.of(m.ytdlpProblemMsg),
				isDestinationFileNotExisted = m.isDestinationFileNotExisted,
				isWaitingForNetwork = m.isWaitingForNetwork,
				isFailedToAccessFile = m.isFailedToAccessFile,
				statusKey = keys.of(m.statusInfo),
				hasStorageDialogMessage = m.msgToShowUserViaDialog.isNotEmpty(),
			),
		)
	}

	/** upstream stores localized status text only: map it back to a key with the current locale's strings */
	private class StatusKeys(val exact: Map<String, StatusKey>, val serverIssuePrefix: String, val serverIssueSuffix: String) {
		fun of(text: String?): StatusKey {
			val t = text?.trim().orEmpty()
			if (t.isEmpty() || t == "--") return StatusKey.NONE
			exact[t]?.let { return it }
			if (serverIssueSuffix.isNotEmpty() && t.startsWith(serverIssuePrefix) && t.endsWith(serverIssueSuffix)) return StatusKey.SERVER_ISSUE
			return StatusKey.OTHER
		}
	}

	@Volatile private var cached: Pair<Locale, StatusKeys>? = null

	private fun statusKeys(): StatusKeys {
		val ctx = AIOApp.INSTANCE
		val locale = ctx.resources.configuration.locales[0]
		cached?.let { if (it.first == locale) return it.second }
		val exact = linkedMapOf(
			R.string.title_paused to StatusKey.PAUSED,
			R.string.title_download_failed to StatusKey.DOWNLOAD_FAILED,
			R.string.title_download_io_failed to StatusKey.FILE_IO_FAILED,
			R.string.title_link_expired_paused to StatusKey.LINK_EXPIRED,
			R.string.title_file_deleted_paused to StatusKey.FILE_DELETED,
			R.string.title_failed_deleted_paused to StatusKey.FILE_DELETED,
			R.string.title_waiting_for_network to StatusKey.WAITING_NETWORK,
			R.string.title_waiting_for_wifi to StatusKey.WAITING_WIFI,
			R.string.title_waiting_for_internet to StatusKey.WAITING_INTERNET,
			R.string.title_paused_server_problem to StatusKey.SERVER_PROBLEM,
			R.string.title_paused_login_required to StatusKey.LOGIN_REQUIRED,
			R.string.title_paused_content_not_available to StatusKey.CONTENT_NOT_AVAILABLE,
			R.string.title_paused_ytdlp_format_not_found to StatusKey.FORMAT_NOT_FOUND,
			R.string.title_site_banned_in_your_area to StatusKey.SITE_BANNED,
			R.string.title_invalid_file_url to StatusKey.INVALID_URL,
			R.string.title_completed to StatusKey.COMPLETED,
			R.string.title_waiting_to_join to StatusKey.QUEUED,
		).entries.associate { (res, key) -> ctx.getString(res).trim() to key }
		val marker = "\u0000"
		val serverIssue = ctx.getString(R.string.title_server_issue, marker)
		val keys = StatusKeys(exact, serverIssue.substringBefore(marker), serverIssue.substringAfter(marker, ""))
		cached = locale to keys
		return keys
	}

	/** "1280x720" / "720p" / "720" -> 720 */
	private fun heightOf(resolution: String): Int? =
		Regex("(\\d{3,4})p\\b").find(resolution)?.groupValues?.get(1)?.toIntOrNull()
			?: Regex("\\d+x(\\d+)").find(resolution)?.groupValues?.get(1)?.toIntOrNull()

	/** audio / video by MIME type or extension; a name that says nothing counts as media (the app is a video downloader) */
	private fun isMediaName(name: String, mime: String): Boolean {
		val m = mime.lowercase(Locale.ROOT)
		if (m.startsWith("video/") || m.startsWith("audio/") || m == "application/vnd.apple.mpegurl" || m == "application/x-mpegurl") return true
		val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
		if (ext.isEmpty()) return m.isEmpty() || m == "application/octet-stream"
		return ext in MEDIA_EXT
	}

	private val MEDIA_EXT = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "flv", "3gp", "ts", "mts", "m2ts", "mpg", "mpeg", "wmv",
		"mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "wma")

	private fun childName(name: String, attemptNo: Int): String {
		val base = name.ifEmpty { "download" }
		val dot = base.lastIndexOf('.')
		return if (dot > 0) "${base.substring(0, dot)} (vidchain $attemptNo)${base.substring(dot)}" else "$base (vidchain $attemptNo)"
	}

	private fun sameHost(a: String, b: String): Boolean {
		val ha = runCatching { URI(a).host }.getOrNull() ?: return false
		val hb = runCatching { URI(b).host }.getOrNull() ?: return false
		return ha.equals(hb, ignoreCase = true)
	}

	private val RESERVED_HEADERS = setOf("Cookie", "Referer", "User-Agent")
}
