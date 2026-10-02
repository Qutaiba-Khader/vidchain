package app.core.engines.downloader

import app.core.*
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.downloader.DownloadModelFilesParser.getDownloadDataModels
import app.core.engines.downloader.DownloadStatus.COMPLETE
import com.aio.*
import kotlinx.coroutines.*
import lib.device.DateTimeUtils.getDaysPassedSince
import lib.process.*
import lib.process.LogHelperUtils.*
import lib.texts.CommonTextUtils.getText

/**
 * Central interface responsible for managing the application's download system.
 *
 * This interface defines the core mechanisms for controlling and monitoring all
 * download activities within the app. It handles task scheduling, progress tracking,
 * and communication between the download logic and the user interface.
 *
 * ### Key Responsibilities
 * - Manage active, paused, and completed downloads
 * - Control download lifecycle actions (start, pause, resume, delete)
 * - Maintain lists of running and queued download tasks
 * - Synchronize download states with the UI layer
 * - Perform cleanup of outdated or completed download data
 * - Enforce download policies and user preferences
 * - Handle automatic retention and cleanup of download records
 *
 * ### System Architecture
 * The download system follows a producer-consumer pattern where:
 * - Downloads are added to waiting queues
 * - A fixed number of parallel downloads execute concurrently
 * - Completed downloads are moved to finished collections
 * - Automatic cleanup occurs based on user-configured retention policies
 *
 * ### Thread Safety
 * Implementations should ensure thread-safe access to all collections and
 * proper synchronization when modifying download states across multiple threads.
 *
 * @see DownloadSystem for the primary implementation
 * @see DownloadDataModel for the data structure representing downloads
 * @see DownloadTaskInf for individual download task execution
 */
interface DownloadSysInf {
	
	/**
	 * Indicates whether the download system is currently initializing.
	 *
	 * This flag helps prevent concurrent initialization routines from running in parallel.
	 * It is typically set to `true` during startup or when the download manager reloads
	 * persisted download data. Operations may be queued or delayed while this flag is set.
	 *
	 * ### Usage Context
	 * - System startup and configuration loading
	 * - Database restoration and state recovery
	 * - Bulk operations that require exclusive access
	 */
	var isInitializing: Boolean
	
	/**
	 * Reference to the main application context.
	 *
	 * Provides access to global application-level resources and services through [AIOApp].
	 * Useful for operations that require a context outside of an Activity or Service scope,
	 * such as file system access, preference management, and system services.
	 *
	 * ### Common Uses
	 * - Accessing application preferences and settings
	 * - File system operations for download storage
	 * - System service integration (notifications, etc.)
	 */
	val appContext: AIOApp
		get() = AIOApp.INSTANCE
	
	/**
	 * Manages and displays system notifications for downloads.
	 *
	 * Handles creation, updating, and removal of notification entries reflecting
	 * download progress, completion, or errors. Notifications provide user visibility
	 * into download operations even when the app is in the background.
	 *
	 * ### Notification Types
	 * - Progress notifications with real-time updates
	 * - Completion notifications with success status
	 * - Error notifications with failure details
	 * - Persistent notifications for ongoing operations
	 */
	val downloadNotification: DownloadNotification
	
	/**
	 * Collection of pre-loaded download models for rapid system initialization.
	 *
	 * This cache contains download models that have been pre-fetched to accelerate
	 * system startup and reduce disk I/O during initialization. When empty, the system
	 * will load models from persistent storage.
	 *
	 * ### Performance Benefits
	 * - Faster application startup times
	 * - Reduced disk access during initialization
	 * - Improved responsiveness when displaying download lists
	 */
	val prefetchedEntireDownloadModels: ArrayList<DownloadDataModel>
	
