package app.core.engines.downloader

import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOTimer.AIOTimerListener
import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.downloader.DownloadURLHelper.getFileInfoFromSever
import app.core.engines.downloader.RegularDownloadPart.DownloadPartListener
import app.core.engines.settings.AIOSettings
import com.aio.R.raw
import com.aio.R.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lib.device.DateTimeUtils.calculateTime
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSizeFormatter.humanReadableSizeOf
import lib.networks.DownloaderUtils.calculateDownloadSpeed
import lib.networks.DownloaderUtils.calculateDownloadSpeedInFormat
import lib.networks.DownloaderUtils.formatDownloadSpeedInSimpleForm
import lib.networks.DownloaderUtils.getFormattedPercentage
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.DownloaderUtils.getOptimalNumberOfDownloadParts
import lib.networks.DownloaderUtils.getRemainingDownloadTime
import lib.networks.NetworkUtility.isNetworkAvailable
import lib.networks.NetworkUtility.isWifiEnabled
import lib.networks.URLUtility.getOriginalURL
import lib.networks.URLUtility.isValidURL
import lib.networks.URLUtilityKT.isInternetConnected
import lib.process.AudioPlayerUtils
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URL

/**
 * Manages a single file download using the provided [DownloadDataModel].
 *
 * This class handles:
 * - Initializing and validating the download environment
 * - Splitting the download into parts for multi-threaded downloading
 * - Tracking download progress and network speed
 * - Updating status and notifying listeners
 * - Handling retries, errors, and cancellations gracefully
 *
 * Implements [DownloadTaskInf] for lifecycle control, [DownloadPartListener]
 * for per-part progress updates, and [AIOTimerListener] for periodic progress ticks.
 *
 * @property downloadDataModel Contains metadata, progress, and configuration for this download
 * @property coroutineScope Scope for managing asynchronous download tasks safely
 * @property downloadStatusListener Optional listener for download progress and status updates
 */
