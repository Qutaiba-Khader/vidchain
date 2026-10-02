package app.ui.main.fragments.browser.webengine

import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile.fromFile
import app.core.AIOApp.Companion.IS_PREMIUM_USER
import app.core.AIOApp.Companion.IS_ULTIMATE_VERSION_UNLOCKED
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.downloadSystem
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import app.core.engines.video_parser.parsers.SupportedURLs.isFacebookUrl
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.main.MotherActivity
import com.aio.R
import lib.device.DateTimeUtils.formatVideoDuration
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.device.IntentUtility.openLinkInSystemBrowser
import lib.device.StorageUtility.getFreeExternalStorageSpace
import lib.files.FileSystemUtility.isFileNameValid
import lib.files.FileSystemUtility.sanitizeFileNameExtreme
import lib.files.FileSystemUtility.sanitizeFileNameNormal
import lib.networks.URLUtilityKT
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import lib.process.ThreadsUtility.executeOnMain
import lib.texts.CommonTextUtils.getText
import lib.texts.CommonTextUtils.removeDuplicateSlashes
import lib.texts.CommonTextUtils.removeEmptyLines
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

class RegularDownloadPrompter(
	private val motherActivity: MotherActivity,
	private val singleResolutionName: String,
	private val extractedVideoLink: String,
	private val currentWebUrl: String? = null,
	private val videoCookie: String? = null,
	private var videoTitle: String? = null,
	private val videoUrlReferer: String? = null,
	private val isFromSocialMedia: Boolean = false,
	private val dontParseFBTitle: Boolean = false,
	private val thumbnailUrlProvided: String? = null,
	private val videoFileDuration: Long = 0L
) {
	private val logger = LogHelperUtils.from(javaClass)
	private val safeMotherActivity = WeakReference(motherActivity).get()
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeMotherActivity)
	private val downloadModel = DownloadDataModel()
	private var videoThumbnailUrl: String = ""

	init {
		dialogBuilder.setView(R.layout.dialog_single_m3u8_prompter_1)
		dialogBuilder.view.apply {
			setupTitleAndThumbnail()
			setupDownloadButton()
			setupCardInfoButton()
			setUpPrivateDownloadToggle()
		}
	}

	fun show() {
		if (!dialogBuilder.isShowing) {
			dialogBuilder.show()
		}
	}

	fun close() {
		if (dialogBuilder.isShowing) {
			dialogBuilder.close()
		}
	}

	private fun showVideoTitleFromURL(layout: View) {
		val videoTitleView = layout.findViewById<TextView>(R.id.txt_video_title)
		if (!videoTitle.isNullOrEmpty()) {
			videoTitleView.isSelected = true
			videoTitleView.text = videoTitle

		} else {
			val hostName = URLUtilityKT.getHostFromUrl(currentWebUrl)
			val resolutionName = singleResolutionName
			val finalTitle = "${hostName}_${resolutionName}"
			videoTitleView.text = finalTitle
		}

		if (dontParseFBTitle) return
		if (currentWebUrl?.let { isFacebookUrl(it) } == true) {
			executeInBackground(codeBlock = {
				executeOnMainThread { animateFadInOutAnim(videoTitleView) }
				getWebpageTitleOrDescription(currentWebUrl) { resultedTitle ->
					if (!resultedTitle.isNullOrEmpty()) {
						executeOnMainThread {
							closeAnyAnimation(videoTitleView)
							videoTitleView.text = resultedTitle
							videoTitle = resultedTitle
						}
					}
				}
			})
		}
	}

	private fun showVideoResolution(layout: View) {
		safeMotherActivity?.let { safeMotherActivity ->
			val videoResView = layout.findViewById<TextView>(R.id.text_video_resolution)
			if (singleResolutionName.isNotEmpty()) {
				val resId = R.string.title_resolution_info
				videoResView.text = safeMotherActivity.getString(resId, singleResolutionName)
			} else videoResView.text = getText(R.string.title_not_available)
		}
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

	private fun showVideoThumb(layout: View) {
		if (!thumbnailUrlProvided.isNullOrEmpty()) {
			videoThumbnailUrl = thumbnailUrlProvided
			val videoThumbnail = layout.findViewById<ImageView>(R.id.image_video_thumbnail)
			loadThumbnailFromUrl(videoThumbnailUrl, videoThumbnail)
			return
		}

		executeInBackground(codeBlock = {
			val websiteUrl = videoUrlReferer ?: currentWebUrl
			if (websiteUrl.isNullOrEmpty()) return@executeInBackground
			val thumbImageUrl = startParsingVideoThumbUrl(websiteUrl)
			if (thumbImageUrl.isNullOrEmpty()) return@executeInBackground
			executeOnMain {
				videoThumbnailUrl = thumbImageUrl
				val videoThumbnail = layout.findViewById<ImageView>(R.id.image_video_thumbnail)
				loadThumbnailFromUrl(thumbImageUrl, videoThumbnail)
			}
		})
	}

	private fun View.setupTitleAndThumbnail() {
		showVideoTitleFromURL(layout = this)
		showVideoResolution(layout = this)
		showVideoThumb(layout = this)
		showFavicon(layout = this)
		showDuration(layout = this)
		updatePrivateFolderIndicator(layout = this)
	}

	private fun View.setupCardInfoButton() {
		val buttonCardInfo = findViewById<View>(R.id.btn_file_info_card)
		buttonCardInfo.setOnClickListener { openVideoUrlInBrowser() }
	}

	private fun View.setupDownloadButton() {
		val buttonDownload = findViewById<View>(R.id.btn_dialog_positive_container)
		buttonDownload.setOnClickListener { addVideoUrlToDownloadSystem() }

		val numberOfDownloadsUserDid = aioSettings.numberOfDownloadsUserDid
		val maxDownloadThreshold = aioSettings.numberOfMaxDownloadThreshold
		if (numberOfDownloadsUserDid >= maxDownloadThreshold) {
			if (!IS_PREMIUM_USER && !IS_ULTIMATE_VERSION_UNLOCKED) {
				val btnDownloadText = findViewById<TextView>(R.id.btn_dialog_positive)
				btnDownloadText.let {
					it.setLeftSideDrawable(R.drawable.ic_button_video)
					it.setText(R.string.title_watch_ad_to_download)
				}
			}
		}
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

	private fun openVideoUrlInBrowser() {
		safeMotherActivity?.let { safeMotherActivityRef ->
			if (currentWebUrl.isNullOrEmpty()) return
			openLinkInSystemBrowser(currentWebUrl, safeMotherActivityRef) {
				safeMotherActivityRef.doSomeVibration(40)
				showToast(activityInf = safeMotherActivity, msgId = R.string.title_failed_open_the_video)
			}
		}
	}

	private fun addVideoUrlToDownloadSystem() {
		addToDownloadSystem()
		close()
	}

	private fun addToDownloadSystem() {
		executeInBackground(codeBlock = {
			safeMotherActivity?.let { safeBaseActivityRef ->
				try {
					if (!isFileUrlValid()) return@executeInBackground
					else downloadModel.fileURL = extractedVideoLink

					validateDownloadFileName()
					validateDownloadDir()
					validateDownloadStartDate()
					validateOptionalConfigs()
					renameIfFileExistsWithSameName()
					checkStorageSpace().let { hasEnoughSpace ->
						if (hasEnoughSpace) {
							close()
							downloadSystem.addDownload(downloadModel) {
								executeOnMainThread {
									val toastMsgResId = R.string.title_download_added_successfully
									showToast(activityInf = safeBaseActivityRef, msgId = toastMsgResId)
								}
							}
							aioSettings.numberOfDownloadsUserDid++
							aioSettings.totalNumberOfSuccessfulDownloads++
							aioSettings.updateInStorage()
						} else {
							executeOnMainThread {
								showMessageDialog(
									baseActivityInf = safeBaseActivityRef,
									isNegativeButtonVisible = false,
									messageTextViewCustomize = {
										it.setText(R.string.text_warning_not_enough_space_msg)
									}, positiveButtonTextCustomize = {
										it.setText(R.string.title_okay)
										it.setLeftSideDrawable(R.drawable.ic_button_checked_circle)
									}
								)?.apply {
									setOnClickForPositiveButton {
										this@RegularDownloadPrompter.close()
										close()
									}
								}
							}
						}
					}
				} catch (error: Exception) {
					error.printStackTrace()
					val failedToAddResId = R.string.title_failed_to_add_download_task
					executeOnMain {
						safeBaseActivityRef.doSomeVibration(50)
						showToast(activityInf = safeBaseActivityRef, msgId = failedToAddResId)
					}
				}
			}
		})
	}

	private suspend fun isFileUrlValid(): Boolean {
		if (extractedVideoLink.isEmpty()) {
			executeOnMain {
				safeMotherActivity?.doSomeVibration(50)
				showToast(activityInf = safeMotherActivity, msgId = R.string.title_something_went_wrong)
			}; return false
		} else return true
	}

	private fun validateOptionalConfigs() {
		downloadModel.videoInfo = null
		downloadModel.videoFormat = null
		downloadModel.thumbnailUrl = videoThumbnailUrl
		downloadModel.siteCookieString = videoCookie ?: ""
		downloadModel.siteReferrer = currentWebUrl ?: ""
		if (videoFileDuration > 0L) downloadModel.mediaFilePlaybackDuration =
			formatVideoDuration(videoFileDuration)
	}

	private fun validateDownloadDir() {
		val fileCategoryName: String = downloadModel.getUpdatedCategoryName()
		val videoFileDir = fromFile(File(downloadModel.fileDirectory))
		val categoryDocumentFile = videoFileDir.createDirectory(fileCategoryName)
		if (categoryDocumentFile?.canWrite() == true) {
			downloadModel.fileCategoryName = fileCategoryName
		}
		generateDestinationFilePath()?.let { downloadModel.fileDirectory = it }
	}

	private fun validateDownloadFileName() {
		val layout = dialogBuilder.view
		val videoTitleView = layout.findViewById<TextView>(R.id.txt_video_title)
		val videoTitle = "${videoTitle ?: videoTitleView.text.toString()}.mp4"

		downloadModel.fileName = sanitizeFileNameNormal(videoTitle)
		if (!isFileNameValid(downloadModel.fileName)) {
			val sanitizeFileNameExtreme = sanitizeFileNameExtreme(downloadModel.fileName)
			downloadModel.fileName = sanitizeFileNameExtreme
		}; downloadModel.fileName = removeEmptyLines(downloadModel.fileName) ?: ""
	}

	private fun generateDestinationFilePath(): String? {
		val finalDirWithCategory = removeDuplicateSlashes(
			"${downloadModel.fileDirectory}/${
				if (downloadModel.fileCategoryName.isNotEmpty()) {
					downloadModel.fileCategoryName + "/"
				} else {
					""
				}
			}"
		)
		return finalDirWithCategory
	}

	private fun renameIfFileExistsWithSameName() {
		var index: Int
		val regex = Regex("^(\\d+)_")

		while (downloadModel.getDestinationDocumentFile().exists()) {
			val matchResult = regex.find(downloadModel.fileName)
			if (matchResult != null) {
				val currentIndex = matchResult.groupValues[1].toInt()
				downloadModel.fileName = downloadModel.fileName.replaceFirst(regex, "")
				index = currentIndex + 1
			} else {
				index = 1
			}
			downloadModel.fileName = "${index}_${downloadModel.fileName}"
		}
	}

	private fun checkStorageSpace(): Boolean {
		val freeSpace = getFreeExternalStorageSpace()
		return freeSpace > downloadModel.fileSize
	}

	private fun validateDownloadStartDate() {
		System.currentTimeMillis().apply {
			downloadModel.startTimeDate = this
			downloadModel.startTimeDateInFormat = millisToDateTimeString(this)
		}
	}
}