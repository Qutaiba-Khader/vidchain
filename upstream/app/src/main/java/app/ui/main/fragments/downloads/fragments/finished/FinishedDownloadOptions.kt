package app.ui.main.fragments.downloads.fragments.finished

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.View
import android.view.View.GONE
import android.view.View.OnClickListener
import android.view.View.VISIBLE
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat.getDrawable
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.downloadSystem
import app.core.bases.BaseActivity
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_ID_KEY
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.video_parser.dialogs.VideoLinkPasteEditor
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.ui.main.MotherActivity
import app.ui.main.fragments.downloads.dialogs.DownloadFileRenamer
import app.ui.main.fragments.downloads.dialogs.DownloadInfoTracker
import app.ui.others.media_player.MediaPlayerActivity
import app.ui.others.media_player.MediaPlayerActivity.Companion.INTENT_EXTRA_MEDIA_FILE_PATH
import app.ui.others.media_player.dialogs.Mp4ToAudioConverterDialog.showMp4ToAudioConverterDialog
import com.aio.R
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.device.ShareUtility.openApkFile
import lib.device.ShareUtility.openFile
import lib.device.ShareUtility.shareMediaFile
import lib.files.FileSystemUtility.endsWithExtension
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideo
import lib.files.FileSystemUtility.isVideoByName
import lib.files.VideoFilesUtility.moveMoovAtomToStart
import lib.networks.URLUtility.isValidURL
import lib.networks.URLUtilityKT.removeWwwFromUrl
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.ActivityAnimator.animActivityFade
import lib.ui.MsgDialogUtils
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.normalizeTallSymbols
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.ViewUtility.showView
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.io.File
import java.lang.ref.WeakReference

class FinishedDownloadOptions(finishedFragment: FinishedTasksFragment?) : OnClickListener {

	private val logger = LogHelperUtils.from(javaClass)
	private val fragmentWeakRef: WeakReference<FinishedTasksFragment>?
	private val activityWeakRef: WeakReference<BaseActivity>?

	private var dialogBuilder: DialogBuilder? = null
	private var downloadModel: DownloadDataModel? = null

	init {
		val safeFragment = finishedFragment?.safeFinishTasksFragment
		val safeActivity = safeFragment?.safeMotherActivityRef
		this.fragmentWeakRef = safeFragment?.let { WeakReference(it) }
		this.activityWeakRef = safeActivity?.let { WeakReference(it) }
	}

	private fun getSafeActivity(): BaseActivity? = activityWeakRef?.get()
	private fun getSafeFragment(): FinishedTasksFragment? = fragmentWeakRef?.get()

	fun initialize() {
		val activityRef = getSafeActivity()
		val fragmentRef = getSafeFragment()

		if (activityRef == null) return
		if (fragmentRef == null) return

		this@FinishedDownloadOptions.dialogBuilder = DialogBuilder(activityRef)
		val dialogBuilder = this@FinishedDownloadOptions.dialogBuilder
		if (dialogBuilder == null) return

		dialogBuilder.setView(R.layout.frag_down_4_finish_1_onclick_1)
		val buttonResIds = getButtonResIds()
		setViewOnClickListener(
			onClickListener = this,
			layout = dialogBuilder.view,
			ids = buttonResIds
		)
	}

	fun setDownloadModel(dataModel: DownloadDataModel) {
		this.downloadModel = dataModel
	}

	fun show(dataModel: DownloadDataModel) {
		val activityRef = getSafeActivity()
		val fragmentRef = getSafeFragment()
		val dialogBuilder = dialogBuilder

		if (activityRef == null) return
		if (fragmentRef == null) return
		if (dialogBuilder == null) return
		if (dialogBuilder.isShowing) return

		setDownloadModel(dataModel)
		updateDialogViewsWith(dataModel)
		dialogBuilder.show()
		dialogBuilder.dialog.setOnCancelListener { this@FinishedDownloadOptions.close() }
		dialogBuilder.dialog.setOnDismissListener { this@FinishedDownloadOptions.close() }
	}

	fun close() {
		val activityRef = getSafeActivity()
		val fragmentRef = getSafeFragment()
		val dialogBuilder = dialogBuilder

		if (activityRef == null) return
		if (fragmentRef == null) return
		if (dialogBuilder == null) return
		if (!dialogBuilder.isShowing) return
		dialogBuilder.close()
		clearReferences()
	}

