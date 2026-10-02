package app.core.engines.downloader

import app.core.AIOApp.Companion.aioBackend
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOTimer.AIOTimerListener
import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.services.AIOForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.files.FileSystemUtility.updateMediaStore
import lib.process.AsyncJobUtils
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility

/**
 * Core implementation of the download management system.
 *
 * This class serves as the central coordinator for all download operations, providing:
 * - Complete download task lifecycle management (add/start/pause/resume/delete)
 * - Parallel download execution with configurable limits
 * - Intelligent task queue management with priority handling
 * - Real-time status updates and system notifications
 * - UI coordination across multiple fragments and activities
 * - Automatic cleanup and maintenance of completed downloads
 * - Error handling and recovery mechanisms for failed downloads
 *
 * The system implements a producer-consumer pattern where tasks are added to a waiting queue
 * and processed based on available parallel slots. It maintains strict synchronization
 * to ensure thread safety across all operations.
 *
 * @see DownloadSysInf for system-level interface definitions
 * @see DownloadTaskListener for task status update callbacks
 * @see AIOTimerListener for periodic system maintenance
 */
class DownloadSystem : AIOTimerListener, DownloadSysInf, DownloadTaskListener {

	// Logger instance for tracking events
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Indicates whether the download system is currently initializing.
	 *
	 * This flag is used to prevent operations during system startup and ensure
	 * proper initialization sequence. Volatile modifier ensures visibility across threads.
	 */
	@Volatile
	override var isInitializing: Boolean = false

	/**
	 * Handles download-related notifications to the user.
	 *
	 * Manages system notifications, progress updates, and status alerts
	 * for both foreground and background download operations.
	 */
	override val downloadNotification: DownloadNotification = DownloadNotification()

	/**
	 * Comprehensive list of all download models pre-fetched from database.
	 *
	 * Includes active, paused, completed, and queued downloads. This serves as the
	 * master reference for all download operations and UI updates.
	 */
	override val prefetchedEntireDownloadModels: ArrayList<DownloadDataModel> = ArrayList()

	/**
	 * List of downloads currently being processed or queued for processing.
	 *
	 * This list contains downloads that are either:
	 * - Actively downloading
	 * - Paused but not completed
	 * - Waiting in the queue for available slots
	 * - Recently added but not yet started
	 */
	override val activeDownloadDataModels: ArrayList<DownloadDataModel> = ArrayList()

	/**
	 * List of downloads that have completed processing successfully.
	 *
	 * Completed downloads remain in this list based on user configuration for
	 * auto-removal settings. Each entry represents a successfully downloaded file.
	 */
	override val finishedDownloadDataModels: ArrayList<DownloadDataModel> = ArrayList()

	/**
	 * List of currently running download tasks.
	 *
	 * These tasks are actively transferring data and consuming system resources.
	 * The size of this list is limited by the parallel download configuration.
	 */
	override val runningDownloadTasks: ArrayList<DownloadTaskInf> = ArrayList()

	/**
	 * List of download tasks waiting to be started.
	 *
	 * Tasks in this queue are ready to execute but waiting for available slots
	 * in the running tasks list. They are processed in FIFO order.
	 */
	override val waitingDownloadTasks: ArrayList<DownloadTaskInf> = ArrayList()

	/**
	 * Manages UI updates related to downloads across the application.
	 *
	 * Coordinates updates between active downloads fragment, finished downloads fragment,
	 * and other UI components that display download progress and status.
	 */
	override val downloadsUIManager: DownloadUIManager = DownloadUIManager(this)

	/**
	 * Listeners that are notified when a download finishes.
	 *
	 * Multiple components can register to receive completion notifications
	 * for updating UI, triggering post-processing, or logging activities.
	 */
	override var downloadOnFinishListeners: ArrayList<DownloadFinishUIListener> = ArrayList()

