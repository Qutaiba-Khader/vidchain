package org.websnake.vidchain.fallback.classifier

import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.CLOSE
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.COMPLETE
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot.Companion.DOWNLOADING

/** Why a download is not (or no longer) running, as far as the fallback coordinator is concerned. */
enum class FailureClass {
	HTTP_OR_SERVER,          // non-2xx / server issue / generic "Download Failed"
	EXPIRED_URL,             // link expired (may also be "offline" for M3U8: upstream marks any probe exception expired)
	EXTRACTOR_LOGIN,         // yt-dlp: login required
	EXTRACTOR_UNAVAILABLE,   // yt-dlp: content not available / site banned in the area
	EXTRACTOR_FORMAT,        // yt-dlp: requested format not found
	EXTRACTOR_OTHER,         // yt-dlp: any other problem
	INVALID_URL,
	STALLED,                 // DOWNLOADING but no progress for too long (Regular: retries exhausted leave it like this)
	CRASH_OR_UNKNOWN,        // "Paused" with no recorded user intent = an error pause (M3U8 exception, process death)
	STORAGE,                 // the phone could not write the file: another download method does not help
}

/** The classifier's answer. [fallbackEligible] = the coordinator may start the next method. */
sealed class Verdict(val fallbackEligible: Boolean) {
	object InProgress : Verdict(false)
	object Success : Verdict(false)
	data class UserStopped(val intent: UserIntent) : Verdict(false)
	data class Waiting(val key: StatusKey) : Verdict(false)
	data class Failure(val cls: FailureClass) : Verdict(cls != FailureClass.STORAGE)
}

/**
 * Pure truth table (docs: fallback-core/CLASSIFIER.md). Order matters: the first matching row wins.
 * Built from the upstream status writes (T1.5 research) and the fault matrix (ci/golden/FAULT-MATRIX.md).
 */
object TerminalClassifier {

	/** Regular downloads whose retries ran out stay DOWNLOADING forever; after this long without progress they failed. */
	const val STALL_AFTER_MS = 120_000L

	fun classify(s: DownloadSnapshot): Verdict {
		// 1. user removed it: never fall back
		if (s.isDeleted || s.isRemoved || s.userIntent in REMOVAL) return Verdict.UserStopped(
			if (s.userIntent == UserIntent.NONE) UserIntent.DELETE else s.userIntent)
		// 2. finished (a bad current-method file only gets a badge, owner decision Q26)
		if (s.status == COMPLETE || s.isComplete) return Verdict.Success
		// 3. waiting for the network is not a failure of the method
		if (s.isWaitingForNetwork) return Verdict.Waiting(
			if (s.statusKey in WAITING) s.statusKey else StatusKey.WAITING_NETWORK)
		// 4. still downloading: in progress, unless it stopped moving (exhausted Regular retries look like this)
		if (s.status == DOWNLOADING) {
			val idle = s.msSinceLastProgress
			return if (idle != null && idle >= STALL_AFTER_MS) Verdict.Failure(FailureClass.STALLED) else Verdict.InProgress
		}
		// from here on: CLOSE (or an unknown status, treated like CLOSE)
		// 5. the user (or the app for the user) paused it: never fall back
		if (s.userIntent in PAUSES) return Verdict.UserStopped(s.userIntent)
		// 6. storage problems first: a storage error can also surface as "expired URL" (FileNotFoundException)
		if (s.isFailedToAccessFile || s.hasStorageDialogMessage || s.statusKey == StatusKey.FILE_IO_FAILED)
			return Verdict.Failure(FailureClass.STORAGE)
		if (s.isDestinationFileNotExisted || s.statusKey == StatusKey.FILE_DELETED)
			return Verdict.Failure(FailureClass.STORAGE)
		// 7. extractor problems carry their own reason
		if (s.isYtdlpHavingProblem) return Verdict.Failure(
			when (s.ytdlpProblem) {
				StatusKey.LOGIN_REQUIRED -> FailureClass.EXTRACTOR_LOGIN
				StatusKey.CONTENT_NOT_AVAILABLE, StatusKey.SITE_BANNED -> FailureClass.EXTRACTOR_UNAVAILABLE
				StatusKey.FORMAT_NOT_FOUND -> FailureClass.EXTRACTOR_FORMAT
				StatusKey.SERVER_PROBLEM, StatusKey.SERVER_ISSUE -> FailureClass.HTTP_OR_SERVER
				else -> FailureClass.EXTRACTOR_OTHER
			})
		// 8. expired link
		if (s.isFileUrlExpired || s.statusKey == StatusKey.LINK_EXPIRED) return Verdict.Failure(FailureClass.EXPIRED_URL)
		// 9. remaining status texts
		return Verdict.Failure(
			when (s.statusKey) {
				StatusKey.INVALID_URL -> FailureClass.INVALID_URL
				StatusKey.DOWNLOAD_FAILED, StatusKey.SERVER_ISSUE, StatusKey.SERVER_PROBLEM -> FailureClass.HTTP_OR_SERVER
				StatusKey.LOGIN_REQUIRED -> FailureClass.EXTRACTOR_LOGIN
				StatusKey.CONTENT_NOT_AVAILABLE, StatusKey.SITE_BANNED -> FailureClass.EXTRACTOR_UNAVAILABLE
				StatusKey.FORMAT_NOT_FOUND -> FailureClass.EXTRACTOR_FORMAT
				// "Paused" (or nothing) without a recorded user intent: an error pause or a process death
				else -> FailureClass.CRASH_OR_UNKNOWN
			})
	}

	private val REMOVAL = setOf(UserIntent.CANCEL, UserIntent.CLEAR, UserIntent.DELETE)
	private val PAUSES = setOf(UserIntent.PAUSE, UserIntent.RENAME_PAUSE, UserIntent.APP_SHUTDOWN)
	private val WAITING = setOf(StatusKey.WAITING_NETWORK, StatusKey.WAITING_WIFI, StatusKey.WAITING_INTERNET)
}
