package app.ui.main.fragments.browser.webengine

import android.view.View
import android.widget.CheckBox
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
import app.core.engines.video_parser.parsers.SupportedURLs.isFacebookUrl
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoInfo
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.others.information.IntentInterceptActivity
import com.aio.R
import lib.device.DateTimeUtils.formatVideoDuration
import lib.device.IntentUtility.openLinkInSystemBrowser
import lib.networks.URLUtilityKT
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.process.ThreadsUtility.executeOnMain
import lib.texts.CommonTextUtils.getText
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
 * A dialog prompter that shows single resolution download options for videos.
 * Handles video metadata display, thumbnail loading, and download initiation.
 *
 * @property baseActivity The parent activity reference
 * @property isDialogCancelable Whether the dialog can be canceled by the user.
 * @property singleResolutionName The resolution name to display (e.g. "720p")
 * @property extractedVideoLink The direct video URL to download
 * @property currentWebUrl The webpage URL where video was found (optional)
 * @property videoCookie Cookie string for authenticated downloads (optional)
 * @property videoTitle Pre-extracted video title (optional)
 * @property videoUrlReferer Referer URL for the video (optional)
 * @property isSocialMediaUrl Whether the URL is from a social media platform
 * @property dontParseFBTitle Skip Facebook title parsing if true
 * @property thumbnailUrlProvided Pre-extracted thumbnail URL (optional)
 * @property isDownloadFromBrowser Whether download originated from browser
 * @property closeActivityOnSuccessfulDownload indicator whether the attached activity should be closed on
 * successful download or not.
 */
