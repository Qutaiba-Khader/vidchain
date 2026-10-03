package org.websnake.vidchain.fallback.classifier

/** Which upstream engine ran the download (DownloadSystem picks one per model). */
enum class Engine { REGULAR, M3U8 }

/**
 * Locale-independent meaning of the upstream `statusInfo` / `ytdlpProblemMsg` text. The upstream code stores
 * localized text only; the app-side adapter (T1.6) maps it back to a key by comparing it with the string
 * resources of the current locale. Keys mirror res/values/strings_library.xml.
 */
enum class StatusKey {
	PAUSED, DOWNLOAD_FAILED, FILE_IO_FAILED, LINK_EXPIRED, FILE_DELETED,
	WAITING_NETWORK, WAITING_WIFI, WAITING_INTERNET,
	SERVER_PROBLEM, LOGIN_REQUIRED, CONTENT_NOT_AVAILABLE, FORMAT_NOT_FOUND, SITE_BANNED, SERVER_ISSUE,
	INVALID_URL, COMPLETED, OTHER, NONE,
}

/** What the user (or the app on the user's behalf) last asked for, recorded at the UI seams (T1.6). */
enum class UserIntent { NONE, START, RESUME, PAUSE, RENAME_PAUSE, APP_SHUTDOWN, CANCEL, CLEAR, DELETE }

/** The persisted upstream state of one download (DownloadDataModel fields) plus facts the observer adds. */
data class DownloadSnapshot(
	val engine: Engine,
	val status: Int,                       // upstream DownloadStatus: DOWNLOADING=2, CLOSE=3, COMPLETE=4
	val isRunning: Boolean = false,
	val isComplete: Boolean = false,
	val isDeleted: Boolean = false,
	val isRemoved: Boolean = false,
	val isFileUrlExpired: Boolean = false,
	val isYtdlpHavingProblem: Boolean = false,
	val ytdlpProblem: StatusKey = StatusKey.NONE,
	val isDestinationFileNotExisted: Boolean = false,
	val isWaitingForNetwork: Boolean = false,
	val isFailedToAccessFile: Boolean = false,
	val statusKey: StatusKey = StatusKey.NONE,
	val hasStorageDialogMessage: Boolean = false,   // msgToShowUserViaDialog set (only the "failed to write file" case)
	val userIntent: UserIntent = UserIntent.NONE,
	val msSinceLastProgress: Long? = null,          // observer: time since downloaded bytes last grew (null = unknown)
) {
	companion object {
		const val DOWNLOADING = 2
		const val CLOSE = 3
		const val COMPLETE = 4
	}
}