	/**
	 * Initializes the download system upon creation.
	 *
	 * Sets up core components, registers the system with the global timer for
	 * periodic monitoring, and logs initialization details. This method should
	 * be called immediately after instance creation to ensure proper system state.
	 *
	 * Initialization sequence:
	 * 1. Sets up internal data structures and managers
	 * 2. Registers with global timer for periodic maintenance
	 * 3. Prepares notification system for user alerts
	 * 4. Logs successful initialization for debugging
	 */
	fun initializeSystem() {
		// Initialize system and register with timer for periodic operations
		initSystem()
		aioTimer.register(this)
		logger.d("DownloadSystem initialized & timer registered.")
	}

	/**
	 * Called periodically by the global AIO timer.
	 *
	 * This callback is triggered automatically at fixed intervals to perform
	 * recurring tasks such as monitoring pending downloads and refreshing
	 * foreground service updates. The frequency is determined by the global
	 * timer configuration.
	 *
	 * Key periodic operations:
	 * - Processes waiting download tasks when slots become available
	 * - Updates foreground service status for user visibility
	 * - Performs system health checks and cleanup
	 *
	 * @param loopCount The number of times this callback has been triggered since registration.
	 *                  Used for conditional operations that don't need to run every tick.
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		monitorPendingWaitingDownloadTask(loopCount)
	}

	/**
	 * Monitors and starts any pending downloads in the waiting list.
	 *
	 * This method runs asynchronously to ensure smooth background execution.
	 * It checks the waiting queue for tasks ready to start and triggers them.
	 * Additionally, it refreshes the foreground service every fifth tick to
	 * keep the UI updated with the latest download states.
	 *
	 * Operation flow:
	 * 1. Verifies current system capacity for new downloads
	 * 2. Starts eligible tasks from the waiting queue
	 * 3. Updates foreground service status periodically
	 * 4. Logs operations for debugging and monitoring
	 *
	 * @param loopCount Current tick count from the timer, used for periodic operations
	 *                  like service updates that don't need to run every cycle.
	 */
	@Synchronized
	private fun monitorPendingWaitingDownloadTask(loopCount: Double) {
		logger.d("Timer tick received. Loop count: $loopCount")
		CoroutineScope(Dispatchers.IO).launch {
			logger.d("Starting pending tasks from waiting list.")
			startPendingTasksFromWaitingList()

			if (loopCount.toInt() % 5 == 0) {
				logger.d("Updating foreground service.")
				AIOForegroundService.updateService()
			}
		}
	}

	/**
	 * Adds a new download to the system and prepares it for execution.
	 *
	 * This method checks if the given download already exists in the active list.
	 * - If it exists, the download is resumed.
	 * - If not, a new task is generated and added to the waiting queue.
	 *
	 * The method also ensures that the UI is updated to reflect the new or resumed
	 * download, and invokes the provided callback once the operation is complete.
	 *
	 * @param downloadModel The data model representing the download to be added.
	 * @param onAdded Callback executed after the download is successfully queued or resumed.
	 */
	override fun addDownload(downloadModel: DownloadDataModel, onAdded: () -> Unit) {
		// Log the start of a new download addition attempt
		logger.d("Attempting to add download: ${downloadModel.fileName}")

		// Execute the operation in a background thread to avoid blocking the main thread
		ThreadsUtility.executeInBackground(codeBlock = {
			val coroutineScope = CoroutineScope(Dispatchers.IO)

			// If the download already exists in the active list, resume it instead of adding a duplicate
			if (existsInActiveDownloadDataModelsList(downloadModel)) {
				logger.d("Download already active, resuming: ${downloadModel.fileName}")
				resumeDownload(
					downloadModel = downloadModel,
					coroutineScope = coroutineScope,
					onResumed = onAdded
				)
				return@executeInBackground
			}

			// Generate a new download task for the given model
			generateDownloadTask(
				downloadDataModel = downloadModel,
				coroutineScope = coroutineScope
			).let { downloadTask ->

				// Only proceed if this task is not already in the waiting list
				if (!waitingDownloadTasks.contains(downloadTask)) {
					waitingDownloadTasks.add(downloadTask)
					addAndSortActiveDownloadModelList(downloadModel)
					logger.d("Download task added to waiting list: ${downloadModel.fileName}")

					// Execute the callback on the main thread safely
					AsyncJobUtils.executeOnMainThread {
						try {
							logger.d("onAdded callback executed for: ${downloadModel.fileName}")
							onAdded()
						} catch (error: Exception) {
							logger.e("Error during addDownload callback execution:", error)
						}
					}

					// Update the UI to reflect the new active download
					downloadsUIManager.addNewActiveUI(
						downloadModel = downloadModel,
						position = activeDownloadDataModels.indexOf(downloadModel)
					)
					logger.d("UI updated for new download: ${downloadModel.fileName}")
				}
			}
		}, errorHandler = {
			// Handle and log any unexpected exceptions during download addition
			logger.e("Failed to add download: ${downloadModel.fileName}", it)
		})
	}

