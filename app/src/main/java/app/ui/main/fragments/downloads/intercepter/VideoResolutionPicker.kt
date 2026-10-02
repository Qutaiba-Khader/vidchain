package app.ui.main.fragments.downloads.intercepter

import android.view.View
import android.widget.CheckBox
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import app.core.AIOApp.Companion.IS_PREMIUM_USER
import app.core.AIOApp.Companion.IS_ULTIMATE_VERSION_UNLOCKED
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.downloadSystem
import app.core.bases.BaseActivity
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.parseSize
import app.core.engines.video_parser.parsers.VideoInfo
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.others.information.IntentInterceptActivity
import com.aio.R
import lib.device.AppVersionUtility.versionName
import lib.device.DateTimeUtils.formatVideoDuration
import lib.device.IntentUtility.openLinkInSystemBrowser
import lib.device.StorageUtility.getFreeExternalStorageSpace
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.networks.URLUtilityKT.extractHostUrl
import lib.networks.URLUtilityKT.getBaseDomain
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.process.ThreadsUtility.executeOnMain
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.animateFadInOutAnim
import lib.ui.ViewUtility.closeAnyAnimation
import lib.ui.ViewUtility.loadThumbnailFromUrl
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference

/**
 * A dialog that allows users to select a specific video resolution or format for downloading.
 *
 * This class provides a comprehensive UI for interacting with video download options and metadata.
 * It handles:
 * - Displaying all available video formats/resolutions in a GridView
 * - Showing detailed video metadata such as title, thumbnail, duration, and source site favicon
 * - Managing download restrictions including premium/non-premium limits and ad requirements
 * - Providing fallback options to open the video URL in a browser if downloads are unavailable
 * - Initiating and adding the selected video format to the app's download system with proper validation
 * - Giving user feedback through toasts, haptic vibration, and error handling for download or network failures
 *
 * This dialog ensures a smooth and informative experience for users when selecting and downloading video content.
 *
 * @param baseActivity The parent activity that hosts this dialog; used for context and UI operations
 * @param videoInfo Contains video metadata (title, thumbnail, duration) and a list of available formats for selection
 * @param errorCallBack Callback invoked if initialization fails or an unexpected error occurs
 * @param onDialogClose Callback invoked whenever the dialog is closed, either by user action or programmatically
 * @param closeActivityOnSuccessfulDownload Whether to automatically close the parent activity after a successful download
 */
