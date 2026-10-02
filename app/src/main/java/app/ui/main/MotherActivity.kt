package app.ui.main

import android.content.*
import androidx.activity.result.contract.ActivityResultContracts.*
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat.*
import androidx.lifecycle.*
import androidx.media3.common.util.*
import androidx.viewpager2.widget.*
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOKeyStrings.ACTIVITY_RESULT_KEY
import app.core.bases.*
import app.core.engines.downloader.DownloadNotification.Companion.FROM_DOWNLOAD_NOTIFICATION
import app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl
import app.core.engines.video_parser.parsers.SupportedURLs.isYtdlpSupportedUrl
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.main.fragments.browser.*
import app.ui.main.fragments.browser.webengine.*
import app.ui.main.fragments.downloads.*
import app.ui.main.fragments.downloads.intercepter.*
import app.ui.main.fragments.home.*
import app.ui.main.fragments.settings.*
import app.ui.others.media_player.MediaPlayerActivity.Companion.INTENT_EXTRA_SOURCE_ORIGIN
import com.aio.*
import kotlinx.coroutines.*
import lib.device.IntentUtility.getIntentDataURI
import lib.networks.*
import lib.networks.URLUtilityKT.fetchWebPageContent
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.*
import lib.process.AsyncJobUtils.*
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.texts.ClipboardUtils.getTextFromClipboard
import lib.ui.*
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.builders.*
import java.lang.ref.*

/**
 * The main activity that hosts all fragments and manages navigation.
 *
 * Responsibilities:
 * - Hosts the main ViewPager for fragment navigation
 * - Manages bottom tab navigation
 * - Handles deep linking and intent URLs
 * - Monitors clipboard for URLs
 * - Manages back press behavior
 * - Coordinates between fragments
 */
class MotherActivity : BaseActivity() {
	
	private val logger = LogHelperUtils.from(javaClass)
	
	// Weak reference to avoid memory leaks
	private var weakMotherActivityRef = WeakReference(this)
	private val safeMotherActivityRef = weakMotherActivityRef.get()
	
	// ViewModel for sharing data between fragments
	private lateinit var sharedViewModel: SharedViewModel
	
	// UI Components
	private lateinit var fragmentViewPager: ViewPager2
	private lateinit var motherBottomTabs: MotherBottomTabs
	
	// Lazy initialized side navigation drawer
	val sideNavigation: WebNavigationDrawer? by lazy {
		logger.d("Initializing WebNavigationDrawer lazily")
		WebNavigationDrawer(safeMotherActivityRef).apply { initialize() }
	}
	
	// Activity result launcher
	val resultLauncher = registerResultOrientedActivityLauncher()
	
	// Fragment references
	var homeFragment: HomeFragment? = null
	var browserFragment: BrowserFragment? = null
	var downloadFragment: DownloadsFragment? = null
	var settingsFragment: SettingsFragment? = null
	
	// Flag for URL parsing
	var isParsingTitleFromUrlAborted = false
	
	/**
	 * Returns the layout resource ID for this activity
	 * @return The layout resource ID (R.layout.activity_mother_1)
	 */
	override fun onRenderingLayout(): Int {
		logger.d("Rendering layout for MotherActivity")
		return R.layout.activity_mother_1
	}
	
	/**
	 * Called after layout is rendered
	 * Sets up main components and shows initial ad
	 */
	override fun onAfterLayoutRender() {
		logger.d("After layout render in MotherActivity")
		safeMotherActivityRef?.let { _ ->
			setupFragmentViewpager()
			setupBottomTabs()
		}
	}
	
	/**
	 * Handles back press events
	 */
	override fun onBackPressActivity() {
		logger.d("Back button pressed in MotherActivity")
		handleBackPressEvent()
	}
	
	/**
	 * Called when activity resumes
	 * Sets up monitoring and handles pending intents
	 */
	@OptIn(UnstableApi::class)
	override fun onResumeActivity() {
		logger.d("MotherActivity onResume")
		monitorClipboardForUrls()
		handleIntentURL()
		openDownloadsFragmentIfIntended()
		updateButtonTabSelectionUI()
	}
	
	/**
	 * Clear the weak reference of the activity. Careful using the function,
	 * and should call by the application life cycle manager to automatically
	 * clear up the reference.
	 */
	override fun clearWeakActivityReference() {
		logger.d("Clearing weak activity reference")
		weakMotherActivityRef.clear()
		super.clearWeakActivityReference()
	}
	