	/**
	 * Resumes a previously paused or interrupted download task.
	 *
	 * This method regenerates the download task for the provided model and re-adds it
	 * to the waiting queue if it’s not already present. It ensures UI synchronization
	 * and invokes the provided callback once the resume operation completes.
	 *
	 * Workflow:
	 * - Validates and prepares a new download task.
	 * - Adds it to the waiting queue if not already active.
	 * - Updates UI to reflect resumed state.
	 * - Executes `onResumed` callback on the main thread.
	 *
	 * @param downloadModel The download model representing the file to resume.
	 * @param coroutineScope The coroutine scope used for async task execution.
	 * @param onResumed Callback invoked when the resume operation successfully completes.
	 */
	override fun resumeDownload(
		downloadModel: DownloadDataModel,
		coroutineScope: CoroutineScope,
		onResumed: () -> Unit
	) {
		// Log the start of a resume attempt
		logger.d("Attempting to resume download: ${downloadModel.fileName}")

		// Run heavy operations in a background thread to avoid blocking the main thread
		ThreadsUtility.executeInBackground(codeBlock = {
			// Generate a new download task instance for the given download model
			generateDownloadTask(
				downloadDataModel = downloadModel,
				coroutineScope = coroutineScope
			).let { downloadTask ->

				// Only add the task if it is not already in the waiting queue
				if (!waitingDownloadTasks.contains(downloadTask)) {
					waitingDownloadTasks.add(downloadTask)
					logger.d("Download task added to waiting list for resume: ${downloadModel.fileName}")

					// Update the UI to reflect resumed state
					downloadsUIManager.updateActiveUI(downloadModel)
					logger.d("UI updated for resumed download: ${downloadModel.fileName}")

					// Safely invoke the callback on the main thread
					AsyncJobUtils.executeOnMainThread {
						try {
							onResumed()
							logger.d("onResumed callback executed for: ${downloadModel.fileName}")
						} catch (error: Exception) {
							logger.e("Error during onResumed callback execution:", error)
						}
					}
				}
			}
		}, errorHandler = {
			// Log any unexpected failure that occurs while attempting to resume
			logger.e("Failed to resume download: ${downloadModel.fileName}", it)
		})
	}

	/**
	 * Forcefully resumes a failing or stuck download task.
	 *
	 * This method attempts to recover downloads that may be stuck or failing due to temporary issues.
	 * If the download is currently active, it first pauses it, waits briefly, and then resumes it.
	 * The operation is skipped if the problem message contains the word "login" to prevent repeated
	 * retries on authentication-related failures.
	 *
	 * Workflow:
	 * - Check if the download is active.
	 * - If active, pause and wait for a short interval before resuming.
	 * - Skip resume if login-related error is detected.
	 * - Resume download using a background coroutine.
	 *
	 * @param downloadModel The download data model representing the file to be forcefully resumed.
	 */
	suspend fun forceResumeDownload(downloadModel: DownloadDataModel) {
		logger.d("Attempting to force resume download: ${downloadModel.fileName}")

		// Step 1: If download is currently active, perform a forced pause before resuming
		if (searchActiveDownloadTaskWith(downloadModel) != null) {
			ThreadsUtility.executeOnMain {
				logger.d("Forced pause initiated for download: ${downloadModel.fileName}")
				pauseDownload(downloadModel)
			}
			// Give time for pause operation to complete
			delay(1500)
		}

		// Step 2: Safely attempt resume on main thread after brief delay
		ThreadsUtility.executeOnMain {
			if (!downloadModel.ytdlpProblemMsg.contains("login", true)) {
				// Resume only if not a login/auth-related issue
				logger.d("Forced resume initiated for download: ${downloadModel.fileName}")
				resumeDownload(
					downloadModel = downloadModel,
					coroutineScope = CoroutineScope(Dispatchers.IO)
				)
			} else {
				// Skip force resume for login errors to prevent infinite retry loops
				logger.d("Skipping forced resume due to login issue: ${downloadModel.fileName}")
			}
		}
	}

