package app.ui.main.fragments.home

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Patterns.WEB_URL
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat.getDrawable
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOTimer
import app.core.bases.BaseFragment
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_ID_KEY
import app.core.engines.downloader.DownloadDataModel.Companion.THUMB_EXTENSION
import app.core.engines.settings.AIOSettings
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.video_parser.dialogs.VideoLinkPasteEditor
import app.ui.main.MotherActivity
import app.ui.main.fragments.browser.activities.BookmarksActivity
import app.ui.main.fragments.browser.activities.HistoryActivity
import app.ui.main.fragments.browser.webengine.WebViewEngine
import app.ui.main.guides.GuidePlatformPicker
import app.ui.others.media_player.MediaPlayerActivity
import app.ui.others.media_player.MediaPlayerActivity.Companion.INTENT_EXTRA_MEDIA_FILE_PATH
import com.aio.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.device.SecureFileUtil.authenticate
import lib.device.ShareUtility
import lib.device.ShareUtility.openApkFile
import lib.files.FileSystemUtility.endsWithExtension
import lib.files.FileSystemUtility.isArchiveByName
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isDocumentByName
import lib.files.FileSystemUtility.isImageByName
import lib.files.FileSystemUtility.isProgramByName
import lib.files.FileSystemUtility.isVideo
import lib.files.FileSystemUtility.isVideoByName
import lib.networks.URLUtility
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.IntentHelperUtils.openDailymotionApp
import lib.process.IntentHelperUtils.openFacebookApp
import lib.process.IntentHelperUtils.openInstagramApp
import lib.process.IntentHelperUtils.openPinterestApp
import lib.process.IntentHelperUtils.openRedditApp
import lib.process.IntentHelperUtils.openSoundCloudApp
import lib.process.IntentHelperUtils.openTedTalksApp
import lib.process.IntentHelperUtils.openTikTokApp
import lib.process.IntentHelperUtils.openWhatsappApp
import lib.process.IntentHelperUtils.openXApp
import lib.process.IntentHelperUtils.openYouTubeApp
import lib.process.IntentHelperUtils.openYouTubeMusicApp
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import lib.ui.ActivityAnimator.animActivityFade
import lib.ui.ViewUtility.getThumbnailFromFile
import lib.ui.ViewUtility.hideOnScreenKeyboard
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.saveBitmapToFile
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.ViewUtility.showView
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference
import java.net.URLEncoder.encode
import java.nio.charset.StandardCharsets.UTF_8

/**
 * HomeFragment is the main landing page of the application, responsible for providing quick access
 * to favorite sites, managing downloads, and offering navigation to key sections like History and Bookmarks.
 * It includes a URL input field with a download button for adding new downloads, and displays both active
 * and recent downloads with periodic updates through the AIOTimer system.
 *
 * The fragment can open supported apps such as YouTube, Facebook, and Instagram if installed, or fall
 * back to browser navigation when needed. Favorite sites and recent downloads are shown using RecyclerViews
 * with GridLayoutManagers, while media files can be played directly using MediaPlayerActivity.
 *
 * It handles various UI states including loading, empty, and populated views, ensuring smooth interaction for the user.
 * Throughout its lifecycle, it registers itself with MotherActivity and AIOTimer when resumed, and unregisters
 * while clearing adapters on pause or destruction to prevent memory leaks.
 *
 * @see BaseFragment
 * @see AIOTimer.AIOTimerListener
 */
class HomeFragment : BaseFragment(), AIOTimer.AIOTimerListener {

	private val logger = LogHelperUtils.from(javaClass)

	// Weak references to prevent memory leaks
	private val safeMotherActivityRef by lazy {
		WeakReference(safeBaseActivityRef as MotherActivity).get()
	}

	private val safeHomeFragmentRef by lazy {
		WeakReference(this).get()
	}

	// State tracking variables
	var isParsingTitleFromUrlAborted = false
	var lastCheckedFinishedTasksSize = 0
	var lastCheckedActiveTasksSize = 0

	/**
	 * Returns the layout resource ID for this fragment.
	 * @return The layout resource ID (R.layout.frag_home_1_main_1)
	 */
	override fun getLayoutResId(): Int {
		return R.layout.frag_home_1_main_1
	}

	/**
	 * Called after the fragment's layout is loaded.
	 * @param layoutView The inflated layout view
	 * @param state The saved instance state bundle
	 */
	override fun onAfterLayoutLoad(layoutView: View, state: Bundle?) {
		setupPasteVideoLinkEditor(layoutView)
		setupHistoryAndBookmarks(layoutView)
		setupFavoriteSitesAdapter(layoutView)
	}

	/**
	 * Called when the fragment resumes.
	 * Registers with timer and updates references.
	 */
	override fun onResumeFragment() {
		registerUIElementsToDownloadUIManager()
		registerSelfReferenceInMotherActivity()
		safeHomeFragmentRef?.let { AIOApp.aioTimer.register(it) }
	}

	/**
	 * Called when the fragment pauses.
	 * Unregisters from timer.
	 */
	override fun onPauseFragment() {
		safeHomeFragmentRef?.let { AIOApp.aioTimer.unregister(it) }
	}

	/**
	 * Called when the fragment view is destroyed.
	 * Cleans up references and adapters.
	 */
	override fun onDestroyView() {
		unregisterUIElementsToDownloadUIManager()
		unregisterSelfReferenceInMotherActivity()
		clearFragmentAdapterFromMemory()
		super.onDestroyView()
	}