	/**
	 * Opens the Home fragment
	 */
	fun openHomeFragment() {
		logger.d("Opening HomeFragment")
		CoroutineScope(Dispatchers.Main).launch {
			sideNavigation?.closeDrawerNavigation()
			if (fragmentViewPager.currentItem != 0) {
				fragmentViewPager.setCurrentItem(0, false)
			}
		}
	}
	
	/**
	 * Opens the Browser fragment
	 */
	fun openBrowserFragment() {
		logger.d("Opening BrowserFragment")
		CoroutineScope(Dispatchers.Main).launch {
			sideNavigation?.closeDrawerNavigation()
			if (fragmentViewPager.currentItem != 1) {
				fragmentViewPager.setCurrentItem(1, false)
			}
		}
	}
	
	/**
	 * Opens the Downloads fragment
	 */
	fun openDownloadsFragment() {
		logger.d("Opening DownloadsFragment")
		CoroutineScope(Dispatchers.Main).launch {
			sideNavigation?.closeDrawerNavigation()
			if (fragmentViewPager.currentItem != 2) {
				fragmentViewPager.setCurrentItem(2, false)
			}
		}
	}
	
	/**
	 * Opens the Settings fragment
	 */
	fun openSettingsFragment() {
		logger.d("Opening SettingsFragment")
		CoroutineScope(Dispatchers.Main).launch {
			sideNavigation?.closeDrawerNavigation()
			if (fragmentViewPager.currentItem != 3) {
				fragmentViewPager.setCurrentItem(3, false)
			}
		}
	}
	
	/**
	 * Gets the current fragment position
	 * @return Current fragment position (0-3)
	 */
	fun getCurrentFragmentNumber(): Int {
		logger.d("Getting current fragment number: ${fragmentViewPager.currentItem}")
		return fragmentViewPager.currentItem
	}
	
	/**
	 * Sets up the ViewPager for fragment navigation
	 */
	private fun setupFragmentViewpager() {
		logger.d("Setting up fragment viewpager")
		safeMotherActivityRef?.let {
			fragmentViewPager = findViewById(R.id.fragment_viewpager)
			// Keep all fragments in memory
			fragmentViewPager.offscreenPageLimit = 1
			fragmentViewPager.adapter = FragmentsPageAdapter(it)
			
			// Listener for page changes
			fragmentViewPager.isUserInputEnabled = false
			
			fragmentViewPager.registerOnPageChangeCallback(object :
				ViewPager2.OnPageChangeCallback() {
				override fun onPageSelected(position: Int) {
					logger.d("ViewPager page selected: $position")
					updateButtonTabSelectionUI(position)
				}
			})
		}
	}
	
