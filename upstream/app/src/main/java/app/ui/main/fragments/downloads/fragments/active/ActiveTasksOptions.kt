package app.ui.main.fragments.downloads.fragments.active

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat.getDrawable
import androidx.core.net.toUri
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.downloadSystem
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.core.engines.downloader.DownloadStatus.DOWNLOADING
import app.core.engines.downloader.DownloadTaskInf
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.video_parser.dialogs.VideoLinkPasteEditor
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.dialogs.DownloadFileRenamer
import app.ui.main.fragments.downloads.dialogs.DownloadInfoTracker
import com.aio.R
import com.aio.R.layout
import com.aio.R.string
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.device.ShareUtility.shareUrl
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideo
import lib.files.FileSystemUtility.isVideoByName
import lib.networks.URLUtility.isValidURL
import lib.process.LogHelperUtils
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.normalizeTallSymbols
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.setRightSideDrawable
import lib.ui.ViewUtility.showView
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference

class ActiveTasksOptions(private val motherActivity: MotherActivity?) {

	private val logger = LogHelperUtils.from(javaClass)
	private val activityWeakRef = motherActivity?.let { WeakReference(it) }

	private var dialogBuilder: DialogBuilder? = DialogBuilder(getSafeActivity())
	private var downloadDataModel: DownloadDataModel? = null

	private fun getSafeActivity(): MotherActivity? = activityWeakRef?.get()

	fun show(downloadModel: DownloadDataModel?) {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		this@ActiveTasksOptions.downloadDataModel = dataModel
		dialogBuilder.setView(layout.frag_down_3_active_1_onclick_1)
		setupDialogClickListeners()
		if (dialogBuilder.isShowing == false) {
			dialogBuilder.show()
			updateDialogFileInfo()
			dialogBuilder.dialog.setOnCancelListener { this@ActiveTasksOptions.close() }
			dialogBuilder.dialog.setOnDismissListener { this@ActiveTasksOptions.close() }
		}
	}

	fun close() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()

		if (dialogBuilder == null) return
		if (activityRef == null) return