class SingleResolutionPrompter(
	private val baseActivity: BaseActivity,
	private val isDialogCancelable: Boolean = true,
	private val singleResolutionName: String,
	private val extractedVideoLink: String,
	private val currentWebUrl: String? = null,
	private val videoCookie: String? = null,
	private var videoTitle: String? = null,
	private val videoUrlReferer: String? = null,
	private val isSocialMediaUrl: Boolean = false,
	private val dontParseFBTitle: Boolean = false,
	private val thumbnailUrlProvided: String? = null,
	private val isDownloadFromBrowser: Boolean = false,
	private val closeActivityOnSuccessfulDownload: Boolean = false,
	private val videoFileDuration: Long = 0L
) {
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to prevent memory leaks
	private val safeBaseActivity = WeakReference(baseActivity).get()

	// Dialog builder for the resolution prompt
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivity)

	// Model to store download information
	private val downloadModel = DownloadDataModel()

	// Cache for video thumbnail URL
	private var videoThumbnailUrl: String = ""

	init {
		// Initialize dialog view and setup components
		dialogBuilder.setView(R.layout.dialog_single_m3u8_prompter_1)
		dialogBuilder.setCancelable(isDialogCancelable)
		dialogBuilder.dialog.setOnCancelListener { safelyCloseIfInterceptActivity() }
		dialogBuilder.dialog.setOnDismissListener { safelyCloseIfInterceptActivity() }

		dialogBuilder.view.apply {
			setupTitleAndThumbnail()
			setupDownloadButton()
			setupCardInfoButton()
			setUpPrivateDownloadToggle()
		}
	}

	/**
	 * Shows the resolution prompt dialog if not already showing
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			dialogBuilder.show()
		}
	}

	/**
	 * Closes the resolution prompt dialog if showing
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			dialogBuilder.close()
		}
	}

	/**
	 * Gets the dialog builder instance
	 * @return DialogBuilder instance
	 */
	fun getDialogBuilder(): DialogBuilder {
		return dialogBuilder
	}

	/**
	 * Displays and fetches video title:
	 * - Uses provided title if available
	 * - Falls back to hostname + resolution
	 * - Fetches Facebook title asynchronously if needed
	 * @param layout The parent view containing title TextView
	 */
	private fun showVideoTitleFromURL(layout: View) {
		val videoTitleView = layout.findViewById<TextView>(R.id.txt_video_title)

		// Use provided title if available
		if (!videoTitle.isNullOrEmpty()) {
			videoTitleView.isSelected = true
			videoTitleView.text = videoTitle

		} else {
			// Fallback title format: "hostname_resolution"
			val hostName = URLUtilityKT.getHostFromUrl(currentWebUrl)
			val resolutionName = singleResolutionName
			val finalTitle = "${hostName}_${resolutionName}"
			videoTitleView.text = finalTitle
		}

		// Skip Facebook title parsing if requested
		if (dontParseFBTitle) return

		// Special handling for Facebook URLs
		if (currentWebUrl?.let { isFacebookUrl(it) } == true) {
			executeInBackground(codeBlock = {
				// Show loading animation while fetching
				executeOnMainThread { animateFadInOutAnim(videoTitleView) }

				// Fetch webpage title asynchronously
				getWebpageTitleOrDescription(currentWebUrl) { resultedTitle ->
					if (!resultedTitle.isNullOrEmpty()) {
						executeOnMainThread {
							closeAnyAnimation(videoTitleView)
							videoTitleView.text = resultedTitle
							videoTitle = resultedTitle // Cache the fetched title
						}
					}
				}
			})
		}
	}

	/**
	 * Displays video resolution information
	 * @param layout The parent view containing resolution TextView
	 */
	private fun showVideoResolution(layout: View) {
		safeBaseActivity?.let { safeMotherActivity ->
			val videoResView = layout.findViewById<TextView>(R.id.text_video_resolution)
			if (singleResolutionName.isNotEmpty()) {
				val resId = R.string.title_resolution_info
				videoResView.text = safeMotherActivity.getString(resId, singleResolutionName)
			} else videoResView.text = getText(R.string.title_not_available)
		}
	}

	/**
	 * Loads and displays video thumbnail:
	 * - Uses provided thumbnail if available
	 * - Fetches thumbnail from URL if needed
	 * @param layout The parent view containing thumbnail ImageView
	 */
	private fun showVideoThumb(layout: View) {
		// Use provided thumbnail if available
		if (!thumbnailUrlProvided.isNullOrEmpty()) {
			videoThumbnailUrl = thumbnailUrlProvided
			val videoThumbnail = layout.findViewById<ImageView>(R.id.image_video_thumbnail)
			loadThumbnailFromUrl(videoThumbnailUrl, videoThumbnail)
			return
		}

		// Fetch thumbnail asynchronously
		executeInBackground(codeBlock = {
			val websiteUrl = videoUrlReferer ?: currentWebUrl
			if (websiteUrl.isNullOrEmpty()) return@executeInBackground

			// Parse thumbnail URL from webpage
			val thumbImageUrl = startParsingVideoThumbUrl(websiteUrl)
			if (thumbImageUrl.isNullOrEmpty()) return@executeInBackground

			// Load thumbnail on UI thread
			executeOnMain {
				videoThumbnailUrl = thumbImageUrl
				val videoThumbnail = layout.findViewById<ImageView>(R.id.image_video_thumbnail)
				loadThumbnailFromUrl(thumbImageUrl, videoThumbnail)
			}
		})
	}

	/**
	 * Loads and displays the site favicon inside the given layout.
	 *
	 * @param layout The parent [View] containing an ImageView with ID [R.id.img_site_favicon].
	 */
	private fun showFavicon(layout: View) {
		layout.findViewById<ImageView>(R.id.img_site_favicon).let { favicon ->
			val defaultFaviconResId = R.drawable.ic_button_information
			logger.i("Attempting to update site favicon for URL: $videoUrlReferer")

			executeInBackground(codeBlock = {
				val referralSite = currentWebUrl ?: videoUrlReferer ?: extractedVideoLink
				logger.i("Fetching favicon for site: $referralSite")

				aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
					val faviconImgFile = File(faviconFilePath)

					if (!faviconImgFile.exists() || !faviconImgFile.isFile) {
						logger.e("Favicon file not found or invalid at: $faviconFilePath")
						return@executeInBackground
					}

					val faviconImgURI = faviconImgFile.toUri()
					executeOnMain(codeBlock = {
						try {
							logger.i("Applying favicon from local file")
							showView(favicon, true)
							favicon.setImageURI(faviconImgURI)
						} catch (error: Exception) {
							logger.e("Failed to set favicon: ${error.message}", error)
							showView(favicon, true)
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
		val privateFolderCheckbox = layout.findViewById<CheckBox>(R.id.checkbox_download_at_private)
		val privateFolderImageView = layout.findViewById<ImageView>(R.id.img_private_folder_indicator)

		// Get ImageView for private folder indicator
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
		val privateFolderCheckbox = layout.findViewById<CheckBox>(R.id.checkbox_download_at_private)

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
		if (videoFileDuration == 0L) return

		// Show the media duration container with animation
		showView(layout.findViewById(R.id.container_media_duration), shouldAnimate = true)

		// Update the duration text
		layout.findViewById<TextView>(R.id.txt_media_duration).let {
			it.text = formatVideoDuration(videoFileDuration)
			logger.d("Displayed video duration: ${it.text}")
		}
	}

	/**
	 * Sets up title, resolution and thumbnail views
	 * @receiver The dialog content view
	 */
	private fun View.setupTitleAndThumbnail() {
		showVideoTitleFromURL(layout = this)
		showVideoResolution(layout = this)
		showVideoThumb(layout = this)
		showFavicon(layout = this)
		showDuration(layout = this)
		updatePrivateFolderIndicator(layout = this)
	}

	/**
	 * Configures the "Info" button in the dialog content view.
	 * When clicked, it opens the associated video URL in the default browser.
	 *
	 * @receiver The dialog's content [View] containing the info button.
	 */
	private fun View.setupCardInfoButton() {
		val buttonCardInfo = findViewById<View>(R.id.btn_file_info_card)
		buttonCardInfo.setOnClickListener { openVideoUrlInBrowser() }
	}

	/**
	 * Sets up the toggle button for enabling/disabling downloads to a private folder.
	 * Clicking the button switches the download mode and may update the UI accordingly.
	 *
	 * @receiver The dialog's content [View] that contains the private download toggle button.
	 */
	private fun View.setUpPrivateDownloadToggle() {
		val buttonPrivateFolderToggle = findViewById<View>(R.id.checkbox_download_at_private)
		buttonPrivateFolderToggle.setOnClickListener { togglePrivateFolderDownload(layout = this) }
	}

	/**
	 * Sets up download button with appropriate state:
	 * - Shows "Watch Ad to Download" after download threshold
	 * - Handles premium user state
	 * @receiver The dialog content view
	 */
	private fun View.setupDownloadButton() {
		val buttonDownload = findViewById<View>(R.id.btn_dialog_positive_container)
		buttonDownload.setOnClickListener { addVideoFormatToDownloadSystem() }

		// Check if user exceeded free download limit
		val numberOfDownloadsUserDid = aioSettings.numberOfDownloadsUserDid
		val maxDownloadThreshold = aioSettings.numberOfMaxDownloadThreshold
		if (numberOfDownloadsUserDid >= maxDownloadThreshold) {
			if (!IS_PREMIUM_USER && !IS_ULTIMATE_VERSION_UNLOCKED) {
				// Show "Watch Ad to Download" for free users over limit
				val btnDownloadText = findViewById<TextView>(R.id.btn_dialog_positive)
				btnDownloadText.let {
					it.setLeftSideDrawable(R.drawable.ic_button_video)
					it.setText(R.string.title_watch_ad_to_download)
				}
			}
		}
	}

	private fun openVideoUrlInBrowser() {
		safeBaseActivity?.let { safeMotherActivityRef ->
			if (currentWebUrl.isNullOrEmpty()) return
			openLinkInSystemBrowser(currentWebUrl, safeMotherActivityRef) {
				// Handle browser open failure
				safeMotherActivityRef.doSomeVibration(40)
				showToast(activityInf = safeMotherActivityRef, msgId = R.string.title_failed_open_the_video)
			}
		}
	}

	private fun addVideoFormatToDownloadSystem() {
		addToDownloadSystem()
	}

	private fun addToDownloadSystem() {
		executeInBackground(codeBlock = {
			safeBaseActivity?.let { safeBaseActivityRef ->
				try {
					// Validate required URL
					if (extractedVideoLink.isEmpty()) {
						executeOnMain {
							safeBaseActivityRef.doSomeVibration(50)
							showToast(activityInf = safeBaseActivityRef, msgId = R.string.title_something_went_wrong)
						}; return@executeInBackground
					}

					val videoCookie = videoCookie
					val videoTitle = videoTitle
					val videoThumbnailUrl = videoThumbnailUrl
					val videoUrlReferer = videoUrlReferer
					val videoThumbnailByReferer = true
					val videoFormats = arrayListOf(
						VideoFormat(
							formatId = safeBaseActivityRef.packageName,
							isFromSocialMedia = isSocialMediaUrl,
							formatResolution = singleResolutionName
						)
					)

					// Prepare video info
					val videoInfo = VideoInfo(
						videoUrl = extractedVideoLink,
						videoTitle = videoTitle,
						videoThumbnailUrl = videoThumbnailUrl,
						videoUrlReferer = videoUrlReferer,
						videoThumbnailByReferer = videoThumbnailByReferer,
						videoCookie = videoCookie,
						videoFormats = videoFormats,
						videoDuration = videoFileDuration
					)

					// Configure download model
					downloadModel.videoInfo = videoInfo
					downloadModel.videoFormat = videoFormats[0]
					downloadModel.isDownloadFromBrowser = isDownloadFromBrowser
					if (videoFileDuration > 0L) downloadModel.mediaFilePlaybackDuration =
						formatVideoDuration(videoFileDuration)

					if (videoUrlReferer != null) downloadModel.siteReferrer = videoUrlReferer

					val urlCookie = videoInfo.videoCookie
					if (!urlCookie.isNullOrEmpty()) downloadModel.siteCookieString = urlCookie

					// Add download to system
					downloadSystem.addDownload(downloadModel) {
						executeOnMainThread {
							// Update download counters
							aioSettings.numberOfDownloadsUserDid++
							aioSettings.totalNumberOfSuccessfulDownloads++
							aioSettings.updateInStorage()

							// Show user toast
							val toastMsgResId = R.string.title_download_added_successfully
							showToast(activityInf = safeBaseActivityRef, msgId = toastMsgResId)

							// Close the dialog
							close()

							if (closeActivityOnSuccessfulDownload) {
								baseActivity.closeActivityWithFadeAnimation(true)
							}
						}
					}
				} catch (error: Exception) {
					error.printStackTrace()
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
	 * Safely close the base activity only if activity is of IntentInterceptActivity.
	 */
	private fun safelyCloseIfInterceptActivity() {
		logger.d("Safely closing base activity")
		if (safeBaseActivity == null) return
		executeOnMainThread {
			try {
				// Special handling for IntentInterceptActivity
				val condition = safeBaseActivity is IntentInterceptActivity
				if (condition) safeBaseActivity.finish()
			} catch (error: Exception) {
				logger.e("Error closing activity: ${error.message}", error)
			}
		}
	}
}