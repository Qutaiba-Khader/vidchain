package org.websnake.vidchain.app

import android.webkit.CookieManager
import app.core.AIOApp
import app.core.engines.video_parser.parsers.SupportedURLs.filterYoutubeUrlWithoutPlaylist
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import lib.networks.DownloaderUtils.generateNetscapeFormattedCookieString
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.fetch.AnalysisStore
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.YtDlpInfo
import java.io.File
import java.security.MessageDigest

/**
 * T9 decision 2 (owner, 2026-10-14): one `yt-dlp -J` analysis per link, shared by the picker and the download.
 *
 * - The picker shows the formats, title and picture of this one run (it used to run `yt-dlp -F`, which has no title,
 *   then fetch the page twice more; YouTube also ran NewPipe and a second yt-dlp for the sizes).
 * - The download replays the kept JSON with `--load-info-json` (no hidden WebView page load, no second extraction).
 * - The kept JSON never holds a cookie ([YtDlpInfo.stripSecrets]); cookies are read fresh from the WebView cookie
 *   store when needed and written host-only, so they never go to another host.
 * - In memory for 10 minutes, at most 16 links (AnalysisStore); the JSON files live in the app's private cache and
 *   are deleted with their record. The trace says only how it went, never the JSON, a cookie or a header.
 */
object VidChainAnalysis {
	/** a kept analysis: the parsed info and the cookie-free JSON file for --load-info-json */
	class Record(val info: YtDlpInfo, val file: File, val replayable: Boolean)

	private const val ANALYSIS_MS = 60_000L
	/** yt-dlp's answers a newer extractor may fix (not a network, login or geo problem) */
	private val NIGHTLY_HELPS = setOf(YtDlpEngine.Problem.FORMAT, YtDlpEngine.Problem.OTHER)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
	private val store = AnalysisStore<Record>(onDrop = { runCatching { it.file.delete() } })

	/** review run 25: an analysis runs now */
	fun busy(): Boolean = store.busy()

	private fun dir(): File = File(AIOApp.INSTANCE.cacheDir, "vidchain-analysis").also { it.mkdirs() }