	/**
	 * Pauses an active or running download safely.
	 *
	 * This function locates the corresponding running download task and cancels it,
	 * effectively pausing the operation. It ensures that the paused task is removed
	 * from both the running and waiting task lists to avoid duplicate scheduling.
	 * The UI is refreshed to reflect the updated state, and a callback is triggered
	 * upon successful pause completion.
	 *
	 * Workflow:
	 * - Validate if the download can be paused.
	 * - Search and retrieve the active download task.
	 * - Cancel the task and remove it from tracking lists.
	 * - Update the UI and invoke the onPaused callback.
	 *
	 * @param downloadModel The download data model representing the download to pause.
	 * @param onPaused Callback invoked once the pause operation completes successfully.
	 */
	override fun pauseDownload(downloadModel: DownloadDataModel, onPaused: () -> Unit) {
		logger.d("Attempting to pause download: ${downloadModel.fileName}")

		// Launch pause operation in background to avoid blocking main thread
		CoroutineScope(Dispatchers.IO).launch {
			try {
				// Step 1: Check if download can be safely paused
				if (canDownloadTaskBePaused(downloadModel)) {
					// Step 2: Find the corresponding active download task
					val resultedTask = searchActiveDownloadTaskWith(downloadModel)

					resultedTask?.let {
						// Step 3: Cancel ongoing download to pause it
						it.cancelDownload(isCanceledByUser = true)

						// Step 4: Remove from active tracking lists
						runningDownloadTasks.remove(it)
						waitingDownloadTasks.remove(it)

						// Step 5: Notify via callback and log the operation
						AsyncJobUtils.executeOnMainThread {
							try {
								onPaused()
								logger.d("Paused download successfully: ${downloadModel.fileName}")
							} catch (error: Exception) {
								logger.e("Error during onPaused callback execution:", error)
							}
						}
					}

					// Step 6: Refresh UI to show paused state
					downloadsUIManager.updateActiveUI(downloadModel)
					logger.d("UI updated after pause for download: ${downloadModel.fileName}")
				} else {
					logger.d("Download cannot be paused: ${downloadModel.fileName}")
				}
			} catch (error: Exception) {
				// Log error details if something goes wrong during pause
				logger.e("Failed to pause download: ${downloadModel.fileName}", error)
			}
		}
	}