	/**
	 * Sets up the bottom navigation tabs
	 */
	private fun setupBottomTabs() {
		logger.d("Setting up bottom tabs")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			motherBottomTabs = MotherBottomTabs(safeMotherActivityRef)
			motherBottomTabs.initialize()
		}
	}
	
	/**
	 * Updates the UI for selected tab
	 * @param currentItem The currently selected tab position (0-3)
	 */
	private fun updateButtonTabSelectionUI(currentItem: Int = fragmentViewPager.currentItem) {
		logger.d("Updating button tab selection UI for item: $currentItem")
		when (currentItem) {
			0 -> motherBottomTabs.updateTabSelectionUI(MotherBottomTabs.Tab.HOME_TAB)
			1 -> motherBottomTabs.updateTabSelectionUI(MotherBottomTabs.Tab.BROWSER_TAB)
			2 -> motherBottomTabs.updateTabSelectionUI(MotherBottomTabs.Tab.DOWNLOADS_TAB)
			3 -> motherBottomTabs.updateTabSelectionUI(MotherBottomTabs.Tab.SETTINGS_TAB)
			else -> motherBottomTabs.updateTabSelectionUI(MotherBottomTabs.Tab.HOME_TAB)
		}
	}
	
	/**
	 * Registers an activity result launcher
	 * @return The configured ActivityResultLauncher
	 */
	private fun registerResultOrientedActivityLauncher() =
		registerForActivityResult(StartActivityForResult()) { result ->
			logger.d("Activity result received with code: ${result.resultCode}")
			if (result.resultCode == RESULT_OK) {
				val data: Intent? = result.data
				data?.getStringExtra(ACTIVITY_RESULT_KEY)?.let { resultString ->
					logger.d("Processing activity result: $resultString")
					// Initialize ViewModel if needed
					if (!::sharedViewModel.isInitialized) sharedViewModel =
						ViewModelProvider(this)[SharedViewModel::class.java]
					
					// Open browser and update URL if needed
					if (fragmentViewPager.currentItem != 1) openBrowserFragment()
					sharedViewModel.updateBrowserURLEditResult(resultString)
				}
			}
		}
	
	/**
	 * Handles back press events with custom behavior
	 */
	private fun handleBackPressEvent() {
		logger.d("Handling back press event")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			sideNavigation?.let { sideNavigation ->
				logger.d("Closing navigation drawer")
				// Close drawer if open
				if (sideNavigation.isDrawerOpened()) {
					sideNavigation.closeDrawerNavigation(); return
				}
				
				// Custom back behavior per fragment
				when (fragmentViewPager.currentItem) {
					1 -> { // Browser fragment
						logger.d("Back press in BrowserFragment")
						if (!::sharedViewModel.isInitialized) {
							logger.d("Initializing SharedViewModel for BrowserFragment")
							val viewModelClass = SharedViewModel::class.java
							sharedViewModel = ViewModelProvider(
								owner = safeMotherActivityRef
							)[viewModelClass]
						}; sharedViewModel.triggerBackPressEvent()
					}
					
					2 -> { // Downloads fragment
						logger.d("Back press in DownloadsFragment")
						val fragmentViewPager = downloadFragment?.fragmentViewPager
						if (fragmentViewPager?.currentItem == 1) {
							logger.d("Switching to finished tab in DownloadsFragment")
							downloadFragment?.openFinishedTab()
						} else {
							logger.d("Opening BrowserFragment from DownloadsFragment")
							openBrowserFragment()
						}
					}
					
					3 -> {
						logger.d("Back press in SettingsFragment")
						openDownloadsFragment() // From settings
					}
					
					4 -> {
						logger.d("Back press in unexpected fragment position 4")
						openSettingsFragment() // Shouldn't happen (only 4 fragments)
					}
					
					else -> {
						logger.d("Exit handling with ${sideNavigation.totalWebViews.size} webviews")
						// Exit handling
						if (sideNavigation.totalWebViews.size > 1) showExitWarningDialog()
						else exitActivityOnDoubleBackPress()
					}
				}
			}
		}
	}
	
	/**
	 * Shows exit warning dialog when multiple tabs are open
	 */
	private fun showExitWarningDialog() {
		logger.d("Showing exit warning dialog")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			sideNavigation?.let { sideNavigation ->
				
				val dialogBuilder = MsgDialogUtils.getMessageDialog(
					baseActivityInf = safeMotherActivityRef,
					messageTextViewCustomize = {
						val resId = R.string.text_many_tabs_open_consider_warning
						val totalWebViews = sideNavigation.totalWebViews
						it.text = getString(resId, totalWebViews.size.toString())
					},
					negativeButtonTextCustomize = {
						it.setText(R.string.title_exit_now)
						it.setLeftSideDrawable(R.drawable.ic_button_exit)
					},
					positiveButtonTextCustomize = {
						it.setText(R.string.title_close_tabs)
						it.setLeftSideDrawable(R.drawable.ic_button_cancel)
					}
				)
				
				dialogBuilder?.let { dialog ->
					dialog.setOnClickForNegativeButton {
						logger.d("User chose to exit the app")
						dialog.close()
						delay(200, object : OnTaskFinishListener {
							override fun afterDelay() = finish()
						})
					}
					
					dialog.setOnClickForPositiveButton {
						logger.d("User chose to close tabs")
						dialog.close()
						openBrowserFragment()
						delay(200, object : OnTaskFinishListener {
							override fun afterDelay() {
								sideNavigation.openDrawerNavigation()
							}
						})
					}
					
					dialog.show()
				}
			}
		}
	}
	
	/**
	 * Opens Downloads fragment if coming from notification
	 */
	@UnstableApi
	private fun openDownloadsFragmentIfIntended() {
		logger.d("Checking if should open DownloadsFragment from intent")
		intent.getIntExtra(INTENT_EXTRA_SOURCE_ORIGIN, -2).let { result ->
			if (result == -2) {
				logger.d("No WHERE_DID_YOU_COME_FROM extra found")
				return
			}
			
			if (result == FROM_DOWNLOAD_NOTIFICATION) {
				logger.d("Opening DownloadsFragment from notification")
				openDownloadsFragment()
				return
			}
			
			logger.d("WHERE_DID_YOU_COME_FROM value: $result")
		}
	}
	
	/**
	 * Handles incoming intent URLs
	 */
	private fun handleIntentURL() {
		logger.d("Handling intent URL")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			val intentURL = getIntentDataURI(safeMotherActivityRef)
			if (intentURL.isNullOrEmpty()) {
				logger.d("No intent URL found")
				return
			}
			
			val browserFragmentBody = browserFragment?.browserFragmentBody
			val alreadyLoadedIntentURL = browserFragmentBody?.alreadyLoadedIntentURL
			val isIntentURLLoaded = alreadyLoadedIntentURL == intentURL
			
			if (!isIntentURLLoaded) {
				logger.d("Loading new intent URL: $intentURL")
				openBrowserFragment()
			} else {
				logger.d("Intent URL already loaded: $intentURL")
			}
		}
	}
	
	/**
	 * Monitors clipboard for URLs and shows prompt if found
	 */
	fun monitorClipboardForUrls() {
		logger.d("Monitoring clipboard for URLs")
		delay(500, object : OnTaskFinishListener {
			override fun afterDelay() {
				safeMotherActivityRef?.let {
					val clipboardText = getTextFromClipboard(safeMotherActivityRef).trim()
					if (clipboardText.isNotEmpty()) {
						logger.d("Clipboard text found: $clipboardText")
						// Skip if same URL was already processed
						if (aioSettings.lastProcessedClipboardText == clipboardText) {
							logger.d("Same URL was already processed")
							return
						}
						
						if (URLUtility.isValidURL(clipboardText)) {
							logger.d("Valid URL found in clipboard")
							aioSettings.lastProcessedClipboardText = clipboardText
							aioSettings.updateInStorage()
							delay(500, object : OnTaskFinishListener {
								override fun afterDelay() {
									logger.d("Showing URL prompt dialog")
									MsgDialogUtils.getMessageDialog(
										baseActivityInf = safeMotherActivityRef,
										isTitleVisible = true,
										titleTextViewCustomize = { it.setText(R.string.title_copied_link) },
										messageTextViewCustomize = {
											it.text = clipboardText
											val colorSecondary = R.color.color_secondary
											val color =
												getColor(safeMotherActivityRef, colorSecondary)
											it.setTextColor(color)
											it.maxLines = 2
										},
										isNegativeButtonVisible = false,
										positiveButtonTextCustomize = {
											it.setText(R.string.title_open_the_link)
											it.setLeftSideDrawable(R.drawable.ic_button_url_link)
										}
									)?.apply {
										logger.d("User chose to open clipboard URL")
										setOnClickForPositiveButton {
											close()
											if (isYtdlpSupportedUrl(clipboardText)) {
												logger.d("Showing video resolution picker")
												showVideoResolutionPicker(
													clipboardText = clipboardText,
													safeMotherActivityRef = safeMotherActivityRef
												)
											} else {
												logger.d("Opening non-YTDLP URL in browser")
												openBrowserFragmentWithLink(clipboardText)
											}
										}
									}?.show()
								}
							})
						}
					}
				}
			}
		})
	}
	
	/**
	 * Shows video resolution picker for supported URLs
	 * @param clipboardText The URL to process
	 * @param safeMotherActivityRef The activity reference
	 */
	private fun showVideoResolutionPicker(
		clipboardText: String,
		safeMotherActivityRef: MotherActivity
	) {
		logger.d("Showing video resolution picker for URL: $clipboardText")
		if (URLUtility.isValidURL(clipboardText)) {
			if (isSocialMediaUrl(clipboardText)) {
				logger.d("URL is social media, parsing title and thumbnail")
				val waitingMsg = getText(R.string.title_analyzing_url_please_wait)
				val waitingDialog = WaitingDialog(
					isCancelable = false,
					baseActivityInf = safeMotherActivityRef,
					loadingMessage = waitingMsg.toString(),
					dialogCancelListener = { dialog ->
						logger.d("URL parsing aborted by user")
						isParsingTitleFromUrlAborted = true
						dialog.dismiss()
					}
				); waitingDialog.show()
				
				ThreadsUtility.executeInBackground(codeBlock = {
					logger.d("Fetching webpage content in background")
					val htmlBody = fetchWebPageContent(clipboardText, true)
					val thumbnailUrl = startParsingVideoThumbUrl(clipboardText, htmlBody)
					getWebpageTitleOrDescription(
						clipboardText,
						userGivenHtmlBody = htmlBody
					) { resultedTitle ->
						waitingDialog.close()
						if (!resultedTitle.isNullOrEmpty() && !isParsingTitleFromUrlAborted) {
							logger.d("Successfully parsed title: $resultedTitle")
							executeOnMainThread {
								SingleResolutionPrompter(
									baseActivity = safeMotherActivityRef,
									singleResolutionName = getString(R.string.title_high_quality),
									extractedVideoLink = clipboardText,
									currentWebUrl = clipboardText,
									videoTitle = resultedTitle,
									videoUrlReferer = clipboardText,
									isSocialMediaUrl = true,
									isDownloadFromBrowser = false,
									dontParseFBTitle = true,
									thumbnailUrlProvided = thumbnailUrl
								).show()
							}
						} else {
							logger.d("Failed to parse title or parsing was aborted")
						}
					}
				})
			} else {
				logger.d("URL is not social media, direct parsing")
				startParingVideoURL(safeMotherActivityRef, clipboardText)
			}
		} else {
			logger.d("Invalid URL found: $clipboardText")
			invalidUrlErrorToast(safeMotherActivityRef)
		}
	}
	
	/**
	 * Shows invalid URL error toast
	 * @param safeMotherActivity The activity reference
	 */
	private fun invalidUrlErrorToast(safeMotherActivity: MotherActivity) {
		logger.d("Showing invalid URL error toast")
		safeMotherActivity.doSomeVibration(50)
		ToastView.showToast(
			activityInf = safeMotherActivity,
			msgId = R.string.title_file_url_not_valid
		)
	}
	
	/**
	 * Starts parsing video URL
	 * @param safeActivity The activity reference
	 * @param userEnteredUrl The URL to parse
	 */
	private fun startParingVideoURL(safeActivity: MotherActivity, userEnteredUrl: String) {
		logger.d("Starting video URL parsing for: $userEnteredUrl")
		val videoInterceptor = SharedVideoURLIntercept(safeActivity)
		videoInterceptor.interceptIntentURI(userEnteredUrl)
	}
	
	/**
	 * Opens browser fragment with given URL
	 * @param targetUrl The URL to load in browser
	 */
	private fun openBrowserFragmentWithLink(targetUrl: String) {
		logger.d("Opening browser fragment with URL: $targetUrl")
		try {
			browserFragment?.getBrowserWebEngine()?.let { webEngine ->
				logger.d("Adding new browsing tab")
				sideNavigation?.addNewBrowsingTab(targetUrl, webEngine)
				openBrowserFragment()
			}
		} catch (error: Exception) {
			logger.d("Error opening browser fragment: ${error.message}")
			error.printStackTrace()
			doSomeVibration(50)
			ToastView.showToast(
				activityInf = safeMotherActivityRef,
				msgId = R.string.title_something_went_wrong
			)
		}
	}
	
	/**
	 * ViewModel for sharing data between fragments
	 */
	class SharedViewModel : ViewModel() {
		
		private val logger = LogHelperUtils.from(javaClass)
		
		// LiveData for URL sharing
		private val liveURLStringData = MutableLiveData<String>()
		val liveURLString: LiveData<String> get() = liveURLStringData
		
		// LiveData for back press events
		private val backPressLiveEventData = MutableLiveData<Unit>()
		val backPressLiveEvent: LiveData<Unit> get() = backPressLiveEventData
		
		/**
		 * Updates the browser URL result
		 * @param result The URL string to share
		 */
		fun updateBrowserURLEditResult(result: String) {
			logger.d("Updating browser URL result: $result")
			liveURLStringData.value = result
		}
		
		/**
		 * Triggers a back press event
		 */
		fun triggerBackPressEvent() {
			logger.d("Triggering back press event")
			backPressLiveEventData.value = Unit
		}
	}
}