	/**
	 * Collection of all currently active downloads.
	 *
	 * This includes downloads that are in-progress, paused, or pending retry.
	 * It serves as the central in-memory representation of all non-completed downloads.
	 *
	 * ### Content Types
	 * - Currently downloading files
	 * - Paused downloads awaiting resume
	 * - Queued downloads waiting for execution slots
	 * - Downloads with temporary errors awaiting retry
	 *
	 * ### Lifecycle
	 * Downloads are removed from this collection when they complete, fail permanently,
	 * or are explicitly removed by the user.
	 */
	val activeDownloadDataModels: ArrayList<DownloadDataModel>
	
	/**
	 * Collection of all completed downloads.
	 *
	 * Holds download models that have finished successfully and can be displayed
	 * in the "Completed Downloads" section of the app. The retention of completed
	 * downloads is controlled by user-configurable auto-removal settings.
	 *
	 * ### Retention Policy
	 * - Controlled by `downloadAutoRemoveTasks` setting
	 * - Configurable retention period in days
	 * - Automatic cleanup during system initialization
	 */
	val finishedDownloadDataModels: ArrayList<DownloadDataModel>
	
	/**
	 * List of currently running download tasks.
	 *
	 * Represents download threads actively fetching data. The size of this collection
	 * is limited by the parallel download configuration to prevent resource exhaustion.
	 *
	 * ### Concurrency Control
	 * - Maximum size controlled by `downloadDefaultParallelConnections` setting
	 * - Tasks are automatically managed to stay within limits
	 * - Excess tasks are moved to waiting queue
	 */
	val runningDownloadTasks: ArrayList<DownloadTaskInf>
	
	/**
	 * Queue of download tasks waiting for execution.
	 *
	 * Tasks in this list are scheduled but not yet running, often due to concurrency limits
	 * or network constraints. They are processed in FIFO order when execution slots become
	 * available in the running tasks collection.
	 *
	 * ### Queue Management
	 * - First-in-first-out processing order
	 * - Automatic promotion to running state when slots available
	 * - Manual prioritization support through reordering
	 */
	val waitingDownloadTasks: ArrayList<DownloadTaskInf>
	
	/**
	 * Manages synchronization between download logic and the user interface.
	 *
	 * Ensures that progress updates, status changes, and UI refresh events
	 * are handled efficiently and safely on the main thread. Coordinates
	 * updates across multiple UI components including fragments and activities.
	 *
	 * ### UI Components Managed
	 * - Active downloads list and progress displays
	 * - Completed downloads history
	 * - Notification badges and status indicators
	 * - Progress bars and download statistics
	 */
	val downloadsUIManager: DownloadUIManager
	
	/**
	 * List of listeners that are notified when a download finishes.
	 *
	 * Each listener implements [DownloadFinishUIListener] and can be used to update
	 * UI components or trigger actions when a download completes. Multiple components
	 * can register to receive completion notifications for different purposes.
	 *
	 * ### Common Use Cases
	 * - Updating download completion counters
	 * - Refreshing file browser contents
	 * - Triggering post-download processing
	 * - Logging download completion events
	 */
	var downloadOnFinishListeners: ArrayList<DownloadFinishUIListener>
	
	/**
	 * Adds a new download entry into the download manager system.
	 *
	 * This function is responsible for registering a new download in memory or persistence layer,
	 * preparing it for execution by the download service or scheduler. It performs validation
	 * and ensures the download is properly integrated into the system's tracking mechanisms.
	 *
	 * ### Operation Flow
	 * 1. Validates the download model for completeness
	 * 2. Adds to active downloads collection
	 * 3. Creates corresponding download task
	 * 4. Places task in appropriate queue (running or waiting)
	 * 5. Triggers UI updates and notifications
	 *
	 * @param downloadModel The [DownloadDataModel] representing the download to be added.
	 *                      Must contain valid URL, destination, and metadata.
	 * @param onAdded A callback invoked after the download has been successfully added.
	 *                Executed on the main thread for UI safety.
	 */
	fun addDownload(downloadModel: DownloadDataModel, onAdded: () -> Unit = {}) {
		onAdded()
	}
	