		if (dialogBuilder.isShowing) dialogBuilder.close()
		clearReferences()
	}

	private fun clearReferences() {
		dialogBuilder = null
		downloadDataModel = null
		activityWeakRef?.clear()
	}

	private fun updateDialogFileInfo() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		val dialogLayout = dialogBuilder.view
		dialogLayout.apply {
			updateFileTitle(dataModel)
			updateFileUrl(dataModel)
			updateThumbnail(dataModel)
			updateFileTypeIndicator(dataModel)
			updatePrivateFolderIndicator(dataModel)
			updateUrlFavicon(dataModel)
			updateMediaPlayIndicator(dataModel)
			updateMediaDuration(dataModel)
			updateDownloadSettingsUI()
		}
	}

	private fun View.updateMediaDuration(dataModel: DownloadDataModel) {
		val mediaDurContainer = findViewById<View>(R.id.container_media_duration)
		if (isMediaFile(dataModel)) {
			mediaDurContainer.apply {
				val durationTextView = findViewById<TextView>(R.id.txt_media_duration)
				val durationString = dataModel.mediaFilePlaybackDuration
				val formattedDuration = durationString.replace("(", "").replace(")", "")
				if (formattedDuration.isNotEmpty()) {
					showView(this, true)
					showView(durationTextView, true)
					durationTextView.text = formattedDuration
				}
			}
		} else {
			mediaDurContainer.visibility = View.GONE
		}
	}

	private fun View.updateMediaPlayIndicator(dataModel: DownloadDataModel) {
		val playIndicatorIcon = findViewById<ImageView>(R.id.img_media_play_indicator)
		playIndicatorIcon.apply { updatePlayIconVisibility(this, dataModel) }
	}

	private fun updatePlayIconVisibility(imageView: ImageView, dataModel: DownloadDataModel) {
		imageView.visibility = (if (isMediaFile(dataModel)) View.VISIBLE else View.GONE)
	}

	private fun View.updateUrlFavicon(dataModel: DownloadDataModel) {
		val siteFavicon = findViewById<ImageView>(R.id.img_site_favicon)
		siteFavicon.apply { updateFaviconInfo(this, dataModel) }
	}

	private fun updateFaviconInfo(favicon: ImageView, downloadDataModel: DownloadDataModel) {
		getSafeActivity()?.getAttachedCoroutineScope()?.launch(IO) {
			val defaultFaviconResId = R.drawable.ic_image_default_favicon
			val defaultFaviconDrawable = getDrawable(INSTANCE.resources, defaultFaviconResId, null)

			if (isVideoThumbnailNotAllowed(downloadDataModel)) {
				withContext(Main) { favicon.setImageDrawable(defaultFaviconDrawable) }
				return@launch
			}

			try {
				val referralSite = downloadDataModel.siteReferrer
				aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
					val faviconImgFile = File(faviconFilePath)
					if (!faviconImgFile.exists() || !faviconImgFile.isFile) {
						return@launch
					}
					val faviconImgURI = faviconImgFile.toUri()
					val signature = ObjectKey(File(faviconFilePath).lastModified())

					withContext(Main) {
						try {
							showView(favicon, true)
							Glide.with(favicon)
								.load(faviconImgURI)
								.placeholder(defaultFaviconDrawable)
								.signature(signature)
								.into(favicon)
						} catch (error: Exception) {
							error.printStackTrace()
							showView(favicon, true)
							favicon.setImageResource(defaultFaviconResId)
						}
					}
				}
			} catch (error: Exception) {
				withContext(Main) {
					favicon.setImageDrawable(defaultFaviconDrawable)
				}
			}
		}
	}

	private fun View.updatePrivateFolderIndicator(dataModel: DownloadDataModel) {
		val folderIndicatorView = findViewById<ImageView>(R.id.img_private_folder_indicator)
		folderIndicatorView.apply { setDownloadDestinationIcon(this, dataModel) }
	}

	private fun setDownloadDestinationIcon(imageView: ImageView, dataModel: DownloadDataModel) {
		val globalSettings = dataModel.globalSettings
		val downloadLocation = globalSettings.defaultDownloadLocation

		imageView.setImageResource(
			when (downloadLocation) {
				PRIVATE_FOLDER -> R.drawable.ic_button_lock
				else -> R.drawable.ic_button_folder
			}
		)
	}

	private fun View.updateFileTypeIndicator(dataModel: DownloadDataModel) {
		val fileTypeIcon = findViewById<ImageView>(R.id.img_file_type_indicator)
		fileTypeIcon.apply { setFileTypeIcon(this, dataModel) }
	}

	private fun setFileTypeIcon(imageView: ImageView, dataModel: DownloadDataModel) {
		imageView.setImageResource(
			when {
				isImageByName(dataModel.fileName) -> R.drawable.ic_button_images
				isAudioByName(dataModel.fileName) -> R.drawable.ic_button_audio
				isVideoByName(dataModel.fileName) -> R.drawable.ic_button_video
				isDocumentByName(dataModel.fileName) -> R.drawable.ic_button_document
				isArchiveByName(dataModel.fileName) -> R.drawable.ic_button_archives
				isProgramByName(dataModel.fileName) -> R.drawable.ic_button_programs
				else -> R.drawable.ic_button_file
			}
		)
	}

	private fun View.updateThumbnail(dataModel: DownloadDataModel) {
		val thumbnailImageView = findViewById<ImageView>(R.id.img_file_thumbnail)
		thumbnailImageView.apply { loadThumbnail(this, dataModel) }
	}

	private fun View.updateFileUrl(dataModel: DownloadDataModel) {
		val fileUrlLabel = findViewById<TextView>(R.id.txt_file_url)
		fileUrlLabel.apply { text = dataModel.fileURL }
	}

	private fun View.updateFileTitle(dataModel: DownloadDataModel) {
		val fileTitleLabel = findViewById<TextView>(R.id.txt_file_title)
		fileTitleLabel.apply { isSelected = true; text = dataModel.fileName }
		fileTitleLabel.normalizeTallSymbols()
	}

	private fun isMediaFile(downloadModel: DownloadDataModel): Boolean =
		isAudioByName(downloadModel.fileName) || isVideoByName(downloadModel.fileName)

	private fun isVideoThumbnailNotAllowed(downloadDataModel: DownloadDataModel): Boolean {
		val isVideoHidden = downloadDataModel.globalSettings.downloadHideVideoThumbnail
		return isVideo(downloadDataModel.getDestinationDocumentFile()) && isVideoHidden
	}

	private fun loadThumbnail(imageView: ImageView, dataModel: DownloadDataModel) {
		getSafeActivity()?.getAttachedCoroutineScope()?.launch(IO) {
			val destinationFile = dataModel.getDestinationFile()
			val defaultThumb = dataModel.getThumbnailDrawableID()
			val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)

			if (dataModel.globalSettings.downloadHideVideoThumbnail) {
				imageView.setImageResource(defaultThumb)
				return@launch
			}

			var shouldReturn = false
			withContext(Main) {
				if (loadApkThumbnail(dataModel, imageView, defaultThumbDrawable)) {
					shouldReturn = true
				}
			}

			if (shouldReturn) return@launch

			val cachedThumbPath = dataModel.thumbPath
			if (cachedThumbPath.isNotEmpty()) {
				withContext(Main) {
					loadBitmapWithGlide(
						imageView = imageView,
						filePath = dataModel.thumbPath,
						defaultThumb = defaultThumb
					)
				}
				return@launch
			}

			val thumbnailUrl = dataModel.videoInfo?.videoThumbnailUrl
			val bitmap = getThumbnailFromFile(destinationFile, thumbnailUrl, 420)

			if (bitmap != null) {
				val isPortrait = bitmap.height > bitmap.width
				val rotatedBitmap = if (isPortrait) {
					rotateBitmap(bitmap = bitmap, angle = 270f)
				} else bitmap

				val thumbnailName = "${dataModel.downloadId}$THUMB_EXTENSION"
				saveBitmapToFile(rotatedBitmap, thumbnailName)?.let { filePath ->
					dataModel.thumbPath = filePath
					dataModel.updateInStorage()
					withContext(Main) {
						loadBitmapWithGlide(
							imageView = imageView,
							filePath = dataModel.thumbPath,
							defaultThumb = defaultThumb
						)
					}
				}
			}
		}
	}

	private fun loadBitmapWithGlide(imageView: ImageView, filePath: String, defaultThumb: Int) {
		try {
			val imageFile = File(filePath)
			val signature = ObjectKey(imageFile.lastModified())
			Glide.with(imageView)
				.load(imageFile)
				.placeholder(defaultThumb)
				.signature(signature)
				.into(imageView)
		} catch (error: Exception) {
			imageView.setImageResource(defaultThumb)
		}
	}

	private fun loadApkThumbnail(dataModel: DownloadDataModel,
		imageView: ImageView, fallbackDrawable: Drawable?): Boolean {
		getSafeActivity()?.let { activityRef ->
			val apkFile = dataModel.getDestinationFile()

			if (!apkFile.exists() || !apkFile.name.lowercase().endsWith(".apk")) {
				imageView.setImageDrawable(fallbackDrawable)
				return false
			}

			val packageManager: PackageManager = activityRef.packageManager
			return try {
				val packageInfo: PackageInfo? = packageManager.getPackageArchiveInfo(
					apkFile.absolutePath,
					PackageManager.GET_ACTIVITIES
				)

				packageInfo?.applicationInfo?.let { appInfo ->
					appInfo.sourceDir = apkFile.absolutePath
					appInfo.publicSourceDir = apkFile.absolutePath

					val icon: Drawable = appInfo.loadIcon(packageManager)
					imageView.setImageDrawable(icon)
					true
				} ?: run {
					false
				}
			} catch (error: Exception) {
				imageView.apply {
					scaleType = ImageView.ScaleType.FIT_CENTER
					setPadding(0, 0, 0, 0)
					setImageDrawable(fallbackDrawable)
				}
				false
			}
		}
		return false
	}

	private fun resumeDownloadTask() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		this@ActiveTasksOptions.close()
		activityRef.getAttachedCoroutineScope().launch(Main) {
			if (searchActiveTaskByDownloadModel(dataModel) != null) {
				pauseDownloadTask()
				delay(1000)
			}

			val isDownloadProblematic = dataModel.isYtdlpHavingProblem &&
				dataModel.ytdlpProblemMsg.isNotEmpty() &&
				dataModel.status != DOWNLOADING

			val requiresLogin = dataModel.ytdlpProblemMsg.contains("login", true)

			if (!isDownloadProblematic && !requiresLogin) {
				downloadSystem.resumeDownload(
					downloadModel = dataModel,
					coroutineScope = CoroutineScope(IO),
					onResumed = {
						val toastMsgId = string.title_resumed_task_successfully
						showToast(activityRef, toastMsgId)
					}
				)
				return@launch
			}

			showMessageDialog(
				baseActivityInf = activityRef,
				isNegativeButtonVisible = false,
				titleTextViewCustomize = {
					it.setText(string.title_login_required)
					activityRef.getColor(R.color.color_error).let { colorResId ->
						it.setTextColor(colorResId)
					}
				},
				messageTextViewCustomize = {
					val loginPromptResId = string.text_login_to_download_private_videos
					it.setText(loginPromptResId)
				},
				positiveButtonTextCustomize = {
					it.setText(string.title_login_now)
					it.setLeftSideDrawable(R.drawable.ic_button_login)
				}
			)?.apply {
				setOnClickForPositiveButton {
					this.close()
					val browserFragment = activityRef.browserFragment
					val webviewEngine = browserFragment?.getBrowserWebEngine()
						?: return@setOnClickForPositiveButton

					val sideNavigation = activityRef.sideNavigation
					sideNavigation?.addNewBrowsingTab(dataModel.siteReferrer, webviewEngine)
					activityRef.openBrowserFragment()

					dataModel.isYtdlpHavingProblem = false
					dataModel.ytdlpProblemMsg = ""
				}
			}?.show()
		}
	}

	private fun pauseDownloadTask() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		this@ActiveTasksOptions.close()
		activityRef.getAttachedCoroutineScope().launch(Main) {
			if (searchActiveTaskByDownloadModel(dataModel) == null) {
				val msgId = string.title_download_task_already_paused
				showToast(activityRef, msgId)
				return@launch
			}

			if (!dataModel.isResumeSupported) {
				getMessageDialog(
					baseActivityInf = activityRef,
					isNegativeButtonVisible = false,
					messageTextViewCustomize = {
						val warningTextResId = string.text_warning_resume_not_supported
						it.setText(warningTextResId)
					},
					negativeButtonTextCustomize = {
						val drawableResId = R.drawable.ic_button_cancel
						it.setLeftSideDrawable(drawableResId)
					},
					positiveButtonTextCustomize = {
						it.setText(string.title_pause_anyway)
						it.setLeftSideDrawable(R.drawable.ic_button_media_pause)
					}
				)?.apply {
					setOnClickForPositiveButton {
						this.close()
						this@ActiveTasksOptions.close()
						downloadSystem.pauseDownload(dataModel)
					}
				}?.show()
			} else {
				downloadSystem.pauseDownload(dataModel)
			}
		}
	}

	private fun removeDownloadTask() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		activityRef.getAttachedCoroutineScope().launch(Main) {
			if (searchActiveTaskByDownloadModel(dataModel) != null) {
				pauseDownloadTask()
				delay(1500)
			}

			if (searchActiveTaskByDownloadModel(dataModel) != null) {
				showMessageDialog(
					baseActivityInf = activityRef,
					isNegativeButtonVisible = false,
					messageTextViewCustomize = { it.setText(string.text_cant_remove_one_active_download) },
					positiveButtonTextCustomize = { it.setLeftSideDrawable(R.drawable.ic_button_checked_circle) }
				)
				return@launch
			}

			getMessageDialog(
				baseActivityInf = activityRef,
				isTitleVisible = true,
				isNegativeButtonVisible = false,
				titleTextViewCustomize = { it.setText(string.title_are_you_sure_about_this) },
				messageTextViewCustomize = { it.setText(string.text_are_you_sure_about_clear) },
				positiveButtonTextCustomize = {
					it.setText(string.title_clear_from_list)
					it.setLeftSideDrawable(R.drawable.ic_button_clear)
				}
			)?.apply {
				setOnClickForPositiveButton {
					this.close()
					this@ActiveTasksOptions.close()
					dataModel.let {
						downloadSystem.clearDownload(it) {
							showToast(activityRef, string.title_successfully_cleared)
						}
					}
				}
			}?.show()
		}
	}

	private suspend fun searchActiveTaskByDownloadModel(dataModel: DownloadDataModel): DownloadTaskInf? {
		return withContext(IO) { downloadSystem.searchActiveDownloadTaskWith(dataModel) }
	}

	private fun deleteDownloadTask() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		activityRef.getAttachedCoroutineScope().launch(Main) {
			if (downloadSystem.searchActiveDownloadTaskWith(dataModel) != null) {
				pauseDownloadTask()
				delay(1500)
			}

			if (downloadSystem.searchActiveDownloadTaskWith(dataModel) != null) {
				showMessageDialog(
					baseActivityInf = activityRef,
					isNegativeButtonVisible = false,
					messageTextViewCustomize = { it.setText(string.text_cant_delete_on_active_download) },
					positiveButtonTextCustomize = { it.setLeftSideDrawable(R.drawable.ic_button_checked_circle) }
				)
				return@launch
			}
		}

		getMessageDialog(
			baseActivityInf = activityRef,
			isTitleVisible = true,
			isNegativeButtonVisible = false,
			titleTextViewCustomize = { it.setText(string.title_are_you_sure_about_this) },
			messageTextViewCustomize = { it.setText(string.text_are_you_sure_about_delete) },
			positiveButtonTextCustomize = {
				it.setText(string.title_delete_file)
				it.setLeftSideDrawable(R.drawable.ic_button_checked_circle)
			}
		)?.apply {
			setOnClickForPositiveButton {
				this.close()
				this@ActiveTasksOptions.close()

				dataModel.let {
					downloadSystem.deleteDownload(it) {
						showToast(activityRef, string.title_successfully_deleted)
					}
				}
			}
		}?.show()
	}

	private fun renameDownloadTask() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		activityRef.getAttachedCoroutineScope().launch(Main) {
			if (downloadSystem.searchActiveDownloadTaskWith(dataModel) != null) {
				showMessageDialog(
					baseActivityInf = activityRef,
					isNegativeButtonVisible = false,
					messageTextViewCustomize = { it.setText(string.text_cant_rename_on_active_download) },
					positiveButtonTextCustomize = { it.setLeftSideDrawable(R.drawable.ic_button_checked_circle) }
				)
				return@launch
			}
			val downloadFileRenamer = DownloadFileRenamer(activityRef, dataModel) { this@ActiveTasksOptions.close() }
			downloadFileRenamer.show(dataModel)
		}
	}

	private fun toggleDownloadThumbnail() {
		val dataModel = downloadDataModel
		val activityRef = getSafeActivity()

		if (activityRef == null) return
		if (dataModel == null) return

		val settings = dataModel.globalSettings
		val shouldHideVideoThumbnail = settings.downloadHideVideoThumbnail
		settings.downloadHideVideoThumbnail = !shouldHideVideoThumbnail
		dataModel.updateInStorage()
		downloadSystem.downloadsUIManager.updateActiveUI(dataModel)
		updateDownloadSettingsUI()
		dialogBuilder?.view?.apply {
			updateFileTitle(dataModel)
			updateFileUrl(dataModel)
			updateThumbnail(dataModel)
			updateFileTypeIndicator(dataModel)
			updatePrivateFolderIndicator(dataModel)
			updateUrlFavicon(dataModel)
			updateMediaPlayIndicator(dataModel)
			updateMediaDuration(dataModel)
		}
	}

	private fun copyDownloadFileLink() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		dataModel.fileURL.takeIf { isValidURL(it) }?.let { fileUrl ->
			copyTextToClipboard(activityRef, fileUrl)
			showToast(activityRef, string.title_file_url_has_been_copied)
			close()
		} ?: run {
			showToast(activityRef, string.title_dont_have_anything_to_copy)
		}
	}

	private fun copyWebsiteLink() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		dataModel.siteReferrer.takeIf { isValidURL(it) }?.let { fileUrl ->
			copyTextToClipboard(activityRef, fileUrl)
			showToast(activityRef, string.title_file_url_has_been_copied)
			close()
		} ?: run {
			showToast(activityRef, string.title_dont_have_anything_to_copy)
		}
	}

	private fun shareDownloadFileLink() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		dataModel.fileURL.takeIf { isValidURL(it) }?.let { fileUrl ->
			val titleText = getText(string.title_share_download_file_url)
			shareUrl(activityRef, fileUrl, titleText) { close() }
		} ?: run {
			showToast(activityRef, string.title_dont_have_anything_to_share)
		}
	}

	private fun openDownloadReferrerLink() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		val downloadSiteReferrerLink = dataModel.siteReferrer
		if (downloadSiteReferrerLink.isEmpty()) {
			activityRef.doSomeVibration()
			showToast(activityRef, string.title_no_referer_link_found)
			return
		}

		this@ActiveTasksOptions.close()
		val webviewEngine = activityRef.browserFragment?.browserFragmentBody?.webviewEngine!!
		activityRef.sideNavigation?.addNewBrowsingTab(downloadSiteReferrerLink, webviewEngine)
		activityRef.openBrowserFragment()
	}

	private fun openDownloadInfoTracker() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		DownloadInfoTracker(activityRef).show(dataModel)
		this@ActiveTasksOptions.close()
	}

	private fun downloadOtherYTResolutions() {
		val dataModel = downloadDataModel
		val activityRef = getSafeActivity()

		if (activityRef == null) return
		if (dataModel == null) return

		close()
		val fileUrl = dataModel.fileURL.ifEmpty { dataModel.siteReferrer }
		if (fileUrl.isEmpty()) return
		VideoLinkPasteEditor(
			motherActivity = activityRef,
			passOnUrl = fileUrl,
			autoStart = true
		).show()
	}

	private fun toggleWifiOnlyDownload() {
		val dataModel = downloadDataModel
		val activityRef = getSafeActivity()

		if (activityRef == null) return
		if (dataModel == null) return

		val settings = dataModel.globalSettings
		val isDownloadWifiOnly = settings.downloadWifiOnly
		settings.downloadWifiOnly = !isDownloadWifiOnly
		dataModel.updateInStorage()
		updateDownloadSettingsUI()
	}

	private fun toggleDownloadNotification() {
		val dataModel = downloadDataModel
		val activityRef = getSafeActivity()

		if (activityRef == null) return
		if (dataModel == null) return

		val settings = dataModel.globalSettings
		val isDownloadWifiOnly = settings.downloadHideNotification
		settings.downloadHideNotification = !isDownloadWifiOnly
		dataModel.updateInStorage()
		updateDownloadSettingsUI()
	}

	private fun toggleDownloadSound() {
		val dataModel = downloadDataModel
		val activityRef = getSafeActivity()

		if (activityRef == null) return
		if (dataModel == null) return

		val settings = dataModel.globalSettings
		val isDownloadWifiOnly = settings.downloadPlayNotificationSound
		settings.downloadPlayNotificationSound = !isDownloadWifiOnly
		dataModel.updateInStorage()
		updateDownloadSettingsUI()
	}

	private fun updateDownloadSettingsUI() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		val settings = dataModel.globalSettings
		dialogBuilder.view.findViewById<TextView>(R.id.txt_wifi_only_download).apply {
			val drawableResIdRes = if (settings.downloadWifiOnly) {
				R.drawable.ic_button_checked_circle_small
			} else R.drawable.ic_button_unchecked_circle_small
			setRightSideDrawable(drawableResIdRes, true)
		}

		dialogBuilder.view.findViewById<TextView>(R.id.txt_download_notification).apply {
			val drawableResIdRes = if (settings.downloadHideNotification) {
				R.drawable.ic_button_unchecked_circle_small
			} else R.drawable.ic_button_checked_circle_small
			setRightSideDrawable(drawableResIdRes, true)
		}

		dialogBuilder.view.findViewById<TextView>(R.id.txt_play_download_sound).apply {
			val drawableResIdRes = if (settings.downloadPlayNotificationSound) {
				R.drawable.ic_button_checked_circle_small
			} else R.drawable.ic_button_unchecked_circle_small
			setRightSideDrawable(drawableResIdRes, true)
		}

		dialogBuilder.view.findViewById<TextView>(R.id.txt_remove_thumbnail).apply {
			val drawableResIdRes = if (settings.downloadHideVideoThumbnail) {
				R.drawable.ic_button_checked_circle_small
			} else R.drawable.ic_button_unchecked_circle_small
			setRightSideDrawable(drawableResIdRes, true)
		}
	}

	private fun setupDialogClickListeners() {
		val dialogBuilder = dialogBuilder
		val activityRef = getSafeActivity()
		val dataModel = downloadDataModel

		if (dialogBuilder == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		dialogBuilder.view.apply {
			val clickActions = mapOf(
				R.id.btn_file_info_card to { openDownloadReferrerLink() },
				R.id.btn_resume_download to { resumeDownloadTask() },
				R.id.btn_pause_download to { pauseDownloadTask() },
				R.id.btn_clear_download to { removeDownloadTask() },
				R.id.btn_delete_download to { deleteDownloadTask() },
				R.id.btn_rename_download to { renameDownloadTask() },
				R.id.btn_remove_thumbnail to { toggleDownloadThumbnail() },
				R.id.btn_copy_site_link to { copyWebsiteLink() },
				R.id.btn_copy_download_url to { copyDownloadFileLink() },
				R.id.btn_share_download_url to { shareDownloadFileLink() },
				R.id.btn_open_website to { openDownloadReferrerLink() },
				R.id.btn_wifi_only_download to { toggleWifiOnlyDownload() },
				R.id.btn_download_notification to { toggleDownloadNotification() },
				R.id.btn_play_download_sound to { toggleDownloadSound() },
				R.id.btn_download_other_resolution to { downloadOtherYTResolutions() },
				R.id.btn_download_system_information to { openDownloadInfoTracker() }
			)
			clickActions.forEach { (viewId, action) ->
				findViewById<View>(viewId).setOnClickListener {
					action()
				}
			}
		}
	}
}