	/**
	 * Clears a download entry from the system while retaining the downloaded files.
	 *
	 * This function performs a safe cleanup of a given download task:
	 * - Pauses the download to ensure no active connections remain.
	 * - Marks the download model as removed and deletes its metadata from disk.
	 * - Removes the model from active and task tracking lists.
	 * - Updates the system notifications and UI.
	 * - Executes the `onCleared` callback on the main thread upon success.
	 *
	 * It ensures that running or waiting tasks related to the download
	 * are also terminated and untracked to prevent orphaned processes.
	 *
	 * @param downloadModel The download data model representing the item to clear.
	 * @param onCleared Callback invoked once the clear operation completes successfully.
	 */
	override fun clearDownload(downloadModel: DownloadDataModel, onCleared: () -> Unit) {
		logger.d("Attempting to clear download: ${downloadModel.fileName}")

		// Launch operation in background to avoid blocking main thread
		CoroutineScope(Dispatchers.IO).launch {
			try {
				// Step 1: Pause download if it's currently active or running
				pauseDownload(downloadModel)

				// Step 2: Mark the model as removed and delete its metadata from disk
				downloadModel.isRemoved = true
				downloadModel.deleteModelFromDisk()
				logger.d("Marked and deleted metadata for download: ${downloadModel.fileName}")

				// Step 3: Remove model from active download list
				activeDownloadDataModels.remove(downloadModel)
				logger.d("Removed download from active list: ${downloadModel.fileName}")

				// Step 4: Clean up any associated running or queued tasks
				searchActiveDownloadTaskWith(downloadModel)?.let { downloadTask ->
					runningDownloadTasks.remove(downloadTask)
					waitingDownloadTasks.remove(downloadTask)
					logger.d("Removed download from running and waiting tasks: ${downloadModel.fileName}")
				}

				// Step 5: Update notification and UI to reflect removal
				downloadNotification.updateNotification(downloadModel)
				downloadsUIManager.updateActiveUI(downloadModel)

				// Step 6: Trigger callback safely on main thread
				AsyncJobUtils.executeOnMainThread {
					try {
						onCleared()
						logger.d("Cleared download successfully: ${downloadModel.fileName}")
					} catch (error: Exception) {
						logger.e("Error during onCleared callback execution:", error)
					}
				}
			} catch (error: Exception) {
				// Step 7: Log failure details for debugging
				logger.e("Failed to clear download: ${downloadModel.fileName}", error)
			}
		}
	}

	/**
	 * Deletes a download entry along with its associated files from the system.
	 *
	 * This function performs a complete removal of a download:
	 * - Clears the download from active/running lists and updates the UI.
	 * - Marks the download as deleted and removes its metadata from disk.
	 * - Deletes the destination file associated with the download.
	 * - Invokes the `onDeleted` callback on the main thread after successful deletion.
	 *
	 * All operations are performed safely on a background thread to prevent blocking the UI.
	 *
	 * @param downloadModel The download data model representing the item to delete.
	 * @param onDeleted Callback invoked once the deletion process is completed successfully.
	 */
	override fun deleteDownload(downloadModel: DownloadDataModel, onDeleted: () -> Unit) {
		logger.d("Attempting to delete download: ${downloadModel.fileName}")

		// Launch deletion process in a background coroutine
		CoroutineScope(Dispatchers.IO).launch {
			try {
				// Step 1: Clear the download (pause and remove from system lists)
				clearDownload(downloadModel) {}

				// Step 2: Mark the model as deleted and remove its metadata
				downloadModel.isDeleted = true
				downloadModel.deleteModelFromDisk()

				// Step 3: Delete the actual downloaded file from storage
				downloadModel.getDestinationFile().delete()
				logger.d("Deleted associated files for download: ${downloadModel.fileName}")

				// Step 4: Execute onDeleted callback safely on the main thread
				AsyncJobUtils.executeOnMainThread {
					try {
						onDeleted()
						logger.d("Deleted download successfully: ${downloadModel.fileName}")
					} catch (error: Exception) {
						logger.e("Error during onDeleted callback execution:", error)
					}
				}
			} catch (error: Exception) {
				// Step 5: Log any exceptions that occur during deletion
				logger.e("Failed to delete download: ${downloadModel.fileName}", error)
			}
		}
	}

	/**
	 * Resumes all active downloads currently managed by the system.
	 *
	 * Iterates through all active download models and resumes each one
	 * on a background coroutine to avoid blocking the UI thread.
	 */
	override fun resumeAllDownloads() {
		logger.d("Resuming all downloads")
		activeDownloadDataModels.forEach {
			resumeDownload(
				downloadModel = it,
				coroutineScope = CoroutineScope(Dispatchers.IO)
			)
		}
	}

	/**
	 * Pauses all active downloads currently managed by the system.
	 *
	 * Iterates through all active download models and pauses each one,
	 * updating their status and UI accordingly.
	 */
	override fun pauseAllDownloads() {
		logger.d("Pausing all downloads")
		activeDownloadDataModels.forEach { pauseDownload(it) }
	}