class RegularDownloader(
	override val downloadDataModel: DownloadDataModel,
	override var coroutineScope: CoroutineScope,
	override var downloadStatusListener: DownloadTaskListener?
) : DownloadTaskInf, DownloadPartListener, AIOTimerListener {

	/** Logger for debug and error messages */
	private val logger: LogHelperUtils = LogHelperUtils.from(javaClass)

	/** Global download settings from the data model */
	private val downloadSettings: AIOSettings = downloadDataModel.globalSettings

	/** Destination file where downloaded data is stored */
	private var destinationOutputFile: File = downloadDataModel.getDestinationFile()

	/** List of all active download parts for this task */
	private var allDownloadParts: List<RegularDownloadPart> = emptyList()

	/** Tracks real-time download speed */
	private var netSpeedTracker: NetSpeedTracker? = null

	/**
	 * Initializes the download process by resetting the download state,
	 * preparing the destination output file, and updating the storage model.
	 *
	 * This function ensures that all download parameters are reset to a safe initial state
	 * before any network operations begin. It also logs key steps for debugging and
	 * returns whether initialization was successful.
	 *
	 * @return `true` if the initialization succeeded, `false` if an exception occurred.
	 */
	override suspend fun initiateDownload(): Boolean {
		logger.d("Initiating download process...")

		try {
			downloadDataModel.status = CLOSE
			downloadDataModel.isRunning = false
			downloadDataModel.isWaitingForNetwork = false
			downloadDataModel.resumeSessionRetryCount = 0

			destinationOutputFile = downloadDataModel.getDestinationFile()
			logger.d("Destination output file set to: ${destinationOutputFile.absolutePath}")

			val statusInfoString = getText(string.title_waiting_to_join)
			downloadDataModel.statusInfo = statusInfoString
			downloadDataModel.updateInStorage()
			logger.d("Download data model initialized and stored successfully with status: $statusInfoString")
			return true
		} catch (error: Exception) {
			logger.e("Error while initiating download", error)
			return false
		}
	}

	/**
	 * Begins the actual download operation.
	 *
	 * Responsibilities:
	 * - Configure the download model and parts
	 * - Prepare the destination file for writing
	 * - Start all download threads and register with the AIO timer
	 * - Update download status accordingly
	 * - Handle I/O or runtime errors gracefully without crashing
	 *
	 * @return `true` if the download started successfully, otherwise `false`
	 */
	override suspend fun startDownload(): Boolean {
		logger.d("Starting download process...")

		try {
			logger.d("Configuring download model...")
			configureDownloadModel()

			logger.d("Configuring download parts...")
			configureDownloadParts()

			logger.d("Preparing destination output file...")
			createEmptyOutputDestinationFile()

			logger.d("Starting all download threads...")
			val isSuccessfullyExecuted = startAllDownloadThreads()

			return if (isSuccessfullyExecuted) {
				val statusInfoString = getText(string.title_started_downloading)
				logger.d("Download threads started successfully. Updating status to DOWNLOADING.")
				updateDownloadStatus(statusInfo = statusInfoString, status = DOWNLOADING)
				aioTimer.register(this@RegularDownloader)
				true
			} else {
				val statusInfoString = getText(string.title_download_failed)
				logger.e("Failed to start download threads. Updating status to CLOSE.")
				updateDownloadStatus(statusInfo = statusInfoString, status = CLOSE)
				false
			}
		} catch (error: Exception) {
			logger.e("Unexpected error occurred while starting the download", error)
			val statusInfoString = getText(string.title_download_failed)
			updateDownloadStatus(statusInfo = statusInfoString, status = CLOSE)
			return false
		}
	}

	/**
	 * Cancels the ongoing download operation.
	 *
	 * Stops all active download parts, updates the task status, and notifies listeners.
	 * Handles exceptions gracefully and logs any errors that occur during cancellation.
	 *
	 * @param cancelReason Optional human-readable message explaining why the download is canceled.
	 *                     Defaults to a "paused" message if empty.
	 * @param isCanceledByUser Indicates whether the cancellation was initiated by the user
	 *                         (`true`) or triggered automatically by the system (`false`).
	 */
	override suspend fun cancelDownload(cancelReason: String, isCanceledByUser: Boolean) {
		logger.d("Cancelling download. Reason: '$cancelReason', User initiated: $isCanceledByUser")

		try {
			// Stop all download parts silently
			allDownloadParts.forEach { part ->
				part.stopDownloadSilently(isCanceledByUser = isCanceledByUser)
				logger.d("Stopped download part: ${part.partIndex}")
			}

			val statusMessage = cancelReason.ifEmpty { getText(string.title_paused) }
			updateDownloadStatus(statusInfo = statusMessage, status = CLOSE)
			logger.d("Download canceled successfully. Status updated to CLOSE with message: '$statusMessage'")
		} catch (error: Exception) {
			logger.e("Error while canceling download process", error)
		} finally {
			coroutineScope.cancel()
		}
	}

	/**
	 * Handles the event when a download part is canceled.
	 *
	 * This function determines whether the cancellation was user-initiated or due to a
	 * critical error. It handles:
	 * - Critical errors such as expired URLs or missing destination files
	 * - Restarting parts if cancellation was not critical
	 * - Updating the download model in storage
	 * - Deleting the destination file if the task is marked as deleted
	 *
	 * @param downloadPart The [RegularDownloadPart] that was canceled
	 */
	@Synchronized
	override fun onPartCanceled(downloadPart: RegularDownloadPart) {
		logger.d("Download part canceled: ${downloadPart.partIndex}," +
				"user initiated: ${downloadPart.isPartCanceledByUser}")

		coroutineScope.launch {
			if (downloadPart.isPartCanceledByUser) return@launch

			if (isCriticalErrorFoundInDownloadPart(downloadPart)) {
				when {
					downloadDataModel.isFileUrlExpired -> {
						val cancelReason = getText(string.title_link_expired_paused)
						logger.d("Critical error: file URL expired. Canceling download.")
						cancelDownload(cancelReason = cancelReason)
						return@launch
					}

					downloadDataModel.isDestinationFileNotExisted -> {
						val cancelReason = getText(string.title_file_deleted_paused)
						logger.d("Critical error: destination file missing. Canceling download.")
						cancelDownload(cancelReason = cancelReason)
						return@launch
					}
				}
			} else {
				logger.d("Non-critical part cancellation. Attempting to restart part: ${downloadPart.partIndex}")
				tryRestartingDownloadPart(regularDownloadPart = downloadPart)
			}

			downloadDataModel.updateInStorage()
			logger.d("Download model updated in storage after part cancellation.")

			if (!downloadDataModel.isRemoved && downloadDataModel.isDeleted) {
				if (destinationOutputFile.exists()) {
					destinationOutputFile.delete()
					logger.d("Destination file deleted as part of cleanup: ${destinationOutputFile.absolutePath}")
				}
			}
		}
	}

	/**
	 * Handles the event when a download part completes.
	 *
	 * Responsibilities:
	 * - Check if all parts are fully downloaded
	 * - Play a notification sound if enabled
	 * - Update the download model to mark the task as complete
	 * - Update status and notify listeners
	 *
	 * @param downloadPart The [RegularDownloadPart] that has just completed
	 */
	@Synchronized
	override fun onPartCompleted(downloadPart: RegularDownloadPart) {
		logger.d("Download part completed: ${downloadPart.partIndex}")

		coroutineScope.launch {
			var allPartsCompleted = true

			for ((index, part) in allDownloadParts.withIndex()) {
				if (!allPartsCompleted) break

				// Check expected vs actual downloaded bytes for known file sizes
				if (!downloadDataModel.isUnknownFileSize && part.partChunkSize > 0) {
					val expectedSize = downloadDataModel.partChunkSizes[index]
					val actualSize = part.partDownloadedByte
					if (actualSize < expectedSize) {
						allPartsCompleted = false
						logger.d("Part $index incomplete: expected $expectedSize, actual $actualSize")
						break
					}
				}

				// Check part status
				if (part.partDownloadStatus != COMPLETE) {
					allPartsCompleted = false
					logger.d("Part $index status not COMPLETE: ${part.partDownloadStatus}")
					break
				}
			}

			if (!allPartsCompleted) return@launch

			logger.d("All download parts completed successfully")

			if (downloadSettings.downloadPlayNotificationSound) {
				logger.d("Playing download finished notification sound")
				AudioPlayerUtils(INSTANCE).play(raw.sound_download_finished)
			}

			downloadDataModel.isRunning = false
			downloadDataModel.isComplete = true
			val statusInfoString = getText(string.title_completed)
			updateDownloadStatus(statusInfo = statusInfoString, status = COMPLETE)
			logger.d("Download marked as COMPLETE with status message: '$statusInfoString'")
		}
	}

	/**
	 * Called periodically by the [AIOTimer] to update download progress.
	 *
	 * Responsibilities:
	 * - Delegate to [handleDownloadProgress] to manage progress updates
	 * - Ensure proper handling of inactive or completed downloads
	 *
	 * @param loopCount The current loop count from the AIOTimer
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		logger.d("AIOTimer tick: loopCount=$loopCount")

		// Only update progress on every 2nd tick
		if (loopCount.toInt() % 3 == 0) {
			handleDownloadProgress()
			logger.d("Progress updated on loopCount=$loopCount")
		}
	}

	/**
	 * Updates the current status and metadata of the download task.
	 *
	 * Responsibilities:
	 * - Update the [downloadDataModel] with the new status and info message
	 * - Reflect changes in persistent storage
	 * - Notify listeners about the updated status
	 * - Unregister from [AIOTimer] if the download is no longer active
	 *
	 * @param statusInfo Optional detailed status message to display
	 * @param status The new download status (from DownloadStatus constants)
	 */
	override suspend fun updateDownloadStatus(statusInfo: String?, status: Int) {
		logger.d("Updating download status: status=$status, info=$statusInfo")

		if (!statusInfo.isNullOrEmpty()) downloadDataModel.statusInfo = statusInfo
		downloadDataModel.status = status
		downloadDataModel.isRunning = (status == DOWNLOADING)
		downloadDataModel.isComplete = (status == COMPLETE)

		logger.d(
			"Status updated in model -> isRunning=${downloadDataModel.isRunning}, " +
					"isComplete=${downloadDataModel.isComplete}"
		)
		downloadDataModel.updateInStorage()

		downloadStatusListener?.onStatusUpdate(this@RegularDownloader)
		logger.d("Status listener notified for status: $status")

		if (!downloadDataModel.isRunning && downloadDataModel.status != DOWNLOADING) {
			logger.d("Download inactive — unregistering from AIO timer")
			aioTimer.unregister(this@RegularDownloader)
			if (downloadDataModel.status == COMPLETE) {
				coroutineScope.cancel()
			}
		}
	}

	/**
	 * Configures and validates the core parameters of the current download model.
	 *
	 * Responsibilities:
	 * - Validate and prepare the [downloadDataModel] before starting download
	 * - Restore any previous download session if available
	 * - Apply user-defined settings such as auto-resume, auto-remove, and URL filtering
	 * - Retrieve and configure remote file information (size, name, resume support)
	 * - Calculate part ranges for multi-threaded downloads
	 *
	 * This function ensures that the download task is properly set up
	 * before threads are initiated.
	 */
	private suspend fun configureDownloadModel() {
		logger.d("Starting download model configuration...")

		val statusInfoString = getText(string.title_validating_download_task)
		updateDownloadStatus(statusInfo = statusInfoString)
		logger.d("Validating download task and checking for previous data...")

		if (doesDownloadModelHasPreviousData()) {
			logger.d("Previous download data found. Skipping reconfiguration.")
			return
		}

		logger.d("Applying download settings and preparing file information...")
		configureDownloadAutoResumeSettings()
		configureDownloadAutoRemoveSettings()
		configureDownloadAutoFilterURL()
		configureDownloadFileInfo()
		configureDownloadPartRange()

		logger.d("Download model configuration completed successfully.")
	}

	/**
	 * Checks whether the current download model has any previously saved progress.
	 *
	 * Responsibilities:
	 * - Detect partially downloaded data and prepare for resumption.
	 * - Validate the existence of the output file corresponding to saved progress.
	 * - Cancel the download gracefully if the file is missing or inaccessible.
	 *
	 * @return `true` if previous download data exists and can be resumed, otherwise `false`.
	 */
	private suspend fun doesDownloadModelHasPreviousData(): Boolean {
		logger.d("Checking for previously downloaded data...")

		val isPreviousDataFound = downloadDataModel.downloadedByte > 0
		if (isPreviousDataFound) {
			logger.d("Previous download data detected (bytes=${downloadDataModel.downloadedByte}).")
			val statusInfoString = getText(string.title_previous_download_data_found)
			updateDownloadStatus(statusInfo = statusInfoString)

			if (!destinationOutputFile.exists()) {
				logger.e("Previous data found, but destination file is missing.")
				downloadDataModel.isFailedToAccessFile = true
				val cancelReasonString = getText(string.title_failed_deleted_paused)
				cancelDownload(cancelReason = cancelReasonString)
			}
		} else {
			logger.d("No previous download data found.")
		}

		return isPreviousDataFound
	}

	/**
	 * Configures the auto-resume behavior for downloads.
	 *
	 * Responsibilities:
	 * - Update the download status to indicate configuration progress.
	 * - Adjust retry and error-handling settings based on user preferences.
	 * - Disable retry logic entirely if auto-resume is turned off.
	 */
	private suspend fun configureDownloadAutoResumeSettings() {
		logger.d("Configuring auto-resume settings...")
		val statusInfoString = getText(string.title_configuring_auto_resume)
		updateDownloadStatus(statusInfo = statusInfoString)

		if (!downloadSettings.downloadAutoResume) {
			logger.d("Auto-resume is disabled; setting max retry errors to 0.")
			downloadSettings.downloadAutoResumeMaxErrors = 0
		} else {
			logger.d("Auto-resume is enabled with max errors = ${downloadSettings.downloadAutoResumeMaxErrors}")
		}
	}

	/**
	 * Configures automatic task removal behavior.
	 *
	 * Responsibilities:
	 * - Update status to indicate configuration progress.
	 * - Set cleanup schedule for completed downloads based on user settings.
	 * - Disable automatic cleanup if the feature is turned off.
	 */
	private suspend fun configureDownloadAutoRemoveSettings() {
		logger.d("Configuring auto-remove settings...")
		val statusInfoString = getText(string.title_configuring_auto_remove)
		updateDownloadStatus(statusInfo = statusInfoString)

		if (!downloadSettings.downloadAutoRemoveTasks) {
			logger.d("Auto-remove disabled; setting cleanup days to 0.")
			downloadSettings.downloadAutoRemoveTaskAfterNDays = 0
		} else {
			logger.d("Auto-remove enabled; removing tasks after ${downloadSettings.downloadAutoRemoveTaskAfterNDays} days.")
		}
	}

	/**
	 * Applies automatic URL redirection and filtering if enabled.
	 *
	 * Responsibilities:
	 * - Verify and resolve redirected URLs when link redirection is allowed.
	 * - Update the download model with the final, valid URL if detected.
	 * - Skip processing if URL filtering is disabled in settings.
	 */
	private suspend fun configureDownloadAutoFilterURL() {
		logger.d("Configuring URL redirection/filtering...")
		if (!downloadSettings.downloadAutoLinkRedirection) {
			logger.d("Auto-link redirection is disabled; skipping.")
			return
		}

		val statusInfoString = getText(string.title_filtering_url)
		updateDownloadStatus(statusInfo = statusInfoString)

		val originalURL = getOriginalURL(downloadDataModel.fileURL)
		if (!originalURL.isNullOrEmpty()) {
			logger.d("Redirected URL detected. Updating file URL from '${downloadDataModel.fileURL}' to '$originalURL'.")
			downloadDataModel.fileURL = originalURL
		} else {
			logger.d("No redirection detected; keeping original URL.")
		}
	}

	/**
	 * Retrieves and configures detailed information about the file being downloaded.
	 *
	 * Responsibilities:
	 * - Validate the download URL before proceeding.
	 * - Fetch file metadata (size, name, checksum, and resume/multipart support) from the server.
	 * - Update the download model with accurate file information.
	 * - Handle edge cases like unknown file size or unsupported resume/multipart downloads.
	 *
	 * This step ensures that the downloader has a complete and valid file profile
	 * before initiating the download process.
	 */
	private suspend fun configureDownloadFileInfo() {
		logger.d("Starting file info configuration...")

		if (downloadDataModel.fileSize > 0) {
			logger.d("File size already known (${downloadDataModel.fileSize} bytes), skipping fetch.")
			return
		}

		val statusInfoString = getText(string.title_recalculating_file_size)
		updateDownloadStatus(statusInfo = statusInfoString)

		if (!isValidURL(downloadDataModel.fileURL)) {
			logger.e("Invalid file URL: ${downloadDataModel.fileURL}")
			val cancelReasonString = getText(string.title_invalid_file_url)
			cancelDownload(cancelReason = cancelReasonString)
			return
		}

		try {
			val fileURL = URL(downloadDataModel.fileURL)
			val remoteFileInfo = getFileInfoFromSever(fileURL)
			logger.d(
				"Fetched remote file info: " +
						"size=${remoteFileInfo.fileSize}, " +
						"name='${remoteFileInfo.fileName}', " +
						"resume=${remoteFileInfo.isSupportsResume}"
			)

			downloadDataModel.remoteFileInfo = remoteFileInfo
			downloadDataModel.fileSize = remoteFileInfo.fileSize
			downloadDataModel.fileChecksum = remoteFileInfo.fileChecksum

			if (downloadDataModel.fileName.isEmpty()) {
				downloadDataModel.fileName = remoteFileInfo.fileName
				logger.d("Assigned file name: ${remoteFileInfo.fileName}")
			}

			downloadDataModel.isUnknownFileSize = false
			downloadDataModel.isResumeSupported = remoteFileInfo.isSupportsResume
			downloadDataModel.isMultiThreadSupported = remoteFileInfo.isSupportsMultipart
			downloadDataModel.fileSizeInFormat = humanReadableSizeOf(downloadDataModel.fileSize)

			if (downloadDataModel.isMultiThreadSupported) {
				val downloadThreads = getOptimalNumberOfDownloadParts(downloadDataModel.fileSize)
				downloadSettings.downloadDefaultThreadConnections = downloadThreads
				logger.d("Multi-threaded download enabled with $downloadThreads threads.")
			}

			// Handle unknown or invalid file size
			if (downloadDataModel.fileSize <= 1) {
				logger.e("File size could not be determined. Marking as unknown.")
				downloadDataModel.isUnknownFileSize = true
				downloadDataModel.isMultiThreadSupported = false
				downloadSettings.downloadDefaultThreadConnections = 1
				downloadDataModel.fileSizeInFormat = getText(string.title_unknown)
			}
		} catch (error: Exception) {
			logger.e("Error fetching file information from server:", error)
			val cancelReasonString = getText(string.title_download_io_failed)
			cancelDownload(cancelReason = cancelReasonString)
		}
	}

	/**
	 * Configures the byte range allocation for each download part.
	 *
	 * Responsibilities:
	 * - Divide the total file size into equal chunks across available threads.
	 * - Handle unknown file size cases gracefully (assign single-part download).
	 * - Align part boundaries to a specified block size for optimal I/O performance.
	 * - Store computed ranges (start, end, size) in the download model.
	 */
	private fun configureDownloadPartRange() {
		logger.d("Configuring download part ranges...")

		val numberOfThreads = downloadSettings.downloadDefaultThreadConnections
		logger.d("Using $numberOfThreads thread(s) for download partitioning.")

		// Initialize data structures
		downloadDataModel.partStartingPoint = LongArray(numberOfThreads)
		downloadDataModel.partEndingPoint = LongArray(numberOfThreads)
		downloadDataModel.partChunkSizes = LongArray(numberOfThreads)
		downloadDataModel.partsDownloadedByte = LongArray(numberOfThreads)
		downloadDataModel.partProgressPercentage = IntArray(numberOfThreads)

		if (downloadDataModel.isUnknownFileSize || downloadDataModel.fileSize < 1) {
			logger.d("Unknown file size detected. Assigning single-part range.")
			downloadDataModel.partChunkSizes[0] = downloadDataModel.fileSize
			return
		}

		// Calculate and apply aligned part ranges
		val ranges = calculateAlignedPartRanges(downloadDataModel.fileSize, numberOfThreads)
		ranges.forEachIndexed { index, (start, end) ->
			downloadDataModel.partStartingPoint[index] = start
			downloadDataModel.partEndingPoint[index] = end
			downloadDataModel.partChunkSizes[index] = end - start + 1
			logger.d("Part $index → Start: $start, End: $end, Size: ${end - start + 1}")
		}
	}

	/**
	 * Splits a file into multiple aligned ranges for concurrent downloading.
	 *
	 * @param fileSize Total size of the file in bytes.
	 * @param numberOfThreads Number of parallel download threads.
	 * @param alignmentBoundary Byte boundary for alignment (default 4 KB).
	 *
	 * @return List of pairs containing (startByte, endByte) for each part.
	 */
	private fun calculateAlignedPartRanges(
		fileSize: Long,
		numberOfThreads: Int,
		alignmentBoundary: Long = 4096L
	): List<Pair<Long, Long>> {
		logger.d("Calculating aligned part ranges: fileSize=$fileSize, threads=$numberOfThreads")

		val basePartSize = fileSize / numberOfThreads
		val ranges = mutableListOf<Pair<Long, Long>>()

		for (threadNumber in 0 until numberOfThreads) {
			val startByte = threadNumber * basePartSize
			val endByte = if (threadNumber == numberOfThreads - 1) fileSize - 1
			else alignToBoundary(startByte + basePartSize - 1, alignmentBoundary)

			ranges.add(Pair(startByte, endByte))
			logger.d("Thread #$threadNumber → Range [$startByte - $endByte]")
		}

		return ranges
	}

	/**
	 * Aligns a given byte position to the nearest upper multiple of the specified boundary.
	 *
	 * @param position Original byte position.
	 * @param boundary Block size for alignment (default: 4096 bytes).
	 * @return Aligned byte position.
	 */
	private fun alignToBoundary(position: Long, boundary: Long): Long {
		val alignedPosition = ((position + boundary - 1) / boundary) * boundary
		logger.d("Aligning position $position to boundary $boundary → $alignedPosition")
		return alignedPosition
	}

	/**
	 * Initializes and prepares the list of download part objects for the current task.
	 *
	 * Each part corresponds to a range of bytes defined in the model.
	 * This step is essential before starting parallel downloads.
	 */
	private suspend fun configureDownloadParts() {
		logger.d("Generating download part instances...")
		allDownloadParts = generateDownloadParts()
		logger.d("Total ${allDownloadParts.size} download part(s) configured.")
	}

	/**
	 * Generates and initializes download parts for the current task.
	 *
	 * Responsibilities:
	 * - Create `RegularDownloadPart` instances for each thread.
	 * - Assign part index, start/end byte ranges, and previously downloaded byte count.
	 * - Update the status to reflect ongoing preparation.
	 *
	 * @return A list of initialized [RegularDownloadPart] instances ready for download.
	 */
	private suspend fun generateDownloadParts(): List<RegularDownloadPart> {
		logger.d("Generating download parts...")

		val statusInfoString = getText(string.title_generating_download_parts)
		updateDownloadStatus(statusInfo = statusInfoString)

		val numberOfThreads = downloadSettings.downloadDefaultThreadConnections
		val regularDownloadParts = mutableListOf<RegularDownloadPart>()

		for (index in 0 until numberOfThreads) {
			val downloadPart = RegularDownloadPart(regularDownloader = this@RegularDownloader)
			downloadPart.initiate(
				downloadPartIndex = index,
				downloadStartingPoint = downloadDataModel.partStartingPoint[index],
				downloadEndingPoint = downloadDataModel.partEndingPoint[index],
				downloadChunkSize = downloadDataModel.partChunkSizes[index],
				downloadedByte = downloadDataModel.partsDownloadedByte[index]
			)

			logger.d(
				"Initialized part #$index → " +
						"Start=${downloadDataModel.partStartingPoint[index]}, " +
						"End=${downloadDataModel.partEndingPoint[index]}, " +
						"Downloaded=${downloadDataModel.partsDownloadedByte[index]}"
			)
			regularDownloadParts.add(downloadPart)
		}

		logger.d("Successfully generated ${regularDownloadParts.size} download parts.")
		return regularDownloadParts
	}

	/**
	 * Creates an empty file at the target destination before download begins.
	 *
	 * Responsibilities:
	 * - Ensure the output file exists and is accessible.
	 * - Pre-allocate file size using `RandomAccessFile` if multi-threaded downloading is enabled.
	 * - Handle I/O exceptions gracefully and update download status accordingly.
	 *
	 * This method prevents partial corruption issues by reserving the file size upfront.
	 */
	private suspend fun createEmptyOutputDestinationFile() {
		if (downloadDataModel.isDeleted) {
			logger.d("Skipping file creation: download marked as deleted.")
			return
		}

		try {
			if (destinationOutputFile.exists()) {
				logger.d("Destination file already exists: ${destinationOutputFile.path}")
				return
			}

			if (canUseMultiThreadedDownload()) {
				val statusInfoString = getText(string.title_creating_empty_destination_file)
				updateDownloadStatus(statusInfo = statusInfoString)
				logger.d("Creating empty destination file: ${destinationOutputFile.path}")
				RandomAccessFile(destinationOutputFile, "rw").setLength(downloadDataModel.fileSize)
			}
		} catch (error: IOException) {
			logger.e("Error while creating an empty output destination file:", error)

			downloadDataModel.resumeSessionRetryCount++
			downloadDataModel.totalTrackedConnectionRetries++
			downloadDataModel.isFailedToAccessFile = true

			val cancelReasonString = getText(string.title_download_io_failed)
			cancelDownload(cancelReason = cancelReasonString)
		}
	}

	/**
	 * Determines whether multi-threaded downloading can be used for the current task.
	 *
	 * Conditions for enabling multi-threading:
	 * - The file size is known and greater than zero.
	 * - Multi-threading is supported by the download source.
	 * - The configured thread count is greater than one.
	 *
	 * @return `true` if multi-threaded download is allowed, otherwise `false`.
	 */
	@Synchronized
	private fun canUseMultiThreadedDownload(): Boolean {
		val maxDownloadThreads = downloadSettings.downloadDefaultThreadConnections
		val isNotUnknownFileSize = !downloadDataModel.isUnknownFileSize
		val isMultiThreadingSupported =
			(downloadDataModel.fileSize > 0) && (maxDownloadThreads > 1) && isNotUnknownFileSize

		logger.d(
			"Multi-thread check → " +
					"FileSize=${downloadDataModel.fileSize}, " +
					"Threads=$maxDownloadThreads, " +
					"Allowed=$isMultiThreadingSupported"
		)

		return isMultiThreadingSupported
	}

	/**
	 * Starts all download threads for the current task.
	 *
	 * Responsibilities:
	 * - Validate file accessibility before starting.
	 * - Launch each [RegularDownloadPart] if not already downloading.
	 * - Skip any parts that are already running.
	 *
	 * @return `true` if threads were started successfully, otherwise `false`.
	 */
	@Synchronized
	private fun startAllDownloadThreads(): Boolean {
		if (downloadDataModel.isFailedToAccessFile) {
			val errorMsgString = getText(string.text_failed_to_write_file_to_storage)
			downloadDataModel.msgToShowUserViaDialog = errorMsgString
			logger.e("Cannot start download threads: failed to access destination file.")
			return false
		}

		logger.d("Starting all download threads (${allDownloadParts.size} parts)...")
		allDownloadParts.forEach { downloadPart ->
			if (downloadPart.partDownloadStatus != DOWNLOADING) {
				logger.d("Starting part #${downloadPart.partIndex}")
				downloadPart.startDownload()
			}
		}
		logger.d("All download threads started successfully.")
		return true
	}

	/**
	 * Checks if the specified download part encountered a critical error.
	 *
	 * Conditions for critical error:
	 * - The download part threw a [FileNotFoundException].
	 * - The target file or its URL is no longer accessible.
	 *
	 * If a critical error is detected:
	 * - Flags the model with `isFileUrlExpired` or `isDestinationFileNotExisted`.
	 *
	 * @param regularDownloadPart The part to check for critical errors.
	 * @return `true` if a critical error was found, otherwise `false`.
	 */
	@Synchronized
	private fun isCriticalErrorFoundInDownloadPart(regularDownloadPart: RegularDownloadPart): Boolean {
		val error = regularDownloadPart.partDownloadErrorException
		if (error != null && error is FileNotFoundException) {
			logger.e("Critical error found in part #${regularDownloadPart.partIndex}: ${error.message}")
			downloadDataModel.isFileUrlExpired = true
			if (!destinationOutputFile.exists()) {
				downloadDataModel.isDestinationFileNotExisted = true
				logger.d("Destination file no longer exists: ${destinationOutputFile.path}")
			}
			return true
		}
		return false
	}

	/**
	 * Determines whether retrying the download is currently allowed.
	 *
	 * Conditions for allowing retry:
	 * - The download is still running.
	 * - The number of retry attempts is less than the allowed maximum.
	 *
	 * @return `true` if retrying is permitted, otherwise `false`.
	 */
	@Synchronized
	private fun isRetryingDownloadAllowed(): Boolean {
		val maxErrorAllowed = downloadSettings.downloadAutoResumeMaxErrors
		val sessionRetryCount = downloadDataModel.resumeSessionRetryCount
		val retryAllowed = downloadDataModel.isRunning && sessionRetryCount < maxErrorAllowed

		logger.d("Retry check → Allowed=$retryAllowed (RetryCount=$sessionRetryCount / Max=$maxErrorAllowed)")
		return retryAllowed
	}

	/**
	 * Attempts to restart a specific download part when it fails or is interrupted.
	 *
	 * Responsibilities:
	 * - Check if retrying is allowed based on retry limits and session state.
	 * - Handle various network conditions:
	 *   - Wait for network connection if unavailable.
	 *   - Pause until Wi-Fi is available (if Wi-Fi-only mode is enabled).
	 *   - Resume download when the network becomes available again.
	 * - Increment retry counters to track reconnection attempts.
	 *
	 * This function ensures that failed download parts automatically recover
	 * without manual intervention when possible.
	 *
	 * @param regularDownloadPart The download part to attempt restarting.
	 */
	private suspend fun tryRestartingDownloadPart(regularDownloadPart: RegularDownloadPart) {
		if (!isRetryingDownloadAllowed()) {
			logger.d("Retry not allowed — max retry count reached or download stopped.")
			return
		}

		// Network unavailable
		if (!isNetworkAvailable()) {
			logger.d("Network unavailable — waiting for reconnection.")
			downloadDataModel.isWaitingForNetwork = true
			val statusInfoString = getText(string.title_waiting_for_network)
			updateDownloadStatus(statusInfoString)
			return
		}

		// Wi-Fi only mode check
		if (downloadSettings.downloadWifiOnly && !isWifiEnabled()) {
			logger.d("Wi-Fi-only mode enabled — waiting for Wi-Fi connection.")
			downloadDataModel.isWaitingForNetwork = true
			val statusInfoString = getText(string.title_waiting_for_wifi)
			updateDownloadStatus(statusInfoString)
			return
		}

		// Internet check for specific part
		if (!regularDownloadPart.isInternetConnected()) {
			logger.d(
				"No internet detected for part " +
						"#${regularDownloadPart.partIndex} — waiting for connectivity."
			)
			downloadDataModel.isWaitingForNetwork = true
			val statusInfoString = getText(string.title_waiting_for_internet)
			updateDownloadStatus(statusInfoString)
			return
		}

		// Resume if previously waiting for network
		if (downloadDataModel.isWaitingForNetwork) {
			downloadDataModel.isWaitingForNetwork = false
			val statusInfoString = getText(string.title_started_downloading)
			updateDownloadStatus(statusInfoString)

			val partDownloadStatus = regularDownloadPart.partDownloadStatus
			if (partDownloadStatus != DOWNLOADING && partDownloadStatus != COMPLETE) {
				logger.d("Restarting download part #${regularDownloadPart.partIndex}")
				regularDownloadPart.startDownload()
			}
		}

		// Increment retry counters
		downloadDataModel.resumeSessionRetryCount++
		downloadDataModel.totalTrackedConnectionRetries++
		logger.d(
			"Retry counters updated → " +
					"SessionRetries=${downloadDataModel.resumeSessionRetryCount}, " +
					"TotalRetries=${downloadDataModel.totalTrackedConnectionRetries}"
		)
	}

	/**
	 * Handles periodic updates of the download progress.
	 *
	 * Responsibilities:
	 * - Check if the download is still running and in the DOWNLOADING state
	 * - Trigger progress updates via [updateDownloadProgress]
	 * - Unregister the [aioTimer] if the download is no longer active
	 */
	@Synchronized
	private fun handleDownloadProgress() {
		logger.d("Handling download progress. Running: ${downloadDataModel.isRunning}, Status: ${downloadDataModel.status}")

		coroutineScope.launch {
			if (downloadDataModel.isRunning && downloadDataModel.status == DOWNLOADING) {
				logger.d("Download is active. Updating progress...")
				updateDownloadProgress()
			} else {
				logger.d("Download inactive. Unregistering AIOTimer.")
				aioTimer.unregister(this@RegularDownloader)
			}
		}
	}

	/**
	 * Periodically updates the overall download progress.
	 *
	 * Responsibilities:
	 * - Aggregate progress from all download parts.
	 * - Validate and update real-time download metrics.
	 * - Trigger retry logic for failed parts if needed.
	 * - Persist progress to storage and update UI via status listeners.
	 *
	 * This function is typically invoked on timer ticks to ensure smooth,
	 * synchronized progress tracking.
	 */
	@Synchronized
	private fun updateDownloadProgress() {
		coroutineScope.launch {
			logger.d("Updating download progress for task: ${downloadDataModel.fileName}")
			calculateProgressAndModifyDownloadModel()
			checkNetworkConnectionAndRetryDownload()
			updateDownloadStatus()
			logger.d("Download progress updated successfully for: ${downloadDataModel.fileName}")
		}
	}

	/**
	 * Recalculates and updates the core metrics for the current download session.
	 *
	 * Tasks include:
	 * - Computing total downloaded bytes and percentage.
	 * - Measuring average, real-time, and max download speeds.
	 * - Estimating remaining time and ensuring file metadata accuracy.
	 * - Persisting updated model data to storage.
	 *
	 * This method keeps the `DownloadDataModel` state accurate and up-to-date.
	 */
	@Synchronized
	private fun calculateProgressAndModifyDownloadModel() {
		logger.d("Recalculating download metrics for: ${downloadDataModel.fileName}")
		calculateTotalDownloadedTime()
		calculateDownloadedBytes()
		calculateDownloadPercentage()
		updateLastModificationDate()
		calculateAverageDownloadSpeed()
		calculateRealtimeDownloadSpeed()
		calculateMaxDownloadSpeed()
		calculateRemainingDownloadTime()
		validatingDownloadCompletion()
		downloadDataModel.updateInStorage()
		logger.d("Metrics recalculated and saved for: ${downloadDataModel.fileName}")
	}

	/**
	 * Validates the completion status of all download parts.
	 *
	 * Responsibilities:
	 * - Ensure each download part completes according to its assigned chunk size.
	 * - Silently stop any part that exceeds or matches its expected size.
	 * - Prevent redundant downloads or overflows in part handling.
	 */
	@Synchronized
	private fun validatingDownloadCompletion() {
		if (downloadDataModel.isUnknownFileSize) return
		if (downloadDataModel.fileSize < 1) return

		allDownloadParts.forEach {
			if (it.partDownloadedByte >= it.partChunkSize) {
				if (it.partDownloadStatus != COMPLETE) {
					logger.d("Marking part #${it.partIndex} as complete — stopping silently.")
					it.stopDownloadSilently()
				}
			}
		}
	}

	/**
	 * Aggregates downloaded bytes from all active download parts.
	 *
	 * Responsibilities:
	 * - Update total bytes downloaded across all parts.
	 * - Track per-part downloaded bytes and progress percentages.
	 * - Convert downloaded bytes into a human-readable format.
	 * - Adjust file size if multi-threading is disabled and the file size is unknown.
	 */
	@Synchronized
	private fun calculateDownloadedBytes() {
		logger.d("Calculating total downloaded bytes for: ${downloadDataModel.fileName}")
		downloadDataModel.downloadedByte = 0

		allDownloadParts.forEach { downloadPart ->
			downloadDataModel.downloadedByte += downloadPart.partDownloadedByte
			downloadDataModel.partsDownloadedByte[downloadPart.partIndex] = downloadPart.partDownloadedByte
			downloadDataModel.partProgressPercentage[downloadPart.partIndex] =
				if (downloadDataModel.isUnknownFileSize) 0 else
					((downloadPart.partDownloadedByte * 100) / downloadPart.partChunkSize).toInt()
		}

		downloadDataModel.downloadedByteInFormat = getHumanReadableFormat(downloadDataModel.downloadedByte)

		if (!canUseMultiThreadedDownload() && downloadDataModel.isUnknownFileSize) {
			downloadDataModel.fileSize = downloadDataModel.getDestinationFile().length()
			downloadDataModel.fileSizeInFormat = humanReadableSizeOf(downloadDataModel.fileSize)
			logger.d("Adjusted unknown file size: ${downloadDataModel.fileSizeInFormat}")
		}
		logger.d("Downloaded bytes calculated: ${downloadDataModel.downloadedByteInFormat}")
	}

	/**
	 * Calculates the overall download completion percentage.
	 *
	 * Responsibilities:
	 * - Compute percentage based on total downloaded bytes and file size.
	 * - Provide a formatted string representation of the progress.
	 */
	@Synchronized
	private fun calculateDownloadPercentage() {
		downloadDataModel.progressPercentage =
			if (downloadDataModel.isUnknownFileSize) 0 else {
				if (downloadDataModel.fileSize != 0L) {
					(downloadDataModel.downloadedByte * 100) / downloadDataModel.fileSize
				} else 0
			}

		downloadDataModel.progressPercentageInFormat = getFormattedPercentage(downloadDataModel)
		logger.d("Download progress updated: ${downloadDataModel.progressPercentageInFormat}")
	}

	/**
	 * Updates the last modification timestamp for the download.
	 *
	 * Responsibilities:
	 * - Record the current system time as the last modification date.
	 * - Store a formatted string representation of the timestamp.
	 */
	@Synchronized
	private fun updateLastModificationDate() {
		downloadDataModel.lastModifiedTimeDate = System.currentTimeMillis()
		downloadDataModel.lastModifiedTimeDateInFormat =
			millisToDateTimeString(downloadDataModel.lastModifiedTimeDate)
		logger.d("Last modification timestamp updated: ${downloadDataModel.lastModifiedTimeDateInFormat}")
	}

	/**
	 * Computes the average download speed based on total bytes downloaded and elapsed time.
	 *
	 * Responsibilities:
	 * - Calculate numeric speed and store it in bytes/sec.
	 * - Convert the numeric speed into a human-readable format for display.
	 */
	@Synchronized
	private fun calculateAverageDownloadSpeed() {
		val downloadedByte = downloadDataModel.downloadedByte
		val downloadedTime = downloadDataModel.timeSpentInMilliSec

		downloadDataModel.averageSpeed = calculateDownloadSpeed(downloadedByte, downloadedTime)
		downloadDataModel.averageSpeedInFormat = calculateDownloadSpeedInFormat(downloadedByte, downloadedTime)
		logger.d("Average download speed: ${downloadDataModel.averageSpeedInFormat}")
	}

	/**
	 * Calculates the real-time download speed based on recent bytes downloaded.
	 *
	 * Responsibilities:
	 * - Initialize a speed tracker if not present.
	 * - Update the real-time speed in bytes/sec.
	 * - Format the speed for display.
	 * - Reset speed to zero if the download is not running.
	 */
	@Synchronized
	private fun calculateRealtimeDownloadSpeed() {
		val bytesDownloaded = downloadDataModel.downloadedByte
		if (netSpeedTracker == null) {
			netSpeedTracker = NetSpeedTracker(initialBytesDownloaded = bytesDownloaded)
			logger.d("NetSpeedTracker initialized")
		}

		netSpeedTracker?.let {
			it.update(bytesDownloaded)
			val currentSpeed = it.getCurrentSpeed()

			downloadDataModel.realtimeSpeed = if (currentSpeed < 0) 0 else currentSpeed
			downloadDataModel.realtimeSpeedInFormat = if (currentSpeed < 0)
				formatDownloadSpeedInSimpleForm(0.0) else it.getFormattedSpeed()

			if (!downloadDataModel.isRunning) {
				downloadDataModel.realtimeSpeed = 0
				downloadDataModel.realtimeSpeedInFormat = "--"
			}

			logger.d("Realtime speed: ${downloadDataModel.realtimeSpeedInFormat}")
		}
	}

	/**
	 * Updates the maximum download speed if the current real-time speed exceeds the previous maximum.
	 */
	@Synchronized
	private fun calculateMaxDownloadSpeed() {
		if (downloadDataModel.realtimeSpeed > downloadDataModel.maxSpeed) {
			downloadDataModel.maxSpeed = downloadDataModel.realtimeSpeed
			downloadDataModel.maxSpeedInFormat =
				formatDownloadSpeedInSimpleForm(downloadDataModel.maxSpeed.toDouble())
			logger.d("Max download speed updated: ${downloadDataModel.maxSpeedInFormat}")
		}
	}

	/**
	 * Updates the total time spent downloading in milliseconds and formatted string.
	 */
	@Synchronized
	private fun calculateTotalDownloadedTime() {
		if (!downloadDataModel.isWaitingForNetwork) {
			downloadDataModel.timeSpentInMilliSec += 200
		}

		val timeSpentInMillis = downloadDataModel.timeSpentInMilliSec.toFloat()
		downloadDataModel.timeSpentInFormat =
			calculateTime(timeSpentInMillis, getText(string.title_spent))

		logger.d("Total download time updated: ${downloadDataModel.timeSpentInFormat}")
	}

	/**
	 * Estimates the remaining download time based on average speed and remaining bytes.
	 * Sets formatted string for display.
	 */
	@Synchronized
	private fun calculateRemainingDownloadTime() {
		if (!downloadDataModel.isUnknownFileSize || !downloadDataModel.isWaitingForNetwork) {
			val remainingByte = downloadDataModel.fileSize - downloadDataModel.downloadedByte
			val averageSpeed = downloadDataModel.averageSpeed
			val remainingTime = getRemainingDownloadTime(remainingByte, averageSpeed)
			downloadDataModel.remainingTimeInSec = remainingTime
			downloadDataModel.remainingTimeInFormat =
				calculateTime(remainingTime.toFloat(), getText(string.title_left))
		} else {
			downloadDataModel.remainingTimeInSec = 0L
			downloadDataModel.remainingTimeInFormat = "-:-"
		}

		logger.d("Remaining download time: ${downloadDataModel.remainingTimeInFormat}")
	}

	/**
	 * Checks network connection for all active download parts and retries download if possible.
	 *
	 * Responsibilities:
	 * - Verify network connectivity for each part.
	 * - Resume download if previously waiting for network and conditions are met.
	 * - Respect Wi-Fi-only download settings.
	 */
	private suspend fun checkNetworkConnectionAndRetryDownload() {
		allDownloadParts.forEach { downloadPart ->
			downloadPart.verifyNetworkConnection()
		}

		if (downloadDataModel.isWaitingForNetwork) {
			if (isNetworkAvailable() && isInternetConnected()) {
				if (downloadSettings.downloadWifiOnly && !isWifiEnabled()) return
				downloadDataModel.isWaitingForNetwork = false
				val statusInfoString = getText(string.title_started_downloading)
				updateDownloadStatus(statusInfoString)
				startAllDownloadThreads()
				logger.d("Network restored, retrying all download threads")
			}
		}
	}
}