package app.ui.main.fragments.downloads.fragments.finished

import android.content.pm.*
import android.graphics.drawable.*
import android.text.*
import android.util.*
import android.view.*
import android.view.View.*
import android.widget.*
import androidx.core.content.res.ResourcesCompat.*
import androidx.core.net.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.downloader.*
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import com.aio.R
import com.bumptech.glide.*
import com.bumptech.glide.signature.*
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import lib.device.DateTimeUtils.formatLastModifiedDate
import lib.files.FileSizeFormatter.humanReadableSizeOf
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideoByName
import lib.networks.DownloaderUtils.getAudioPlaybackTimeIfAvailable
import lib.process.*
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.drawableToBitmap
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.normalizeTallSymbols
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.showView
import java.io.*
import java.lang.ref.*

class FinishedTasksViewHolder(layout: View) {
	
	private val logger = LogHelperUtils.from(javaClass)
	private val weakReferenceOfLayout = WeakReference(layout)
	private val safeLayoutRef: View? get() = weakReferenceOfLayout.get()
	private val mediaMetadataCache = object : LruCache<String, Spanned>(500) {}
	private val mediaTitleCache = object : LruCache<String, Spanned>(500) {}
	private val coroutineScope = CoroutineScope(SupervisorJob() + Main.immediate)
	
	private var rootConLayout: RelativeLayout? = null
	private var thumbImgView: ImageView? = null
	private var faviconImgView: ImageView? = null
	private var titleTxtView: TextView? = null
	private var metadataTxtView: TextView? = null
	private var durationTxtView: TextView? = null
	private var durationConLayout: View? = null
	private var playIndicatorView: View? = null
	private var fileTypeImgView: ImageView? = null
	private var openFileIndicatorImgView: ImageView? = null
	private var privateFolderImgView: ImageView? = null
	private var newIndicatorImgView: ImageView? = null
	private val noThumbResId = R.drawable.image_no_thumb_available
	
	init {
		rootConLayout = safeLayoutRef?.findViewById(R.id.button_finish_download_row)
		thumbImgView = safeLayoutRef?.findViewById(R.id.img_file_thumbnail)
		faviconImgView = safeLayoutRef?.findViewById(R.id.img_site_favicon)
		titleTxtView = safeLayoutRef?.findViewById(R.id.txt_file_name)
		metadataTxtView = safeLayoutRef?.findViewById(R.id.txt_file_info)
		durationTxtView = safeLayoutRef?.findViewById(R.id.txt_media_duration)
		durationConLayout = safeLayoutRef?.findViewById(R.id.container_media_duration)
		playIndicatorView = safeLayoutRef?.findViewById(R.id.img_media_play_indicator)
		fileTypeImgView = safeLayoutRef?.findViewById(R.id.img_file_type_indicator)
		openFileIndicatorImgView = safeLayoutRef?.findViewById(R.id.btn_open_download_file)
		privateFolderImgView = safeLayoutRef?.findViewById(R.id.img_private_folder_indicator)
		newIndicatorImgView = safeLayoutRef?.findViewById(R.id.img_new_indicator)
	}
	
	fun updateView(dataModel: DownloadDataModel?, listener: FinishedTasksClickEvents?) {
		if (dataModel == null) {
			clearViewImmediately()
			return
		}
		
		if (listener == null) return
		setDataImmediately(dataModel)
		
		coroutineScope.coroutineContext.cancelChildren()
		coroutineScope.launch {
			setupItemClickListeners(listener, dataModel)
			
			val titleJob = async(Default) { updateFilesTitle(dataModel) }
			val metaJob = async(Default) { updateFilesMetaInfo(dataModel) }
			val faviconJob = async(IO) { updateFaviconInfo(dataModel) }
			val typeJob = async(Default) { updateFileTypeIndicator(dataModel) }
			val privateJob = async(Default) { updatePrivateFolderIndicator(dataModel) }
			val openFileJob = async(Default) { updateOpenFileIndicator(dataModel) }
			val newFileJob = async(Default) { updateNewFileIndicator(dataModel) }
			val thumbJob = async(IO) { updateThumbnailInfo(dataModel) }
			
			awaitAll(
				titleJob, metaJob, faviconJob, typeJob,
				privateJob, openFileJob, newFileJob, thumbJob
			)
		}
	}
	