	/**
	 * Resumes a paused or interrupted download task.
	 *
	 * This function re-initializes the necessary download components, restores the coroutine context,
	 * and resumes downloading from the last saved progress if supported by the remote server.
	 * It handles both manually paused downloads and automatically resumed interrupted transfers.
	 *
	 * ### Resume Behavior
	 * - Attempts to resume from last byte position if server supports range requests
	 * - Falls back to restart from beginning if resume not supported
	 * - Maintains download metadata and progress tracking
	 * - Updates UI state to reflect resumed status
	 *
	 * @param downloadModel The [DownloadDataModel] representing the download to be resumed.
	 *                      Must be in a pausable state (previously started but not completed).
	 * @param coroutineScope The [CoroutineScope] in which the download will run asynchronously.
	 *                       Provides lifecycle management and cancellation support.
	 * @param onResumed A callback invoked after the download successfully resumes.
	 *                  Indicates the download is now active in the queue or running.
	 */
	fun resumeDownload(
		downloadModel: DownloadDataModel,
		coroutineScope: CoroutineScope,
		onResumed: () -> Unit = {}
	) {
		onResumed()
	}
	
	/**
	 * Pauses an active download, temporarily halting data transfer.
	 *
	 * Stops the download process while preserving its state, allowing it to be
	 * resumed later from the same point. The download remains in the active
	 * collection but its task is removed from execution queues.
	 *
	 * ### Pause Behavior
	 * - Gracefully stops data transfer if possible
	 * - Preserves download progress and metadata
	 * - Removes from running tasks but keeps in active list
	 * - Updates UI to show paused state
	 *
	 * @param downloadModel The download to pause. Must be currently running or waiting.
	 * @param onPaused Callback invoked after successful pause, indicating the download
	 *                 is now in a suspended state and can be resumed later.
	 */
	fun pauseDownload(downloadModel: DownloadDataModel, onPaused: () -> Unit = {}) {
		onPaused()
	}
	
	/**
	 * Clears a download from the system while preserving downloaded files.
	 *
	 * Removes the download from active tracking and UI displays but keeps the
	 * downloaded file intact on the file system. This is useful for cleaning up
	 * the download list without deleting the actual content.
	 *
	 * ### Clear vs Delete
	 * - **Clear**: Removes from download manager but keeps file
	 * - **Delete**: Removes both from manager and deletes file
	 *
	 * @param downloadModel The download to clear from active management.
	 * @param onCleared Callback invoked after successful clear operation.
	 */
	fun clearDownload(downloadModel: DownloadDataModel, onCleared: () -> Unit = {}) {
		onCleared()
	}
	
	/**
	 * Permanently deletes a download and its associated files.
	 *
	 * Completely removes the download from the system, including:
	 * - Download metadata and tracking information
	 * - Partially downloaded temporary files
	 * - Completed download files
	 * - System notifications and UI entries
	 *
	 * ### Data Removal
	 * - Deletes download model from persistent storage
	 * - Removes partially downloaded content
	 * - Deletes final downloaded file if complete
	 * - Cleans up all system references
	 *
	 * @param downloadModel The download to permanently remove from the system.
	 * @param onDeleted Callback invoked after successful deletion of all components.
	 */
	fun deleteDownload(downloadModel: DownloadDataModel, onDeleted: () -> Unit = {}) {
		onDeleted()
	}
	
	/**
	 * Resumes all currently paused downloads in the system.
	 *
	 * Iterates through all active downloads that are in a paused state and
	 * attempts to resume them. This is typically used for bulk operations
	 * or after system events that require mass resumption.
	 *
	 * ### Bulk Operation Notes
	 * - Respects parallel download limits
	 * - May queue some downloads if limit exceeded
	 * - Processes downloads in chronological order
	 * - Updates all relevant UI components
	 */
	fun resumeAllDownloads() {}
	
	/**
	 * Pauses all currently active downloads in the system.
	 *
	 * Stops all running downloads and prevents waiting downloads from starting.
	 * This is useful for conserving bandwidth or preparing for system maintenance.
	 *
	 * ### Use Cases
	 * - Network conservation during limited bandwidth
	 * - System shutdown preparation
	 * - User-initiated mass pause operations
	 */
	fun pauseAllDownloads() {}
	