	/**
	 * Timer callback that updates the UI periodically.
	 * @param loopCount The current timer loop count
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		safeFragmentLayoutRef?.let { layout ->
			try {
				updateDownloadsUI(layout)
			} catch (error: Exception) {
				error.printStackTrace()
			}
		}
	}

	fun refreshRecentDownloadListUI() {
		safeHomeFragmentRef?.safeFragmentLayoutRef?.let {
			setupRecentDownloadsSitesAdapter(it)
		}
	}

	/**
	 * Updates the downloads-related UI components.
	 * @param fragmentLayout The fragment's root view
	 */
	private fun updateDownloadsUI(fragmentLayout: View) {
		val activeDownloadsContainer = fragmentLayout.findViewById<View>(R.id.container_active_downloads)
		val recentContainer = fragmentLayout.findViewById<View>(R.id.container_recent_downloads)
		val emptyDownloadContainer = fragmentLayout.findViewById<View>(R.id.container_empty_downloads)
		val buttonHowToDownload = fragmentLayout.findViewById<View>(R.id.btn_how_to_download)
		val loadingDownloadsContainer = fragmentLayout.findViewById<View>(R.id.container_loading_downloads)

		buttonHowToDownload.setOnClickListener { GuidePlatformPicker(safeMotherActivityRef).show() }
		val finishedDownloadModels = downloadSystem.finishedDownloadDataModels
		val activeDownloadModels = downloadSystem.activeDownloadDataModels

		updateFinishedDownloadsUI(
			finishedDownloadModels = finishedDownloadModels,
			recentContainer = recentContainer,
			emptyDownloadContainer = emptyDownloadContainer,
			loadingDownloadsContainer = loadingDownloadsContainer,
			fragmentLayout = fragmentLayout
		)

		updateActiveDownloadsUI(
			activeDownloadModels = activeDownloadModels,
			activeDownloadsContainer = activeDownloadsContainer,
			fragmentLayout = fragmentLayout
		)
	}

	/**
	 * Updates the UI for finished downloads section.
	 * @param finishedDownloadModels List of finished download models
	 * @param recentContainer View container for recent downloads
	 * @param emptyDownloadContainer View container for empty state
	 * @param loadingDownloadsContainer View container for loading state
	 * @param fragmentLayout The fragment's root view
	 */
	private fun updateFinishedDownloadsUI(
		finishedDownloadModels: List<DownloadDataModel>,
		recentContainer: View,
		emptyDownloadContainer: View,
		loadingDownloadsContainer: View,
		fragmentLayout: View
	) {
		if (downloadSystem.isInitializing && finishedDownloadModels.isEmpty()) {
			showView(loadingDownloadsContainer, true, 300)
		} else {
			CoroutineScope(Dispatchers.Main).launch {
				hideView(loadingDownloadsContainer, true, 300)
				delay(300)
				if (finishedDownloadModels.isEmpty()) {
					lastCheckedFinishedTasksSize = 0
					hideView(recentContainer, true, 300)
					showView(emptyDownloadContainer, true, 100)
				} else {
					hideView(emptyDownloadContainer, true, 100)
					showView(recentContainer, true, 300)
					if (finishedDownloadModels.size != lastCheckedFinishedTasksSize) {
						lastCheckedFinishedTasksSize = finishedDownloadModels.size
						setupRecentDownloadsSitesAdapter(fragmentLayout)
					}
				}
			}
		}
	}

	/**
	 * Updates the UI for active downloads section.
	 * @param activeDownloadModels List of active download models
	 * @param activeDownloadsContainer View container for active downloads
	 * @param fragmentLayout The fragment's root view
	 */
	private fun updateActiveDownloadsUI(
		activeDownloadModels: List<DownloadDataModel>,
		activeDownloadsContainer: View,
		fragmentLayout: View
	) {
		if (activeDownloadModels.isEmpty()) {
			lastCheckedActiveTasksSize = 0
			hideView(activeDownloadsContainer, true, 300).let {
				activeDownloadsContainer.visibility = GONE
			}
		} else {
			showView(activeDownloadsContainer, true, 300)
			if (activeDownloadModels.size != lastCheckedActiveTasksSize) {
				lastCheckedActiveTasksSize = activeDownloadModels.size
				updateActiveDownloadsInfo(fragmentLayout, activeDownloadModels.size)
				setupActiveDownloadsClickListener(fragmentLayout)
			}
		}
	}

	/**
	 * Updates the active downloads info text.
	 * @param layout The fragment's root view
	 * @param activeDownloadsCount Number of active downloads
	 */
	private fun updateActiveDownloadsInfo(layout: View, activeDownloadsCount: Int) {
		val activeDownloadsInfo = layout.findViewById<TextView>(R.id.txt_active_downloads_info)
		val string = getString(
			R.string.title_you_have_b_active_downloads_b,
			activeDownloadsCount.toString()
		)
		activeDownloadsInfo.text = string
	}

	/**
	 * Sets up click listener for active downloads button.
	 * @param layout The fragment's root view
	 */
	private fun setupActiveDownloadsClickListener(layout: View) {
		with(layout.findViewById<View>(R.id.btn_open_active_downloads)) {
			setOnClickListener {
				safeMotherActivityRef?.downloadFragment?.openActiveTab()?.let {
					safeMotherActivityRef?.openDownloadsFragment()
				}
			}
		}
	}