	/**
	 * Clears all active downloads while keeping their downloaded files.
	 *
	 * Each active download is paused, removed from the system lists,
	 * and the UI is updated to reflect the cleared status.
	 */
	override fun clearAllDownloads() {
		logger.d("Clearing all downloads")
		activeDownloadDataModels.forEach { clearDownload(it) }
	}

	/**
	 * Deletes all active downloads along with their associated files.
	 *
	 * Iterates through all active download models, clears their state,
	 * deletes both metadata and downloaded files, and updates the UI.
	 */
	override fun deleteAllDownloads() {
		logger.d("Deleting all downloads")
		activeDownloadDataModels.forEach { deleteDownload(it) }
	}

	/**
	 * Handles status updates from individual download tasks.
	 *
	 * This method is invoked whenever a download task reports a status change.
	 * It updates the running tasks list, UI components, notifications, and
	 * internal data structures based on the current state of the download.
	 *
	 * Actions performed include:
	 * - Adding/removing tasks to/from the running list based on active status
	 * - Persisting completed downloads in logs
	 * - Updating active and finished download lists
	 * - Notifying UI and listeners of status changes
	 *
	 * @param downloadTaskInf The download task reporting the status change
	 */
	override suspend fun onStatusUpdate(downloadTaskInf: DownloadTaskInf) {
		logger.d("Status update received for: ${downloadTaskInf.downloadDataModel.fileName}")
		try {
			val downloadDataModel = downloadTaskInf.downloadDataModel

			// Add task to running list if currently downloading
			if (downloadDataModel.isRunning && downloadDataModel.status == DOWNLOADING) {
				addToRunningDownloadTasksList(downloadTaskInf)
				logger.d("Added to running tasks: ${downloadDataModel.fileName}")
			}

			// Remove task from running list if closed
			if (!downloadDataModel.isRunning && downloadDataModel.status == CLOSE) {
				removeFromRunningDownloadTasksList(downloadTaskInf)
				logger.d("Removed from running tasks: ${downloadDataModel.fileName}")
			}

			// Handle completed downloads
			if (downloadDataModel.isComplete && downloadDataModel.status == COMPLETE) {
				logger.d("Download completed: ${downloadDataModel.fileName}")
				aioBackend.saveDownloadLog(downloadDataModel)

				removeFromActiveDownloadDataModelsList(downloadDataModel)

				withContext(Dispatchers.Main) {
					downloadsUIManager.updateActiveUI(downloadDataModel)
					logger.d("Updated UI for completed download: ${downloadDataModel.fileName}")
				}

				updateFinishedDownloadDataModelsList(downloadDataModel)

				withContext(Dispatchers.Main) {
					downloadOnFinishListeners.forEach { finishUIListener ->
						finishUIListener.onFinishUIDownload(downloadDataModel)
					}
					logger.d("Notified listeners of completion: ${downloadDataModel.fileName}")
				}
			}

			// Update UI and notification for any status change
			updateUIAndNotification(downloadDataModel)
			logger.d("UI and notification updated for: ${downloadDataModel.fileName}")

		} catch (error: Exception) {
			logger.e("Error in status update for: ${downloadTaskInf.downloadDataModel.fileName}", error)
		}
	}

	/**
	 * Adds a download task to the running tasks list if it is not already present.
	 *
	 * Ensures that the running tasks list accurately reflects all currently active downloads.
	 * This helps in tracking which tasks are being processed and avoids duplicates.
	 *
	 * @param downloadTaskInf The download task to add to the running list
	 */
	private fun addToRunningDownloadTasksList(downloadTaskInf: DownloadTaskInf) {
		// Only add if the task is not already in the running list
		if (!runningDownloadTasks.contains(downloadTaskInf)) {
			runningDownloadTasks.add(downloadTaskInf)
			logger.d("Task added to running list: ${downloadTaskInf.downloadDataModel.fileName}")
		}
	}

