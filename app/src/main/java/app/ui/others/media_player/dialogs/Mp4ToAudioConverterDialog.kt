package app.ui.others.media_player.dialogs

import android.view.View
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.downloadSystem
import app.core.bases.BaseActivity
import app.core.engines.downloader.DownloadDataModel
import app.ui.others.media_player.MediaPlayerActivity
import com.aio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lib.device.DateTimeUtils.millisToDateTimeString
import lib.files.FileSystemUtility
import lib.files.VideoToAudioConverter
import lib.files.VideoToAudioConverter.ConversionListener
import lib.networks.DownloaderUtils.getHumanReadableFormat
import lib.process.CopyObjectUtils.deepCopy
import lib.process.LogHelperUtils
import lib.process.UniqueNumberUtils.getUniqueNumberForDownloadModels
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.io.File

object Mp4ToAudioConverterDialog {

	private val logger = LogHelperUtils.from(javaClass)

	@OptIn(UnstableApi::class)
	fun showMp4ToAudioConverterDialog(
		baseActivity: BaseActivity?,
		downloadModel: DownloadDataModel?
	) {
		if (baseActivity == null) return
		if (downloadModel == null) return

		logger.d("Initializing MP4 to Audio conversion dialog")
		val coroutineScope = baseActivity.getAttachedCoroutineScope()
		val videoToAudioConverter = VideoToAudioConverter()
		val loadingMessage = getText(R.string.title_converting_audio_progress_0)
		val waitingDialog = WaitingDialog(
			baseActivityInf = baseActivity,
			loadingMessage = loadingMessage,
			isCancelable = false,
			shouldHideOkayButton = false
		)

		waitingDialog.dialogBuilder?.view?.apply {
			findViewById<TextView>(R.id.btn_dialog_positive)?.apply {
				this.setText(R.string.title_cancel_converting)
				this.setLeftSideDrawable(R.drawable.ic_button_cancel)
			}

			findViewById<View>(R.id.btn_dialog_positive_container)
				?.setOnClickListener {
					logger.d("User clicked cancel during conversion")
					videoToAudioConverter.cancel()
					waitingDialog.close()
				}
		}

		waitingDialog.show()

		if (baseActivity is MediaPlayerActivity) {
			baseActivity.pausePlayback()
		}

		try {
			val inputMediaFilePath = downloadModel.getDestinationFile().absolutePath
			val convertedAudioFileName = downloadModel.fileName + "_converted.mp3"
			val outputPath = downloadModel.fileDirectory
			val outputMediaFile = File(outputPath, convertedAudioFileName)

			logger.d("Starting audio extraction from: " +
				"$inputMediaFilePath -> $outputMediaFile")

			fun onSuccessUIUpdate(outputFile: String) {
				coroutineScope.launch(Dispatchers.Main) {
					try {
						logger.d("Conversion completed successfully: $outputFile")
						waitingDialog.close()

						FileSystemUtility.addToMediaStore(outputMediaFile)
						showToast(baseActivity, R.string.title_converted_successfully)

						addNewDownloadModelToSystem(downloadModel, outputMediaFile)

						if (baseActivity is MediaPlayerActivity) {
							baseActivity.resumePlayback()
						}
					} catch (error: Exception) {
						logger.e("Error adding converted file to the system: " +
							"${error.message}")
					}
				}
			}

			fun onProgressUIUpdate(progress: Int) {
				coroutineScope.launch(Dispatchers.Main) {
					val progressTextView: TextView? = waitingDialog.dialogBuilder
						?.view
						?.findViewById(R.id.txt_progress_info)

					if (progressTextView != null) {
						val resId = R.string.title_converting_audio_progress
						val progressString = INSTANCE.getString(resId, "$progress%")
						logger.d("Progress: $progress%")
						progressTextView.text = progressString
					} else {
						logger.d("Progress update skipped: Dialog view already released.")
					}
				}
			}

			fun onFailureUIUpdate(errorMessage: String) {
				coroutineScope.launch(Dispatchers.Main) {
					waitingDialog.close()
					showToast(baseActivity, R.string.title_converting_failed)
					if (baseActivity is MediaPlayerActivity) {
						baseActivity.resumePlayback()
					}
				}
			}

			coroutineScope.launch(Dispatchers.IO) {
				videoToAudioConverter.extractAudio(
					inputFile = inputMediaFilePath,
					outputFile = outputMediaFile.absolutePath,
					listener = object : ConversionListener {
						override fun onProgress(progress: Int) = onProgressUIUpdate(progress)
						override fun onSuccess(outputFile: String) = onSuccessUIUpdate(outputFile)
						override fun onFailure(errorMessage: String) = onFailureUIUpdate(errorMessage)
					}
				)
			}
		} catch (error: Exception) {
			logger.e("Unexpected error during conversion: ${error.message}")
			coroutineScope.launch(Dispatchers.Main) {
				waitingDialog.close()
				showToast(baseActivity, R.string.title_something_went_wrong)
				if (baseActivity is MediaPlayerActivity) {
					baseActivity.resumePlayback()
				}
			}
		}
	}

	private fun addNewDownloadModelToSystem(
		downloadDataModel: DownloadDataModel,
		outputMediaFile: File
	) {
		logger.d("Adding converted audio file to download system: ${outputMediaFile.name}")

		val copiedModel = deepCopy(downloadDataModel) ?: return
		copiedModel.downloadId = getUniqueNumberForDownloadModels()
		copiedModel.fileName = outputMediaFile.name
		copiedModel.fileDirectory = outputMediaFile.parentFile?.absolutePath.toString()
		copiedModel.fileSize = outputMediaFile.length()
		copiedModel.fileSizeInFormat = getHumanReadableFormat(copiedModel.fileSize)
		copiedModel.fileCategoryName = copiedModel.getUpdatedCategoryName()

		copiedModel.startTimeDate = System.currentTimeMillis()
		copiedModel.lastModifiedTimeDate = copiedModel.startTimeDate

		val t = copiedModel.lastModifiedTimeDate
		copiedModel.startTimeDateInFormat = millisToDateTimeString(t)
		copiedModel.lastModifiedTimeDateInFormat = millisToDateTimeString(t)

		logger.d("Converted model prepared, updating storage and UI")

		copiedModel.updateInStorage()
		downloadSystem.addAndSortFinishedDownloadDataModels(copiedModel)

		downloadSystem
			.downloadsUIManager
			.finishedTasksFragment
			?.finishedTasksListAdapter
			?.notifyDataSetChangedOnSort(false)

		logger.d("Download system updated with converted file: ${copiedModel.fileName}")
	}
}