	private fun registerUIElementsToDownloadUIManager() {
		try {
			safeFragmentLayoutRef?.let { layout ->
				val loadingProgressTextview = layout.findViewById<TextView>(R.id.txt_progress_info)
				val downloadsUIManager = downloadSystem.downloadsUIManager
				downloadsUIManager.loadingDownloadModelTextview = loadingProgressTextview
			}
		} catch (error: Exception) {
			logger.e("Error found while registering ui elements to download ui manager:", error)
		}
	}

	private fun unregisterUIElementsToDownloadUIManager() {
		try {
			safeFragmentLayoutRef?.let { layout ->
				val loadingProgressTextview = layout.findViewById<TextView>(R.id.txt_progress_info)
				val downloadsUIManager = downloadSystem.downloadsUIManager
				downloadsUIManager.loadingDownloadModelTextview = loadingProgressTextview
			}
		} catch (error: Exception) {
			logger.e("Error found while registering ui elements to download ui manager:", error)
		}
	}

	/**
	 * Registers this fragment with the parent activity.
	 */
	private fun registerSelfReferenceInMotherActivity() {
		safeMotherActivityRef?.homeFragment = safeHomeFragmentRef
		safeMotherActivityRef?.sideNavigation?.closeDrawerNavigation()
	}

	/**
	 * Unregisters this fragment from the parent activity.
	 */
	private fun unregisterSelfReferenceInMotherActivity() {
		safeMotherActivityRef?.homeFragment = null
	}

	/**
	 * Clears adapter references to prevent memory leaks.
	 */
	private fun clearFragmentAdapterFromMemory() {
		safeFragmentLayoutRef?.apply {
			val faviconList = findViewById<RecyclerView>(R.id.favicons_recycler_list)
			val recentDownloadsList = findViewById<RecyclerView>(R.id.recent_downloads_recycle_list)
			faviconList.adapter = null
			recentDownloadsList.adapter = null
		}
	}

	/**
	 * Sets up the URL input editor and download button.
	 * @param layoutView The fragment's root view
	 */
	private fun setupPasteVideoLinkEditor(layoutView: View) {
		safeMotherActivityRef?.let { safeMotherActivity ->
			with(layoutView) {
				val editFiledUrlContainer = findViewById<View>(R.id.edit_url_container)
				val editFiledUrl = findViewById<EditText>(R.id.edit_file_url)
				val buttonDownload = findViewById<View>(R.id.btn_add_download)

				setupUrlEditor(editFiledUrlContainer, editFiledUrl, safeMotherActivity)
				setupDownloadButton(buttonDownload, editFiledUrl, safeMotherActivity)
			}
		}
	}

	/**
	 * Sets up the URL editor with click listeners.
	 * @param container The container view
	 * @param editText The URL input field
	 * @param activity The parent activity
	 */
	private fun setupUrlEditor(
		container: View,
		editText: EditText,
		activity: MotherActivity
	) {
		container.setOnClickListener {
			editText.focusable
			editText.selectAll()
			showOnScreenKeyboard(activity, editText)
		}
	}

