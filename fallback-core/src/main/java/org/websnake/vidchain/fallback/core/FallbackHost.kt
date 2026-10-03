package org.websnake.vidchain.fallback.core

import org.websnake.vidchain.fallback.classifier.DownloadSnapshot

/** One upstream download as the coordinator sees it. [snapshot] carries the persisted state; the coordinator adds intent and idle time. */
data class HostDownload(
	val id: String,
	val url: String,                  // source URL (page / watch URL for extractor downloads, else the file URL)
	val mediaUrl: String,             // the URL the current method downloads
	val snapshot: DownloadSnapshot,
	val bytes: Long = 0,
	val referer: String? = null,
	val userAgent: String? = null,
	val fileName: String? = null,
	val filePath: String? = null,          // the download's destination file (fileDirectory/fileName), verified once it completes
	val expectedBytes: Long? = null,       // size the server announced, null when unknown
	val expectedDurationMs: Long? = null,  // from the extractor's metadata, null when unknown
	val expectMedia: Boolean = true,       // false for downloads that are not audio / video (zip, apk, pdf, ...)
	val preferredHeight: Int? = null,      // height of the format the user picked, when known
	val audioOnly: Boolean = false,
)

/** The app side (VidChainFallbackHost): reads the upstream download lists and queues child downloads. Calls run on the coordinator's host context (main thread in the app). */
interface FallbackHost {
	fun downloads(): List<HostDownload>

	/** Queue [candidate] as a new download next to [parent] through the existing download system. Returns the child's id, or null when it could not be queued. */
	fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String?

	/** A file an executor method produced and the coordinator committed: list it in the app's finished downloads. Returns its id. */
	fun registerDelivered(parent: HostDownload, file: java.io.File, method: String): String? = null

	/** The browsing session for [url] (method S): cookies from the in-app browser for that host, full page Referer, browser UA. */
	fun session(parent: HostDownload, url: String): org.websnake.vidchain.fallback.context.SessionContext? = null
}