	private fun clearReferences() {
		dialogBuilder = null
		downloadModel = null
		activityWeakRef?.clear()
		fragmentWeakRef?.clear()
	}

	override fun onClick(view: View?) {
		if (view == null) return
		when (view.id) {
			R.id.btn_file_info_card -> playTheMedia()
			R.id.btn_play_the_media -> playTheMedia()
			R.id.btn_open_download_file -> openFile()
			R.id.btn_copy_site_link -> copySiteLink()
			R.id.btn_share_download_file -> shareFile()
			R.id.btn_clear_download -> clearFromList()
			R.id.btn_delete_download -> deleteFile()
			R.id.btn_rename_download -> renameFile()
			R.id.btn_open_website -> openWebsite()
			R.id.btn_move_to_private -> toggleMoveToPrivateOrGallery()
			R.id.btn_remove_thumbnail -> toggleThumbnail()
			R.id.btn_fix_unseekable_mp4_file -> fixUnseekableMp4s()
			R.id.btn_mp4_to_mp3_convert -> convertMp4ToAudio()
			R.id.btn_download_other_resolution -> downloadOtherYTResolutions()
			R.id.btn_download_system_information -> downloadInfo()
		}
	}

	@OptIn(UnstableApi::class)
	fun playTheMedia() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		if (isMediaFile(dataModel) == false) {
			openFile()
			return
		}

		val mediaPlayerClass = MediaPlayerActivity::class.java
		val downloadedFilePath = dataModel.getDestinationFile().path

		val intent = Intent(activityRef, mediaPlayerClass)
		intent.flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
		intent.putExtra(DOWNLOAD_MODEL_ID_KEY, dataModel.downloadId)
		intent.putExtra(INTENT_EXTRA_MEDIA_FILE_PATH, downloadedFilePath)
		activityRef.startActivity(intent)

