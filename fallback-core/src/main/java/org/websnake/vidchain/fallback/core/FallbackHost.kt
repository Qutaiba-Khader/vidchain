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
)

/** The app side (VidChainFallbackHost): reads the upstream download lists and queues child downloads. Calls run on the coordinator's host context (main thread in the app). */
interface FallbackHost {
	fun downloads(): List<HostDownload>

	/** Queue [candidate] as a new download next to [parent] through the existing download system. Returns the child's id, or null when it could not be queued. */
	fun enqueueChild(parent: HostDownload, candidate: Candidate, attemptNo: Int, method: String): String?
}