	private fun sha(s: String): String =
		MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)

	private fun cookiesFor(url: String): String? = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }

	/** the link as the analysis sees it (a YouTube playlist parameter removed) */
	fun normalise(url: String): String = if (isYouTubeUrl(url)) filterYoutubeUrlWithoutPlaylist(url) else url

	/** the store key: the link and a generation of its site's cookies (only a short hash of them, never the cookies) */
	fun key(url: String, cookie: String? = cookiesFor(url)): AnalysisStore.Key =
		AnalysisStore.Key(normalise(url), cookie?.let { sha(it).take(12) } ?: "-")

	/** a kept analysis of [url] that may be replayed now, or null ([cookie] as given to [analyse]) */
	fun recordFor(url: String, cookie: String? = null): Record? =
		store.get(key(url, cookie?.takeIf { it.isNotBlank() } ?: cookiesFor(normalise(url))))?.takeIf { it.replayable && it.file.isFile }

	/**
	 * the analysis of [url]: kept, joined while another caller runs it, or run now. [cookie] = the cookies the caller
	 * already holds for the page (a browser download); else the WebView cookie store's. null = yt-dlp is not ready or
	 * the site gave nothing.
	 */
	suspend fun analyse(url: String, cookie: String? = null, why: String = "picker"): Record? {
		// measured on the A50 (2026-10-14): for YouTube one -J run took 13.8 s (picker 15.3 s instead of 11.5 s) and
		// its replayed streams downloaded slower (20.5 s instead of 12.2 s); YouTube keeps the app's own path
		if (isYouTubeUrl(url)) return null
		val engine = FallbackRuntime.ytdlp?.takeIf { it.ready } ?: return null
		val page = normalise(url)
		val cookies = cookie?.takeIf { it.isNotBlank() } ?: cookiesFor(page)
		val k = key(page, cookies)
		return store.analyse(k) {
			val t0 = System.currentTimeMillis()
			val cookieFile = cookies?.let { c ->
				File(dir(), "c-${sha(page + System.nanoTime())}.txt").apply { writeText(generateNetscapeFormattedCookieString(c, page)) }
			}
			try {
				val ua = runCatching { AIOApp.aioSettings.downloadHttpUserAgent }.getOrNull()?.ifBlank { null }
				val options = YtDlpEngine.Options(userAgent = ua, cookiesFile = cookieFile)
				val stable = withTimeoutOrNull(ANALYSIS_MS) { engine.infoWithJson(page, options, timeoutMs = ANALYSIS_MS) }
				// T9 (corpus test): the stable yt-dlp could not read the page (an extractor out of date): the nightly one
				// tries with the time left (Dailymotion "No video formats found" with 2025.11.12)
				// review run 25: the share screen waits VidChainWaits.ANALYSIS_MS (40 s) for a picker; a nightly answer after
				// that is wasted, so a picker's analysis budgets on it (a download's on the full 60 s)
				val social = runCatching { app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl(page) }.getOrDefault(true)
				val budget = if (why == "download" || why == "social" || social) ANALYSIS_MS else VidChainWaits.ANALYSIS_MS - 2_000L
				val left = budget - (System.currentTimeMillis() - t0)
				val night = if (stable is YtDlpEngine.Result.Failed && stable.problem in NIGHTLY_HELPS && left > 15_000)
					runCatching { FallbackRuntime.nightlyYtdlp() }.getOrNull()
						?.let { e -> withTimeoutOrNull(left) { e.infoWithJson(page, options, timeoutMs = left) } }
						?.takeIf { it is YtDlpEngine.Result.Ok }
				else null
				val r = night ?: stable
				val via = if (night != null) " via the nightly yt-dlp" else ""
				when (r) {
					is YtDlpEngine.Result.Ok -> {
						val clean = YtDlpInfo.stripSecrets(r.value.second)
						val file = File(dir(), "${sha(k.url + k.cookieGeneration)}.info.json").apply { writeText(clean) }
						val rec = Record(r.value.first, file, YtDlpInfo.replayable(clean))
						Trace.event { TraceEvent("analysis", result = "ok", reason = "$why: ${rec.info.formats.size} formats, replayable=${rec.replayable}$via", durationMs = System.currentTimeMillis() - t0) }
						rec
					}
					is YtDlpEngine.Result.Failed -> {
						Trace.event { TraceEvent("analysis", result = "failed", reason = "$why: ${r.problem}", durationMs = System.currentTimeMillis() - t0) }
						null
					}
					null -> {
						Trace.event { TraceEvent("analysis", result = "timeout", reason = why, durationMs = System.currentTimeMillis() - t0) }
						null
					}
				}
			} finally {
				cookieFile?.delete()
			}
		}
	}

	/** start the analysis of [url] now (a share or a picker opened); the picker or the download joins it later */
	@JvmStatic
	fun prefetch(url: String?, cookie: String? = null) {
		val u = url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return
		scope.launch { runCatching { analyse(u, cookie, "prefetch") } }
	}

	/**
	 * the download of [url] was refused with its kept analysis (expired / 403 / 410): drop it. true = analyse once more;
	 * false = it was already analysed again once, so the fallback methods take over
	 */
	fun refused(url: String, cookie: String? = null): Boolean = store.invalidate(key(url, cookie?.takeIf { it.isNotBlank() } ?: cookiesFor(normalise(url)))).also {
		Trace.event { TraceEvent("analysis", result = "refused", reason = if (it) "analysing once more" else "second refusal") }
	}

	/**
	 * the picker rows of a kept analysis: for YouTube the app's own "720p" / "Audio" rows (its downloader picks the
	 * streams by height), each with its size; for other sites yt-dlp's formats, the way the `-F` table gave them
	 */
	fun pickerFormats(rec: Record, url: String): ArrayList<app.core.engines.video_parser.parsers.VideoFormat> {
		val unknown = AIOApp.INSTANCE.getString(com.aio.R.string.title_unknown)
		if (isYouTubeUrl(url)) {
			val pkg = AIOApp.INSTANCE.packageName
			return ArrayList(org.websnake.vidchain.ytdlp.YtDlpFormats.pickerRows(rec.info).map { row ->
				val size = row.bytes?.let { b -> (if (row.approximate) "≈ " else "") + lib.networks.DownloaderUtils.getHumanReadableFormat(b) } ?: unknown
				app.core.engines.video_parser.parsers.VideoFormat(formatId = pkg, formatResolution = row.label, formatFileSize = size)
			})
		}
		val fs = org.websnake.vidchain.ytdlp.YtDlpFormats
		return ArrayList(rec.info.formats.filter { it.hasVideo || it.hasAudio || (it.vcodec == null && it.acodec == null) }.map { f ->
			app.core.engines.video_parser.parsers.VideoFormat(
				formatId = f.id, formatExtension = f.ext, formatResolution = fs.resolution(f),
				formatFileSize = fs.size(f.fileSize), formatTBR = fs.tbr(f),
				formatVcodec = f.vcodec ?: "", formatAcodec = fs.appAcodec(f.acodec), formatProtocol = f.protocol ?: "",
			)
		})
	}

	/** yt-dlp's answer when an address it was given no longer works */
	private val REFUSED = Regex("""HTTP Error (403|410)|\b(403|410)\b.*(Forbidden|Gone)|expired|Unable to download video data|Requested format is not available""", RegexOption.IGNORE_CASE)

	fun isRefusal(output: String?): Boolean = output != null && REFUSED.containsMatchIn(output)
}