		animActivityFade(activityRef)
		this@FinishedDownloadOptions.close()
	}

	fun openFile() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		this@FinishedDownloadOptions.close()

		val apkFileExt = listOf("apk").toTypedArray()
		if (!endsWithExtension(dataModel.fileName, apkFileExt)) {
			val destinationFile = dataModel.getDestinationFile()
			openFile(destinationFile, activityRef)
			return
		}

		logger.d("Apk file is detected, trying to open it.")
		val authority = "${activityRef.packageName}.provider"
		val apkFile = dataModel.getDestinationFile()
		openApkFile(activityRef, apkFile, authority)
	}

	fun shareFile() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		this@FinishedDownloadOptions.close()

		val destinationFile = dataModel.getDestinationFile()
		shareMediaFile(activityRef, destinationFile)
	}

	fun clearFromList() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		val titleResId = R.string.title_are_you_sure_about_this
		val msgResId = R.string.text_are_you_sure_about_clear
		val messageDialog = getMessageDialog(
			baseActivityInf = activityRef,
			isTitleVisible = true,
			isNegativeButtonVisible = false,
			titleTextViewCustomize = { it.setText(titleResId) },
			messageTextViewCustomize = { it.setText(msgResId) },
			positiveButtonTextCustomize = {
				it.setText(R.string.title_clear_from_list)
				it.setLeftSideDrawable(R.drawable.ic_button_clear)
			}
		)

		if (messageDialog == null) return

		messageDialog.setOnClickForPositiveButton {
			this.close()
			this@FinishedDownloadOptions.close()

			val downloadSys = downloadSystem
			val dataModels = downloadSys.finishedDownloadDataModels
			val toastMsgResId = R.string.title_successfully_cleared

			dataModel.deleteModelFromDisk()
			dataModels.remove(dataModel)
			val safeActivity = getSafeActivity()
			if (safeActivity != null) {
				showToast(safeActivity, msgId = toastMsgResId)
			}
		}

		messageDialog.show()
	}

	fun deleteFile() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		val titleResId = R.string.title_are_you_sure_about_this
		val messageResId = R.string.text_are_you_sure_about_delete

		val messageDialog = getMessageDialog(
			baseActivityInf = activityRef,
			isTitleVisible = true,
			isNegativeButtonVisible = false,
			titleTextViewCustomize = { it.setText(titleResId) },
			messageTextViewCustomize = { it.setText(messageResId) },
			positiveButtonTextCustomize = {
				it.setText(R.string.title_delete_file)
				it.setLeftSideDrawable(R.drawable.ic_button_delete)
			}
		)

		if (messageDialog == null) return

		messageDialog.setOnClickForPositiveButton {
			messageDialog.close()
			this@FinishedDownloadOptions.close()
			activityRef.runCodeOnAttachedThread(isUIThread = false) {
				val downloadSys = downloadSystem
				val modelsList = downloadSys.finishedDownloadDataModels
				val toastMsgResId = R.string.title_successfully_deleted

				dataModel.deleteModelFromDisk()
				dataModel.getDestinationFile().delete()
				modelsList.remove(dataModel)
				val safeActivity = getSafeActivity()
				if (safeActivity != null) {
					showToast(safeActivity, msgId = toastMsgResId)
				}
			}
		}

		messageDialog.show()
	}

	fun renameFile() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return
		if (activityRef !is MotherActivity) return

		val fileRenamer = DownloadFileRenamer(activityRef, dataModel) {
			activityRef.getAttachedCoroutineScope().launch {
				dialogBuilder?.close()
				delay(300)
				val adapter = fragmentRef.finishedTasksListAdapter
				adapter?.notifyDataSetChangedOnSort(true)
			}
		}

		fileRenamer.downloadDataModel = dataModel
		fileRenamer.show(dataModel)
	}

	fun openWebsite() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return
		if (activityRef !is MotherActivity) return

		val websiteLink = dataModel.siteReferrer.ifEmpty { dataModel.fileURL }
		if (websiteLink.isEmpty()) {
			this@FinishedDownloadOptions.close()
			activityRef.doSomeVibration(20)
			val msgTxt = getText(R.string.text_missing_webpage_link_info)
			val titleTxt = getText(R.string.title_missing_associate_webpage)
			MsgDialogUtils.showMessageDialog(
				baseActivityInf = activityRef,
				titleText = titleTxt,
				isTitleVisible = true,
				messageTxt = msgTxt,
				isNegativeButtonVisible = false
			)
			return
		}

		val browserFragment = activityRef.browserFragment
		val webviewEngine = browserFragment?.browserFragmentBody?.webviewEngine
		if (webviewEngine == null) return

		this@FinishedDownloadOptions.close()
		val webNavigation = activityRef.sideNavigation
		webNavigation?.addNewBrowsingTab(websiteLink, webviewEngine)
		activityRef.openBrowserFragment()
	}

	fun toggleMoveToPrivateOrGallery() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		val globalSettings = dataModel.globalSettings
		val location = globalSettings.defaultDownloadLocation
		if (location == PRIVATE_FOLDER) moveToGallery() else moveToPrivate()
	}

	fun moveToPrivate() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return
		if (activityRef !is MotherActivity) return

		this@FinishedDownloadOptions.close()
		val msgResID = R.string.title_moving_to_private_folder_wait
		val waiting = WaitingDialog(
			baseActivityInf = activityRef,
			loadingMessage = getText(msgResID),
			shouldHideOkayButton = true,
			isCancelable = false
		)
		val dialogBuilder = waiting.dialogBuilder
		dialogBuilder?.setOnClickForPositiveButton { waiting.close() }
		waiting.show()

		fun onErrorInstructions() {
			val safeActivity = getSafeActivity() ?: return
			val msgResId = R.string.title_something_went_wrong
			waiting.close()
			showToast(safeActivity, msgId = msgResId)
		}

		fun onSuccessInstructions() {
			val safeFragment = getSafeFragment() ?: return
			val safeActivity = getSafeActivity() ?: return
			if (safeActivity !is MotherActivity) return

			val adapter = safeFragment.finishedTasksListAdapter
			val homeFragment = safeActivity.homeFragment
			val txtResId = R.string.title_move_to_private_successfully

			adapter?.notifyDataSetChangedOnSort(true)
			homeFragment?.refreshRecentDownloadListUI()

			waiting.close()
			showToast(safeActivity, msgId = txtResId)
		}

		dataModel.moveToPrivateFolder(
			onError = { onErrorInstructions() },
			onSuccess = { onSuccessInstructions() }
		)
	}

	fun moveToGallery() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity() as? MotherActivity

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		this@FinishedDownloadOptions.close()

		val megResID = R.string.title_moving_to_gallery_folder_wait
		val waitingDialog = WaitingDialog(
			baseActivityInf = activityRef,
			loadingMessage = getText(megResID),
			shouldHideOkayButton = true,
			isCancelable = false
		)

		val waitingDB = waitingDialog.dialogBuilder
		waitingDB?.setOnClickForPositiveButton { waitingDialog.close() }
		waitingDialog.show()

		fun onErrorInstructions() {
			val safeActivity = getSafeActivity() ?: return
			waitingDialog.close()
			val toastMsgId = R.string.title_something_went_wrong
			showToast(safeActivity, msgId = toastMsgId)
		}

		fun onSuccessInstructions() {
			val safeFragment = getSafeFragment() ?: return
			val safeActivity = getSafeActivity() as? MotherActivity ?: return

			val adapter = safeFragment.finishedTasksListAdapter
			val homeFragment = safeActivity.homeFragment

			adapter?.notifyDataSetChangedOnSort(true)
			homeFragment?.refreshRecentDownloadListUI()

			waitingDialog.close()
			val toastMsgId = R.string.title_move_to_gallery_successfully
			showToast(safeActivity, msgId = toastMsgId)
		}

		dataModel.moveToSysGalleryFolder(
			onError = { onErrorInstructions() },
			onSuccess = { onSuccessInstructions() }
		)
	}

	fun toggleThumbnail() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return
		if (activityRef !is MotherActivity) return

		this@FinishedDownloadOptions.close()

		fun updateGlobalDownloadSettings() {
			val settings = downloadModel?.globalSettings
			if (settings == null) return
			val hideVideoThumbConfig = settings.downloadHideVideoThumbnail
			settings.downloadHideVideoThumbnail = !hideVideoThumbConfig
			downloadModel?.updateInStorage()
		}

		fun updateFragmentListAdapter() {
			val listAdapter = fragmentRef.finishedTasksListAdapter
			listAdapter?.notifyDataSetChangedOnSort(true)
			activityRef.homeFragment?.refreshRecentDownloadListUI()
		}

		try {
			updateGlobalDownloadSettings()
			updateFragmentListAdapter()
		} catch (error: Exception) {
			logger.e("Error found at hide/show thumbnail -", error)
			showToast(activityRef, msgId = R.string.title_something_went_wrong)
		}
	}

	fun fixUnseekableMp4s() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return

		val msgResId = R.string.text_msg_of_fixing_unseekable_mp4_files
		val titleString = getText(R.string.title_are_you_sure_about_this)
		val resources = activityRef.resources
		val errorColor = resources.getColor(R.color.color_error, null)

		val promptDialog = getMessageDialog(
			baseActivityInf = activityRef,
			isNegativeButtonVisible = false,
			isTitleVisible = true,
			titleTextViewCustomize = {
				it.setTextColor(errorColor)
				it.text = titleString
			},
			messageTextViewCustomize = { it.setText(msgResId) },
			positiveButtonTextCustomize = {
				it.setLeftSideDrawable(R.drawable.ic_button_fix_hand)
				it.setText(R.string.title_proceed_anyway)
			}
		)

		if (promptDialog == null) return

		fun executeMovingFile(waitingDialog: WaitingDialog, destinationFile: File) {
			activityRef.runCodeOnAttachedThread(isUIThread = false) {
				val msgId = R.string.title_fixing_mp4_done_successfully
				executeOnMainThread { waitingDialog.show() }
				moveMoovAtomToStart(destinationFile, destinationFile)
				val safeActivity = getSafeActivity()
				if (safeActivity != null) {
					executeOnMainThread {
						showToast(safeActivity, msgId)
						waitingDialog.close()
					}
				}
			}
		}

		promptDialog.setOnClickForPositiveButton {
			val loadingMessage = getText(R.string.title_fixing_mp4_file_please_wait)
			val waitingDialog = WaitingDialog(
				baseActivityInf = activityRef,
				loadingMessage = loadingMessage,
				isCancelable = false,
				shouldHideOkayButton = true
			)

			try {
				promptDialog.close()
				val destinationFile = downloadModel?.getDestinationFile()
				if (destinationFile == null || !destinationFile.exists()) {
					activityRef.doSomeVibration(50)
					showToast(activityRef, R.string.title_something_went_wrong)
					return@setOnClickForPositiveButton
				}

				executeMovingFile(waitingDialog, destinationFile)
			} catch (error: Exception) {
				logger.e("Error in fixing unseekable mp4 file:", error)
				executeOnMainThread { waitingDialog.show() }
			}

		}

		promptDialog.show()
	}

	fun downloadInfo() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return
		if (activityRef !is MotherActivity) return

		this@FinishedDownloadOptions.close()

		val downloadInfoTracker = DownloadInfoTracker(activityRef)
		downloadInfoTracker.show(dataModel)
	}

	fun convertMp4ToAudio() {
		this@FinishedDownloadOptions.close()
		val activityRef = getSafeActivity()
		if (activityRef == null) return
		if (activityRef !is MotherActivity) return
		showMp4ToAudioConverterDialog(activityRef, downloadModel)
	}

	fun downloadOtherYTResolutions() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dataModel == null) return
		if (activityRef !is MotherActivity) return

		this@FinishedDownloadOptions.close()
		val fileUrl = dataModel.fileURL.ifEmpty { dataModel.siteReferrer }
		if (fileUrl.isEmpty()) return
		VideoLinkPasteEditor(
			motherActivity = activityRef,
			passOnUrl = fileUrl,
			autoStart = true
		).show()
	}

	private fun getButtonResIds(): IntArray {
		return listOf(
			R.id.btn_file_info_card,
			R.id.btn_play_the_media,
			R.id.btn_open_download_file,
			R.id.btn_copy_site_link,
			R.id.btn_share_download_file,
			R.id.btn_clear_download,
			R.id.btn_delete_download,
			R.id.btn_rename_download,
			R.id.btn_open_website,
			R.id.btn_move_to_private,
			R.id.btn_remove_thumbnail,
			R.id.btn_fix_unseekable_mp4_file,
			R.id.btn_mp4_to_mp3_convert,
			R.id.btn_download_other_resolution,
			R.id.btn_download_system_information
		).toIntArray()
	}

	private fun updateDialogViewsWith(dataModel: DownloadDataModel) {
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()
		val dialogBuilder = dialogBuilder

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dialogBuilder == null) return

		val dialogView = dialogBuilder.view
		dialogView.apply {
			val txtSubtitle = findViewById<TextView>(R.id.txt_file_url)
			val txtTitle = findViewById<TextView>(R.id.txt_file_title)
			val btnPlay = findViewById<TextView>(R.id.txt_play_the_media)
			val imgThumbnail = findViewById<ImageView>(R.id.img_file_thumbnail)
			val imgFavicon = findViewById<ImageView>(R.id.img_site_favicon)
			val btnToggleThumb = findViewById<TextView>(R.id.txt_remove_thumbnail)
			val txtMoveToPrivate = findViewById<TextView>(R.id.txt_move_to_private)
			val btnConvertToMp3 = findViewById<View>(R.id.btn_mp4_to_mp3_convert)
			val btnFixUnseekableMp4s = findViewById<View>(R.id.container_mp4_file_fix)
			val containerDuration = findViewById<View>(R.id.container_media_duration)
			val containerDownloadYtRes = findViewById<View>(R.id.container_another_res_download)
			val txtPlaybackDuration = findViewById<TextView>(R.id.txt_media_duration)
			val imgPlayIndicator = findViewById<View>(R.id.img_media_play_indicator)
			val imgFileIndicator = findViewById<ImageView>(R.id.img_file_type_indicator)
			val imgPrivateIndicator = findViewById<ImageView>(R.id.img_private_folder_indicator)

			txtTitle.isSelected = true
			txtTitle.text = dataModel.fileName
			txtTitle.normalizeTallSymbols()

			val fileURL = dataModel.fileURL
			txtSubtitle.text = removeWwwFromUrl(fileURL).ifEmpty { fileURL }

			val dataModelSettings = dataModel.globalSettings
			val hideVideoThumbnail = dataModelSettings.downloadHideVideoThumbnail
			if (hideVideoThumbnail) {
				val defaultThumb = dataModel.getThumbnailDrawableID()
				imgThumbnail.setImageResource(defaultThumb)
			} else {
				updateThumbnail(imgThumbnail, dataModel)
			}

			updateFaviconInfo(dataModel, imgFavicon)
			updatePrivateFolderIndicator(dataModel, imgPrivateIndicator)

			btnToggleThumb.text = ((if (hideVideoThumbnail)
				getText(R.string.title_show_thumbnail) else
				getText(R.string.title_hide_thumbnail)))

			btnPlay.text = when {
				isAudioByName(dataModel.fileName) -> getText(R.string.title_play_the_audio)
				isVideoByName(dataModel.fileName) -> getText(R.string.title_play_the_video)
				else -> getText(R.string.title_open_the_file)
			}

			val downloadLocation = dataModelSettings.defaultDownloadLocation
			if (downloadLocation == PRIVATE_FOLDER) {
				txtMoveToPrivate.setLeftSideDrawable(R.drawable.ic_button_folder)
				txtMoveToPrivate.text = getText(R.string.title_move_to_gallery)
			} else {
				txtMoveToPrivate.setLeftSideDrawable(R.drawable.ic_button_private_folder)
				txtMoveToPrivate.text = getText(R.string.title_move_to_private)
			}

			if (isMediaFile(dataModel)) {
				val isVideoOnly = if (isVideoByName(dataModel.fileName)) VISIBLE else GONE
				btnConvertToMp3.visibility = isVideoOnly
				btnFixUnseekableMp4s.visibility = isVideoOnly

				imgPlayIndicator.apply { showView(this, true) }

				containerDuration.apply {
					val duration = dataModel.mediaFilePlaybackDuration
					val timeString = duration.replace("(", "").replace(")", "")
					if (timeString.isNotEmpty()) {
						showView(this, true)
						showView(txtPlaybackDuration, true)
						txtPlaybackDuration.text = timeString
					}
				}
			} else {
				containerDuration.visibility = GONE
				imgPlayIndicator.visibility = GONE
				btnConvertToMp3.visibility = GONE
				btnFixUnseekableMp4s.visibility = GONE
			}

			imgFileIndicator.setImageResource(
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

			val link = dataModel.siteReferrer.ifEmpty { dataModel.fileURL }
			val visibility = if (link.isNotEmpty() && isYouTubeUrl(link)) VISIBLE else GONE
			containerDownloadYtRes.visibility = visibility
		}
	}

	private fun updateFaviconInfo(dataModel: DownloadDataModel, imgFavicon: ImageView) {
		val viewWeakRef = WeakReference(imgFavicon)
		val activityWeakRef = this.activityWeakRef

		val defaultResId = R.drawable.ic_image_default_favicon
		val defaultDrawable = getDrawable(INSTANCE.resources, defaultResId, null)

		if (isVideoThumbnailNotAllowed(dataModel)) {
			executeOnMainThread { viewWeakRef.get()?.setImageDrawable(defaultDrawable) }
			return
		}

		ThreadsUtility.executeInBackground(timeOutInMilli = 500, codeBlock = {
			val referralSite = dataModel.siteReferrer
			aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
				val faviconImgFile = File(faviconFilePath)
				if (!faviconImgFile.exists() || !faviconImgFile.isFile) {
					return@executeInBackground
				}

				ThreadsUtility.executeOnMain(codeBlock = {
					try {
						showView(viewWeakRef.get(), true)
						loadBitmapWithGlide(
							imageView = viewWeakRef.get(),
							activity = activityWeakRef?.get(),
							filePath = faviconFilePath,
							defaultThumb = defaultResId)
					} catch (error: Exception) {
						logger.e("Error setting favicon: ${error.message}", error)
						showView(viewWeakRef.get(), true)
						viewWeakRef.get()?.setImageResource(defaultResId)
					}
				})
			}
		}, errorHandler = {
			logger.e("Error loading favicon: ${it.message}", it)
			viewWeakRef.get()?.setImageDrawable(defaultDrawable)
		})
	}

	private fun updatePrivateFolderIndicator(dataModel: DownloadDataModel, imageView: ImageView) {
		val downloadLocation = dataModel.globalSettings.defaultDownloadLocation
		imageView.setImageResource(
			when (downloadLocation) {
				PRIVATE_FOLDER -> R.drawable.ic_button_lock
				else -> R.drawable.ic_button_folder
			}
		)
	}

	private fun updateThumbnail(thumbImageView: ImageView, downloadModel: DownloadDataModel) {
		val viewWeakRef = WeakReference(thumbImageView)
		val activityWeakRef = this.activityWeakRef

		val destinationFile = downloadModel.getDestinationFile()
		val defaultThumb = downloadModel.getThumbnailDrawableID()
		val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)

		if (loadApkThumbnail(
				downloadModel = downloadModel,
				imageView = viewWeakRef.get(),
				defaultThumb = defaultThumbDrawable)) return

		ThreadsUtility.executeInBackground(timeOutInMilli = 500, codeBlock = {
			val cachedThumbPath = downloadModel.thumbPath
			if (cachedThumbPath.isNotEmpty()) {
				loadBitmapWithGlide(
					imageView = thumbImageView,
					activity = activityWeakRef?.get(),
					filePath = cachedThumbPath,
					defaultThumb = defaultThumb
				)
				return@executeInBackground
			}

			val thumbnailUrl = downloadModel.videoInfo?.videoThumbnailUrl
			val bitmap = getThumbnailFromFile(destinationFile, thumbnailUrl, 420)

			if (bitmap != null) {
				val isPortrait = bitmap.height > bitmap.width
				val rotatedBitmap = if (isPortrait) rotateBitmap(bitmap, 270f) else bitmap

				val thumbnailName = "${downloadModel.downloadId}$THUMB_EXTENSION"
				saveBitmapToFile(rotatedBitmap, thumbnailName)?.let { filePath ->
					downloadModel.thumbPath = filePath
					downloadModel.updateInStorage()
					loadBitmapWithGlide(
						imageView = viewWeakRef.get(),
						activity = activityWeakRef?.get(),
						filePath = filePath,
						defaultThumb = defaultThumb
					)
				}
			}
		})
	}

	private fun isVideoThumbnailNotAllowed(dataModel: DownloadDataModel): Boolean {
		val isVideoHidden = dataModel.globalSettings.downloadHideVideoThumbnail
		val result = isVideo(dataModel.getDestinationDocumentFile()) && isVideoHidden
		return result
	}

	private fun loadBitmapWithGlide(imageView: ImageView?,
		activity: BaseActivity?, filePath: String, defaultThumb: Int) {
		if (imageView == null || activity == null) return
		executeOnMainThread {
			try {
				if (imageView.context != activity) return@executeOnMainThread
				val imgURI = File(filePath).toUri()
				val lastModified = File(filePath).lastModified()
				Glide.with(activity)
					.load(imgURI)
					.signature(ObjectKey(lastModified))
					.placeholder(R.drawable.image_no_thumb_available)
					.into(imageView)
			} catch (error: Exception) {
				logger.e("Error loading thumbnail with " +
					"Glide: ${error.message}", error)
				imageView.setImageResource(defaultThumb)
			}
		}
	}

	private fun loadApkThumbnail(
		downloadModel: DownloadDataModel?,
		imageView: ImageView?,
		defaultThumb: Drawable?
	): Boolean {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()

		val dialogBuilder = dialogBuilder

		if (fragmentRef == null) return false
		if (activityRef == null) return false
		if (dialogBuilder == null) return false
		if (dataModel == null) return false
		if (imageView == null) return false

		val apkFile = dataModel.getDestinationFile()

		if (!apkFile.exists() || !apkFile.name.lowercase().endsWith(".apk")) {
			imageView.setImageDrawable(defaultThumb)
			return false
		}

		val pkgM: PackageManager = activityRef.packageManager
		return try {
			val archivePath = apkFile.absolutePath
			val flags = PackageManager.GET_ACTIVITIES
			val packageInfo = pkgM.getPackageArchiveInfo(archivePath, flags)
			val appInfo = packageInfo?.applicationInfo

			if (appInfo == null) {
				imageView.setImageDrawable(defaultThumb)
				false
			} else {
				appInfo.sourceDir = archivePath
				appInfo.publicSourceDir = archivePath
				imageView.setImageDrawable(appInfo.loadIcon(pkgM))
				true
			}
		} catch (error: Exception) {
			logger.e("Error loading APK thumbnail: ${error.message}", error)
			imageView.apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setPadding(0, 0, 0, 0)
				setImageDrawable(defaultThumb)
			}
			false
		}
	}

	private fun isMediaFile(downloadModel: DownloadDataModel?): Boolean {
		if (downloadModel == null) return false
		return isAudioByName(downloadModel.fileName) || isVideoByName(downloadModel.fileName)
	}

	private fun copySiteLink() {
		val dataModel = downloadModel
		val fragmentRef = getSafeFragment()
		val activityRef = getSafeActivity()
		val dialogBuilder = dialogBuilder

		if (fragmentRef == null) return
		if (activityRef == null) return
		if (dialogBuilder == null) return
		if (dataModel == null) return

		dataModel.siteReferrer
			.takeIf { isValidURL(it) }
			?.let { fileUrl ->
				copyTextToClipboard(activityRef, fileUrl)
				showToast(activityRef, R.string.title_file_url_has_been_copied)
				close()
			} ?: run {
			showToast(activityRef, R.string.title_dont_have_anything_to_copy)
		}
	}
}