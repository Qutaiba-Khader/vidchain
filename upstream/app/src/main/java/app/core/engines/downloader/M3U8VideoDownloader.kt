package app.core.engines.downloader

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOApp.Companion.internalDataFolder
import app.core.AIOTimer.AIOTimerListener
import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.video_parser.parsers.SupportedURLs.filterYoutubeUrlWithoutPlaylist
import app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.cleanYtdlpLoggingSting
import app.core.engines.video_parser.parsers.VideoFormatsUtils.formatDownloadSpeedForYtDlp
import app.core.engines.video_parser.parsers.VideoFormatsUtils.isFormatVideoOnly
import app.core.engines.video_parser.parsers.VideoFormatsUtils.isValidSpeedFormat
import app.core.engines.video_parser.parsers.VideoParserUtility.getSanitizedTitle
import app.core.engines.video_parser.parsers.VideoParserUtility.getVideoTitleFromURL
import com.aio.R
import com.anggrayudi.storage.file.getAbsolutePath
import com.yausername.youtubedl_android.YoutubeDL.getInstance
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import lib.device.AppVersionUtility.versionName
import lib.device.DateTimeUtils.calculateTime
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSystemUtility.findFileStartingWith
import lib.files.FileSystemUtility.isFileNameValid
import lib.files.FileSystemUtility.isVideoByName
import lib.files.FileSystemUtility.sanitizeFileNameExtreme
import lib.networks.DownloaderUtils.getAudioPlaybackTimeIfAvailable
import lib.networks.DownloaderUtils.getFormattedPercentage
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.DownloaderUtils.renameIfDownloadFileExistsWithSameName
import lib.networks.DownloaderUtils.updateSmartCatalogDownloadDir
import lib.networks.DownloaderUtils.validateExistedDownloadedFileName
import lib.networks.NetworkUtility.isNetworkAvailable
import lib.networks.NetworkUtility.isWifiEnabled
import lib.networks.URLUtilityKT.getBaseDomain
import lib.networks.URLUtilityKT.isInternetConnected
import lib.networks.URLUtilityKT.isUrlExpired
import lib.process.AudioPlayerUtils
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.texts.CommonTextUtils.capitalizeWords
import lib.texts.CommonTextUtils.generateRandomString
import lib.texts.CommonTextUtils.getText
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.lang.System.currentTimeMillis

/**
 * Handles the process of video downloading, including initialization, state tracking,
 * and status updates. Implements both [DownloadTaskInf] and [AIOTimerListener] interfaces
 * for integration with the app’s task management and timing systems.
 *
 * The downloader coordinates model setup, progress monitoring, and event notifications
 * to maintain smooth and observable download operations.
 */
