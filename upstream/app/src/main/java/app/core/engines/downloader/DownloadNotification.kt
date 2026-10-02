package app.core.engines.downloader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.PendingIntent.getActivity
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat.Builder
import androidx.core.app.NotificationCompat.PRIORITY_LOW
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.DocumentFile.fromFile
import androidx.media3.common.util.UnstableApi
import app.core.AIOApp.Companion.INSTANCE
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_ID_KEY
import app.ui.main.MotherActivity
import app.ui.others.media_player.MediaPlayerActivity
import app.ui.others.media_player.MediaPlayerActivity.Companion.INTENT_EXTRA_MEDIA_FILE_PATH
import com.aio.R
import lib.files.FileSystemUtility.isAudio
import lib.files.FileSystemUtility.isVideo
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.io.File

/**
 * DownloadNotificationManager – Centralized Download Notification Handler
 *
 * Manages all download-related notifications, ensuring smooth user interaction
 * during and after downloads.
 *
 * Features:
 * - Real-time progress updates with percentage and speed
 * - Completion alerts with context-aware open/share actions
 * - Automatic notification channel setup for Android 8.0+
 * - Error, retry, and cancellation handling
 *
 * Behavior:
 * - Updates notifications dynamically for ongoing downloads
 * - Replaces progress with completion when finished
 * - Routes taps to the correct Activity or media viewer
 * - Honors user preferences for silent or disabled notifications
 *
 * Provides a consistent, safe, and responsive experience across devices.
 */

class DownloadNotification {

	/** Logger instance for tracking notification lifecycle and debugging */
	private val logger = LogHelperUtils.from(javaClass)

	/** NotificationManager instance for system notification operations */
	private lateinit var notificationManager: NotificationManager

	/**
	 * Initializes the DownloadNotification system.
	 * Creates the required notification channel for Android 8.0+ during initialization.
	 */
	init {
		logger.d("Initializing DownloadNotification → creating notification channel")
		createNotificationChannelForSystem()
	}

	/**
	 * Updates or cancels the notification based on download state.
	 * This method serves as the main entry point for notification management,
	 * handling suppression, cancellation, and progress updates based on the
	 * current download state and user preferences.
	 *
	 * @param downloadDataModel The download model containing current state information
	 */
	fun updateNotification(downloadDataModel: DownloadDataModel) {
		logger.d("updateNotification() called for id=${downloadDataModel.downloadId}, " +
				"file=${downloadDataModel.fileName}")

		// Check if notifications should be hidden based on user settings
		if (shouldStopNotifying(downloadDataModel)) {
			logger.d("Notification suppressed for id=${downloadDataModel.downloadId}")
			return
		}

		// Check if notification should be cancelled due to download removal/deletion
		if (shouldCancelNotification(downloadDataModel)) {
			logger.d("Cancelling notification for id=${downloadDataModel.downloadId}")
			notificationManager.cancel(downloadDataModel.downloadId)
			return
		}

		// Update the progress notification with current download state
		updateDownloadProgress(downloadDataModel)
	}

	/**
	 * Creates or updates a progress notification for the download.
	 * This method builds and displays a notification that shows the current
	 * download progress, status, and provides appropriate actions based on
	 * whether the download is complete or still in progress.
	 *
	 * @param downloadDataModel The download model containing current progress information
	 */
	private fun updateDownloadProgress(downloadDataModel: DownloadDataModel) {
		logger.d("Updating progress notification for id=${downloadDataModel.downloadId}, " +
				"complete=${downloadDataModel.isComplete}")
		val notificationId = downloadDataModel.downloadId
		val notificationBuilder = Builder(INSTANCE, CHANNEL_ID)

		// Build the notification with appropriate content and behavior
		notificationBuilder
			.setContentTitle(downloadDataModel.fileName)
			.setContentText(getContentTextByStatus(downloadDataModel))
			.setSmallIcon(R.drawable.ic_launcher_logo_v4)
			.setPriority(PRIORITY_LOW)
			.setAutoCancel(true)
			.setContentIntent(
				createNotificationPendingIntent(
					isDownloadCompleted = downloadDataModel.isComplete,
					downloadModel = downloadDataModel
				)
			)

		// Display the notification using the download ID as the notification ID
		notificationManager.notify(notificationId, notificationBuilder.build())
	}

	/**
	 * Generates appropriate notification text based on download status.
	 * This method selects the most relevant status message to display in the notification
	 * based on the current state of the download (completed, running, paused, or other).
	 *
	 * @param downloadDataModel The download model to evaluate for status
	 * @return The appropriate status message text for notification display
	 */
	private fun getContentTextByStatus(downloadDataModel: DownloadDataModel): String {
		val completedText = getText(R.string.title_download_complete_click_to_open)
		val pausedText = getText(R.string.title_download_has_been_paused)
		val runningText = downloadDataModel.generateDownloadInfoInString()

		val result = when {
			downloadDataModel.isComplete -> completedText
			downloadDataModel.isRunning -> runningText
			else -> if (downloadDataModel.statusInfo.contains(
					getText(R.string.title_paused), true
				)
			) pausedText
			else downloadDataModel.statusInfo
		}
		logger.d("Generated notification text for id=${downloadDataModel.downloadId}: $result")
		return result
	}

