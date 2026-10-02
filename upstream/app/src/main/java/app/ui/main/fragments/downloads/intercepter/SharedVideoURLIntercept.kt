package app.ui.main.fragments.downloads.intercepter

import android.widget.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.bases.*
import app.core.engines.video_parser.parsers.*
import app.core.engines.video_parser.parsers.SupportedURLs.filterYoutubeUrlWithoutPlaylist
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.core.engines.video_parser.parsers.SupportedURLs.isYtdlpSupportedUrl
import app.core.engines.video_parser.parsers.VideoParserUtility.getYtdlpVideoFormatsWithRetry
import app.core.engines.youtube.*
import app.ui.main.*
import app.ui.others.information.*
import com.aio.*
import lib.device.IntentUtility.openLinkInSystemBrowser
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.URLUtility.*
import lib.process.*
import lib.process.AsyncJobUtils.*
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.builders.*
import lib.ui.builders.ToastView.Companion.showToast
import org.schabi.newpipe.extractor.stream.*
import java.lang.ref.*

/**
 * Intercepts shared video URLs and processes them to extract downloadable formats,
 * especially using YTDLP (youtube-dl/pytube) supported parsing, and displays
 * a resolution picker dialog if formats are available.
 *
 * This class acts as a handler for incoming video links (e.g., YouTube, Vimeo, HLS),
 * automatically detecting supported formats, retrieving metadata, and offering
 * download or playback options to the user.
 *
 * @param baseActivity The [BaseActivity] context used to manage UI components, dialogs, and lifecycle events.
 * @param userGivenVideoInfo Optional pre-filled [VideoInfo] containing basic metadata such as title or duration.
 * @param onOpeningBuiltInBrowser Optional callback invoked when the user chooses to open the video link in browser.
 * @param closeActivityOnSuccessfulDownload A flag indicating whether the current activity should automatically close
 *                                          once a download is successfully initiated or completed.
 */