	/**
	 * Sets up the download button with click listeners.
	 * @param button The download button
	 * @param editText The URL input field
	 * @param activity The parent activity
	 */
	private fun setupDownloadButton(
		button: View,
		editText: EditText,
		activity: MotherActivity
	) {
		editText.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE ||
				actionId == EditorInfo.IME_ACTION_SEARCH
			) {
				safeMotherActivityRef?.let {
					hideOnScreenKeyboard(activity, editText)
					val userEnteredUrl = editText.text.toString()
					val webviewEngine = it.browserFragment?.getBrowserWebEngine()
					if (webviewEngine != null) {
						var urlToLoad = userEnteredUrl
						urlToLoad = (if (WEB_URL.matcher(urlToLoad).matches()) urlToLoad
						else "https://www.google.com/search?q=${
							encode(
								urlToLoad,
								UTF_8.toString()
							)
						}")
						it.sideNavigation?.addNewBrowsingTab(urlToLoad, webviewEngine)
						it.openBrowserFragment()
					}
				}
				true
			} else false
		}

		button.setOnClickListener {
			try {
				hideOnScreenKeyboard(activity, editText)
				val userEnteredUrl = editText.text.toString()
				if (URLUtility.isValidURL(userEnteredUrl)) {
					VideoLinkPasteEditor(
						motherActivity = activity,
						passOnUrl = userEnteredUrl,
						autoStart = true
					).show()
					editText.setText(getString(R.string.text_empty_string))
				} else {
					activity.doSomeVibration(50)
					safeMotherActivityRef?.let {
						val webviewEngine = it.browserFragment?.getBrowserWebEngine()
						if (webviewEngine != null) {
							var urlToLoad = userEnteredUrl
							urlToLoad = (if (WEB_URL.matcher(urlToLoad).matches()) urlToLoad
							else "https://www.google.com/search?q=${
								encode(
									urlToLoad,
									UTF_8.toString()
								)
							}")
							it.sideNavigation?.addNewBrowsingTab(urlToLoad, webviewEngine)
							it.openBrowserFragment()
						}
					}
				}
			} catch (error: Exception) {
				error.printStackTrace()
				activity.doSomeVibration(50)
				showToast(
					activityInf = safeMotherActivityRef,
					msgId = R.string.title_something_went_wrong
				)
			}
		}
	}

	/**
	 * Sets up history and bookmarks buttons with click listeners.
	 * @param layoutView The fragment's root view
	 */
	private fun setupHistoryAndBookmarks(layoutView: View) {
		val buttonHistory = layoutView.findViewById<View>(R.id.btn_open_history)
		val buttonBookmark = layoutView.findViewById<View>(R.id.btn_open_bookmark)

		setupHistoryButton(buttonHistory)
		setupBookmarksButton(buttonBookmark)
	}

	/**
	 * Sets up the history button click listener.
	 * @param button The history button
	 */
	private fun setupHistoryButton(button: View) {
		button.setOnClickListener {
			safeMotherActivityRef?.let { motherActivity ->
				val input = Intent(motherActivity, HistoryActivity::class.java)
				motherActivity.resultLauncher.launch(input)
			}
		}
	}

	/**
	 * Sets up the bookmarks button click listener.
	 * @param button The bookmarks button
	 */
	private fun setupBookmarksButton(button: View) {
		button.setOnClickListener {
			safeMotherActivityRef?.let { motherActivity ->
				val input = Intent(motherActivity, BookmarksActivity::class.java)
				motherActivity.resultLauncher.launch(input)
			}
		}
	}

	/**
	 * Sets up the favorite sites grid adapter.
	 * @param layoutView The fragment's root view
	 */
	private fun setupFavoriteSitesAdapter(layoutView: View) {
		with(layoutView) {
			val recyclerView = findViewById<RecyclerView>(R.id.favicons_recycler_list)
			val favicons = createFaviconList()

			recyclerView.layoutManager = GridLayoutManager(safeMotherActivityRef, 4)
			recyclerView.adapter = createFaviconAdapter(favicons)
		}
	}

	/**
	 * Creates the list of favorite site icons and titles.
	 * @return List of Pair<Int, Int> where first is icon resource ID, second is title string ID
	 */
	private fun createFaviconList(): List<Pair<Int, Int>> {
		return listOf(
			Pair(first = R.drawable.ic_site_youtube, second = R.string.title_youtube),
			Pair(first = R.drawable.ic_site_facebook, second = R.string.title_facebook),
			Pair(first = R.drawable.ic_site_instagram, second = R.string.title_instagram),
			Pair(first = R.drawable.ic_site_whatsapp, second = R.string.title_whatsapp),
			Pair(first = R.drawable.ic_site_youtubemusic, second = R.string.title_youtube_music),
			Pair(first = R.drawable.ic_site_soundcloud, second = R.string.title_soundcloud),
			Pair(first = R.drawable.ic_site_pinterest, second = R.string.title_pinterest),
			Pair(first = R.drawable.ic_site_tiktok, second = R.string.title_tiktok),
			Pair(first = R.drawable.ic_site_dailymotion, second = R.string.title_dailymotion),
			Pair(first = R.drawable.ic_site_reddit, second = R.string.title_reddit),
			Pair(first = R.drawable.ic_site_twitter, second = R.string.title_x_com),
			Pair(first = R.drawable.ic_site_tedtalks, second = R.string.title_tedtalks),
		)
	}

	/**
	 * Creates the adapter for favorite sites recycler view.
	 * @param favicons List of favorite site icons and titles
	 * @return RecyclerView.Adapter for the favorite sites grid
	 */
	private fun createFaviconAdapter(favicons: List<Pair<Int, Int>>): RecyclerView.Adapter<FaviconViewHolder> {
		return object : RecyclerView.Adapter<FaviconViewHolder>() {
			override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaviconViewHolder {
				val view = LayoutInflater.from(parent.context)
					.inflate(R.layout.frag_home_1_main_1_fav_item_1, parent, false)
				return FaviconViewHolder(view) { siteName ->
					handleFaviconClick(siteName)
				}
			}

			override fun onBindViewHolder(holder: FaviconViewHolder, position: Int) {
				holder.setImageFavicon(favicons[position].first)
				holder.setFaviconTitle(favicons[position].second)
			}

			override fun getItemCount() = favicons.size
		}
	}

	/**
	 * Handles click events for favorite site icons.
	 * @param siteName The name of the clicked site
	 */
	private fun handleFaviconClick(siteName: String) {
		val siteMap = createSiteUrlMap()
		siteMap.entries.firstOrNull { siteName.contains(it.key, ignoreCase = true) }?.let { match ->
			safeMotherActivityRef?.let { activity ->
				val webviewEngine = activity.browserFragment?.getBrowserWebEngine()
				webviewEngine?.let {
					when (match.key) {
						getText(R.string.title_youtube).toString() -> {
							openYouTubeApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_facebook).toString() -> {
							openFacebookApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_instagram).toString() -> {
							openInstagramApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_whatsapp).toString() -> {
							openWhatsappApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_youtube_music).toString() -> {
							openYouTubeMusicApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_soundcloud).toString() -> {
							openSoundCloudApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_pinterest).toString() -> {
							openPinterestApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_tiktok).toString() -> {
							openTikTokApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_dailymotion).toString() -> {
							openDailymotionApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_reddit).toString() -> {
							openRedditApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_x_com).toString() -> {
							openXApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						getText(R.string.title_tedtalks).toString() -> {
							openTedTalksApp(INSTANCE) {
								handleFallbackBrowserNavigation(activity, match.value, it)
							}
						}

						else -> {
							activity.sideNavigation?.addNewBrowsingTab(match.value, it)
							activity.openBrowserFragment()
						}
					}
				}
			}
		}
	}

	/**
	 * Creates a mapping of site names to their URLs.
	 * @return Map of site names to URLs
	 */
	private fun createSiteUrlMap(): Map<String, String> {
		return mapOf(
			getText(R.string.title_youtube).toString() to "https://youtube.com/",
			getText(R.string.title_facebook).toString() to "https://facebook.com/",
			getText(R.string.title_instagram).toString() to "https://instagram.com/",
			getText(R.string.title_whatsapp).toString() to "https://web.whatsapp.com/",
			getText(R.string.title_youtube_music).toString() to "https://music.youtube.com/",
			getText(R.string.title_soundcloud).toString() to "https://soundcloud.com/",
			getText(R.string.title_pinterest).toString() to "https://pinterest.com/",
			getText(R.string.title_tiktok).toString() to "https://tiktok.com/",
			getText(R.string.title_dailymotion).toString() to "https://dailymotion.com/",
			getText(R.string.title_reddit).toString() to "https://reddit.com/",
			getText(R.string.title_x_com).toString() to "https://x.com/",
			getText(R.string.title_tedtalks).toString() to "https://ted.com/talks/",

			//Adult sites
			getText(R.string.title_xhamster).toString() to "https://xhamster.com/",
			getText(R.string.title_redwap).toString() to "https://redwap.sex/",
			getText(R.string.title_hentaicity).toString() to "https://hentaicity.com/",
			getText(R.string.title_xvideos).toString() to "https://xvideos.com/",
			getText(R.string.title_pornhub).toString() to "https://pornhub.org/",
			getText(R.string.title_pornhub).toString() to "https://pornhub.org/",
		)
	}

	/**
	 * Handles fallback browser navigation when native app isn't available.
	 * @param activity The parent activity
	 * @param url The URL to navigate to
	 * @param webviewEngine The webview engine instance
	 */
	private fun handleFallbackBrowserNavigation(
		activity: MotherActivity,
		url: String,
		webviewEngine: WebViewEngine
	) {
		activity.sideNavigation?.addNewBrowsingTab(url, webviewEngine)
		activity.openBrowserFragment()
		activity.doSomeVibration(50)
	}

	/**
	 * ViewHolder for favorite site items.
	 * @param holderLayoutView The item view
	 * @param onItemClick Callback when item is clicked
	 */
	open class FaviconViewHolder(
		private val holderLayoutView: View,
		private val onItemClick: (String) -> Unit
	) : RecyclerView.ViewHolder(holderLayoutView) {
		open val image: ImageView = holderLayoutView.findViewById(R.id.img_site_favicon)
		open val title: TextView = holderLayoutView.findViewById(R.id.txt_favicon_site_name)

		init {
			holderLayoutView.setOnClickListener {
				onItemClick(title.text.toString())
			}
		}

		/**
		 * Sets the favicon image.
		 * @param resId The resource ID of the image
		 */
		fun setImageFavicon(resId: Int) {
			image.setImageResource(resId)
		}

		/**
		 * Sets the favicon title.
		 * @param resId The resource ID of the title string
		 */
		fun setFaviconTitle(resId: Int) {
			title.text = getText(resId)
		}
	}

	/**
	 * Sets up the recent downloads grid adapter.
	 * @param layoutView The fragment's root view
	 */
	private fun setupRecentDownloadsSitesAdapter(layoutView: View) {
		with(layoutView) {
			if (downloadSystem.isInitializing) return
			val downloadsModels = getRecentDownloadModels()
			val recyclerView = findViewById<RecyclerView>(R.id.recent_downloads_recycle_list)
			setupRecentDownloadsRecycler(recyclerView, downloadsModels)
			setupOpenDownloadsClickListener(layoutView)
		}
	}

	/**
	 * Gets the list of recent download models to display.
	 * @return List of recent DownloadDataModel objects
	 */
	private fun getRecentDownloadModels(): List<DownloadDataModel> {
		return downloadSystem.finishedDownloadDataModels.filter {
			it.globalSettings.defaultDownloadLocation == AIOSettings.SYSTEM_GALLERY
		}.take(9)
	}

	/**
	 * Sets up the recycler view for recent downloads.
	 * @param recyclerView The RecyclerView instance
	 * @param downloadsModels List of download models to display
	 */
	private fun setupRecentDownloadsRecycler(
		recyclerView: RecyclerView,
		downloadsModels: List<DownloadDataModel>
	) {
		recyclerView.layoutManager = GridLayoutManager(safeMotherActivityRef, 3)
		recyclerView.adapter = null
		recyclerView.adapter = createRecentDownloadsAdapter(downloadsModels)
		showView(targetView = recyclerView, shouldAnimate = true)
	}

	/**
	 * Creates the adapter for recent downloads recycler view.
	 * @param downloadsModels List of download models to display
	 * @return RecyclerView.Adapter for the recent downloads grid
	 */
	private fun createRecentDownloadsAdapter(
		downloadsModels: List<DownloadDataModel>
	): RecyclerView.Adapter<RecentDownloadsViewHolder> {
		return object : RecyclerView.Adapter<RecentDownloadsViewHolder>() {
			override fun onCreateViewHolder(
				parent: ViewGroup,
				viewType: Int
			): RecentDownloadsViewHolder {
				val layoutInflater = LayoutInflater.from(parent.context)
				val layoutResId = R.layout.frag_home_1_main_1_recent_item_1
				val view = layoutInflater.inflate(layoutResId, parent, false)
				return RecentDownloadsViewHolder(view)
			}

			override fun onBindViewHolder(holder: RecentDownloadsViewHolder, position: Int) {
				holder.setImageThumbnail(downloadsModels[position])
				holder.setSiteImageFavicon(downloadsModels[position])
				holder.setFileTypeIndication(downloadsModels[position])
				holder.setPrivateFolderIndicator(downloadsModels[position])
				holder.setMediaDurationPreview(downloadsModels[position])
				holder.setOnClickEvent(downloadsModels[position], safeMotherActivityRef)
			}

			override fun getItemCount(): Int {
				return downloadsModels.size
			}
		}
	}

	/**
	 * Sets up click listener for "open downloads" button.
	 * @param layoutView The fragment's root view
	 */
	private fun setupOpenDownloadsClickListener(layoutView: View) {
		layoutView.findViewById<View>(R.id.open_downloads_tab)
			.setOnClickListener { safeMotherActivityRef?.openDownloadsFragment() }
	}

	/**
	 * ViewHolder for recent download items.
	 * @param holderLayoutView The item view
	 */
	class RecentDownloadsViewHolder(private val holderLayoutView: View) :
		RecyclerView.ViewHolder(holderLayoutView) {

		/** The media thumbnail ImageView showing video or file preview. */
		val thumbnail: ImageView = holderLayoutView.findViewById(R.id.img_file_thumbnail)

		/** The site favicon ImageView representing the source website. */
		val favicon: ImageView = holderLayoutView.findViewById(R.id.img_site_favicon)

		/** The media playback indicator ImageView (e.g., play icon overlay). */
		val mediaIndicator: ImageView = holderLayoutView.findViewById(R.id.img_media_play_indicator)

		/** The TextView displaying the media duration in minutes/seconds. */
		val duration: TextView = holderLayoutView.findViewById(R.id.txt_media_duration)

		/** Container view for the media duration, used for layout and visibility control. */
		val durationContainer: View = holderLayoutView.findViewById(R.id.container_media_duration)

		/** ImageView indicating the file type (e.g., video, audio, document). */
		val fileTypeIndicator: ImageView = holderLayoutView.findViewById(R.id.img_file_type_indicator)

		/** ImageView showing private folder status (locked/unlocked) for the download. */
		val privateFolderImageView: ImageView = holderLayoutView.findViewById(R.id.img_private_folder_indicator)

		/**
		 * Sets the click event for the download item.
		 * @param downloadDataModel The download data model
		 * @param safeMotherActivityRef The parent activity reference
		 */
		fun setOnClickEvent(
			downloadDataModel: DownloadDataModel,
			safeMotherActivityRef: MotherActivity?
		) {
			val activityRef = safeMotherActivityRef
			if (activityRef == null) return
			holderLayoutView.findViewById<View>(R.id.main_container).setOnClickListener {
				val globalSettings = downloadDataModel.globalSettings
				val downloadLocation = globalSettings.defaultDownloadLocation
				if (downloadLocation == PRIVATE_FOLDER) {
					authenticate(activity = activityRef, onResult = { isSuccess ->
						if (isSuccess) playTheMedia(downloadDataModel, activityRef)
					})
				} else playTheMedia(downloadDataModel, activityRef)
			}
		}

		/**
		 * Sets the thumbnail image for the download item.
		 * @param downloadDataModel The download data model
		 */
		fun setImageThumbnail(downloadDataModel: DownloadDataModel) {
			updateThumbnailInfo(downloadDataModel)
		}

		/**
		 * Sets the site favicon image for the download item.
		 *
		 * @param downloadDataModel The download data model containing the site referrer.
		 */
		fun setSiteImageFavicon(downloadDataModel: DownloadDataModel) {
			updateFaviconInfo(downloadDataModel)
		}

		/**
		 * Updates the file type indicator icon in the UI based on the file type
		 * detected from the download model's file name.
		 *
		 * @param downloadDataModel The model containing information about the downloaded file
		 */
		fun setFileTypeIndication(downloadDataModel: DownloadDataModel) {
			// Determine the correct icon by checking file type via file name
			fileTypeIndicator.setImageResource(
				when {
					isImageByName(downloadDataModel.fileName) -> R.drawable.ic_button_images   // Image files
					isAudioByName(downloadDataModel.fileName) -> R.drawable.ic_button_audio    // Audio files
					isVideoByName(downloadDataModel.fileName) -> R.drawable.ic_button_video    // Video files
					isDocumentByName(downloadDataModel.fileName) -> R.drawable.ic_button_document // Documents
					isArchiveByName(downloadDataModel.fileName) -> R.drawable.ic_button_archives  // Archives
					isProgramByName(downloadDataModel.fileName) -> R.drawable.ic_button_programs  // Executables/programs
					else -> R.drawable.ic_button_file // Default for unknown file types
				}
			)
		}

		/**
		 * Refreshes the private folder indicator in the dialog UI.
		 *
		 * Updates the icon and checkbox to reflect whether downloads are
		 * currently set to a private (locked) folder or a public directory.
		 * Ensures the UI accurately represents the user's active download
		 * location preference.
		 *
		 * @param downloadModel The [DownloadDataModel] containing
		 * the dialog's private folder indicator and settings.
		 */
		fun setPrivateFolderIndicator(downloadModel: DownloadDataModel) {
			val downloadLocation = downloadModel.globalSettings.defaultDownloadLocation

			// Update indicator icon based on folder type
			privateFolderImageView.setImageResource(
				when (downloadLocation) {
					PRIVATE_FOLDER -> R.drawable.ic_button_lock  // Private folder
					else -> R.drawable.ic_button_folder         // Normal folder
				}
			)
		}

		/**
		 * Sets the media duration for the download item.
		 * @param downloadDataModel The download data model
		 */
		fun setMediaDurationPreview(downloadDataModel: DownloadDataModel) {
			ThreadsUtility.executeInBackground(codeBlock = {
				val downloadedFileName = downloadDataModel.fileName
				if (isVideoByName(downloadedFileName) || isAudioByName(downloadedFileName)) {
					repeat(5) { attempt ->
						Thread.sleep(300) // wait 300 millisecond between retries
						val cleaned = downloadDataModel.mediaFilePlaybackDuration
							.replace("(", "")
							.replace(")", "")
						if (cleaned.isNotEmpty()) {
							ThreadsUtility.executeOnMain {
								duration.text = cleaned
								showView(durationContainer, true)
								showView(mediaIndicator, true)
							}
							return@executeInBackground // stop retries once valid
						}
					}
					// optional: handle case when still empty after retries
					ThreadsUtility.executeOnMain {
						showView(durationContainer, false)
						showView(mediaIndicator, false)
					}
				}
			})
		}

		/**
		 * Plays the media associated with the download.
		 * @param downloadDataModel The download data model
		 * @param safeMotherActivityRef The parent activity reference
		 */
		@OptIn(UnstableApi::class)
		private fun playTheMedia(
			downloadDataModel: DownloadDataModel,
			safeMotherActivityRef: MotherActivity?
		) {
			safeMotherActivityRef?.let { _ ->
				downloadDataModel.let { downloadDataModel ->
					val downloadedFileName = downloadDataModel.fileName
					if (isVideoByName(downloadedFileName) || isAudioByName(downloadedFileName)) {
						safeMotherActivityRef.startActivity(
							Intent(safeMotherActivityRef, MediaPlayerActivity::class.java).apply {
								flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
								putExtra(DOWNLOAD_MODEL_ID_KEY, downloadDataModel.downloadId)
								putExtra(
									/* name = */ INTENT_EXTRA_MEDIA_FILE_PATH,
									/* value = */ downloadDataModel.getDestinationFile().path)
							})
						animActivityFade(safeMotherActivityRef)
					} else {
						// For non-media files, just open them
						openFile(safeMotherActivityRef, downloadDataModel)
					}
				}
			}
		}

		/**
		 * Opens the downloaded file using appropriate application.
		 * Handles APK files specially with proper installation flow.
		 */
		fun openFile(safeMotherActivityRef: MotherActivity, downloadDataModel: DownloadDataModel) {
			val extensions = listOf("apk").toTypedArray()
			val downloadedFile = downloadDataModel.getDestinationFile()
			if (endsWithExtension(downloadDataModel.fileName, extensions)) {
				// Special handling for APK files
				val authority = "${safeMotherActivityRef.packageName}.provider"
				val apkFile = downloadedFile
				openApkFile(safeMotherActivityRef, apkFile, authority)
			} else {
				// Open other file types normally
				ShareUtility.openFile(downloadedFile, safeMotherActivityRef)
			}
		}

		/**
		 * Loads and sets the favicon for the given download item.
		 *
		 * Attempts to load a favicon from cache (via AIOApp.aioFavicons). If unavailable,
		 * falls back to a default drawable.
		 *
		 * @param downloadDataModel The download data model containing the site referrer.
		 */
		private fun updateFaviconInfo(downloadDataModel: DownloadDataModel) {
			val defaultFaviconResId = R.drawable.ic_image_default_favicon
			val defaultFaviconDrawable = getDrawable(INSTANCE.resources, defaultFaviconResId, null)
			if (isVideoThumbnailNotAllowed(downloadDataModel)) {
				favicon.setImageDrawable(defaultFaviconDrawable)
				showView(favicon, true)
				return
			}

			ThreadsUtility.executeInBackground(codeBlock = {
				val referralSite = downloadDataModel.siteReferrer
				aioFavicons.getFavicon(referralSite)?.let { faviconFilePath ->
					val faviconImgFile = File(faviconFilePath)
					if (!faviconImgFile.exists() || !faviconImgFile.isFile) return@executeInBackground
					val faviconImgURI = faviconImgFile.toUri()
					ThreadsUtility.executeOnMain(codeBlock = {
						showView(favicon, true)
						try {
							favicon.setImageURI(faviconImgURI)
						} catch (error: Exception) {
							error.printStackTrace()
							favicon.setImageResource(defaultFaviconResId)
						}
					})
				}
			}, errorHandler = {
				it.printStackTrace()
			})
		}

		/**
		 * Updates the thumbnail information for the download item.
		 * @param downloadDataModel The download data model
		 */
		private fun updateThumbnailInfo(downloadDataModel: DownloadDataModel) {
			val destinationFile = downloadDataModel.getDestinationFile()
			val defaultThumb = downloadDataModel.getThumbnailDrawableID()
			val defaultThumbDrawable = getDrawable(INSTANCE.resources, defaultThumb, null)

			if (isVideoThumbnailNotAllowed(downloadDataModel)) {
				thumbnail.setImageDrawable(defaultThumbDrawable)
				return
			}

			if (loadApkThumbnail(
					downloadDataModel = downloadDataModel,
					imageViewHolder = thumbnail,
					defaultThumbDrawable = defaultThumbDrawable
				)
			) return

			loadThumbnailFromFile(downloadDataModel, destinationFile, defaultThumb)
		}

		/**
		 * Loads thumbnail from file or generates a new one.
		 * @param downloadDataModel The download data model
		 * @param destinationFile The downloaded file
		 * @param defaultThumb Default thumbnail resource ID
		 */
		private fun loadThumbnailFromFile(
			downloadDataModel: DownloadDataModel,
			destinationFile: File,
			defaultThumb: Int
		) {
			executeInBackground {
				val cachedThumbPath = downloadDataModel.thumbPath
				if (cachedThumbPath.isNotEmpty()) {
					executeOnMainThread {
						loadBitmapWithGlide(
							thumbFilePath = downloadDataModel.thumbPath,
							defaultThumb = defaultThumb
						)
					}
					return@executeInBackground
				}

				generateAndSaveThumbnail(downloadDataModel, destinationFile, defaultThumb)
			}
		}

		/**
		 * Generates and saves a thumbnail for the download.
		 * @param downloadDataModel The download data model
		 * @param destinationFile The downloaded file
		 * @param defaultThumb Default thumbnail resource ID
		 */
		private fun generateAndSaveThumbnail(
			downloadDataModel: DownloadDataModel,
			destinationFile: File,
			defaultThumb: Int
		) {
			val bitmap = getThumbnailFromFile(
				targetFile = destinationFile,
				thumbnailUrl = downloadDataModel.videoInfo?.videoThumbnailUrl,
				requiredThumbWidth = 420
			)

			if (bitmap != null) {
				val rotatedBitmap = if (bitmap.height > bitmap.width) {
					rotateBitmap(bitmap, 270f)
				} else bitmap

				saveThumbnailFile(downloadDataModel, rotatedBitmap, defaultThumb)
			}
		}

		/**
		 * Saves the generated thumbnail to file.
		 * @param downloadDataModel The download data model
		 * @param bitmap The generated bitmap
		 * @param defaultThumb Default thumbnail resource ID
		 */
		private fun saveThumbnailFile(
			downloadDataModel: DownloadDataModel,
			bitmap: Bitmap,
			defaultThumb: Int
		) {
			val thumbnailName = "${downloadDataModel.downloadId}$THUMB_EXTENSION"
			saveBitmapToFile(bitmap, thumbnailName)?.let { filePath ->
				downloadDataModel.thumbPath = filePath
				downloadDataModel.updateInStorage()
				executeOnMainThread {
					loadBitmapWithGlide(downloadDataModel.thumbPath, defaultThumb)
				}
			}
		}

		/**
		 * Loads the thumbnail image using Glide.
		 * @param thumbFilePath The path to the thumbnail file
		 * @param defaultThumb The default thumbnail resource ID
		 */
		private fun loadBitmapWithGlide(thumbFilePath: String, defaultThumb: Int) {
			val imgURI = File(thumbFilePath).toUri()
			try {
				thumbnail.setImageURI(imgURI)
			} catch (error: Exception) {
				error.printStackTrace()
				thumbnail.setImageResource(defaultThumb)
			}
		}

		/**
		 * Checks if video thumbnails are allowed for this download.
		 * @param downloadDataModel The download data model
		 * @return true if thumbnails are not allowed, false otherwise
		 */
		private fun isVideoThumbnailNotAllowed(downloadDataModel: DownloadDataModel): Boolean {
			val isVideoHidden = downloadDataModel.globalSettings.downloadHideVideoThumbnail
			return isVideo(downloadDataModel.getDestinationDocumentFile()) && isVideoHidden
		}

		/**
		 * Loads an APK file's icon as its thumbnail.
		 * @param downloadDataModel The download data model
		 * @param imageViewHolder The ImageView to display the icon
		 * @param defaultThumbDrawable The default thumbnail to use if APK icon can't be loaded
		 * @return true if APK icon was loaded successfully, false otherwise
		 */
		private fun loadApkThumbnail(
			downloadDataModel: DownloadDataModel,
			imageViewHolder: ImageView,
			defaultThumbDrawable: Drawable?
		): Boolean {
			val apkFile = downloadDataModel.getDestinationFile()
			if (!apkFile.exists() || !apkFile.name.lowercase().endsWith(".apk")) {
				imageViewHolder.setImageDrawable(defaultThumbDrawable)
				return false
			}

			val packageManager: PackageManager = INSTANCE.packageManager
			return try {
				val packageInfo: PackageInfo? = packageManager.getPackageArchiveInfo(
					apkFile.absolutePath, PackageManager.GET_ACTIVITIES
				)
				packageInfo?.applicationInfo?.let { appInfo ->
					appInfo.sourceDir = apkFile.absolutePath
					appInfo.publicSourceDir = apkFile.absolutePath
					val icon: Drawable = appInfo.loadIcon(packageManager)
					imageViewHolder.setImageDrawable(icon)
					true
				} ?: false
			} catch (error: Exception) {
				error.printStackTrace()
				imageViewHolder.apply {
					scaleType = ImageView.ScaleType.FIT_CENTER
					setPadding(0, 0, 0, 0)
					setImageDrawable(defaultThumbDrawable)
				}
				false
			}
		}
	}
}