	private fun setDataImmediately(dataModel: DownloadDataModel) {
		thumbImgView?.setImageResource(noThumbResId)
	}
	
	private fun setFileTypeIndicatorImmediately(fileName: String?) {
		val icon = when {
			isImageByName(fileName) -> R.drawable.ic_button_images
			isAudioByName(fileName) -> R.drawable.ic_button_audio
			isVideoByName(fileName) -> R.drawable.ic_button_video
			isDocumentByName(fileName) -> R.drawable.ic_button_document
			isArchiveByName(fileName) -> R.drawable.ic_button_archives
			isProgramByName(fileName) -> R.drawable.ic_button_programs
			else -> R.drawable.ic_button_file
		}
		fileTypeImgView?.setImageResource(icon)
	}
	
	private fun clearViewImmediately() {
		titleTxtView?.text = ""
		metadataTxtView?.text = ""
		thumbImgView?.setImageDrawable(null)
		fileTypeImgView?.setImageDrawable(null)
		durationConLayout?.visibility = GONE
		playIndicatorView?.visibility = GONE
		newIndicatorImgView?.visibility = GONE
		faviconImgView?.setImageDrawable(null)
	}
	
	fun clearResources(clearWeakReference: Boolean = true) {
		try {
			coroutineScope.coroutineContext.cancelChildren()
			thumbImgView?.let { Glide.with(it).clear(it) }
			faviconImgView?.let { Glide.with(it).clear(it) }
			
			thumbImgView?.setImageDrawable(null)
			faviconImgView?.setImageDrawable(null)
			newIndicatorImgView?.setImageDrawable(null)
			
			rootConLayout?.setOnClickListener(null)
			rootConLayout?.setOnLongClickListener(null)
			rootConLayout?.isClickable = false
			
			if (clearWeakReference) {
				mediaMetadataCache.evictAll()
				mediaTitleCache.evictAll()
				safeLayoutRef?.tag = null
				weakReferenceOfLayout.clear()
				coroutineScope.cancel()
			}
		} catch (error: Exception) {
			logger.e(
				"clearResources: Error during cleanup " +
					"- ${error.message}", error
			)
		}
	}
	
	fun cancelAll() {
		clearResources(false)
	}
	
	private suspend fun setupItemClickListeners(
		listener: FinishedTasksClickEvents,
		dataModel: DownloadDataModel
	) {
		withContext(Main) {
			if (!isActive) return@withContext
			rootConLayout?.apply {
				isClickable = true
				
				setOnClickListener {
					listener.onFinishedDownloadClick(dataModel)
				}
				
				setOnLongClickListener {
					listener.onFinishedDownloadLongClick(dataModel)
					true
				}
			}
		}
	}
	
	private suspend fun updateFilesTitle(dataModel: DownloadDataModel) {
		withContext(Main) {
			titleTxtView?.let { tv ->
				if (!isActive) return@withContext
				
				val tv = titleTxtView ?: return@withContext
				val key = dataModel.downloadId.toString()
				val cached = mediaTitleCache.get(key)
				
				if (cached != null && cached.toString() == dataModel.fileName) {
					tv.text = cached
					tv.background = null
					return@withContext
				}
				
				tv.text = dataModel.fileName
				tv.background = null
				tv.normalizeTallSymbols(scope = coroutineScope) { result ->
					mediaTitleCache.put(key, result)
				}
			}
		}
	}
	
