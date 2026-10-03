package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import java.io.File

/**
 * D - Android DownloadManager (T3.7), the last resort for one plain file: the phone's own downloader (its own
 * retries, network rules and notification). The file is copied next to the destination and DownloadManager's record
 * removed, then the coordinator verifies, commits and lists it like every other fallback file.
 */
class DownloadManagerMethod(
	private val system: () -> SystemDownloads?,
	private val pollMs: Long = 1_000,
	private val stallMs: Long = 10 * 60_000,
) : FallbackMethod {
	override val id = "D"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.mediaUrl.ifEmpty { ctx.url }.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) URL")
		if (ctx.urlClass == UrlClass.HLS_DASH || ctx.urlClass == UrlClass.MAGNET_TORRENT) return MethodOutcome.Unsupported("${ctx.urlClass.name}: not one file")
		val dm = system() ?: return MethodOutcome.Unsupported("DownloadManager not available")
		val temp = HttpFileExecutor.partialFile(ctx, id) ?: return MethodOutcome.Unsupported("destination folder unknown")
		// no cookies: DownloadManager would send them on to every redirect target
		val headers = buildMap { ctx.userAgent?.let { put("User-Agent", it) }; ctx.referer?.takeIf { it.startsWith("http") }?.let { put("Referer", it) } }
		val name = "${ctx.parentId}-$id-" + (ctx.fileName ?: "download").replace(Regex("""[\\/:*?"<>|]"""), "_")
		val dmId = withContext(Dispatchers.IO) { dm.enqueue(url, headers, name) }
		var lastBytes = -1L
		var lastMove = System.currentTimeMillis()
		try {
			while (true) {
				val s = withContext(Dispatchers.IO) { dm.status(dmId) }
				when (s.state) {
					SystemDownloads.State.SUCCESSFUL -> {
						val src = s.localFile?.takeIf { it.isFile } ?: return MethodOutcome.Failed("DownloadManager finished but its file is missing")
						withContext(Dispatchers.IO) { src.copyTo(temp, overwrite = true) }
						return MethodOutcome.Delivered(temp.path)
					}
					SystemDownloads.State.FAILED -> return MethodOutcome.Failed("DownloadManager: ${reason(s.reason)}")
					SystemDownloads.State.GONE -> return MethodOutcome.Failed("DownloadManager: the download was removed")
					else -> {
						if (s.bytes != lastBytes) { lastBytes = s.bytes; lastMove = System.currentTimeMillis() }
						else if (System.currentTimeMillis() - lastMove > stallMs) return MethodOutcome.Failed("DownloadManager: no progress for ${stallMs / 60_000} min (${s.state.name.lowercase()})")
					}
				}
				delay(pollMs)
			}
		} catch (c: CancellationException) {
			throw c
		} finally {
			withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { dm.remove(dmId) }   // record and its copy go; ours stays
		}
	}

	companion object {
		/** DownloadManager's COLUMN_REASON for failures (HTTP status codes pass through as themselves) */
		fun reason(code: Int): String = when (code) {
			in 300..599 -> "HTTP $code"
			1000 -> "unknown error"; 1001 -> "file error"; 1002 -> "unhandled HTTP code"; 1004 -> "HTTP data error"
			1005 -> "too many redirects"; 1006 -> "not enough space"; 1007 -> "storage not found"
			1008 -> "cannot resume"; 1009 -> "file already exists"
			else -> "reason $code"
		}
	}
}
