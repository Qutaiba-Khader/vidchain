package app.core.engines.downloader

import kotlinx.coroutines.CoroutineScope

/**
 * Defines the core contract for all download task implementations.
 *
 * Implementations represent an individual, self-contained download operation
 * managed by the broader download system. This interface provides:
 * - Lifecycle management (initialization, execution, cancellation)
 * - Status reporting and progress updates
 * - Access to the associated download model and configuration
 *
 * Implementations must ensure thread safety, structured coroutine usage, and
 * consistent state transitions under all network and system conditions.
 */
interface DownloadTaskInf {

	/**
	 * The data model representing this download task.
	 *
	 * Contains all essential metadata and runtime information such as:
	 * - Current status and progress
	 * - File name, size, and checksum
	 * - Network configuration and download options
	 */
	val downloadDataModel: DownloadDataModel

	/**
	 * Listener for receiving download state and progress updates.
	 *
	 * The implementing class must invoke this listener to communicate:
	 * - Status changes (e.g., started, paused, completed, failed)
	 * - Progress updates
	 * - Error events or exceptional conditions
	 */
	var downloadStatusListener: DownloadTaskListener?

	/**
	 * The coroutine scope associated with this download task.
	 *
	 * Used to launch and manage coroutines tied to the lifecycle of
	 * the download operation. Ensures that all background work is
	 * properly canceled when the task is stopped.
	 */
	var coroutineScope: CoroutineScope

	/**
	 * Prepares the download task for execution.
	 *
	 * Responsibilities:
	 * - Validate essential parameters (e.g., file URL, destination path)
	 * - Initialize required resources and internal state
	 * - Set up a clean environment for download execution
	 * - Handle initialization errors internally without throwing exceptions
	 *
	 * @return `true` if initialization succeeded, otherwise `false`
	 */
	suspend fun initiateDownload(): Boolean

	/**
	 * Begins the actual download operation.
	 *
	 * Responsibilities:
	 * - Start and manage network transfers (including multi-threaded downloads)
	 * - Periodically update progress and notify listeners
	 * - Handle writing data safely to disk
	 * - Gracefully manage retries, pauses, cancellations, and errors
	 *
	 * @return `true` if the download started successfully, otherwise `false`
	 */
	suspend fun startDownload(): Boolean

	/**
	 * Cancels an ongoing download operation.
	 *
	 * This method should be invoked when a download must be stopped due to
	 * user interaction or internal failure. It ensures cleanup of active
	 * resources and maintains consistent download state.
	 *
	 * Responsibilities:
	 * - Stop all active network transfers and background coroutines
	 * - Finalize or discard partially written files to prevent corruption
	 * - Update the internal state and persistent storage to reflect cancellation
	 * - Notify UI components and listeners of the event
	 *
	 * @param cancelReason A human-readable message explaining the reason for cancellation.
	 *                     Useful for logs, debugging, or displaying to the user.
	 * @param isCanceledByUser `true` if the user initiated the cancellation;
	 *                         `false` if triggered automatically (e.g., due to errors).
	 */
	suspend fun cancelDownload(cancelReason: String = "", isCanceledByUser: Boolean = false)

	/**
	 * Updates the current status of the download task.
	 *
	 * Responsibilities:
	 * - Reflect the latest download state in the model
	 * - Validate and apply status transitions safely
	 * - Notify observers or UI components of the change
	 * - Handle invalid or redundant transitions gracefully
	 *
	 * @param statusInfo Optional human-readable message describing the current state
	 *                   (e.g., “Connecting to server”, “Resuming download”).
	 * @param status The new status code representing the current state, typically from `DownloadStatus`.
	 */
	suspend fun updateDownloadStatus(
		statusInfo: String? = null,
		status: Int = downloadDataModel.status
	)
}