	/**
	 * Clears all downloads from the system while preserving files.
	 *
	 * Removes all downloads from active management but keeps all downloaded
	 * files intact. This effectively resets the download manager while
	 * preserving the user's downloaded content.
	 */
	fun clearAllDownloads() {}
	
	/**
	 * Permanently deletes all downloads and their associated files.
	 *
	 * Completely clears the download system, removing all downloads, their
	 * metadata, and downloaded files. This operation cannot be undone and
	 * should be used with caution.
	 *
	 * ### Data Loss Warning
	 * This operation permanently deletes all downloaded files and cannot
	 * be reversed. Use only when intentional mass deletion is required.
	 */
	fun deleteAllDownloads() {}
	
	/**
	 * Initializes the download system by loading and syncing existing downloads.
	 *
	 * Performs system startup procedures including:
	 * - Loading persisted download models from storage
	 * - Applying automatic cleanup based on retention policies
	 * - Sorting downloads into active and completed categories
	 * - Preparing the system for new download operations
	 *
	 * ### Initialization Sequence
	 * 1. Sets initialization flag to prevent concurrent operations
	 * 2. Loads download models from disk or uses pre-fetched cache
	 * 3. Applies retention policies to remove expired downloads
	 * 4. Categorizes downloads into active and completed
	 * 5. Updates UI components with loaded data
	 */
	fun initSystem() {
		parseDownloadDataModelsAndSync()
	}
	
	/**
	 * Gets the current count of actively running download tasks.
	 *
	 * This count reflects the number of downloads currently transferring data
	 * and is used to enforce parallel download limits and manage system resources.
	 *
	 * @return Number of active downloads currently executing. This value will be
	 *         less than or equal to the configured parallel download limit.
	 */
	fun numberOfRunningTasks(): Int = runningDownloadTasks.size
	
	/**
	 * Checks if a download exists in the running tasks list.
	 *
	 * Determines whether a specific download is currently actively transferring data.
	 * This is useful for preventing duplicate downloads and managing download states.
	 *
	 * @param downloadModel The download to check for active execution status.
	 * @return true if the download is currently running and transferring data,
	 *         false if it is paused, waiting, or completed.
	 */
	fun existsInRunningTasksList(downloadModel: DownloadDataModel): Boolean {
		return runningDownloadTasks.any { it.downloadDataModel.downloadId == downloadModel.downloadId }
	}
	
	/**
	 * Checks if a download exists in the waiting tasks list.
	 *
	 * Determines whether a specific download is queued for execution but not
	 * yet actively running. This typically occurs when parallel download limits
	 * have been reached.
	 *
	 * @param downloadModel The download to check for queued status.
	 * @return true if the download is queued but not yet running, waiting for
	 *         an available execution slot.
	 */
	fun existsInWaitingTasksList(downloadModel: DownloadDataModel): Boolean {
		return waitingDownloadTasks.any { it.downloadDataModel.downloadId == downloadModel.downloadId }
	}
	
	/**
	 * Checks if a download exists in the active downloads list.
	 *
	 * Determines whether a download is currently being managed by the system,
	 * regardless of its execution state (running, paused, or waiting).
	 *
	 * @param downloadModel The download to check for active management status.
	 * @return true if the download is active (running, paused, or waiting),
	 *         false if it is completed, cleared, or never added.
	 */
	fun existsInActiveDownloadDataModelsList(downloadModel: DownloadDataModel): Boolean {
		return activeDownloadDataModels.contains(downloadModel)
	}
	