class SharedVideoURLIntercept(
	private val baseActivity: BaseActivity?,
	private val userGivenVideoInfo: VideoInfo? = null,
	private val onOpeningBuiltInBrowser: (() -> Unit?)? = null,
	private val closeActivityOnSuccessfulDownload: Boolean = false
) {
	
	/** Logger instance for recording debug and error messages related to video interception. */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Holds a weak reference to the associated [BaseActivity] to prevent memory leaks.
	 * This ensures the class can access context-related resources safely without
	 * keeping the activity in memory unnecessarily.
	 */
	private var safeBaseActivityRef = WeakReference(baseActivity).get()
	
	/**
	 * Flag indicating whether a video interception or extraction process
	 * is currently running. Helps prevent duplicate or parallel interception tasks.
	 */
	@Volatile
	private var isInterceptingInProcess: Boolean = false
	
	/**
	 * Flag that marks whether the current interception process has been
	 * either cancelled by the user or completed successfully.
	 */
	@Volatile
	private var isInterceptingTerminated: Boolean = false
	
	/**
	 * Dialog instance used to show a loading or progress indicator
	 * while analyzing or parsing a shared video link.
	 */
	@Volatile
	private var interceptWaitingDialog: WaitingDialog? = null
	
	/**
	 * Determines whether the app should automatically open the video link
	 * in an external browser if interception or format extraction fails.
	 */
	private var shouldOpenBrowserAsFallback = true
	
	/**
	 * Initiates the interception and analysis of a shared video URL.
	 *
	 * This method validates the given URL, prepares a waiting dialog for user feedback,
	 * and triggers the background interception process to extract video information.
	 * If the provided URL is invalid, the process is aborted safely.
	 *
	 * @param targetUrl The shared video URL to be intercepted and analyzed.
	 * @param shouldOpenBrowserAsFallback Determines whether the app should
	 * automatically open the URL in an external browser if extraction fails.
	 */
	fun interceptIntentURI(targetUrl: String?, shouldOpenBrowserAsFallback: Boolean = true) {
		logger.d("Starting URL interception process")
		
		// Save fallback behavior flag
		this.shouldOpenBrowserAsFallback = shouldOpenBrowserAsFallback
		
		targetUrl?.let {
			// Validate the provided URL before proceeding
			if (!isValidURL(targetUrl)) {
				logger.d("Invalid URL provided: $targetUrl")
				return
			}
			
			// Show loading dialog while processing the link
			logger.d("Initializing waiting dialog for URL interception")
			initializeWaitingMessageDialog()
			
			// Begin actual video interception and parsing
			startInterceptingUrl(targetUrl)
		} ?: logger.d("No URL provided — skipping interception")
	}
	
	/**
	 * Initializes and displays the waiting dialog during video URL analysis.
	 *
	 * This dialog informs the user that the shared link is being analyzed
	 * for downloadable formats. It is non-cancelable by default, but if the
	 * user manually cancels it (via a close or back action), the interception
	 * process is safely aborted.
	 *
	 * Responsibilities:
	 * - Create a non-cancelable loading dialog.
	 * - Display an informative "analyzing URL" message.
	 * - Handle user cancellation with proper cleanup.
	 */
	private fun initializeWaitingMessageDialog() {
		logger.d("Creating waiting dialog for video analysis")
		
		interceptWaitingDialog = WaitingDialog(
			isCancelable = false,
			baseActivityInf = safeBaseActivityRef,
			loadingMessage = getText(R.string.title_analyzing_url_please_wait),
			dialogCancelListener = {
				logger.d("User canceled the waiting dialog manually")
				interceptWaitingDialog?.let { waitingDialog ->
					waitingDialog.close()
					onCancelInterceptRequested(waitingDialog)
				}
			}
		)
		
		logger.d("Waiting dialog initialized successfully")
	}
	
	/**
	 * Updates the text message displayed in the interception waiting dialog.
	 *
	 * This method safely updates the progress message on the main thread while
	 * the shared video URL is being analyzed or processed. It ensures that the
	 * dialog remains responsive and prevents crashes from background thread UI access.
	 *
	 * @param newUpdatedMessage The new message to display inside the dialog.
	 *
	 * Responsibilities:
	 * - Locate the progress TextView in the dialog.
	 * - Update its text safely on the main thread.
	 * - Log the message update for debugging.
	 */
	private fun updateInterceptWaitingDialogMessage(newUpdatedMessage: String) {
		logger.d("Updating waiting dialog message to: \"$newUpdatedMessage\"")
		
		interceptWaitingDialog?.let { waitingDialog ->
			executeOnMainThread {
				waitingDialog.dialogBuilder?.apply {
					val textProgressInfo = view.findViewById<TextView>(R.id.txt_progress_info)
					textProgressInfo.text = newUpdatedMessage
					logger.d("Waiting dialog message updated successfully")
				}
			}
		} ?: logger.d("No active waiting dialog found — message not updated")
	}
	
	/**
	 * Begins the interception and analysis process for the provided video URL.
	 *
	 * This method determines if the given URL is eligible for analysis (e.g., supported by yt-dlp),
	 * ensures no duplicate interception is already running, and initiates background processing.
	 * If unsupported, it provides appropriate user feedback and may open the link in the built-in browser.
	 *
	 * @param targetVideoUrl The URL of the video to analyze.
	 *
	 * Workflow:
	 * - Prevents duplicate interception if already in progress.
	 * - Displays the waiting dialog to the user.
	 * - Verifies if the URL is supported by yt-dlp.
	 * - Starts detailed analysis for valid video links.
	 */
	private fun startInterceptingUrl(targetVideoUrl: String) {
		logger.d("Starting interception for URL: $targetVideoUrl")
		
		interceptWaitingDialog?.let { waitingDialog ->
			// Prevent multiple interceptions running simultaneously
			if (isInterceptingInProcess) {
				logger.d("Interception already in progress — ignoring duplicate request")
				return
			}
			
			// Show waiting dialog and mark process as active
			waitingDialog.show()
			isInterceptingInProcess = true
			
			// Execute network and parsing logic on background thread
			ThreadsUtility.executeInBackground(codeBlock = {
				// Handle unsupported yt-dlp URLs
				if (!isYtdlpSupportedUrl(targetVideoUrl)) {
					logger.d("URL not supported by yt-dlp: $targetVideoUrl")
					ThreadsUtility.executeOnMain {
						waitingDialog.close()
						safeBaseActivityRef?.doSomeVibration(50)
						showToast(
							activityInf = safeBaseActivityRef,
							msgId = R.string.title_unsupported_video_link
						)
						openInBuiltInBrowser(targetVideoUrl)
					}
					return@executeInBackground
				}
				
				// Begin actual video analysis
				logger.d("Supported URL detected — initiating yt-dlp analysis")
				startAnalyzingVideoUrl(targetVideoUrl)
			})
		} ?: logger.d("No waiting dialog initialized — interception aborted")
	}
	
	/**
	 * Analyzes the provided video URL to extract available formats and resolutions,
	 * then displays a resolution picker dialog for user selection.
	 *
	 * This function supports both YouTube and general direct video URLs (via yt-dlp).
	 * It runs asynchronously and updates the UI upon completion.
	 *
	 * @param videoUrl The URL of the video to analyze.
	 *
	 * Workflow:
	 * - Detects if the URL belongs to YouTube and fetches stream info.
	 * - Retrieves metadata such as title, description, thumbnail, and duration.
	 * - Uses yt-dlp or YouTube parser to extract available video formats.
	 * - Displays a resolution picker dialog if formats are found.
	 * - Falls back to the built-in browser if extraction fails.
	 */
	private suspend fun startAnalyzingVideoUrl(videoUrl: String) {
		logger.d("Analyzing video URL: $videoUrl")
		
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			// Step 1: Handle YouTube URLs separately
			val ytStreamInfo = if (isYouTubeUrl(videoUrl)) {
				logger.d("YouTube URL detected — fetching stream info")
				val urlWithoutPlaylist = filterYoutubeUrlWithoutPlaylist(videoUrl)
				YouTubeVideoStreamParser.getStreamInfo(urlWithoutPlaylist)
			} else null
			
			// Step 2: Gather and prioritize available metadata
			val videoCookie = userGivenVideoInfo?.videoCookie
			val videoTitle = ytStreamInfo?.name ?: userGivenVideoInfo?.videoTitle
			val videoDescription = ytStreamInfo?.description?.content ?: userGivenVideoInfo?.videoDescription
			val videoThumbnailUrl = ytStreamInfo?.thumbnails?.firstOrNull()?.url ?: userGivenVideoInfo?.videoThumbnailUrl
			val videoUrlReferer = userGivenVideoInfo?.videoUrlReferer
			val videoDurationFromYtStream = ytStreamInfo?.duration?.let { playbackInSec ->
				if (playbackInSec > 0L) playbackInSec * 1000 else 0L
			}
			val videoThumbnailByReferer = userGivenVideoInfo?.videoThumbnailByReferer ?: false
			
			// Step 3: Extract available video formats using the appropriate parser
			val videoFormats = if (isYouTubeUrl(videoUrl)) {
				logger.d("Fetching available YouTube resolutions")
				getYoutubeVideoResolutionsFrom(ytStreamInfo)
			} else {
				logger.d("Fetching video formats via yt-dlp for: $videoUrl")
				ArrayList(getYtdlpVideoFormatsWithRetry(videoUrl, videoCookie))
			}
			
			// Step 4: Build video information model for display
			val videoInfo = VideoInfo(
				videoUrl = videoUrl,
				videoTitle = videoTitle,
				videoDescription = videoDescription,
				videoThumbnailUrl = videoThumbnailUrl,
				videoUrlReferer = videoUrlReferer,
				videoThumbnailByReferer = videoThumbnailByReferer,
				videoCookie = videoCookie,
				videoFormats = videoFormats,
				videoDuration = videoDurationFromYtStream ?: userGivenVideoInfo?.videoDuration ?: 0L
			)
			
			// Step 5: Switch to main thread to update UI
			ThreadsUtility.executeOnMain(codeBlock = {
				logger.d("Video analysis complete — preparing UI update")
				interceptWaitingDialog?.let { waitingDialog ->
					waitingDialog.close()
					isInterceptingInProcess = false
					if (isInterceptingTerminated) {
						logger.d("Interception terminated before completion — aborting UI display")
						return@let
					}
					
					// Handle empty formats (no downloadable content)
					if (videoInfo.videoFormats.isEmpty()) {
						logger.d("No downloadable video formats found for: $videoUrl")
						if (shouldOpenBrowserAsFallback) {
							openInBuiltInBrowser(targetUrl = videoUrl)
						} else {
							val msgId = R.string.title_no_video_found_try_later
							showToast(safeBaseActivityRef, msgId = msgId)
						}
						return@let
					}
					
					// Step 6: Show available resolutions in picker dialog
					logger.d("Displaying resolution picker — total formats: ${videoInfo.videoFormats.size}")
					VideoResolutionPicker(
						baseActivity = safeBaseActivityRef,
						videoInfo = videoInfo,
						onDialogClose = { safelyCloseBaseActivity() },
						closeActivityOnSuccessfulDownload = closeActivityOnSuccessfulDownload,
						errorCallBack = {
							logger.d("Error while handling video picker — triggering fallback")
							if (shouldOpenBrowserAsFallback) showOpeningInBrowserPrompt(videoUrl)
							else onOpeningBuiltInBrowser?.invoke()
						}
					).show()
				} ?: logger.d("Waiting dialog not found — skipping UI update")
			})
		} ?: logger.d("Base activity reference lost — aborting video analysis")
	}
	
	/**
	 * Retrieves a list of available YouTube video resolutions (and audio-only option) from a [StreamInfo] object.
	 * If no stream data is provided (null), returns a default list of resolutions.
	 *
	 * @param ytStreamInfo The [StreamInfo] object containing YouTube stream data. Can be null.
	 * @return A list of [VideoFormat] objects representing available resolutions/audio options.
	 *         Each [VideoFormat] includes:
	 *         - `formatId`: Identifier (uses app package name).
	 *         - `formatResolution`: Resolution (e.g., "720p") or "Audio" for audio-only.
	 *         - `formatFileSize`: Placeholder text (replace with actual size if available).
	 */
	private fun getYoutubeVideoResolutionsFrom(ytStreamInfo: StreamInfo?): List<VideoFormat> {
		logger.d("Getting YouTube video resolutions")
		// Case 1: No stream info provided → return default resolutions
		if (ytStreamInfo == null) {
			logger.d("No stream info, returning default resolutions")
			return arrayListOf(
				"Audio", "2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p"
			).map { resolution ->
				VideoFormat(
					formatId = INSTANCE.packageName,
					formatResolution = resolution,
					formatFileSize = getText(R.string.title_unknown)
				)
			}.let { ArrayList(it) }
		}
		// Case 2: Stream info available → extract actual resolutions
		else {
			logger.d("Processing stream info for resolutions")
			val formats = mutableListOf<VideoFormat>()
			
			// Add audio-only option (always first)
			ytStreamInfo.audioStreams
				.maxByOrNull { it.bitrate }?.let { bestStream ->
					val contentLength = bestStream.itagItem?.contentLength
					formats.add(
						VideoFormat(
							formatId = INSTANCE.packageName,
							formatResolution = "Audio", // Should be hard coded
							formatFileSize = if (contentLength != null && contentLength > 0L == true)
								getHumanReadableFormat(contentLength) else getText(R.string.title_unknown)
						)
					)
				}
			
			// Process video streams:
			// 1. Filter streams with valid bitrate.
			// 2. Group by height (resolution).
			// 3. Select the stream with highest bitrate per resolution.
			ytStreamInfo.videoOnlyStreams
				.filter { it.bitrate > 0 }
				.groupBy { it.height }
				.forEach { (_, streams) ->
					streams.maxByOrNull { it.bitrate }?.let { bestStream ->
						val contentLength = bestStream.itagItem?.contentLength
						formats.add(
							VideoFormat(
								formatId = INSTANCE.packageName,
								formatResolution = "${bestStream.height}p",
								formatFileSize = if (contentLength != null && contentLength > 0L == true)
									getHumanReadableFormat(contentLength) else getText(R.string.title_unknown)
							)
						)
					}
				}
			
			logger.d("Found ${formats.size} video formats")
			return ArrayList(formats)
		}
	}
	
	/**
	 * Displays a fallback prompt asking the user whether to open the provided video URL
	 * in the in-app browser when no downloadable or playable formats are found.
	 *
	 * This function is typically called when yt-dlp or YouTube parsing fails to
	 * detect any valid video streams. It shows a message dialog with an action
	 * button to directly open the link in the built-in browser.
	 *
	 * @param videoUrl The video URL that failed to fetch playable/downloadable formats.
	 *
	 * Workflow:
	 * - Logs the fallback action for debugging.
	 * - Shows a non-cancelable message dialog explaining the issue.
	 * - Allows the user to open the link in the built-in browser with one click.
	 */
	private fun showOpeningInBrowserPrompt(videoUrl: String) {
		logger.d("Initializing browser fallback prompt for URL: $videoUrl")
		
		// Define message and button text resources
		val msgResId = R.string.text_error_failed_to_fetch_video_format
		val buttonTextResId = R.string.title_open_link_in_browser
		
		// Step 1: Build and display the message dialog
		showMessageDialog(
			baseActivityInf = safeBaseActivityRef,
			isNegativeButtonVisible = false,
			messageTextViewCustomize = {
				it.setText(msgResId)
				logger.d("Set error message text in dialog")
			},
			positiveButtonTextCustomize = {
				it.setText(buttonTextResId)
				it.setLeftSideDrawable(R.drawable.ic_button_open_v2)
				logger.d("Configured positive button: Open in Browser")
			}
		)?.apply {
			// Step 2: Handle positive button click
			setOnClickForPositiveButton {
				logger.d("User selected 'Open in Browser' action for: $videoUrl")
				close()
				openInBuiltInBrowser(videoUrl)
			}
		} ?: logger.d("Failed to show browser fallback prompt — dialog instance is null")
	}
	
	/**
	 * Attempts to open the provided URL using the app's built-in browser.
	 *
	 * This method first verifies the validity of the URL, then checks whether the current
	 * activity context supports in-app browser operations (i.e., is a [MotherActivity]).
	 * If so, it opens the target URL in a new browser tab within the app’s internal webview.
	 *
	 * In case of any unexpected errors or if the internal browser cannot be used, it
	 * automatically falls back to the system browser to ensure a smooth user experience.
	 *
	 * @param targetUrl The URL to open inside the app's built-in browser.
	 *
	 * Workflow:
	 *  - Logs each significant step for debugging.
	 *  - Validates the URL before attempting navigation.
	 *  - Opens a new browsing tab in the in-app webview if valid.
	 *  - Falls back to the system browser if any failure occurs.
	 */
	private fun openInBuiltInBrowser(targetUrl: String) {
		logger.d("Request received to open URL in built-in browser: $targetUrl")
		
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			try {
				// Step 1: Ensure the activity is capable of handling browser operations
				if (safeBaseActivityRef is MotherActivity) {
					logger.d("Activity confirmed as MotherActivity, proceeding with in-app browser launch")
					
					val browserFragment = safeBaseActivityRef.browserFragment
					val browserFragmentBody = browserFragment?.browserFragmentBody
					val webviewEngine = browserFragmentBody?.webviewEngine ?: run {
						logger.d("WebView engine not found, aborting built-in browser launch")
						return
					}
					
					// Step 2: Validate the target URL before navigating
					if (targetUrl.isNotEmpty() && isValidURL(targetUrl)) {
						logger.d("Valid URL detected: $targetUrl — adding new browsing tab")
						
						val sideNavigation = safeBaseActivityRef.sideNavigation
						sideNavigation?.addNewBrowsingTab(url = targetUrl, webviewEngine = webviewEngine)
						safeBaseActivityRef.openBrowserFragment()
						logger.d("Successfully opened URL in built-in browser")
					} else {
						logger.d("Invalid or empty URL: $targetUrl — showing invalid URL toast")
						showInvalidUrlToast()
					}
				} else {
					// Step 3: Handle cases where activity isn't MotherActivity
					logger.d("Activity is not MotherActivity — invoking fallback browser callback")
					onOpeningBuiltInBrowser?.let { it() }
				}
			} catch (error: Exception) {
				// Step 4: Error handling and fallback to system browser
				logger.e("Exception occurred while opening built-in browser: ${error.message}", error)
				logger.d("Falling back to system browser for URL: $targetUrl")
				openInSystemBrowser(targetUrl)
			}
		} ?: logger.d("Safe activity reference is null — cannot open built-in browser")
	}
	
	/**
	 * Opens the given URL in the device's default system browser.
	 *
	 * This method is used as a fallback when the app’s built-in browser cannot
	 * handle a given link or when an explicit system-level redirect is preferred.
	 * It attempts to open the URL safely using Android’s standard intent mechanism.
	 *
	 * @param urlFromIntent The URL to open using the system browser.
	 *
	 * Behavior:
	 * - Logs the URL being opened.
	 * - Invokes [openLinkInSystemBrowser] utility with a fallback toast if it fails.
	 */
	private fun openInSystemBrowser(urlFromIntent: String) {
		logger.d("Attempting to open URL in system browser: $urlFromIntent")
		
		openLinkInSystemBrowser(urlFromIntent, safeBaseActivityRef) {
			logger.d("System browser failed to open URL, showing error toast")
			showToast(
				activityInf = safeBaseActivityRef,
				msgId = R.string.title_failed_open_the_video
			)
		}
	}
	
	/**
	 * Displays a short toast message when the provided URL is invalid or malformed.
	 *
	 * This provides immediate user feedback for cases where a shared or intercepted
	 * link cannot be processed (e.g., missing scheme, unsupported protocol).
	 *
	 * Behavior:
	 * - Vibrates briefly to notify the user.
	 * - Displays a predefined invalid URL message.
	 */
	private fun showInvalidUrlToast() {
		logger.d("Displaying invalid URL toast to user")
		safeBaseActivityRef?.doSomeVibration(50)
		showToast(
			activityInf = safeBaseActivityRef,
			msgId = R.string.title_invalid_url
		)
	}
	
	/**
	 * Handles user cancellation during an ongoing video URL interception.
	 *
	 * This method is called when the waiting dialog is dismissed by the user.
	 * It stops any active interception process, closes the dialog, and
	 * optionally closes the activity if required.
	 *
	 * @param waitingDialog The active [WaitingDialog] instance being dismissed.
	 */
	private fun onCancelInterceptRequested(waitingDialog: WaitingDialog) {
		logger.d("User requested cancellation of video URL interception")
		
		waitingDialog.dialogBuilder?.let { dialogBuilder ->
			if (dialogBuilder.isShowing) {
				logger.d("Closing waiting dialog due to user cancellation")
				dialogBuilder.close()
				safelyCloseBaseActivity()
			}
		}
		
		isInterceptingInProcess = false
		isInterceptingTerminated = true
		logger.d("Interception state updated: process stopped and terminated")
	}
	
	/**
	 * Safely closes the current activity if it’s an [IntentInterceptActivity].
	 *
	 * This ensures that the interception flow terminates gracefully without
	 * leaving unnecessary activities in the back stack. It executes on the
	 * main thread to maintain UI consistency.
	 */
	private fun safelyCloseBaseActivity() {
		logger.d("Attempting to safely close the base activity")
		
		executeOnMainThread {
			try {
				// Close only if the base activity was started as an intercept intent
				val shouldClose = safeBaseActivityRef is IntentInterceptActivity
				if (shouldClose) {
					logger.d("Base activity is IntentInterceptActivity — finishing activity")
					safeBaseActivityRef?.finish()
				} else {
					logger.d("Base activity is not an intercept activity — skipping close")
				}
			} catch (error: Exception) {
				logger.e("Error while closing base activity: ${error.message}", error)
			}
		}
	}
}