	private suspend fun updateFilesMetaInfo(dataModel: DownloadDataModel) {
		val downloadId = dataModel.downloadId.toString()
		
		val cacheDetails = mediaMetadataCache.get(downloadId)
		if (cacheDetails != null) {
			withContext(Main) {
				if (!isActive) return@withContext
				metadataTxtView?.text = cacheDetails
				updatePlaybackTimeInfo(dataModel)
			}
			return
		}
		
		val category = dataModel.getUpdatedCategoryName(true)
		val fileSize = humanReadableSizeOf(dataModel.fileSize.toDouble())
		
		val playbackTime = dataModel.mediaFilePlaybackDuration.ifEmpty {
			getAudioPlaybackTimeIfAvailable(dataModel)
		}
		
		val modelInfoEmpty = dataModel.mediaFilePlaybackDuration.isEmpty()
		if (modelInfoEmpty && playbackTime.isNotEmpty()) {
			dataModel.mediaFilePlaybackDuration = playbackTime
			dataModel.updateInStorage()
		}
		
		val modifyDate = formatLastModifiedDate(dataModel.lastModifiedTimeDate)
		
		val metaInfoDetail = safeLayoutRef?.context?.getString(
			R.string.title_b_b_b_date_b,
			getText(R.string.title_info),
			category.removePrefix("AIO"),
			fileSize, playbackTime, modifyDate
		)?.let { fromHtmlStringToSpanned(it) }
		
		if (!metaInfoDetail.isNullOrEmpty()) {
			mediaMetadataCache.put(downloadId, metaInfoDetail)
			
			withContext(Main) {
				if (!isActive) return@withContext
				metadataTxtView?.text = metaInfoDetail
				updatePlaybackTimeInfo(dataModel)
			}
		}
	}
	
	private suspend fun updatePlaybackTimeInfo(dataModel: DownloadDataModel) {
		val fileName = dataModel.fileName
		val isMedia = isVideoByName(fileName) || isAudioByName(fileName)
		
		val playbackDuration = dataModel.mediaFilePlaybackDuration
		val mediaDuration = playbackDuration.replace("(", "").replace(")", "")
		
		withContext(Main) {
			if (!isActive) return@withContext
			if (isMedia && mediaDuration.isNotEmpty()) {
				showView(durationConLayout, true)
				showView(playIndicatorView, true)
				durationTxtView?.text = mediaDuration
			} else {
				playIndicatorView?.visibility = GONE
				durationConLayout?.visibility = GONE
			}
		}
	}
	
	private fun isVideoThumbnailNotAllowed(dataModel: DownloadDataModel): Boolean {
		val dataModelConfig = dataModel.globalSettings
		val isThumbHidden = dataModelConfig.downloadHideVideoThumbnail
		return isThumbHidden
	}
	
	private suspend fun updateFaviconInfo(dataModel: DownloadDataModel) {
		val defaultFaviconResId = R.drawable.ic_image_default_favicon
		withContext(Main) {
			if (!isActive) return@withContext
			faviconImgView?.setImageResource(defaultFaviconResId)
		}
		
		if (isVideoThumbnailNotAllowed(dataModel)) {
			withContext(Main) {
				if (!isActive) return@withContext
				faviconImgView?.let {
					Glide.with(it)
						.load(defaultFaviconResId)
						.placeholder(defaultFaviconResId)
						.into(it)
				}
			}
			return
		}
		
		try {
			aioFavicons.getFavicon(dataModel.siteReferrer)?.let { faviconFilePath ->
				val faviconImgFile = File(faviconFilePath)
				if (!faviconImgFile.exists() || !faviconImgFile.isFile) return
				
				val signature = ObjectKey(File(faviconFilePath).lastModified())
				val faviconImgURI = faviconImgFile.toUri()
				withContext(Main) {
					if (!isActive) return@withContext
					showView(faviconImgView, true)
					
					faviconImgView?.let {
						Glide.with(it)
							.load(faviconImgURI)
							.placeholder(defaultFaviconResId)
							.signature(signature)
							.into(it)
					}
				}
			}
		} catch (error: Exception) {
			logger.e(
				"updateFaviconInfo: Error loading favicon " +
					"- ${error.message}", error
			)
			withContext(Main) {
				if (!isActive) return@withContext
				faviconImgView?.setImageResource(defaultFaviconResId)
			}
		}
	}
	
