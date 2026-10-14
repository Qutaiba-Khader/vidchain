package org.websnake.vidchain.app

import app.core.AIOApp
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl
import org.websnake.vidchain.fallback.classifier.UserIntent
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.core.FallbackSettings
import org.websnake.vidchain.fallback.core.OwnPath
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent

/**
 * T9 decision 1: the original app's own paths are methods in the Methods list ([OwnPath]). This tells which one a
 * download uses and whether the user switched it off: then the app does not start its engine and VidChain's methods
 * take the download at once.
 */
object VidChainOwnPath {
	@JvmStatic
	fun of(m: DownloadDataModel): String =
		OwnPath.of(viaExtractor = m.videoInfo != null && m.videoFormat != null, social = runCatching { isSocialMediaUrl(m.fileURL) }.getOrDefault(false),
			fromBrowser = m.isDownloadFromBrowser)

	/** true = this download's own path is switched off: do not start the app's engine (VidChain takes it) */
	@JvmStatic
	fun skip(m: DownloadDataModel): Boolean {
		val id = of(m)
		if (runCatching { FallbackSettings.methodOn(AIOApp.INSTANCE, id) }.getOrDefault(true)) return false
		Trace.event { TraceEvent("own.skipped", downloadId = m.downloadId.toString(), method = id, reason = "switched off: VidChain's methods take it") }
		// the download counts as started (so its failure is VidChain's to handle), then ends as failed
		FallbackRuntime.recordIntent(m.downloadId.toString(), UserIntent.START)
		return true
	}
}