	/**
	 * Finds a download task by its associated data model.
	 *
	 * Searches both running and waiting task lists to locate the task instance
	 * that corresponds to the provided download model. This is useful for
	 * operations that need to interact with specific download tasks.
	 *
	 * @param downloadModel The download model to search for in task lists.
	 * @return The matching [DownloadTaskInf] instance if found in either running
	 *         or waiting lists, null if no active task exists for this model.
	 */
	fun searchActiveDownloadTaskWith(downloadModel: DownloadDataModel): DownloadTaskInf? {
		return runningDownloadTasks.toList().find { it.downloadDataModel.downloadId == downloadModel.downloadId }
			?: waitingDownloadTasks.toList().find { it.downloadDataModel.downloadId == downloadModel.downloadId }
	}
	
	/**
	 * Checks if a download task can be safely paused given its current state.
	 *
	 * This method validates whether a download task meets the criteria for pausing.
	 * It considers factors such as current download status, network conditions,
	 * and task-specific constraints that might prevent safe pausing.
	 *
	 * ### Pausability Conditions
	 * - Download must exist in either running or waiting tasks
	 * - Download must not be in a completed or final state
	 * - System must not be in a transitional or error state
	 *
	 * @param downloadModel The download data model to check for pausability.
	 * @return true if the download can be safely paused at its current state,
	 *         false if pausing might cause data loss or system instability.
	 */
	fun canDownloadTaskBePaused(downloadModel: DownloadDataModel): Boolean {
		return existsInRunningTasksList(downloadModel) || existsInWaitingTasksList(downloadModel)
	}
	
	/**
	 * Loads and synchronizes download models from persistent storage.
	 *
	 * Performs comprehensive system synchronization including:
	 * - Loading download models from disk storage
	 * - Applying automatic cleanup based on retention settings
	 * - Validating file existence for completed downloads
	 * - Categorizing downloads into active and completed collections
	 *
	 * ### Synchronization Process
	 * 1. Sets initialization flag to prevent concurrent access
	 * 2. Loads models from pre-fetched cache or disk
	 * 3. Applies retention policies to remove expired downloads
	 * 4. Validates file existence for completed downloads
	 * 5. Categorizes and sorts remaining downloads
	 * 6. Updates UI components with synchronized data
	 *
	 * @throws RuntimeException if critical errors occur during parsing that
	 *         prevent proper system initialization.
	 */
	fun parseDownloadDataModelsAndSync(onComplete: (Boolean) -> Unit = {}) {
		ThreadsUtility.executeInBackground(codeBlock = {
			isInitializing = true
			val prefetchedDownloadModels = prefetchedEntireDownloadModels.ifEmpty { getDownloadDataModels() }
			prefetchedDownloadModels.forEach {
				if (isValidCompletedDownloadModel(it)) {
					if (it.globalSettings.downloadAutoRemoveTasks) {
						if (it.globalSettings.downloadAutoRemoveTaskAfterNDays == 0) {
							it.deleteModelFromDisk(); return@forEach
						}
						
						val autoRemoveDaysSettings = aioSettings.downloadAutoRemoveTaskAfterNDays
						if (getDaysPassedSince(it.lastModifiedTimeDate) > autoRemoveDaysSettings) {
							it.deleteModelFromDisk(); return@forEach
						}
					}
					
					it.statusInfo = getText(R.string.title_completed)
					addAndSortFinishedDownloadDataModels(it)
				}
				
				if (isValidActiveDownloadModel(it)) {
					it.statusInfo = getText(R.string.title_paused)
					addAndSortActiveDownloadModelList(it)
				}
			}
			isInitializing = false
			onComplete.invoke(true)
		}, errorHandler = {
			isInitializing = false
			onComplete.invoke(false)
			error("Error found in parsing download model from disk.")
		})
	}
	
	/**
	 * Adds a download to the active list and maintains proper sorting.
	 *
	 * Ensures the download is present in the active collection and applies
	 * the current sorting criteria (typically by start time, newest first).
	 *
	 * @param downloadModel The download to add to active management.
	 *                      Will be ignored if already present in the collection.
	 */
	fun addAndSortActiveDownloadModelList(downloadModel: DownloadDataModel) {
		if (!activeDownloadDataModels.contains(downloadModel)) {
			activeDownloadDataModels.add(downloadModel)
		}; sortActiveDownloadDataModels()
	}
	