	/**
	 * Removes a download task from the running tasks list if it exists.
	 *
	 * This ensures that completed, canceled, or paused tasks are no longer tracked
	 * as active, maintaining the integrity of the running tasks list.
	 *
	 * @param downloadTaskInfo The download task to remove from the running list
	 */
	private fun removeFromRunningDownloadTasksList(downloadTaskInfo: DownloadTaskInf) {
		// Only remove if the task exists in the running list
		if (runningDownloadTasks.contains(downloadTaskInfo)) {
			runningDownloadTasks.remove(downloadTaskInfo)
			logger.d("Task removed from running list: ${downloadTaskInfo.downloadDataModel.fileName}")
		}
	}

	/**
	 * Removes a download data model from the active downloads list if it exists.
	 *
	 * This ensures that downloads which are completed, deleted, or cleared
	 * are no longer tracked as active, keeping the active downloads list consistent.
	 *
	 * @param downloadDataModel The download data model to remove from active list
	 */
	private fun removeFromActiveDownloadDataModelsList(downloadDataModel: DownloadDataModel) {
		// Remove the model only if it exists in the list
		if (activeDownloadDataModels.contains(downloadDataModel)) {
			activeDownloadDataModels.remove(downloadDataModel)
			logger.d("Download removed from active list: ${downloadDataModel.fileName}")
		}
	}

	/**
	 * Starts pending download tasks from the waiting list based on allowed parallel downloads.
	 *
	 * Ensures that the number of simultaneously running tasks does not exceed
	 * the configured limit. If a queued task is available and the limit allows,
	 * it starts the download and updates the task status.
	 *
	 * @throws Exception if starting the queued download task fails
	 */
	private suspend fun startPendingTasksFromWaitingList() {
		logger.d("Starting pending tasks from waiting list.")

		// Check for any leftover running tasks that may have finished unexpectedly
		verifyLeftoverRunningTasks()

		val maxAllowedParallelDownloads = aioSettings.downloadDefaultParallelConnections
		if (numberOfRunningTasks() >= maxAllowedParallelDownloads) {
			logger.d("Max parallel downloads reached. Skipping starting new tasks.")
			return
		}

		if (waitingDownloadTasks.isEmpty()) {
			logger.d("No pending tasks to start.")
			return
		}

		// Start the first task in the waiting queue
		val queuedTask = waitingDownloadTasks.removeAt(0)
		try {
			logger.d("Starting queued task: ${queuedTask.downloadDataModel.fileName}")
			startDownloadTask(queuedTask)
		} catch (error: Exception) {
			logger.e("Failed to start queued task: ${queuedTask.downloadDataModel.fileName}", error)
			queuedTask.updateDownloadStatus(status = CLOSE)
		}
	}