	private suspend fun updateThumbnailInfo(dataModel: DownloadDataModel) {
		val destinationFile = dataModel.getDestinationFile()
		val defaultThumb = dataModel.getThumbnailDrawableID()
		val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)
		withContext(Main) { thumbImgView?.setImageDrawable(defaultThumbDrawable) }
		
		if (isVideoThumbnailNotAllowed(dataModel)) {
			withContext(Main) {
				if (!isActive) return@withContext
				thumbImgView?.let {
					Glide.with(it)
						.load(defaultThumbDrawable)
						.placeholder(defaultThumbDrawable)
						.into(it)
				}
			}
			return
		}
		
		if (loadApkThumbnail(dataModel, thumbImgView, defaultThumb)) return
		
		val cachedThumbPath = dataModel.thumbPath
		if (cachedThumbPath.isNotEmpty()) {
			withContext(Main) {
				if (!isActive) return@withContext
				loadBitmapWithGlide(
					target = thumbImgView,
					filePath = cachedThumbPath,
					placeHolder = defaultThumb
				)
			}
			return
		}
		
		val thumbnailResult = withContext(IO) {
			getThumbnailFromFile(
				targetFile = destinationFile,
				thumbnailUrl = dataModel.videoInfo?.videoThumbnailUrl,
				requiredThumbWidth = 420
			)
		}
		