	/**
	 * Sorts active downloads by start time with newest downloads first.
	 *
	 * Applies chronological sorting to ensure the most recently started
	 * downloads appear at the beginning of the active downloads list.
	 * This provides users with quick access to their most recent downloads.
	 */
	fun sortActiveDownloadDataModels() {
		activeDownloadDataModels.sortByDescending { it.startTimeDate }
	}
	
	/**
	 * Adds a download to the completed list and maintains proper sorting.
	 *
	 * Ensures the download is present in the completed collection and applies
	 * the current sorting criteria (typically by completion time, newest first).
	 *
	 * @param downloadModel The completed download to add to the history.
	 *                      Will be ignored if already present in the collection.
	 */
	fun addAndSortFinishedDownloadDataModels(downloadModel: DownloadDataModel) {
		if (!finishedDownloadDataModels.contains(downloadModel)) {
			finishedDownloadDataModels.add(downloadModel)
		}; sortFinishedDownloadDataModels()
	}
	
	/**
	 * Sorts completed downloads by start time with newest downloads first.
	 *
	 * Applies chronological sorting to ensure the most recently completed
	 * downloads appear at the beginning of the completed downloads list.
	 * This helps users quickly find their most recent successful downloads.
	 */
	fun sortFinishedDownloadDataModels() {
		finishedDownloadDataModels.sortByDescending { it.startTimeDate }
	}
	
	/**
	 * Validates if a download model represents an active (incomplete) download.
	 *
	 * Performs comprehensive validation to determine if a download model
	 * represents a valid active download that should be managed by the system.
	 *
	 * ### Validation Criteria
	 * - Download status must not be COMPLETE
	 * - Download must not be marked as complete
	 * - Download must not be marked as removed or deleted
	 * - Download must not be in private folder (special handling)
	 *
	 * @param downloadModel The model to validate for active download status.
	 * @return true if the model represents a valid active download that should
	 *         be managed by the system, false otherwise.
	 */
	fun isValidActiveDownloadModel(downloadModel: DownloadDataModel): Boolean {
		val isValidActiveTask = (downloadModel.status != COMPLETE && !downloadModel.isComplete)
		return isValidActiveTask && !downloadModel.isRemoved && !downloadModel.isDeleted &&
			!downloadModel.isWentToPrivateFolder
	}
	
	/**
	 * Validates if a download model represents a successfully completed download.
	 *
	 * Performs comprehensive validation to ensure a download model represents
	 * a legitimate completed download with all necessary components intact.
	 *
	 * ### Validation Criteria
	 * - Download status must be COMPLETE or marked as complete
	 * - Download must not be in private folder (special handling)
	 * - Destination file must exist on the file system
	 * - Automatic cleanup of invalid records if file missing
	 *
	 * @param downloadModel The model to validate for completed download status.
	 * @return true if the model represents a valid completed download with
	 *         existing destination file, false otherwise.
	 */
	fun isValidCompletedDownloadModel(downloadModel: DownloadDataModel): Boolean {
		val isValid = (downloadModel.status == COMPLETE || downloadModel.isComplete) &&
			!downloadModel.isWentToPrivateFolder &&
			downloadModel.getDestinationFile().exists()
		val isExisted = downloadModel.getDestinationFile().exists()
		if (!isExisted) downloadModel.deleteModelFromDisk()
		return isValid && isExisted
	}
	
	/**
	 * Logs the current status of a download task for debugging and monitoring.
	 *
	 * Provides standardized logging of download states which is useful for
	 * troubleshooting, user support, and system monitoring.
	 *
	 * @param downloadModel The download to log, including its unique identifier
	 *                      and current status information.
	 */
	fun logDownloadTaskStatus(downloadModel: DownloadDataModel) {
		from(javaClass).d("Download ${downloadModel.downloadId} status: ${downloadModel.status}")
	}
}