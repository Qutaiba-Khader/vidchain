package org.websnake.vidchain.fallback.methods

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import java.io.File

/** The phone's own downloader, reduced to what method D needs (an interface so its states can be tested). */
interface SystemDownloads {
	enum class State { PENDING, RUNNING, PAUSED, SUCCESSFUL, FAILED, GONE }
	data class Status(val state: State, val reason: Int = 0, val localFile: File? = null, val bytes: Long = 0, val total: Long = -1)

	fun enqueue(url: String, headers: Map<String, String>, fileName: String): Long
	fun status(id: Long): Status
	/** removes the record AND whatever DownloadManager wrote */
	fun remove(id: Long)
}

/** Android's DownloadManager writing into the app's own external files folder (no storage permission needed). */
class AndroidSystemDownloads(private val context: Context) : SystemDownloads {
	private val dm get() = context.getSystemService(DownloadManager::class.java)

	override fun enqueue(url: String, headers: Map<String, String>, fileName: String): Long {
		val r = DownloadManager.Request(Uri.parse(url))
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
			.setTitle(fileName)
			.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "vidchain-dm/$fileName")
		headers.forEach { (k, v) -> r.addRequestHeader(k, v) }
		return dm.enqueue(r)
	}

	override fun status(id: Long): SystemDownloads.Status {
		dm.query(DownloadManager.Query().setFilterById(id))?.use { c ->
			if (!c.moveToFirst()) return SystemDownloads.Status(SystemDownloads.State.GONE)
			fun col(n: String) = c.getColumnIndex(n)
			val state = when (c.getInt(col(DownloadManager.COLUMN_STATUS))) {
				DownloadManager.STATUS_PENDING -> SystemDownloads.State.PENDING
				DownloadManager.STATUS_RUNNING -> SystemDownloads.State.RUNNING
				DownloadManager.STATUS_PAUSED -> SystemDownloads.State.PAUSED
				DownloadManager.STATUS_SUCCESSFUL -> SystemDownloads.State.SUCCESSFUL
				else -> SystemDownloads.State.FAILED
			}
			val local = c.getString(col(DownloadManager.COLUMN_LOCAL_URI))?.let { Uri.parse(it).path }?.let(::File)
			return SystemDownloads.Status(state, c.getInt(col(DownloadManager.COLUMN_REASON)), local,
				c.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(col(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
		}
		return SystemDownloads.Status(SystemDownloads.State.GONE)
	}

	override fun remove(id: Long) { runCatching { dm.remove(id) } }
}