		if (thumbnailResult != null) {
			val isPortrait = thumbnailResult.height > thumbnailResult.width
			val rotatedBitmap = if (isPortrait) {
				rotateBitmap(thumbnailResult, 270f)
			} else {
				thumbnailResult
			}
			
			val thumbnailName = "${dataModel.downloadId}$THUMB_EXTENSION"
			
			val filePath = withContext(IO) {
				saveBitmapToFile(rotatedBitmap, thumbnailName)
			}
			
			filePath?.let {
				dataModel.thumbPath = it
				dataModel.updateInStorage()
				
				withContext(Main) {
					if (!isActive) return@withContext
					loadBitmapWithGlide(thumbImgView, dataModel.thumbPath, defaultThumb)
				}
			}
		}
	}
	
	private fun loadBitmapWithGlide(target: ImageView?, filePath: String, placeHolder: Int) {
		target?.let {
			val imgURI = File(filePath).toUri()
			val lastModified = File(filePath).lastModified()
			Glide.with(target)
				.load(imgURI)
				.signature(ObjectKey(lastModified))
				.placeholder(placeHolder)
				.into(target)
		}
	}
	
	private suspend fun updateFileTypeIndicator(dataModel: DownloadDataModel) {
		withContext(Main) {
			if (!isActive) return@withContext
			
			val icon = when {
				isImageByName(dataModel.fileName) -> R.drawable.ic_button_images
				isAudioByName(dataModel.fileName) -> R.drawable.ic_button_audio
				isVideoByName(dataModel.fileName) -> R.drawable.ic_button_video
				isDocumentByName(dataModel.fileName) -> R.drawable.ic_button_document
				isArchiveByName(dataModel.fileName) -> R.drawable.ic_button_archives
				isProgramByName(dataModel.fileName) -> R.drawable.ic_button_programs
				else -> R.drawable.ic_button_file
			}
			
			fileTypeImgView?.let {
				Glide.with(it)
					.load(icon)
					.into(it)
			}
		}
	}
	
	private suspend fun updatePrivateFolderIndicator(dataModel: DownloadDataModel) {
		withContext(Main) {
			if (!isActive) return@withContext
			
			val globalSettings = dataModel.globalSettings
			val downloadLocation = globalSettings.defaultDownloadLocation
			val isPrivate = downloadLocation == PRIVATE_FOLDER
			
			val icon = if (isPrivate) {
				R.drawable.ic_button_private_folder
			} else {
				R.drawable.ic_button_folder
			}
			
			privateFolderImgView?.let {
				Glide.with(it)
					.load(icon)
					.into(it)
			}
		}
	}
	
	private suspend fun updateOpenFileIndicator(dataModel: DownloadDataModel) {
		withContext(Main) {
			val singleClick = aioSettings.openDownloadedFileOnSingleClick
			val playResId = R.drawable.ic_button_player
			val openResId = R.drawable.ic_button_open_v2
			val imgResId = if (!singleClick) openResId else playResId
			openFileIndicatorImgView?.setImageResource(imgResId)
		}
	}
	
	private suspend fun updateNewFileIndicator(dataModel: DownloadDataModel) {
		withContext(Main) {
			newIndicatorImgView?.let {
				Glide.with(it)
					.load(R.drawable.ic_button_dot)
					.placeholder(R.drawable.rounded_transparent)
					.into(it)
				
				val hasUserOpenedTheFile = dataModel.hasUserOpenedTheFile
				it.visibility = if (hasUserOpenedTheFile) GONE else VISIBLE
			}
		}
	}
	
	private suspend fun loadApkThumbnail(
		downloadModel: DownloadDataModel,
		targetImageView: ImageView?,
		placeHolderDrawableResId: Int
	): Boolean = withContext(Main) {
		if (!isActive) return@withContext false
		if (targetImageView == null) return@withContext false
		
		val cachedThumbFilePath = downloadModel.thumbPath
		if (cachedThumbFilePath.isNotEmpty()) {
			loadBitmapWithGlide(
				target = targetImageView,
				filePath = downloadModel.thumbPath,
				placeHolder = placeHolderDrawableResId
			)
			return@withContext true
		}
		
		val apkFile = downloadModel.getDestinationFile()
		if (!apkFile.exists() || !apkFile.name.endsWith(".apk", true)) {
			Glide.with(targetImageView)
				.load(placeHolderDrawableResId)
				.into(targetImageView)
			return@withContext false
		}
		
		val context = targetImageView.context ?: return@withContext false
		val pm: PackageManager = context.packageManager
		
		try {
			val flags = PackageManager.GET_ACTIVITIES
			val apkFileAbsolutePath = apkFile.absolutePath
			val pkInfo = pm.getPackageArchiveInfo(apkFileAbsolutePath, flags)
			
			pkInfo?.applicationInfo?.let { appInfo ->
				appInfo.sourceDir = apkFileAbsolutePath
				appInfo.publicSourceDir = apkFileAbsolutePath
				val appIconDrawable: Drawable = appInfo.loadIcon(pm)
				
				Glide.with(targetImageView)
					.load(appIconDrawable)
					.placeholder(placeHolderDrawableResId)
					.into(targetImageView)
				
				withContext(IO) {
					drawableToBitmap(appIconDrawable)?.let { bmp ->
						val thumbnailName = "${downloadModel.downloadId}$THUMB_EXTENSION"
						saveBitmapToFile(bmp, thumbnailName)?.let { filePath ->
							downloadModel.thumbPath = filePath
							downloadModel.updateInStorage()
						}
					}
				}
				
				true
			} ?: run { false }
			
		} catch (error: Exception) {
			logger.e(
				"loadApkThumbnail: Error extracting APK icon " +
					"- ${error.message}", error
			)
			
			targetImageView.apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setPadding(0, 0, 0, 0)
				Glide.with(targetImageView)
					.load(placeHolderDrawableResId)
					.placeholder(placeHolderDrawableResId)
					.into(targetImageView)
			}
			
			false
		}
	}
}