	/**
	 * Executes a download task asynchronously in the background.
	 *
	 * Launches the task using a background thread and ensures that
	 * any exceptions during execution are caught. In case of failure,
	 * the task's status is updated to CLOSE to prevent it from
	 * remaining in an inconsistent state.
	 *
	 * @param downloadTaskInf The download task to start
	 */
	private fun startDownloadTask(downloadTaskInf: DownloadTaskInf) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				// Log the start of the download task
				logger.d("Starting download task: ${downloadTaskInf.downloadDataModel.fileName}")
				downloadTaskInf.startDownload()
			} catch (error: Exception) {
				// Log any exception and mark the task as closed
				logger.e("Error starting task", error)
				downloadTaskInf.updateDownloadStatus(status = CLOSE)
			}
		})
	}

	/**
	 * Cleans up any running tasks that are no longer active or have completed.
	 *
	 * Iterates through the current running download tasks and removes
	 * tasks whose associated download models are not running or not in
	 * a downloading state. This prevents stale tasks from occupying
	 * the running tasks list and ensures proper task management.
	 */
	private fun verifyLeftoverRunningTasks() {
		try {
			val iterator = runningDownloadTasks.iterator()
			while (iterator.hasNext()) {
				val runningTask = iterator.next()
				val dataModel = runningTask.downloadDataModel

				// Remove task if it is not running and not downloading
				if (!dataModel.isRunning && dataModel.status != DOWNLOADING) {
					logger.d("Removing leftover task: ${dataModel.fileName}")
					iterator.remove()
				}
			}
		} catch (error: Exception) {
			logger.e("Error verifying leftover running tasks", error)
		}
	}

	/**
	 * Generates an appropriate download task based on the type of file being downloaded.
	 *
	 * - If the download model contains video metadata (`videoInfo` and `videoFormat`),
	 *   a `VideoDownloader` is created.
	 * - Otherwise, a `RegularDownloader` is created for standard files.
	 *
	 * The created downloader is initialized and its status listener is set
	 * to the current `DownloadSystem` instance for monitoring progress.
	 *
	 * @param downloadDataModel The model representing the file to download
	 * @param coroutineScope The coroutine scope to use for asynchronous operations
	 * @return The initialized download task implementing [DownloadTaskInf]
	 */
	private suspend fun generateDownloadTask(
		downloadDataModel: DownloadDataModel,
		coroutineScope: CoroutineScope
	): DownloadTaskInf {
		logger.d("Generating download task for: ${downloadDataModel.fileName}")

		return if (downloadDataModel.videoInfo != null && downloadDataModel.videoFormat != null) {
			// Create and initialize a video downloader
			val m3U8VideoDownloader = M3U8VideoDownloader(
				downloadDataModel = downloadDataModel,
				coroutineScope = coroutineScope,
				downloadStatusListener = this@DownloadSystem
			)
			m3U8VideoDownloader.initiateDownload()
			m3U8VideoDownloader
		} else {
			// Create and initialize a regular file downloader
			val regularDownloader = RegularDownloader(
				downloadDataModel = downloadDataModel,
				coroutineScope = coroutineScope,
				downloadStatusListener = this@DownloadSystem
			)
			regularDownloader.initiateDownload()
			regularDownloader
		}
	}

	/**
	 * Updates the UI and notification for a specific download.
	 *
	 * Ensures that the active download UI is refreshed and
	 * the notification is updated to reflect the current state.
	 *
	 * @param downloadDataModel The download model whose UI and notification need updating
	 */
	private fun updateUIAndNotification(downloadDataModel: DownloadDataModel) {
		logger.d("Updating UI and notification for: ${downloadDataModel.fileName}")

		// Update active UI only if download is not complete
		if (!downloadDataModel.isComplete) {
			downloadsUIManager.updateActiveUI(downloadDataModel)
		}

		// Update the system notification
		downloadNotification.updateNotification(downloadDataModel)
	}

	/**
	 * Updates the finished downloads list according to user or system settings.
	 *
	 * Adds the download to the finished list if automatic removal is disabled
	 * or if the removal period is not zero.
	 *
	 * @param downloadDataModel The completed download to process
	 */
	private fun updateFinishedDownloadDataModelsList(downloadDataModel: DownloadDataModel) {
		logger.d("Updating finished downloads list for: ${downloadDataModel.fileName}")

		val setting = downloadDataModel.globalSettings
		if (!setting.downloadAutoRemoveTasks || setting.downloadAutoRemoveTaskAfterNDays != 0) {
			addToFinishDownloadList(downloadDataModel)
		}
	}

	/**
	 * Adds a completed download to the finished downloads list and updates the UI.
	 *
	 * Notifies the finished tasks fragment to refresh and also updates the media store.
	 *
	 * @param downloadDataModel The download to add to the finished list
	 */
	private fun addToFinishDownloadList(downloadDataModel: DownloadDataModel) {
		logger.d("Adding to finished download list: ${downloadDataModel.fileName}")

		// Insert and sort the finished downloads list
		addAndSortFinishedDownloadDataModels(downloadDataModel)

		// Update UI and media store on the main thread
		CoroutineScope(Dispatchers.Main).launch {
			downloadsUIManager.finishedTasksFragment
				?.finishedTasksListAdapter?.notifyDataSetChangedOnSort(false)
			updateMediaStore()
		}
	}

	/**
	 * Cleans up system resources used by the download system.
	 *
	 * Unregisters the timer and logs the cleanup process.
	 */
	fun cleanUp() {
		logger.d("Cleaning up DownloadSystem.")
		aioTimer.unregister(this)
		logger.d("Timer unregistered.")
	}

}