class VideoResolutionPicker(
	private val baseActivity: BaseActivity?,
	private val videoInfo: VideoInfo,
	private val errorCallBack: () -> Unit = {},
	private val onDialogClose: () -> Unit = {},
	private val closeActivityOnSuccessfulDownload: Boolean = false
) {

	/** Logger instance for debug messages and error tracking within this dialog class */
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to the parent activity to avoid memory leaks */
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	/** Builder for creating and managing the resolution picker dialog */
	private val dialogBuilder = DialogBuilder(safeBaseActivityRef)

	/** Model holding download-related data for the selected video */
	private val downloadModel = DownloadDataModel()

	/** Stores the video title extracted from the URL, if available */
	private var titleExtractedFromUrl: String? = null

	/** ImageView displaying the video's thumbnail */
	private lateinit var videoThumbnail: ImageView

	/** TextView showing the video's duration */
	private lateinit var videoDuration: TextView

	/** TextView displaying the video's title */
	private lateinit var videoTitleView: TextView

	/** ImageView indicating whether the download is set to the private folder */
	private lateinit var privateFolderImageView: ImageView

	/** GridView displaying the list of available video formats for selection */
	private lateinit var formatsGridView: GridView

	/** CheckBox allowing the user to toggle the "Download to Private Folder" option */
	private lateinit var privateFolderCheckbox: CheckBox

	/** Adapter responsible for managing video format items and selection in the GridView */
	private lateinit var videoFormatAdapter: VideoFormatAdapter

	init {
		try {
			logger.d("Initializing VideoResolutionPicker dialog layout and components")

			// Initialize dialog layout and components
			val dialogLayoutResId = R.layout.dialog_video_res_picker_1
			dialogBuilder.setView(dialogLayoutResId)
			dialogBuilder.setCancelable(true)
			logger.d("Dialog layout set with resource ID: $dialogLayoutResId")

			// Assign listeners for dialog cancel/dismiss events
			dialogBuilder.dialog.setOnCancelListener {
				logger.d("Dialog cancelled by user")
				onDialogClose.invoke()
			}

			dialogBuilder.dialog.setOnDismissListener {
				logger.d("Dialog dismissed")
				onDialogClose.invoke()
			}

			// Initialize UI components and button click handlers
			dialogBuilder.view.apply {
				logger.d("Initializing dialog UI components")
				initializeDialogLayoutViews(this)
				setupButtonsOnClickListeners(this)
			}

			logger.d("VideoResolutionPicker initialization complete")
		} catch (error: Exception) {
			logger.e("Error initializing VideoResolutionPicker: ${error.message}", error)
			close()
			errorCallBack()
		}
	}

	/**
	 * Displays the resolution picker dialog if it is not already showing.
	 * Logs the show action for debugging purposes.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Showing VideoResolutionPicker dialog")
			dialogBuilder.show()
		} else {
			logger.d("VideoResolutionPicker dialog is already showing")
		}
	}

	/**
	 * Closes the resolution picker dialog and safely finishes the parent activity
	 * if it is of type [IntentInterceptActivity]. Logs actions and errors.
	 */
	fun close() {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			if (dialogBuilder.isShowing) {
				logger.d("Closing VideoResolutionPicker dialog")
				dialogBuilder.close()
				try {
					// Special handling for IntentInterceptActivity
					val condition = safeBaseActivityRef is IntentInterceptActivity
					if (condition) {
						logger.d("Parent activity is IntentInterceptActivity, finishing activity")
						safeBaseActivityRef.finish()
					}
				} catch (error: Exception) {
					logger.e("Error while closing activity: ${error.message}", error)
				}
			} else {
				logger.d("VideoResolutionPicker dialog is not currently showing")
			}
		}
	}

	/**
	 * Initializes all views in the resolution picker dialog layout.
	 * Sets up video title, thumbnail, URL, site favicon, formats grid, and duration.
	 *
	 * @param layout The root view of the dialog
	 */
	private fun initializeDialogLayoutViews(layout: View) {
		logger.d("Initializing dialog layout views")
		showVideoTitleFromURL(layout)
		showVideoThumb(layout)
		showVideoURL(layout)
		showSiteFavicon(layout)
		setupFormatsGridAdapter(layout)
		showDuration(layout)
		updatePrivateFolderIndicator(layout)
	}

	/**
	 * Displays the video title in the dialog, prioritizing metadata from [VideoInfo],
	 * and falling back to fetching the title from the webpage if needed.
	 *
	 * @param layout The root view containing the title TextView
	 */
	private fun showVideoTitleFromURL(layout: View) {
		logger.d("Displaying video title from metadata or webpage")
		videoTitleView = layout.findViewById(R.id.txt_video_title)

		if (!videoInfo.videoTitle.isNullOrEmpty()) {
			// Use title from video info if available
			logger.d("Using cached VideoInfo title: ${videoInfo.videoTitle}")
			videoTitleView.isSelected = true
			videoTitleView.text = videoInfo.videoTitle
			titleExtractedFromUrl = videoInfo.videoTitle
			return
		}

		// Fetch title from webpage asynchronously
		executeInBackground(codeBlock = {
			logger.d("Fetching video title from webpage: ${videoInfo.videoUrl}")
			executeOnMain { animateFadInOutAnim(videoTitleView) }
			val websiteUrl = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
			getWebpageTitleOrDescription(websiteUrl) { fetchedUrlTitle ->
				executeOnMainThread {
					videoTitleView.isSelected = true
					closeAnyAnimation(videoTitleView)

					if (fetchedUrlTitle.isNullOrEmpty()) {
						logger.d("No title found from webpage; applying fallback")
						updateTitleByFormatId(videoTitleView)
					} else {
						logger.d("Title fetched from webpage successfully: $fetchedUrlTitle")
						videoTitleView.text = fetchedUrlTitle
						videoInfo.videoTitle = fetchedUrlTitle
						titleExtractedFromUrl = fetchedUrlTitle
					}
				}
			}
		})
	}

	/**
	 * Loads and displays the video thumbnail in the dialog.
	 *
	 * Determines the appropriate URL to use for fetching the thumbnail, either from
	 * the referer or the direct video URL. Thumbnail loading is performed in a background thread.
	 *
	 * @param layout The root view containing the thumbnail ImageView
	 */
	private fun showVideoThumb(layout: View) {
		if (!::videoThumbnail.isInitialized) {
			videoThumbnail = layout.findViewById(R.id.image_video_thumbnail)
		}

		logger.d("Loading video thumbnail for URL: ${videoInfo.videoUrl}")

		// Load thumbnail in background
		executeInBackground(codeBlock = {
			val websiteUrl = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
			val videoThumbnailByReferer = videoInfo.videoThumbnailByReferer

			// Determine whether to use referer URL or direct video URL for thumb parsing
			val thumbParsingUrl = if (videoThumbnailByReferer) websiteUrl else videoInfo.videoUrl
			val thumbImageUrl = videoInfo.videoThumbnailUrl
				?: startParsingVideoThumbUrl(thumbParsingUrl)
				?: run {
					logger.d("Thumbnail URL could not be determined")
					return@executeInBackground
				}

			// Update UI with thumbnail on main thread
			executeOnMain {
				logger.d("Setting thumbnail image URL: $thumbImageUrl")
				videoInfo.videoThumbnailUrl = thumbImageUrl
				loadThumbnailFromUrl(thumbImageUrl, videoThumbnail)
			}
		})
	}

	/**
	 * Displays the video URL in the dialog.
	 *
	 * @param layout The root view containing the URL TextView
	 */
	private fun showVideoURL(layout: View) {
		logger.d("Displaying video URL: ${videoInfo.videoUrl}")
		layout.findViewById<TextView>(R.id.txt_video_url).apply {
			isSelected = true
			text = videoInfo.videoUrl
		}
	}

	/**
	 * Loads and displays the site favicon inside the provided layout.
	 *
	 * Fetches the favicon for the video’s referring site or URL in a background thread.
	 * If fetching fails or the file is invalid, a default icon is displayed.
	 *
	 * @param layout The parent [View] containing an ImageView with ID [R.id.img_site_favicon]
	 */
	private fun showSiteFavicon(layout: View) {
		layout.findViewById<ImageView>(R.id.img_site_favicon).let { favicon ->
			val defaultFaviconResId = R.drawable.ic_button_information
			logger.d("Attempting to update site favicon for URL: ${videoInfo.videoUrl}")

			executeInBackground(codeBlock = {
				val referralSite = videoInfo.videoUrlReferer ?: videoInfo.videoUrl
				logger.d("Fetching favicon for site: $referralSite")

				aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
					val faviconImgFile = File(faviconFilePath)

					if (!faviconImgFile.exists() || !faviconImgFile.isFile) {
						logger.e("Favicon file not found or invalid at: $faviconFilePath")
						return@executeInBackground
					}

					val faviconImgURI = faviconImgFile.toUri()
					executeOnMain(codeBlock = {
						try {
							logger.d("Applying favicon from local file")
							showView(targetView = favicon, shouldAnimate = true)
							favicon.setImageURI(faviconImgURI)
						} catch (error: Exception) {
							logger.e("Failed to set favicon: ${error.message}", error)
							showView(targetView = favicon, shouldAnimate = true)
							favicon.setImageResource(defaultFaviconResId)
						}
					})
				}
			}, errorHandler = {
				logger.e("Unexpected error while loading favicon: ${it.message}", it)
			})
		}
	}

	/**
	 * Updates the file type indicator icon in the dialog UI based on the detected media type
	 * (audio or video) from the currently selected video format.
	 *
	 * This method identifies the type of file by analyzing the format information and updates
	 * the indicator icon accordingly to visually represent whether the selected format
	 * is an audio-only or video-inclusive file.
	 *
	 * Behavior:
	 * - Displays an audio icon if the selected format contains only audio.
	 * - Displays a video icon for all other formats (default case).
	 * - Ensures the indicator is visible once updated.
	 *
	 * This helps users quickly distinguish between audio-only and full video formats before downloading.
	 *
	 * @param layout The root dialog [View] that contains the file type indicator ImageView.
	 */
	private fun updateFileTypeIndicator(layout: View) {
		val selectedFormatIndex = videoFormatAdapter.selectedPosition
		val videoFormat = videoInfo.videoFormats[selectedFormatIndex]
		val fileTypeIndicator = layout.findViewById<ImageView>(R.id.img_file_type_indicator)

		val formatResolution = videoFormat.formatResolution
		logger.d("Updating file type indicator for video resolution: $formatResolution")

		// Determine and set the correct icon based on file type
		fileTypeIndicator.setImageResource(
			when {
				formatResolution.contains("audio", ignoreCase = true) -> R.drawable.ic_button_audio // Audio format
				else -> R.drawable.ic_button_video // Default for video format
			}
		)

		fileTypeIndicator.visibility = View.VISIBLE
	}

	/**
	 * Updates the private folder indicator icon and checkbox state in the dialog UI.
	 *
	 * This function visually communicates to the user whether downloads are currently
	 * configured to be saved in a private (locked) folder or a standard (public) directory.
	 * It ensures that both the checkbox and the icon remain in sync with the user's
	 * active download location preference.
	 *
	 * @param layout The root dialog [View] containing the private folder indicator and checkbox.
	 */
	private fun updatePrivateFolderIndicator(layout: View) {
		logger.d("Updating private folder indicator UI state")

		// Ensure checkbox reference is initialized
		if (!::privateFolderCheckbox.isInitialized) {
			privateFolderCheckbox = layout.findViewById(R.id.checkbox_download_at_private)
			logger.d("Private folder checkbox reference initialized")
		}

		// Get ImageView for private folder indicator
		privateFolderImageView = layout.findViewById(R.id.img_private_folder_indicator)
		val downloadLocation = downloadModel.globalSettings.defaultDownloadLocation
		logger.d("Current download location: $downloadLocation")

		// Update indicator icon based on folder type
		privateFolderImageView.setImageResource(
			when (downloadLocation) {
				PRIVATE_FOLDER -> R.drawable.ic_button_lock  // Indicates private folder
				else -> R.drawable.ic_button_folder          // Indicates normal folder
			}
		)

		// Sync checkbox state with folder type
		privateFolderCheckbox.isChecked = (downloadLocation == PRIVATE_FOLDER)

		logger.d(
			"Private folder indicator updated — " +
					"icon=${if (downloadLocation == PRIVATE_FOLDER) "lock" else "folder"}, " +
					"checked=${privateFolderCheckbox.isChecked}"
		)
	}

	/**
	 * Initializes the GridView adapter to display available video formats.
	 *
	 * Populates the GridView with [VideoFormatAdapter] and sets a click listener
	 * to update the video title when a format is selected.
	 *
	 * @param layout The root view containing the GridView with ID [R.id.video_format_container]
	 */
	private fun setupFormatsGridAdapter(layout: View) {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			formatsGridView = layout.findViewById(R.id.video_format_container)
			videoFormatAdapter =
				VideoFormatAdapter(safeBaseActivityRef, videoInfo, videoInfo.videoFormats) {
					val videoTitleView = layout.findViewById<TextView>(R.id.txt_video_title)
					updateTitleByFormatId(videoTitleView)
					updateFileTypeIndicator(layout)
				}
			formatsGridView.adapter = videoFormatAdapter
		}
	}

	/**
	 * Displays the video duration on the provided layout if available.
	 *
	 * This function checks whether a valid video duration is present.
	 * If so, it makes the duration container visible (with animation)
	 * and updates the text field with the formatted time.
	 *
	 * @param layout The parent view containing the duration container and text view.
	 */
	private fun showDuration(layout: View) {
		// Skip if duration is not available
		if (videoInfo.videoDuration == 0L) return

		// Show the media duration container with animation
		showView(layout.findViewById(R.id.container_media_duration), shouldAnimate = true)

		// Update the duration text
		layout.findViewById<TextView>(R.id.txt_media_duration).let {
			it.text = formatVideoDuration(videoInfo.videoDuration)
			logger.d("Displayed video duration: ${it.text}")
		}
	}

	/**
	 * Updates the video title TextView based on the currently selected video format.
	 *
	 * If the title was not previously extracted from the URL, generates a new title
	 * using [madeUpTitleFromSelectedVideoFormat] and updates both the TextView and
	 * the [videoInfo] object.
	 *
	 * @param textView The TextView that displays the video title
	 */
	private fun updateTitleByFormatId(textView: TextView) {
		if (!titleExtractedFromUrl.isNullOrEmpty()) return
		val madeUpTitle = madeUpTitleFromSelectedVideoFormat()
		textView.text = madeUpTitle
		videoInfo.videoTitle = madeUpTitle
	}

	/**
	 * Generates a descriptive title for the video based on the selected format and metadata.
	 *
	 * This is used as a fallback or default filename when no user-provided title is available.
	 *
	 * @return A generated title string, or a prompt if no format is selected.
	 */
	private fun madeUpTitleFromSelectedVideoFormat(): String {
		if (videoFormatAdapter.selectedPosition < 0) {
			// Return default prompt when no format is selected
			val textResID = R.string.title_pick_video_resolution_for_file_name
			logger.d("No video format selected; using default prompt title")
			return getText(textResID)
		}

		val selectedFormatIndex = videoFormatAdapter.selectedPosition
		val videoFormat = videoInfo.videoFormats[selectedFormatIndex]

		// Construct a descriptive title from format metadata
		val madeUpTitle = "${videoFormat.formatId}_" +
				"${videoFormat.formatResolution}_" +
				"${videoFormat.formatVcodec}_" +
				"${getBaseDomain(videoInfo.videoUrl)}_" +
				"Downloaded_From_${extractHostUrl(videoInfo.videoUrl)}_" +
				"By_AIO_Version_${versionName}"

		logger.d("Generated title from selected format: $madeUpTitle")
		return madeUpTitle
	}

	/**
	 * Configures click listeners for all interactive elements in the video resolution picker dialog.
	 *
	 * This method:
	 * - Checks if the user has exceeded the download threshold and updates
	 *   the download button text for non-premium users.
	 * - Assigns listeners for:
	 *   - Opening the video URL in a browser.
	 *   - Downloading the selected video format.
	 *   - Toggling private folder download option.
	 *
	 * @param dialogLayout The root view of the dialog containing all buttons and checkboxes.
	 */
	private fun setupButtonsOnClickListeners(dialogLayout: View) = with(dialogLayout) {
		logger.d("Setting up click listeners for video resolution picker dialog buttons")

		// Check and update UI if the user has reached the download threshold limit
		val numberOfDownloadsUserDid = aioSettings.numberOfDownloadsUserDid
		val maxDownloadThreshold = aioSettings.numberOfMaxDownloadThreshold
		if (numberOfDownloadsUserDid >= maxDownloadThreshold) {
			if (!IS_PREMIUM_USER && !IS_ULTIMATE_VERSION_UNLOCKED) {
				logger.i("User reached download threshold — updating button to 'Watch Ad to Download'")
				val btnDownload = dialogLayout.findViewById<TextView>(R.id.btn_dialog_positive)
				btnDownload.apply {
					setLeftSideDrawable(R.drawable.ic_button_video)
					setText(R.string.title_watch_ad_to_download)
				}
			}
		}

		// Map buttons and their associated actions
		val buttonActions = listOf(
			R.id.btn_file_info_card to {
				logger.d("File info card clicked — opening video URL in browser")
				openVideoUrlInBrowser()
			},
			R.id.btn_dialog_positive_container to {
				logger.d("Download button clicked — attempting to download selected format")
				downloadSelectedVideoFormat()
			},
			R.id.checkbox_download_at_private to {
				logger.d("Private folder checkbox toggled — updating download location")
				togglePrivateFolderDownload(dialogLayout)
			}
		)

		// Assign click listeners to each view
		buttonActions.forEach { (id, action) ->
			findViewById<View>(id).setOnClickListener { action() }
		}

		logger.d("Button click listeners setup completed successfully")
	}

	/**
	 * Toggles the download location between the private folder and system gallery
	 * based on the state of the private folder checkbox.
	 *
	 * This method updates both:
	 * - The global download location in [downloadModel.globalSettings]
	 * - The UI indicator reflecting the current folder selection
	 *
	 * @param layout The root view containing the private folder checkbox and indicator
	 */
	private fun togglePrivateFolderDownload(layout: View) {
		logger.d("Toggling private folder download option")

		// Initialize the checkbox reference if not already done
		if (!::privateFolderCheckbox.isInitialized) {
			privateFolderCheckbox = layout.findViewById(R.id.checkbox_download_at_private)
			logger.d("Initialized private folder checkbox reference")
		}

		// Update the download location based on checkbox state
		downloadModel.globalSettings.defaultDownloadLocation =
			if (privateFolderCheckbox.isChecked) {
				logger.i("Private folder selected for download")
				PRIVATE_FOLDER
			} else {
				logger.i("System gallery selected for download")
				SYSTEM_GALLERY
			}

		// Refresh the update download folder for the download model
		downloadModel.refreshUpdatedDownloadFolder()

		// Update the UI indicator accordingly
		updatePrivateFolderIndicator(layout)
		logger.d("Private folder indicator updated successfully")
	}

	/**
	 * Initiates the download of the currently selected video format.
	 *
	 * Performs checks before starting the download:
	 * 1. Ensures a format is selected.
	 * 2. Ensures the video title has been loaded.
	 *
	 * Provides haptic feedback and shows a toast message if any check fails.
	 * Logs the actions for debugging purposes.
	 */
	private fun downloadSelectedVideoFormat() {
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			val selectedFormat = videoFormatAdapter.selectedPosition
			logger.d("Attempting to download selected video format at position: $selectedFormat")

			if (selectedFormat < 0) {
				// No format selected, notify user
				logger.d("No video format selected")
				safeBaseActivityRef.doSomeVibration(20)
				showToast(
					activityInf = safeBaseActivityRef,
					msgId = R.string.title_select_a_video_resolution
				)
				return
			}

			if (videoInfo.videoTitle.isNullOrEmpty()) {
				// Video title not yet loaded, notify user
				logger.d("Video title not loaded yet")
				safeBaseActivityRef.doSomeVibration(20)
				showToast(
					activityInf = safeBaseActivityRef,
					msgId = R.string.title_wait_till_server_return_video_title
				)
				return
			}

			logger.d("All checks passed, adding selected format to download system")
			addVideoFormatToDownloadSystem(selectedFormat)
		}
	}

	/**
	 * Adds the selected video format to download system
	 * @param selectedFormat Index of the selected format
	 */
	private fun addVideoFormatToDownloadSystem(selectedFormat: Int) {
		val videoFormat: VideoFormat = videoInfo.videoFormats[selectedFormat]
		addToDownloadSystem(videoFormat)
		close()
	}

	/**
	 * Prepares and adds the selected video format to the download system.
	 *
	 * This function configures the [DownloadDataModel] with all necessary metadata, including:
	 * - Video information and selected format
	 * - Title, cookies, and referrer
	 * - File size and human-readable format
	 * - Media playback duration if available
	 *
	 * It then checks for available storage space before adding the download task.
	 * Provides user feedback via toast messages and dialogs for success or errors.
	 *
	 * @param videoFormat The [VideoFormat] selected for download.
	 */
	private fun addToDownloadSystem(videoFormat: VideoFormat) {
		logger.d("Preparing download for format: ${videoFormat.formatId}, resolution: ${videoFormat.formatResolution}")
		executeInBackground(codeBlock = {
			safeBaseActivityRef?.let { safeBaseActivityRef ->
				try {
					// Configure download model
					downloadModel.videoInfo = videoInfo
					downloadModel.videoFormat = videoFormat
					if (videoInfo.videoDuration > 0L) {
						downloadModel.mediaFilePlaybackDuration =
							"(${formatVideoDuration(videoInfo.videoDuration)})"
					}

					// Set cookies and referrer
					videoInfo.videoCookie?.let { downloadModel.siteCookieString = it }
					videoInfo.videoUrlReferer?.let { downloadModel.siteReferrer = it }

					// Ensure title is set
					if (titleExtractedFromUrl.isNullOrEmpty()) {
						videoInfo.videoTitle = madeUpTitleFromSelectedVideoFormat()
						logger.d("Generated title from selected video format: ${videoInfo.videoTitle}")
					}

					// Parse and set file size
					val sizeInFormat = cleanFileSize(videoFormat.formatFileSize)
					downloadModel.fileSize = parseSize(sizeInFormat)
					if (downloadModel.fileSize > 0) {
						downloadModel.fileSizeInFormat = getHumanReadableFormat(downloadModel.fileSize)
					}

					// Check available storage space
					val freeSpace = getFreeExternalStorageSpace()
					logger.d("Free storage space: $freeSpace, required: ${downloadModel.fileSize}")
					if (freeSpace > downloadModel.fileSize) {
						logger.d("Enough space available, adding to download system")
						downloadSystem.addDownload(downloadModel, onAdded = {
							logger.i("Download added successfully")
							aioSettings.numberOfDownloadsUserDid++
							aioSettings.totalNumberOfSuccessfulDownloads++
							aioSettings.updateInStorage()

							val msgId = R.string.title_download_added_successfully
							showToast(activityInf = safeBaseActivityRef, msgId = msgId)
							if (closeActivityOnSuccessfulDownload) {
								baseActivity?.closeActivityWithFadeAnimation(true)
							}
						})
					} else {
						logger.d("Not enough storage space for download")
						executeOnMainThread {
							val icButtonCheckedCircle = R.drawable.ic_button_checked_circle
							val textWarningNotEnoughSpaceMsg = R.string.text_warning_not_enough_space_msg
							showMessageDialog(
								baseActivityInf = safeBaseActivityRef,
								isNegativeButtonVisible = false,
								messageTextViewCustomize = { it.setText(textWarningNotEnoughSpaceMsg) },
								positiveButtonTextCustomize = { it.setLeftSideDrawable(icButtonCheckedCircle) }
							)?.apply {
								setOnClickForPositiveButton {
									this@VideoResolutionPicker.close()
									this.close()
								}
							}
						}
					}
				} catch (error: Exception) {
					logger.e("Failed to add download task: ${error.message}", error)
					val failedToAddResId = R.string.title_failed_to_add_download_task
					executeOnMain {
						safeBaseActivityRef.doSomeVibration(20)
						showToast(activityInf = safeBaseActivityRef, msgId = failedToAddResId)
					}
				}
			}
		})
	}

	/**
	 * Opens the video URL in the system's default browser.
	 *
	 * Provides haptic feedback and a toast message if opening fails.
	 */
	private fun openVideoUrlInBrowser() {
		logger.d("Opening video URL in external browser: ${videoInfo.videoUrl}")
		safeBaseActivityRef?.let { safeBaseActivityRef ->
			openLinkInSystemBrowser(videoInfo.videoUrl, safeBaseActivityRef) {
				logger.d("Failed to open video URL in system browser")
				safeBaseActivityRef.doSomeVibration(40)
				showToast(
					activityInf = safeBaseActivityRef,
					msgId = R.string.title_failed_open_the_video
				)
			}
		}
	}

	/**
	 * Cleans a file size string by removing non-digit prefixes.
	 *
	 * Useful for standardizing file size display or parsing.
	 *
	 * @param input Raw file size string (e.g., "≈20MB")
	 * @return Cleaned numeric string (e.g., "20MB")
	 */
	private fun cleanFileSize(input: String): String {
		logger.d("Cleaning file size input: $input")
		return input.replace(Regex("^\\D+"), "")
	}
}