class M3U8VideoDownloader(
	override val downloadDataModel: DownloadDataModel,
	override var coroutineScope: CoroutineScope,
	override var downloadStatusListener: DownloadTaskListener?
) : DownloadTaskInf, AIOTimerListener {

	/** Logger instance for capturing debug and error logs. */
	private val logger = LogHelperUtils.from(javaClass)

	/** Global download configuration derived from the provided model. */
	private val downloadGlobalSettings = downloadDataModel.globalSettings

	/** File object pointing to the download’s final destination path. */
	private var destinationOutputFile = downloadDataModel.getDestinationFile()

	/**
	 * Flag indicating whether a WebView is actively loading content,
	 * often used for authentication or cookie retrieval.
	 *
	 * Volatile ensures that updates to this flag are immediately
	 * visible across multiple threads.
	 */
	@Volatile
	private var isWebViewLoading = false

	/**
	 * Tracks the timestamp of the most recent progress update.
	 * This helps in detecting potential download stalls.
	 *
	 * Volatile ensures visibility of updates between threads.
	 */
	@Volatile
	private var lastUpdateTime = 0L

	/**
	 * Prepares the download task by initializing and persisting
	 * the [DownloadDataModel] state.
	 *
	 * The process involves:
	 * - Setting up initial model attributes.
	 * - Updating the task’s initial download status to “waiting.”
	 * - Persisting state changes to ensure consistency.
	 *
	 * @return `true` if the download was initialized successfully;
	 *         `false` if any exception occurred during setup.
	 */
	override suspend fun initiateDownload(): Boolean {
		try {
			// Begin initialization
			logger.d("Initializing download data model...")
			initDownloadDataModel()

			// Update task status to "waiting to join"
			logger.d("Updating initial download status...")
			val statusInfoString = getText(R.string.title_waiting_to_join)
			updateDownloadStatus(statusInfo = statusInfoString)

			// Persist state and confirm success
			logger.d("Download data model initialized and stored successfully")
			return true

		} catch (error: Exception) {
			// Log and handle initialization failure
			logger.e("Error while initiating download", error)
			return false
		}
	}

	/**
	 * Handles the main execution flow of the video download process.
	 *
	 * The method transitions the task into an active downloading state,
	 * prepares the download environment, and selects the correct
	 * download procedure depending on the file source type.
	 *
	 * The general workflow is:
	 * - Update the task status to "DOWNLOADING"
	 * - Prepare download settings and file destination
	 * - Dispatch to the appropriate download handler (social media or standard)
	 *
	 * @return `true` if the download started without errors;
	 *         `false` if any failure occurred during preparation or execution.
	 */
	override suspend fun startDownload(): Boolean {
		try {
			// Log and set download to "preparing" state
			logger.d("Starting download process...")
			val statusInfoString = getText(R.string.title_preparing_download)
			updateDownloadStatus(statusInfo = statusInfoString, status = DOWNLOADING)

			// Configure model parameters and ensure file destination is ready
			configureDownloadModel()
			createEmptyDestinationFile()

			// Detect if the source URL belongs to a social media platform
			if (isSocialMediaUrl(downloadDataModel.fileURL)) {
				logger.d("Detected social media URL; starting social media download...")
				startSocialMediaDownload()
			} else {
				logger.d("Regular URL detected; starting standard download...")
				startRegularDownload()
			}

			// Successfully initiated download
			return true

		} catch (error: Exception) {
			// Log and handle download initialization errors
			logger.e("Unexpected error occurred while starting the download", error)
			val statusInfoString = getText(R.string.title_download_failed)
			updateDownloadStatus(statusInfo = statusInfoString, status = CLOSE)
			return false
		}
	}

	/**
	 * Aborts an active or pending download process.
	 *
	 * This function ensures that any ongoing tasks, connections,
	 * or progress indicators are safely stopped. It also updates
	 * the model status and cancels coroutine activity when required.
	 *
	 * Operational steps:
	 * - Terminate any active download sessions.
	 * - Update status with a clear reason (defaults to “Paused”).
	 * - Cancel coroutine execution if user initiated the cancellation.
	 *
	 * @param cancelReason Message describing why the download was canceled.
	 *                     Defaults to a "Paused" message if left blank.
	 * @param isCanceledByUser Indicates whether the cancellation was initiated by the user.
	 */
	override suspend fun cancelDownload(cancelReason: String, isCanceledByUser: Boolean) {
		try {
			// Begin download cancellation sequence
			logger.d("Cancelling download...")
			closeYTDLProgress() // Stop any active YouTube-DL or background task

			// Define message for UI update
			val statusMessage = cancelReason.ifEmpty { getText(R.string.title_paused) }
			logger.d("Updating status after cancellation: $statusMessage")

			// Reflect cancellation in the download state
			updateDownloadStatus(statusInfo = statusMessage, status = CLOSE)

		} catch (error: Exception) {
			// Log and safely handle any errors during cancellation
			logger.e("Error during cancellation: ${error.message}", error)
		} finally {
			// Ensure coroutine cleanup
			coroutineScope.cancel()
		}
	}

	/**
	 * Prepares the [DownloadDataModel] for a new download session by
	 * resetting all transient and state-related fields.
	 *
	 * The initialization ensures that any residual data from a
	 * previous session (such as status flags or retry counters)
	 * is cleared before the new download begins.
	 *
	 * The workflow includes:
	 * - Resetting operational flags (running, waiting, retry count)
	 * - Setting an initial user-visible status message
	 * - Initializing fundamental model information
	 * - Persisting the clean model state to local storage
	 */
	private fun initDownloadDataModel() {
		logger.d("Initializing download data model fields...")

		// Reset primary operational status flags
		downloadDataModel.status = CLOSE
		downloadDataModel.isRunning = false
		downloadDataModel.isWaitingForNetwork = false
		downloadDataModel.resumeSessionRetryCount = 0

		// Set initial status message for the UI
		downloadDataModel.statusInfo = getText(R.string.title_waiting_to_join)

		// Initialize basic download details (metadata, identifiers, etc.)
		initBasicDownloadModelInfo()

		// Persist the cleaned model state for reliability
		logger.d("Saving initial state to storage...")
		downloadDataModel.updateInStorage()
	}

	/**
	 * Periodically triggered by the [AIOTimer] to manage and update
	 * ongoing download progress in real time.
	 *
	 * This timer-driven update mechanism keeps the user interface
	 * and model synchronized with actual download activity. It
	 * selectively invokes [handleDownloadProgress] at specific intervals
	 * to avoid unnecessary processing overhead.
	 *
	 * Execution pattern:
	 * - Triggered once per timer tick
	 * - Progress updates are applied every third tick
	 * - Skips updates when downloads are idle or completed
	 *
	 * @param loopCount Numeric counter representing the timer iteration count.
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		logger.d("AIOTimer tick: loopCount=$loopCount")

		// Perform progress update only on every third tick
		if (loopCount.toInt() % 3 == 0) {
			handleDownloadProgress()
			logger.d("Progress updated on loopCount=$loopCount")
		}
	}

	/**
	 * Monitors and handles the progress of a running download.
	 *
	 * This method is synchronized to prevent concurrent modifications.
	 * It checks if the download is active and not complete, handles stalled downloads,
	 * updates progress, or attempts restart if the download is waiting for network.
	 */
	@Synchronized
	private fun handleDownloadProgress() {
		coroutineScope.launch {
			logger.d("Timer tick: Checking download state...")

			// If download is running, not complete, and network is available
			if (downloadDataModel.isRunning &&
				!downloadDataModel.isComplete &&
				!downloadDataModel.isWaitingForNetwork
			) {
				logger.d("Download is running and not complete.")

				// Skip tick if this is the first check
				if (lastUpdateTime == 0L) {
					logger.d("Last update time is zero, skipping tick.")
					return@launch
				}

				// Check if 10 seconds have passed since last update
				if (currentTimeMillis() - lastUpdateTime >= (1000 * 10)) {
					// If download appears stalled, force restart
					if (downloadDataModel.tempYtdlpStatusInfo.contains("left", true)) {
						logger.d("Download stalled for over 10 seconds, forcing restart...")
						forcedRestartDownload()
					} else {
						// Otherwise, update status and progress normally
						downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_processing_fragments)
						updateDownloadProgress()
					}
				}

			} else if (downloadDataModel.isWaitingForNetwork) {
				// If waiting for network, try to restart download
				logger.d("Waiting for network, attempting to restart download...")
				restartDownload()
			}
		}
	}

	/**
	 * Attempts to restart the current download session if the retry limit
	 * has not yet been exceeded. This mechanism helps recover from transient
	 * network interruptions or stalled connections.
	 *
	 * The process:
	 * - Checks whether retry attempts are within the allowed threshold.
	 * - Increments the retry counter for tracking connection stability.
	 * - Initiates a forced resume of the download via the system handler.
	 *
	 * This safeguard ensures that downloads can self-recover up to a defined
	 * retry limit before being marked as failed or paused.
	 */
	private suspend fun forcedRestartDownload() {
		logger.d("Attempting forced restart of the download...")

		// Allow retry only if below the maximum threshold
		if (downloadDataModel.resumeSessionRetryCount <
			downloadGlobalSettings.downloadAutoResumeMaxErrors) {
			downloadDataModel.resumeSessionRetryCount++
			logger.d("Increasing retry count to ${downloadDataModel.resumeSessionRetryCount}")

			// Trigger download system to resume forcibly
			closeYTDLProgress()
			startDownload()
			logger.d("Triggered force resume download.")
		} else {
			// Avoid infinite retry loops after threshold reached
			logger.d("Maximum retry attempts reached, skipping forced restart.")
		}
	}

	/**
	 * Configures essential parameters and system behaviors
	 * for the current [DownloadDataModel] before the download begins.
	 *
	 * This setup ensures that the download process adheres to
	 * the application’s user preferences and operational rules.
	 *
	 * Configuration includes:
	 * - Setting up YTDLP (YouTube-DL Python) options for format and execution
	 * - Enabling automatic resume upon failures
	 * - Defining cleanup behavior after successful completion
	 * - Assigning part range configuration for segmented downloads
	 */
	private fun configureDownloadModel() {
		logger.d("Configuring download model...")

		// Apply YTDLP or extraction settings
		configureDownloadModelForYTDLP()

		// Enable automatic resume handling for temporary network drops
		configureDownloadAutoResumeSettings()

		// Configure auto-remove policy after completion
		configureDownloadAutoRemoveSettings()

		// Set up download part range for multi-part transfers
		configureDownloadPartRange()

		logger.d("Download model configuration complete.")
	}

	/**
	 * Prepares and configures the downloadDataModel for execution via yt-dlp.
	 *
	 * Workflow:
	 * - Ensures smart category directory setup and handles filename collisions.
	 * - Generates a sanitized, unique temporary filename for yt-dlp downloads.
	 * - Assigns execution command for yt-dlp with the selected video format.
	 * - Resets error flags/messages and persists updated state.
	 */
	private fun configureDownloadModelForYTDLP() {
		logger.d("Configuring yt-dlp specific settings...")
		val videoFormat = downloadDataModel.videoFormat!!
		logger.d("Selected video format: $videoFormat")

		// Ensure download directory and filename setup if not processed
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			logger.d("Smart category directory not processed. Setting up directories and filenames.")

			updateSmartCatalogDownloadDir(downloadDataModel)
			logger.d("Smart category directory updated.")

			renameIfDownloadFileExistsWithSameName(downloadDataModel)
			logger.d("Checked and renamed file if necessary to avoid collisions.")

			val internalDirPath = internalDataFolder.getAbsolutePath(INSTANCE)
			logger.d("Internal directory path: $internalDirPath")

			// Generate a unique random filename in the internal directory
			var randomFileName: String
			do {
				randomFileName = sanitizeFileNameExtreme(generateRandomString(10))
			} while (File(internalDirPath, randomFileName).exists())
			logger.d("Generated unique random filename: $randomFileName")

			// Validate filename against existing downloads
			val sanitizedTempName =
				validateExistedDownloadedFileName(internalDirPath, randomFileName)
			logger.d("Sanitized filename validated: $sanitizedTempName")

			// Assign temp yt-dlp destination file
			val ytTempDownloadFile = File(internalDirPath, sanitizedTempName)
			downloadDataModel.tempYtdlpDestinationFilePath = ytTempDownloadFile.absolutePath
			logger.d("Temporary yt-dlp file set at: ${ytTempDownloadFile.absolutePath}")

			// Set final destination file
			destinationOutputFile = downloadDataModel.getDestinationFile()
			logger.d("Final destination file set at: ${destinationOutputFile.absolutePath}")

			downloadDataModel.isSmartCategoryDirProcessed = true
			logger.d("Smart category directory processed flag set to true.")
		} else {
			logger.d("Smart category directory already processed, skipping setup.")
		}

		// Configure yt-dlp command for the selected format
		downloadDataModel.executionCommand = getYtdlpExecutionCommand(videoFormat)
		logger.d("yt-dlp execution command configured.")

		// Reset error state
		downloadDataModel.isYtdlpHavingProblem = false
		downloadDataModel.ytdlpProblemMsg = ""
		logger.d("Reset yt-dlp error state.")

		// Persist updated state
		downloadDataModel.updateInStorage()
		logger.d("Download data model updated in storage.")

		// Log the final command
		logger.d("yt-dlp execution command set to: ${downloadDataModel.executionCommand}")
	}

	/**
	 * Extracts the vertical resolution (height in pixels) from a resolution string.
	 *
	 * Supported formats:
	 * - "1280x720", "1920×1080" (using 'x' or '×' as a separator)
	 * - "720p", "1080P"
	 * - "1280px720p", "1920Px1080P"
	 * - "720" (a standalone number)
	 *
	 * @param resolutionStr The resolution string to parse.
	 * @return The extracted height as an Int, or null if no valid resolution found.
	 */
	private fun extractResolutionNumber(resolutionStr: String): Int? {
		logger.d("Extracting resolution number from: $resolutionStr")
		val patterns = listOf(
			// Pattern 1: 1280x720 or 1920×1080 (with × symbol)
			Regex("""(\d{3,4})[xX×](\d{3,4})"""),
			// Pattern 2: 720p or 1080P
			Regex("""(\d{3,4})[pP]"""),
			// Pattern 3: 1280px720p or 1920Px1080P
			Regex("""(\d{3,4})[pPxX×](\d{3,4})[pP]"""),
			// Pattern 4: Standalone number
			Regex("""^(\d{3,4})$""")
		)

		for (regex in patterns) {
			val match = regex.find(resolutionStr)
			if (match != null) {
				// Return the last numeric group (height)
				logger.d("Pattern matched: ${regex.pattern}")
				val resolution = match.groupValues.last {
					it.isNotEmpty() && it.all(Char::isDigit)
				}.toIntOrNull()
				logger.d("Extracted resolution: $resolution")
				return resolution
			}
		}
		logger.d("No valid resolution found.")
		return null
	}

	/**
	 * Initializes the [downloadDataModel] with core metadata and default flags
	 * required before starting a YTDLP download.
	 *
	 * What this method does:
	 * - Prevents re-initialization if already initialized.
	 * - Resolves the target filename and applies sanitization if needed.
	 * - Ensures the video title is present (extracts from URL if missing).
	 * - Determines output file extension (`.mp3` for audio-only, `.mp4` otherwise).
	 * - Assigns the video URL and site referrer.
	 * - Applies default download flags (resume support, threading, etc.).
	 * - Records the download start timestamp.
	 */
	private fun initBasicDownloadModelInfo() {
		// Skip if the model has already been initialized
		logger.d("Initializing basic download model info...")
		if (downloadDataModel.isBasicYtdlpModelInitialized) {
			logger.d("Model already initialized, skipping.")
			return
		}

		val videoInfo = downloadDataModel.videoInfo!!
		val videoFormat = downloadDataModel.videoFormat!!
		logger.d("Video format: ${videoFormat.formatResolution}")

		// Determine file extension based on audio/video type
		val formatResolution = videoFormat.formatResolution.lowercase()
		val fileExtension: String =
			if (formatResolution.contains("audio", ignoreCase = true)) "mp3" else "mp4"
		logger.d("Determined file extension: $fileExtension")

		// Ensure video title is set
		if (videoInfo.videoTitle.isNullOrEmpty()) {
			logger.d("Video title missing, extracting from URL...")
			val titleFromURL = getVideoTitleFromURL(videoInfo.videoUrl)
			videoInfo.videoTitle = titleFromURL.ifEmpty(::madeUpTitleFromSelectedVideoFormat)
			logger.d("Video title set to: ${videoInfo.videoTitle}")
		} else {
			logger.d("Video title already present.")
		}

		// Assign essential fields: URL, referrer, filename
		downloadDataModel.fileURL = videoInfo.videoUrl
		downloadDataModel.siteReferrer = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
		logger.d("File URL set to: ${downloadDataModel.fileURL}")
		logger.d("Site referrer set to: ${downloadDataModel.siteReferrer}")

		// Generate sanitized filename
		downloadDataModel.fileName = "${getSanitizedTitle(videoInfo, videoFormat)}.$fileExtension"
		logger.d("Generated filename: ${downloadDataModel.fileName}")

		// If filename is invalid, regenerate with strict sanitization
		if (!isFileNameValid(downloadDataModel.fileName)) {
			logger.d("Filename invalid, regenerating with stricter sanitization.")
			downloadDataModel.fileName =
				"${getSanitizedTitle(videoInfo, videoFormat, true)}.$fileExtension"
			logger.d("Regenerated filename: ${downloadDataModel.fileName}")
		}

		// Set baseline download flags
		downloadDataModel.isUnknownFileSize = false
		downloadDataModel.isMultiThreadSupported = false
		downloadDataModel.isResumeSupported = true
		downloadDataModel.globalSettings.downloadDefaultThreadConnections = 1
		logger.d("Download flags set: unknownFileSize=false, multiThreadSupported=false, resumeSupported=true")

		// Record download start timestamp (both raw and human-readable formats)
		val currentTime = currentTimeMillis()
		downloadDataModel.startTimeDate = currentTime
		downloadDataModel.startTimeDateInFormat = millisToDateTimeString(currentTime)
		logger.d("Download start time recorded: $currentTime (${downloadDataModel.startTimeDateInFormat})")

		// Mark initialization as complete
		downloadDataModel.isBasicYtdlpModelInitialized = true
		logger.d("Basic download model initialization complete.")
	}

	/**
	 * Generates a fallback title string when the video has no valid title.
	 *
	 * The constructed title uses technical details from the selected format
	 * and the base domain of the video URL. This ensures the resulting title
	 * is unique enough for identification, even if not user-friendly.
	 *
	 * Output format:
	 *   <formatId>_<resolution>_<videoCodec>_<baseDomain>_From_<siteReferrer>_Downloaded_By_AIO_v<version>_
	 *
	 * Example:
	 *   "137_1080p_avc1_Youtube.com_From_youtube.com_Downloaded_By_AIO_v1.0_"
	 *
	 * @return A machine-generated title string derived from format metadata.
	 */
	private fun madeUpTitleFromSelectedVideoFormat(): String {
		logger.d("Generating fallback title from selected video format...")
		val videoFormat = downloadDataModel.videoFormat!!
		logger.d(
			"Video format details - ID: ${videoFormat.formatId}, " +
					"Resolution: ${videoFormat.formatResolution}, " +
					"Codec: ${videoFormat.formatVcodec}"
		)

		val baseDomain = capitalizeWords(getBaseDomain(downloadDataModel.videoInfo!!.videoUrl))
		logger.d("Extracted base domain: $baseDomain")

		val siteReferrer = downloadDataModel.siteReferrer
		logger.d("Site referrer: $siteReferrer")

		// Build a synthetic title by concatenating format details + domain
		val madeUpTitle = "${videoFormat.formatId}_" +
				"${videoFormat.formatResolution}_" +
				"${videoFormat.formatVcodec}_" +
				"$baseDomain" +
				"_From_${siteReferrer}" +
				"_Downloaded_By_AIO_v${versionName}_"

		logger.d("Generated fallback title: $madeUpTitle")
		return madeUpTitle
	}

	/**
	 * Builds the execution command string for YTDLP based on the selected video format.
	 *
	 * Command logic:
	 * 1. **Special "dynamic" format case** (when formatId == app's packageName):
	 *    - If the format comes from a social media site:
	 *        → Always request best video up to 2400p + best audio
	 *        → Fallback: best[height<=2400] / best
	 *    - Otherwise (normal sites):
	 *        → Use the resolution number (if extracted) to build a height filter
	 *          (e.g., bestvideo[height<=1080]+bestaudio).
	 *        → If no resolution number is available, fallback to generic `bestvideo+bestaudio/best`.
	 *        → For YouTube links specifically:
	 *            - If the format resolution is audio-only, force "bestaudio".
	 *            - Otherwise, use the common pattern.
	 *
	 * 2. **Normal format case** (when formatId != app's packageName):
	 *    - Just return the `formatId` directly.
	 *
	 * This method ensures that the generated YTDLP command adapts dynamically
	 * based on resolution, source type (YouTube vs. others), and audio-only cases.
	 *
	 * @param videoFormat The selected video format metadata (id, resolution, etc.).
	 * @return A valid YTDLP command string for format selection.
	 */
	private fun getYtdlpExecutionCommand(videoFormat: VideoFormat): String {
		logger.d("Generating yt-dlp execution command for format ID: ${videoFormat.formatId}")
		val packageName = INSTANCE.packageName
		val resolutionNumber = extractResolutionNumber(videoFormat.formatResolution)
		logger.d("Extracted resolution number: $resolutionNumber")

		val command = if (videoFormat.formatId == packageName) {
			logger.d("Format ID matches package name.")
			// Case 1: Dynamic handling if formatId matches package name
			if (videoFormat.isFromSocialMedia) {
				// Social media sites → always cap resolution at 2400p
				logger.d("Video is from social media; using resolution cap at 2400p.")
				"bestvideo[height<=2400]+bestaudio/best[height<=2400]/best"
			} else {
				// Build a resolution-specific pattern, or fallback if not available
				val commonPattern = if (resolutionNumber != null) {
					logger.d("Building resolution-specific pattern with max height $resolutionNumber.")
					"bestvideo[height<=$resolutionNumber]+bestaudio/best[height<=$resolutionNumber]/best"
				} else {
					logger.d("No resolution number available; using fallback pattern.")
					"bestvideo+bestaudio/best"
				}

				// Special handling for YouTube
				if (isYouTubeUrl(downloadDataModel.fileURL)) {
					logger.d("Video URL is from YouTube.")
					val isAudio = videoFormat.formatResolution.contains("audio", ignoreCase = true)
					logger.d("Format is ${if (isAudio) "audio-only" else "video or mixed"}.")
					if (isAudio) "bestaudio" else commonPattern
				} else {
					logger.d("Video URL is not from YouTube; using common pattern.")
					commonPattern
				}
			}
		} else {
			logger.d("Format ID does not match package name; using provided format ID.")
			// Case 2: Normal formats → just return the formatId
			if (isFormatVideoOnly(videoFormat)) "${videoFormat.formatId}+ba" else videoFormat.formatId
		}

		logger.d("Generated yt-dlp command: $command")
		return command
	}

	/**
	 * Configures the auto-resume behavior for a download task.
	 *
	 * Logic:
	 * - Runs only if the "smart category" directory has not been processed yet.
	 * - If auto-resume is **disabled** in the config, forcefully sets the maximum
	 *   allowed resume error count to `0`.
	 *
	 * Purpose:
	 * Ensures that downloads won't automatically retry/resume when the user
	 * has explicitly disabled auto-resume functionality.
	 */
	private fun configureDownloadAutoResumeSettings() {
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			if (!downloadGlobalSettings.downloadAutoResume)
				downloadGlobalSettings.downloadAutoResumeMaxErrors = 0
		}
	}

	/**
	 * Configures the auto-remove behavior for completed downloads.
	 *
	 * Logic:
	 * - Runs only if the "smart category" directory has not been processed yet.
	 * - If auto-remove is **disabled** in the config, resets the
	 *   "remove after N days" timer to `0`.
	 *
	 * Purpose:
	 * Ensures that completed downloads remain in storage indefinitely
	 * unless the user has explicitly enabled auto-removal.
	 */
	private fun configureDownloadAutoRemoveSettings() {
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			if (!downloadGlobalSettings.downloadAutoRemoveTasks)
				downloadGlobalSettings.downloadAutoRemoveTaskAfterNDays = 0
		}
	}

	/**
	 * Initializes the download part-ranges for multi-threaded downloading.
	 *
	 * Logic:
	 * - Runs only if the "smart category" directory has not been processed yet.
	 * - Creates arrays to track:
	 *   1. Bytes downloaded per thread (`partsDownloadedByte`).
	 *   2. Progress percentage per thread (`partProgressPercentage`).
	 *
	 * Purpose:
	 * Allows accurate tracking and coordination of multiple threads
	 * working in parallel on different parts of the same file.
	 */
	private fun configureDownloadPartRange() {
		if (!downloadDataModel.isSmartCategoryDirProcessed) {
			val numberOfThreads = downloadGlobalSettings.downloadDefaultThreadConnections
			downloadDataModel.partsDownloadedByte = LongArray(numberOfThreads)
			downloadDataModel.partProgressPercentage = IntArray(numberOfThreads)
		}
	}

	/**
	 * Updates the overall download progress.
	 *
	 * Synchronized to avoid race conditions between multiple threads.
	 *
	 * Steps performed:
	 * 1. Recalculate the current download progress and update the model.
	 * 2. Check the network connection and attempt a retry if needed.
	 * 3. Update the download's status (active, paused, failed, etc.).
	 * 4. Persist the latest state to storage (so progress isn't lost).
	 *
	 * Purpose:
	 * Keeps the download model consistent, reliable, and crash-safe during execution.
	 */
	@Synchronized
	private fun updateDownloadProgress() {
		coroutineScope.launch {
			calculateProgressAndModifyDownloadModel()
			checkNetworkConnectionAndRetryDownload()
			updateDownloadStatus()
		}
	}

	/**
	 * Updates the current download status and related flags in the download model.
	 *
	 * This method:
	 * - Updates the status message if provided.
	 * - Sets the status code and related flags (`isRunning`, `isComplete`).
	 * - Persists the updated state in storage.
	 * - Notifies any registered listener on the main thread to reflect the status change.
	 *
	 * @param statusInfo Optional status message to display.
	 * @param status The current status code (e.g., DOWNLOADING, COMPLETE).
	 */
	override suspend fun updateDownloadStatus(statusInfo: String?, status: Int) {
		logger.d("Updating download status...")

		// Update status information if a new message is provided
		if (!statusInfo.isNullOrEmpty()) {
			logger.d("Updating status info: $statusInfo")
			downloadDataModel.statusInfo = statusInfo
		}

		// Update model flags and status
		val classRef = this@M3U8VideoDownloader
		logger.d("Setting status to $status")
		downloadDataModel.status = status
		downloadDataModel.isRunning = (status == DOWNLOADING)
		logger.d("Download running flag set to ${downloadDataModel.isRunning}")
		downloadDataModel.isComplete = (status == COMPLETE)
		logger.d("Download complete flag set to ${downloadDataModel.isComplete}")

		// Persist the updated state
		downloadDataModel.updateInStorage()
		logger.d("Download data model updated in storage.")

		logger.d("Notifying status listener on main thread.")
		downloadStatusListener?.onStatusUpdate(classRef)

		if (downloadDataModel.isRunning) aioTimer.register(this@M3U8VideoDownloader)
		else aioTimer.unregister(this@M3U8VideoDownloader)

		if (!downloadDataModel.isRunning && downloadDataModel.status != DOWNLOADING) {
			if (downloadDataModel.status == COMPLETE) {
				coroutineScope.cancel()
			}
		}
	}

	/**
	 * Calculates and updates download progress metrics in the model.
	 *
	 * This method performs the following actions:
	 * - Updates the total time spent downloading.
	 * - Calculates the number of bytes downloaded so far.
	 * - Updates the completion percentage.
	 * - Refreshes the last modification timestamp.
	 * - Persists the updated state in storage.
	 */
	private fun calculateProgressAndModifyDownloadModel() {
		logger.d("Calculating download progress...")

		logger.d("Updating total downloaded time.")
		calculateTotalDownloadedTime()

		logger.d("Updating downloaded bytes.")
		calculateDownloadedBytes()

		logger.d("Updating download completion percentage.")
		calculateDownloadPercentage()

		logger.d("Updating last modification timestamp.")
		updateLastModificationDate()

		logger.d("Saving updated download model state to storage.")
		downloadDataModel.updateInStorage()
	}

	/**
	 * Checks the current network connection and attempts to retry the download if possible.
	 *
	 * Workflow:
	 * - Verifies network connectivity and closes yt-dlp progress if disconnected.
	 * - If waiting for network, it rechecks availability.
	 * - Ensures WiFi-only settings are respected before resuming.
	 * - Updates the download status and restarts the download process when conditions are met.
	 */
	private suspend fun checkNetworkConnectionAndRetryDownload() {
		logger.d("Checking network connection and deciding whether to retry download.")

		// Close yt-dlp progress if the network connection is invalid
		if (!verifyNetworkConnection()) {
			logger.d("Network connection invalid. Closing yt-dlp progress.")
			closeYTDLProgress()
		}

		// If the download is waiting for network, recheck conditions
		if (downloadDataModel.isWaitingForNetwork) {
			logger.d("Download is waiting for network. Rechecking connectivity...")
			if (isNetworkAvailable() && isInternetConnected()) {
				logger.d("Network is available and internet is connected.")

				// Check if WiFi-only mode is enabled and ensure WiFi is active
				if (downloadGlobalSettings.downloadWifiOnly && !isWifiEnabled()) {
					logger.d("WiFi-only mode is enabled but WiFi is not active. Aborting retry.")
					return
				}

				// Resume download as network conditions are satisfied
				logger.d("Network ready. Resuming download.")
				downloadDataModel.isWaitingForNetwork = false
				val statusInfoString = getText(R.string.title_started_downloading)
				updateDownloadStatus(statusInfoString)
				executeDownloadProcess()
			} else {
				logger.d("Network or internet not available. Cannot resume download.")
			}
		}
	}

	/**
	 * Forcefully terminates any active YTDL process
	 * related to the current download task.
	 */
	private fun closeYTDLProgress() {
		getInstance().destroyProcessById(downloadDataModel.downloadId.toString())
		logger.e("YTDL progress closed for download task ID: ${downloadDataModel.downloadId}")
	}

	/**
	 * Validates current network conditions.
	 *
	 * @return true if network is available and WiFi-only preference is respected.
	 */
	private fun verifyNetworkConnection(): Boolean {
		val isWifiOnly = downloadGlobalSettings.downloadWifiOnly
		return !(!isNetworkAvailable() || (isWifiOnly && !isWifiEnabled()))
	}

	/**
	 * Calculates and updates the total time spent downloading.
	 *
	 * This method:
	 * - Increments the download time if the download is not waiting for network connectivity.
	 * - Converts the accumulated time into a human-readable formatted string.
	 * - Updates the download model with the latest time metrics.
	 */
	private fun calculateTotalDownloadedTime() {
		logger.d("Calculating total downloaded time.")

		// Increase time only if not waiting for network
		if (!downloadDataModel.isWaitingForNetwork) {
			logger.d("Download is active. Adding 500 milliseconds to time spent.")
			downloadDataModel.timeSpentInMilliSec += 500
		} else {
			logger.d("Download is waiting for network. Time not incremented.")
		}

		// Convert total time spent into a formatted string
		val timeSpentMillis = downloadDataModel.timeSpentInMilliSec.toFloat()
		val format = calculateTime(timeSpentMillis, getText(R.string.title_spent))
		logger.d("Formatted time spent: $format")

		// Update the download model
		downloadDataModel.timeSpentInFormat = format
	}

	/**
	 * Calculates the total downloaded bytes by scanning part files generated by yt-dlp.
	 *
	 * This method:
	 * - Searches the internal directory for part files matching the temporary file prefix.
	 * - Updates the downloaded byte count and file size from the matching part file.
	 * - Converts the byte count into a human-readable format.
	 * - Updates the download model with the latest byte metrics.
	 */
	private fun calculateDownloadedBytes() {
		logger.d("Calculating downloaded bytes from part files.")

		try {
			val ytTempDownloadFile = downloadDataModel.tempYtdlpDestinationFilePath
			val ytTempFileNamePrefix = File(ytTempDownloadFile).name
			val internalDir = internalDataFolder

			logger.d(
				"Searching for part files with prefix: " +
						" $ytTempFileNamePrefix in directory: " +
						" ${internalDir.getAbsolutePath(INSTANCE)}"
			)

			// Look for YTDLP-generated part files matching the temp prefix
			internalDir.listFiles()
				.filter { file ->
					file.isFile &&
							file.name!!.startsWith(ytTempFileNamePrefix) &&
							file.name!!.endsWith(".part")
				}
				.forEach { file ->
					try {
						logger.d("Found part file: ${file.name}, size: ${file.length()} bytes")
						// Update byte count from part file size
						downloadDataModel.downloadedByte = file.length()
						downloadDataModel.fileSize = file.length()
					} catch (error: Exception) {
						logger.d("Error reading file ${file.name}: ${error.message}")
						error.printStackTrace()
					}
				}

			// Update human-readable format and progress tracking
			val downloadedByte = downloadDataModel.downloadedByte
			downloadDataModel.downloadedByteInFormat = getHumanReadableFormat(downloadedByte)
			logger.d(
				"Downloaded bytes: $downloadedByte, " +
						"formatted: ${downloadDataModel.downloadedByteInFormat}"
			)

			downloadDataModel.partsDownloadedByte[0] = downloadedByte
			logger.d("Updated partsDownloadedByte array with latest count.")

		} catch (error: Exception) {
			logger.d("Exception occurred while calculating downloaded bytes: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Calculates the overall download progress percentage.
	 * Updates both the numeric percentage and the formatted string in the download model.
	 */
	private fun calculateDownloadPercentage() {
		logger.d("Calculating download percentage.")
		try {
			val totalProcess = downloadDataModel.progressPercentage
			logger.d("Total progress percentage: $totalProcess")

			downloadDataModel.partProgressPercentage[0] = totalProcess.toInt()
			logger.d("Updated part progress percentage: ${downloadDataModel.partProgressPercentage[0]}")

			downloadDataModel.progressPercentageInFormat = getFormattedPercentage(downloadDataModel)
			logger.d("Formatted progress percentage: ${downloadDataModel.progressPercentageInFormat}")
		} catch (error: Exception) {
			logger.d("Error while calculating download percentage: ${error.message}")
			error.printStackTrace()
		}
	}

	/**
	 * Updates the download model's last modification timestamp
	 * and converts it into a formatted date-time string.
	 */
	private fun updateLastModificationDate() {
		logger.d("Updating last modification date.")
		downloadDataModel.lastModifiedTimeDate = currentTimeMillis()
		logger.d("Set lastModifiedTimeDate to ${downloadDataModel.lastModifiedTimeDate}")

		downloadDataModel.lastModifiedTimeDateInFormat =
			millisToDateTimeString(downloadDataModel.lastModifiedTimeDate)
		logger.d("Formatted last modification date: ${downloadDataModel.lastModifiedTimeDateInFormat}")
	}

	/**
	 * Creates an empty destination file for the download if it doesn't already exist.
	 * This file acts as a placeholder and ensures disk access is working correctly.
	 *
	 * If any I/O error occurs, the download is marked as failed and cancelled.
	 */
	private suspend fun createEmptyDestinationFile() {
		logger.d("Creating empty destination file if necessary.")

		if (downloadDataModel.isDeleted) {
			logger.d("Download is marked as deleted. Skipping file creation.")
			return
		}

		if (downloadDataModel.downloadedByte < 1) {
			try {
				if (destinationOutputFile.exists()) {
					logger.d("Destination file already exists. No need to create.")
					return
				}
				logger.d("Creating new empty file with size 108 bytes.")
				RandomAccessFile(destinationOutputFile, "rw").setLength(108)
			} catch (error: IOException) {
				logger.d("IOException while creating file: ${error.message}")
				error.printStackTrace()
				downloadDataModel.resumeSessionRetryCount++
				downloadDataModel.totalTrackedConnectionRetries++
				logger.d("Incremented total connection retries: ${downloadDataModel.resumeSessionRetryCount}")

				downloadDataModel.isFailedToAccessFile = true
				logger.d("Marked download as failed to access file.")

				cancelDownload(getText(R.string.title_download_io_failed))
				logger.d("Cancelled download due to file I/O error.")
			}
		}
	}

	/**
	 * Starts the download process specifically for social media content.
	 *
	 * Workflow:
	 * - Updates the download status and sets temporary information about fetching cookies.
	 * - Checks if the download is initiated from a browser.
	 *   - If not from a browser, starts a regular download directly.
	 *   - If from a browser, resumes the process in a background thread.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	private suspend fun startSocialMediaDownload() {
		logger.d("Starting social media download.")
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_getting_cookie_from_server)

		if (!downloadDataModel.isDownloadFromBrowser) {
			logger.d("Download is not from browser. Proceeding with regular download.")
			downloadDataModel.isDownloadFromBrowser = false
			downloadDataModel.updateInStorage()
			startRegularDownload()
		} else {
			logger.d("Download initiated from browser. Resuming in background thread.")
			executeInBackground(codeBlock = {
				downloadDataModel.isDownloadFromBrowser = false
				downloadDataModel.updateInStorage()
				executeDownloadProcess()
			})
		}
	}

	/**
	 * Starts the standard download procedure when browser involvement is unnecessary.
	 *
	 * Workflow:
	 * - Allows up to 2 retry attempts before falling back to direct execution.
	 * - Loads the URL in a WebView to extract cookies or session data.
	 * - Triggers the actual download process after the page finishes loading or if retries are exhausted.
	 *
	 * @param retryCount Number of retry attempts already made.
	 */
	@SuppressLint("SetJavaScriptEnabled")
	private suspend fun startRegularDownload(retryCount: Int = 0) {
		logger.d("Starting regular download. Retry count: $retryCount")

		// If max retries reached → fallback to direct execution
		if (retryCount >= 2) {
			logger.d("Max retries reached. Falling back to direct download execution.")
			executeDownloadProcess()
			return
		}

		// Update status before initializing WebView
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_getting_cookie_from_server)

		Handler(Looper.getMainLooper()).post {
			logger.d("Initializing WebView for URL: ${downloadDataModel.fileURL}")
			val webView = WebView(INSTANCE).apply {
				settings.javaScriptEnabled = true

				// Handle page load success / failure
				webViewClient = object : WebViewClient() {
					override fun onPageFinished(view: WebView?, url: String?) {
						logger.d("WebView page finished loading. URL: $url")
						if (isWebViewLoading) {
							handleWebPageLoaded(url)
							destroy()
						}
					}

					@Deprecated("Deprecated in Java")
					override fun onReceivedError(
						view: WebView?, errorCode: Int,
						description: String?, failingUrl: String?
					) {
						logger.d("WebView encountered an error: $errorCode, $description")
						coroutineScope.launch { retryOrFail(retryCount) }
					}
				}

				// Handle page title event as a backup signal for load complete
				webChromeClient = object : WebChromeClient() {
					override fun onReceivedTitle(view: WebView?, title: String?) {
						super.onReceivedTitle(view, title)
						logger.d("WebView received title: $title")
						if (isWebViewLoading) {
							handleWebPageLoaded(url)
							destroy()
						}
					}
				}

				// Start loading the target URL
				loadUrl(downloadDataModel.fileURL)
				isWebViewLoading = true
				logger.d("WebView started loading.")
			}

			// Timeout safeguard → destroy and retry if still stuck
			Handler(Looper.getMainLooper()).postDelayed({
				if (isWebViewLoading) {
					logger.d("WebView loading timeout. Destroying and retrying.")
					webView.destroy()
					coroutineScope.launch { retryOrFail(retryCount) }
				}
			}, 10_000)
		}
	}

	/**
	 * Called when the WebView has finished loading the target page.
	 * Extracts cookies from the loaded URL and saves them into the data model.
	 *
	 * Workflow:
	 * - Marks WebView loading as complete.
	 * - Retrieves cookies from the given URL using CookieManager.
	 * - Saves the cookies to the data model and persists them if available.
	 * - Continues the download process in the background.
	 *
	 * @param url The URL that was loaded in the WebView.
	 */
	private fun handleWebPageLoaded(url: String?) {
		logger.d("WebView page loaded for URL: $url")
		isWebViewLoading = false

		// Try to capture cookies from the loaded URL
		val cookies = CookieManager.getInstance().getCookie(url)
		cookies?.let {
			logger.d("Cookies found: $cookies")
			downloadDataModel.apply {
				videoInfo?.videoCookie = cookies
				siteCookieString = cookies
				updateInStorage()

				// Persist cookies for reuse
				saveCookiesIfAvailable(shouldOverride = true)
				logger.d("Cookies saved to data model and storage.")
			}
		}

		logger.d("Starting download process after extracting cookies.")
		coroutineScope.launch { executeDownloadProcess() }
	}

	/**
	 * Handles retrying the WebView-based download initialization.
	 * If within retry limits, it attempts to restart the download process.
	 *
	 * @param currentRetry The current retry attempt count.
	 */
	private suspend fun retryOrFail(currentRetry: Int) {
		logger.d("Retrying or failing download. Current retry count: $currentRetry")
		isWebViewLoading = false
		startRegularDownload(currentRetry + 1)
	}

	/**
	 * Executes the actual download process using yt-dlp.
	 *
	 * Workflow:
	 * - Updates download status and initializes necessary settings.
	 * - Builds yt-dlp request with standard options, cookies, and throttling.
	 * - Starts retry timers and monitors progress.
	 * - Handles success, failure, and retries based on the response.
	 */
	private suspend fun executeDownloadProcess() {
		logger.d("Executing download process.")
		updateDownloadStatus(getText(R.string.title_started_downloading), DOWNLOADING)
		downloadDataModel.tempYtdlpStatusInfo = getText(R.string.title_connecting_to_the_server)

		try {
			lastUpdateTime = currentTimeMillis()
			val urlWithoutPlaylist = filterYoutubeUrlWithoutPlaylist(downloadDataModel.fileURL)
			val request = YoutubeDLRequest(urlWithoutPlaylist)
			logger.d("YT-DLP request initialized for URL: $urlWithoutPlaylist")

			// Add standard options
			request.addOption("--continue")
			request.addOption("-f", downloadDataModel.executionCommand)
			request.addOption("-o", downloadDataModel.tempYtdlpDestinationFilePath)
			request.addOption("--playlist-items", "1")
			request.addOption("--user-agent", downloadGlobalSettings.downloadHttpUserAgent)
			request.addOption("--retries", downloadGlobalSettings.downloadAutoResumeMaxErrors)
			request.addOption("--socket-timeout", downloadGlobalSettings.downloadMaxHttpReadingTimeout)
			request.addOption("--concurrent-fragments", 10)
			request.addOption("--fragment-retries", 10)
			request.addOption("--no-check-certificate")
			request.addOption("--force-ipv4")
			request.addOption("--source-address", "0.0.0.0")

			if (isVideoByName(downloadDataModel.fileName)) {
				request.addOption("--merge-output-format", "mp4")
				logger.d("Added merge output option for mp4 format.")
			}
			if (AIOApp.IS_DEBUG_MODE_ON) {
				request.addOption("-v")
				logger.d("Debug mode enabled for yt-dlp.")
			}

			// Add cookie support if available
			downloadDataModel.getCookieFilePathIfAvailable()?.let {
				val cookieFile = File(it)
				if (cookieFile.exists() && cookieFile.canWrite()) {
					logger.d("Using cookie file: ${cookieFile.absolutePath}")
					request.addOption("--cookies", cookieFile.absolutePath)
				}
			}

			// Add download speed throttling if configured
			if (downloadGlobalSettings.downloadMaxNetworkSpeed > 0) {
				val downloadMaxNetworkSpeed = downloadGlobalSettings.downloadMaxNetworkSpeed
				val ytDlpSpeedLimit = formatDownloadSpeedForYtDlp(downloadMaxNetworkSpeed)
				if (isValidSpeedFormat(ytDlpSpeedLimit)) {
					request.addOption("--limit-rate", ytDlpSpeedLimit)
					logger.d("Download speed limit set to: $ytDlpSpeedLimit")
				}
			}

			// Execute yt-dlp request with progress callback
			val response = getInstance().execute(
				request = request,
				processId = downloadDataModel.downloadId.toString()
			) { progress, _, status ->
				if (progress > 0) downloadDataModel.progressPercentage = progress.toLong()
				downloadDataModel.tempYtdlpStatusInfo = cleanYtdlpLoggingSting(status)
				logger.d("Download progress: ${downloadDataModel.progressPercentage}%")
				logger.d("Status message: ${downloadDataModel.tempYtdlpStatusInfo}")

				val currentTime = currentTimeMillis()
				if (currentTime - lastUpdateTime >= 500) {
					lastUpdateTime = currentTime
					updateDownloadProgress()
				}
			}

			// Handle response after yt-dlp finishes
			if (response.exitCode != 0) {
				logger.d("Download failed with exit code ${response.exitCode}")
				onYtdlpDownloadFailed(response.out)
			} else {
				logger.d("Download completed successfully.")
				moveToUserSelectedDestination()

				if (downloadGlobalSettings.downloadPlayNotificationSound) {
					logger.d("Playing download completion sound.")
					AudioPlayerUtils(INSTANCE).play(R.raw.sound_download_finished)
				}

				downloadDataModel.isRunning = false
				downloadDataModel.isComplete = true
				updateDownloadStatus(getText(R.string.title_completed), COMPLETE)

				logger.d("Download status updated to COMPLETE.")
				logger.d("Elapsed time: ${response.elapsedTime} ms")
			}
		} catch (error: Exception) {
			logger.e("Exception during download process: ${error.message}", error)

			if (isRetryingAllowed()) {
				logger.d("Retrying download due to error.")
				onYtdlpDownloadFailed(error.message)
			} else {
				logger.d("Cancelling download due to unrecoverable error.")
				cancelDownload()
				updateDownloadStatus(getText(R.string.title_paused), CLOSE)
			}
		}
	}

	/**
	 * Handles failures during a YTDLP download attempt.
	 * Determines the failure type (critical error, expired link, missing file, etc.),
	 * and either cancels, retries, or restarts the download accordingly.
	 *
	 * @param response Optional error message returned from YTDLP.
	 */
	/**
	 * Handles yt-dlp download failures by checking for critical errors or retrying the download process.
	 *
	 * Workflow:
	 * - Runs in the background thread to process error response.
	 * - Checks for critical errors like server problems, expired URLs, or missing files.
	 * - Pauses, retries, or cancels the download depending on the nature of the error.
	 * - Persists the updated state and cleans up if needed.
	 *
	 * @param response The error message or output from yt-dlp, if available.
	 */
	private suspend fun onYtdlpDownloadFailed(response: String? = null) {
		logger.d("yt-dlp download failed. Response: $response")
		if (response != null && isCriticalErrorFound(response)) {
			// Critical error handling
			logger.d("Critical error detected in response.")
			if (downloadDataModel.isYtdlpHavingProblem) {
				val pausedMsg = downloadDataModel.ytdlpProblemMsg.ifEmpty {
					getText(R.string.title_paused)
				}
				logger.d("Pausing download due to persistent yt-dlp problems.")
				cancelDownload(pausedMsg) // Pause due to persistent YTDLP issues
				return
			}

			if (downloadDataModel.isFileUrlExpired) {
				logger.d("Pausing download due to expired URL.")
				cancelDownload(getText(R.string.title_link_expired_paused)) // Expired link
				return
			}

			if (downloadDataModel.isDestinationFileNotExisted) {
				logger.d("Pausing download because destination file is missing.")
				cancelDownload(getText(R.string.title_file_deleted_paused)) // Target file deleted
				return
			}
		} else if (!response.isNullOrEmpty()) {
			// Generic failure with error response
			if (downloadDataModel.resumeSessionRetryCount <
				downloadGlobalSettings.downloadAutoResumeMaxErrors) {
				logger.d(
					"Retrying download, connection retries:" +
							" ${downloadDataModel.resumeSessionRetryCount}"
				)
				forcedRestartDownload()
			} else {
				logger.d("Max retries reached, cancelling download.")
				updateDownloadStatus(getText(R.string.title_download_failed))
				cancelDownload(getText(R.string.title_download_failed))
			}
		} else {
			// No response → retry logic
			logger.d("No response, retrying download process.")
			restartDownload()
		}

		// Always persist the latest state
		logger.d("Updating download model storage.")
		downloadDataModel.updateInStorage()

		// Clean up destination file if marked deleted
		if (!downloadDataModel.isRemoved && downloadDataModel.isDeleted) {
			if (destinationOutputFile.exists()) {
				logger.d("Cleaning up deleted destination file.")
				destinationOutputFile.delete()
			}
		}
	}

	/**
	 * Checks if the yt-dlp download failure is caused by critical issues.
	 * Updates relevant flags in the downloadDataModel and persists them.
	 *
	 * Checks:
	 * - yt-dlp server or internal errors
	 * - Expired download URL
	 * - Destination file missing or deleted
	 *
	 * @param response The error message or output received from yt-dlp.
	 * @return `true` if a critical error is detected, otherwise `false`.
	 */
	private fun isCriticalErrorFound(response: String): Boolean {
		logger.d("Checking for critical error in response.")

		// Case 1: YTDLP server/internal issue
		if (isYtdlHavingProblem(response)) {
			logger.d("Critical error: yt-dlp server problem detected.")
			downloadDataModel.isYtdlpHavingProblem = true

			// Set problem message if not already set
			if (downloadDataModel.ytdlpProblemMsg.isEmpty()) {
				val msgString = getText(R.string.title_paused_server_problem)
				downloadDataModel.ytdlpProblemMsg = msgString
				logger.d("Set problem message: $msgString")
			}

			downloadDataModel.updateInStorage()
			return true
		}

		// Case 2: Expired file URL
		if (isUrlExpired(downloadDataModel.fileURL)) {
			logger.d("Critical error: URL expired.")
			downloadDataModel.isFileUrlExpired = true
			return true
		}

		// Case 3: Destination file missing/deleted
		if (!destinationOutputFile.exists()) {
			logger.d("Critical error: Destination file missing.")
			downloadDataModel.isDestinationFileNotExisted = true
			return true
		}

		// No critical issues found
		logger.d("No critical error found.")
		return false
	}

	/**
	 * Checks if the given yt-dlp response contains any known fatal errors.
	 * Updates the `ytdlpProblemMsg` field in the download model with a user-friendly message.
	 *
	 * @param response The raw response from yt-dlp.
	 * @return `true` if a known problem is detected, `false` otherwise.
	 */
	private fun isYtdlHavingProblem(response: String): Boolean {
		return when {
			// Login required or rate limit error
			response.contains("rate-limit reached or login required", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_login_required)
				logger.d("YTDLP problem detected: rate limit or login required.")
				true
			}

			// Login required or rate limit error
			response.contains("content may be inappropriate", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_login_required)
				logger.d("YTDLP problem detected: rate limit or login required.")
				true
			}

			// Content unavailable
			response.contains("Requested content is not available", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg =
					getText(R.string.title_paused_content_not_available)
				logger.d("YTDLP problem detected: content not available.")
				true
			}

			// Format not available
			response.contains("Requested format is not available", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg =
					getText(R.string.title_paused_ytdlp_format_not_found)
				logger.d("YTDLP problem detected: format not available.")
				true
			}

			// Restricted video due to region or authentication
			response.contains("Restricted Video", ignoreCase = true) ||
					response.contains("--cookies for the authentication", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_paused_login_required)
				logger.d("YTDLP problem detected: restricted video or auth required.")
				true
			}

			// Network or ISP block issues
			response.contains("Connection reset by peer", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = getText(R.string.title_site_banned_in_your_area)
				logger.d("YTDLP problem detected: connection reset or site blocked.")
				true
			}

			// Generic yt-dlp exception
			response.contains("YoutubeDLException", ignoreCase = true) -> {
				downloadDataModel.ytdlpProblemMsg = INSTANCE.getString(
					R.string.title_server_issue,
					getText(R.string.title_download_failed)
				)
				logger.d("YTDLP problem detected: YoutubeDLException.")
				true
			}

			// No known problem detected
			else -> false
		}
	}

	/**
	 * Attempts to restart the download process if retrying is allowed.
	 * Updates the model's state based on network conditions.
	 * Logs state changes at each step for debugging and traceability.
	 */
	private suspend fun restartDownload() {
		if (!isRetryingAllowed()) return

		// Case 1: No network available at all
		if (!isNetworkAvailable()) {
			// Case: No network connection
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.title_waiting_for_network))
			logger.d("Network unavailable. Waiting for network.")
			return
		}

		// Case 2: Wi-Fi required but not enabled
		if (downloadGlobalSettings.downloadWifiOnly && !isWifiEnabled()) {
			// Case: Wi-Fi required but not enabled
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.title_waiting_for_wifi))
			logger.d("Wi-Fi not enabled. Waiting for Wi-Fi.")
			return
		}

		// Case 3: Network available but no internet access
		if (!isInternetConnected()) {
			// Case: No internet access despite network connection
			downloadDataModel.isWaitingForNetwork = true
			updateDownloadStatus(getText(R.string.title_waiting_for_internet))
			logger.d("Internet unavailable. Waiting for internet.")
			return
		}

		// Case 4: Network conditions are now favorable → restart the download
		if (downloadDataModel.isWaitingForNetwork) {
			// Case: Network is now available, resume download
			downloadDataModel.isWaitingForNetwork = false
			updateDownloadStatus(getText(R.string.title_started_downloading))
			logger.d("Network or Wi-Fi now available. Restarting download.")
			executeDownloadProcess()
		}

		// Increment retry counter for diagnostics
		downloadDataModel.resumeSessionRetryCount++
		downloadDataModel.totalTrackedConnectionRetries++
		logger.d("Retry attempt #${downloadDataModel.resumeSessionRetryCount}.")
	}

	/**
	 * Determines if the download can be retried based on current state and retry limits.
	 *
	 * @return `true` if retrying is allowed, `false` otherwise.
	 */
	private fun isRetryingAllowed(): Boolean {
		val maxErrorAllowed = downloadGlobalSettings.downloadAutoResumeMaxErrors
		val retryAllowed = downloadDataModel.isRunning &&
				downloadDataModel.resumeSessionRetryCount < maxErrorAllowed
		logger.d(
			"Checking if retry is allowed: $retryAllowed " +
					"(retries: ${downloadDataModel.resumeSessionRetryCount}/$maxErrorAllowed)"
		)
		return retryAllowed
	}

	/**
	 * Moves the downloaded temporary file into the user-selected destination folder.
	 *
	 * - Finds the temp file generated by yt-dlp.
	 * - Copies it to the destination file, handling overwrites.
	 * - Updates metadata (file size, progress, formatted sizes).
	 * - On failure, applies fallback strategies like renaming and retrying.
	 */
	private fun moveToUserSelectedDestination() {
		val inputFile = findFileStartingWith(
			internalDir = File(internalDataFolder.getAbsolutePath(INSTANCE)),
			namePrefix = File(downloadDataModel.tempYtdlpDestinationFilePath).name
		)

		inputFile?.let { ytdlpTempfile ->
			try {
				// Copy temp file to final destination and delete original
				val outputFile = downloadDataModel.getDestinationFile()
				logger.d("Preparing to move file: ${ytdlpTempfile.absolutePath} to ${outputFile.absolutePath}")
				ytdlpTempfile.copyTo(outputFile, overwrite = true)
				ytdlpTempfile.delete()

				// Update metadata with file stats
				downloadDataModel.fileSize = outputFile.length()
				downloadDataModel.fileSizeInFormat = getHumanReadableFormat(downloadDataModel.fileSize)
				logger.d("File size updated: ${downloadDataModel.fileSizeInFormat}")

				downloadDataModel.downloadedByte = downloadDataModel.fileSize
				downloadDataModel.downloadedByteInFormat = getHumanReadableFormat(downloadDataModel.downloadedByte)
				logger.d("Downloaded byte info updated: ${downloadDataModel.downloadedByteInFormat}")

				downloadDataModel.progressPercentage = 100
				downloadDataModel.partsDownloadedByte[0] = downloadDataModel.downloadedByte
				downloadDataModel.partProgressPercentage[0] = 100
				downloadDataModel.updateInStorage()
				logger.d("Download progress marked as complete")

				logger.d("File successfully moved to destination: ${outputFile.absolutePath}")
			} catch (error: Exception) {
				logger.e("Error while moving file: ${error.message}. Attempting recovery: ", error)

				// Attempt recovery by reverting rename and retrying
				val outputFile = downloadDataModel.getDestinationFile()
				logger.d("Attempting recovery for file: ${outputFile.absolutePath}")
				outputFile.renameTo(File(ytdlpTempfile.name)) // revert rename attempt
				outputFile.delete()
				logger.d("Reverted rename and deleted corrupted output file")

				// Fix invalid filenames & retry copying
				val currentName = downloadDataModel.fileName
				logger.d("Sanitizing filename: $currentName")
				downloadDataModel.fileName = sanitizeFileNameExtreme(currentName)
				renameIfDownloadFileExistsWithSameName(downloadDataModel)
				logger.d("Filename sanitized and renamed if needed")

				copyFileToUserDestination(ytdlpTempfile)
				logger.d("Recovery steps executed for moving file.")
			}
		}
	}

	/**
	 * Copies a given file to the user-selected download destination.
	 *
	 * Workflow:
	 * - Copies the file to the final output location.
	 * - Deletes the original temp file.
	 * - Updates metadata (file size, cached thumbnail, playback duration).
	 * - Cleans up temporary cookie files if present.
	 * - Persists changes in storage.
	 *
	 * @param sourceFile The file to be copied into the destination folder.
	 */
	private fun copyFileToUserDestination(sourceFile: File) {
		val outputFile = downloadDataModel.getDestinationFile()

		// Copy file and remove the temp source
		sourceFile.copyTo(outputFile, overwrite = true)
		sourceFile.delete()
		logger.d("Copied file to destination: ${outputFile.absolutePath}")

		// Update file metadata
		downloadDataModel.fileSize = outputFile.length()
		downloadDataModel.clearCachedThumbnailFile()

		// Update media playback duration if available
		downloadDataModel.mediaFilePlaybackDuration =
			getAudioPlaybackTimeIfAvailable(downloadDataModel)

		// Clean up temporary cookie file if present
		downloadDataModel.videoInfo?.videoCookieTempPath?.let { cookiePath ->
			val tempCookieFile = File(cookiePath)
			if (tempCookieFile.isFile && tempCookieFile.exists()) {
				tempCookieFile.delete()
				logger.d("Deleted temporary cookie file: ${tempCookieFile.absolutePath}")
			}
		}

		// Save updated metadata
		downloadDataModel.updateInStorage()
		logger.d("Updated download data model after copying file.")
	}

}