	/**
	 * Checks if notifications should be suppressed for this download.
	 * This method respects user preferences for hiding download notifications
	 * as configured in the global application settings.
	 *
	 * @param downloadModel The download model to check for notification settings
	 * @return true if notifications should be hidden, false if they should be shown
	 */
	private fun shouldStopNotifying(downloadModel: DownloadDataModel): Boolean {
		val result = downloadModel.globalSettings.downloadHideNotification
		logger.d("shouldStopNotifying() → $result for id=${downloadModel.downloadId}")
		return result
	}

	/**
	 * Determines if the notification should be cancelled.
	 * This method checks if the download has been removed or deleted from the system,
	 * in which case the associated notification should be removed from the status bar.
	 *
	 * @param downloadModel The download model to check for removal/deletion status
	 * @return true if the notification should be cancelled, false if it should remain
	 */
	private fun shouldCancelNotification(downloadModel: DownloadDataModel): Boolean {
		val result = downloadModel.isRemoved || downloadModel.isDeleted
		logger.d("shouldCancelNotification() → $result for id=${downloadModel.downloadId}")
		return result
	}

	/**
	 * Creates a pending intent appropriate for the download state.
	 * This method generates different PendingIntents based on whether the download
	 * has completed or is still in progress, ensuring proper navigation behavior
	 * when the user interacts with the notification.
	 *
	 * @param isDownloadCompleted Whether the download has finished successfully
	 * @param downloadModel The associated download model containing file information
	 * @return Configured PendingIntent for notification tap action
	 */
	@OptIn(UnstableApi::class)
	private fun createNotificationPendingIntent(isDownloadCompleted: Boolean = false,
		downloadModel: DownloadDataModel): PendingIntent {
		logger.d("Creating PendingIntent for id=${downloadModel.downloadId}, isComplete=$isDownloadCompleted")
		return if (isDownloadCompleted) {
			// For completed downloads: Open specific file in appropriate activity
			getActivity(
				INSTANCE, 0, generatePendingIntent(downloadModel),
				FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
			)
		} else {
			// For ongoing downloads: Open main activity to show download progress
			getActivity(
				INSTANCE, 0, Intent(INSTANCE, MotherActivity::class.java),
				FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
			)
		}
	}

	/**
	 * Generates an intent based on the downloaded file type.
	 * This method creates a properly configured Intent that will launch the appropriate
	 * activity (MediaPlayerActivity for media files, MotherActivity for others) when
	 * the user taps on the download completion notification.
	 *
	 * @param downloadModel The completed download model containing file information
	 * @return Intent configured to open the appropriate activity with necessary extras
	 */
	@OptIn(UnstableApi::class)
	private fun generatePendingIntent(downloadModel: DownloadDataModel): Intent {
		// Construct the full file path from directory and filename
		val destFile = File("${downloadModel.fileDirectory}/${downloadModel.fileName}")
		val downloadFile = fromFile(destFile)

		logger.d("Generating PendingIntent → file=${destFile.path}")
		return Intent(INSTANCE, getCorrespondingActivity(downloadFile)).apply {
			// Set intent flags to ensure proper activity stack behavior
			flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP

			// Add relevant extras for the target activity
			putExtra(DOWNLOAD_MODEL_ID_KEY, downloadModel.downloadId)
			putExtra(
				/* name = */ INTENT_EXTRA_MEDIA_FILE_PATH, /*
				value = */ downloadModel.getDestinationFile().path)
			putExtra(WHERE_DID_YOU_COME_FROM, FROM_DOWNLOAD_NOTIFICATION)
		}
	}

	/**
	 * Determines the appropriate activity class based on file type.
	 * This method routes media files (video/audio) to the media player for playback
	 * and all other file types to the main activity for general file handling.
	 *
	 * @param downloadedFile The downloaded file to evaluate for media type
	 * @return MediaPlayerActivity::class for media files, MotherActivity::class otherwise
	 */
	@OptIn(UnstableApi::class)
	private fun getCorrespondingActivity(downloadedFile: DocumentFile) =
		if (isVideo(downloadedFile) || isAudio(downloadedFile)) {
			logger.d("File is media → routing to MediaPlayerActivity")
			MediaPlayerActivity::class.java
		} else {
			logger.d("File is not media → routing to MotherActivity")
			MotherActivity::class.java
		}

	/**
	 * Creates the notification channel required for Android 8.0+ (Oreo and above).
	 * This method sets up the notification channel with low importance for download
	 * completion notifications, ensuring compatibility with modern Android versions.
	 */
	private fun createNotificationChannelForSystem() {
		logger.d("Creating notification channel: $CHANNEL_ID / $CHANNEL_NAME")
		val notificationChannel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, IMPORTANCE_LOW)
		val nm = INSTANCE.getSystemService(NOTIFICATION_SERVICE)
		notificationManager = nm as NotificationManager
		notificationManager.createNotificationChannel(notificationChannel)
	}

	companion object {
		/** Notification channel ID for download notifications */
		const val CHANNEL_ID = "Download_Notification_Channel"

		/** User-visible name for the notification channel */
		const val CHANNEL_NAME = "Download Notifications"

		/** Intent extra key for tracking notification source */
		const val WHERE_DID_YOU_COME_FROM = "WHERE_DID_YOU_COME_FROM"

		/** Value indicating the notification came from download completion */
		const val FROM_DOWNLOAD_NOTIFICATION = 4
	}

}