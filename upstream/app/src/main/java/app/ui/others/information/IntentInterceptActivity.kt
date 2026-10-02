package app.ui.others.information

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import androidx.lifecycle.lifecycleScope
import app.core.AIOApp.Companion.IS_PREMIUM_USER
import app.core.AIOApp.Companion.IS_ULTIMATE_VERSION_UNLOCKED
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOKeyStrings.DONT_PARSE_URL_ANYMORE
import app.core.bases.BaseActivity
import app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.main.MotherActivity
import app.ui.main.fragments.browser.webengine.SingleResolutionPrompter
import app.ui.main.fragments.downloads.intercepter.SharedVideoURLIntercept
import com.aio.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.device.IntentUtility.getIntentDataURI
import lib.networks.URLUtility.isValidURL
import lib.networks.URLUtilityKT.fetchWebPageContent
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.ui.ActivityAnimator.animActivityFade
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.lang.ref.WeakReference

/**
 * Activity that intercepts and processes external video URLs shared from other applications.
 *
 * This activity serves as an entry point for handling "Share" actions from browsers,
 * social media apps, and other applications. It performs intelligent URL processing
 * based on the URL type and user's premium status.
 *
 * ## Primary Responsibilities:
 * - Intercept shared video URLs from external applications
 * - Differentiate between social media and generic video URLs
 * - Extract video metadata (title, thumbnail) for social media URLs
 * - Handle premium vs non-premium user flows appropriately
 * - Provide seamless redirection to main application components
 *
 * ## URL Processing Flow:
 * 1. **Validation**: Check if the URL is valid and non-empty
 * 2. **Premium Check**: Verify user's premium status for advanced features
 * 3. **URL Classification**: Determine if URL is from social media or generic source
 * 4. **Metadata Extraction**: For social media URLs, fetch title and thumbnail
 * 5. **User Prompting**: Show download resolution dialog for social media content
 * 6. **Fallback Handling**: Redirect to browser for unsupported or generic URLs
 *
 * ## Memory Management:
 * Uses WeakReference pattern to prevent memory leaks and ensure proper
 * garbage collection when the activity is destroyed.
 *
 * @see MotherActivity for the main application entry point
 * @see SharedVideoURLIntercept for generic URL processing
 * @see SingleResolutionPrompter for social media download dialogs
 */
class IntentInterceptActivity : BaseActivity() {

	/**
	 * Logger instance for tracking activity lifecycle and debugging operations.
	 * Provides structured logging for monitoring URL interception and processing.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the activity instance to prevent memory leaks.
	 *
	 * Using WeakReference allows the garbage collector to reclaim the activity
	 * when it's no longer needed, while still providing access when the activity
	 * is alive. This is crucial for background operations that might outlive
	 * the activity's lifecycle.
	 */
	private val weakSelfReference = WeakReference(this)

	/**
	 * Safe reference to the activity retrieved from the weak reference.
	 *
	 * This pattern ensures that background tasks can safely access the activity
	 * without causing memory leaks or null pointer exceptions. Always check
	 * for null before using this reference.
	 */
	private val safeIntentInterceptActivityRef = weakSelfReference.get()

	/**
	 * Flag indicating whether title parsing from URL has been aborted by the user.
	 *
	 * This flag is set to true when the user cancels the parsing operation,
	 * allowing background tasks to exit gracefully and avoid unnecessary processing.
	 */
	private var isParsingTitleFromUrlAborted = false

	/**
	 * Provides the layout resource for this transparent activity.
	 *
	 * This activity uses a transparent layout to provide a seamless user experience
	 * while processing URLs in the background. The fade animation ensures smooth
	 * visual transitions.
	 *
	 * @return The layout resource ID for the transparent activity layout
	 */
	override fun onRenderingLayout(): Int {
		logger.d("onRenderingLayout: Applying fade animation to activity.")
		animActivityFade(safeIntentInterceptActivityRef)
		return R.layout.activity_transparent_1
	}

	/**
	 * Handles back button press with proper animation and cleanup.
	 *
	 * Ensures the activity closes with a smooth fade-out animation and
	 * performs necessary cleanup operations to maintain system stability.
	 */
	override fun onBackPressActivity() {
		logger.d("onBackPressActivity: User pressed back, closing with fade animation.")
		closeActivityWithFadeAnimation(true)
	}

	/**
	 * Core method that drives the URL interception and processing logic.
	 *
	 * This method is called after the layout is rendered and performs the following steps:
	 * 1. Extracts the URL from the incoming intent
	 * 2. Validates the URL format and content
	 * 3. Checks user's premium status for feature access
	 * 4. Waits for full app initialization if necessary
	 * 5. Routes the URL to appropriate processing based on type
	 *
	 * The method handles both social media URLs (with advanced parsing) and
	 * generic URLs (with basic interception).
	 */
	override fun onAfterLayoutRender() {
		// Handle premium user flow
		safeIntentInterceptActivityRef?.let { safeActivityRef ->
			logger.d("onAfterLayoutRender: Starting intent processing.")

			// Retrieve the shared URL from the incoming intent
			val intentUrl = getIntentDataURI(safeActivityRef)
			logger.d("onAfterLayoutRender: Retrieved intent URL = $intentUrl")

			// Handle invalid URLs with user feedback
			if (isValidURL(intentUrl) == false) {
				logger.d("onAfterLayoutRender: Invalid URL detected.")
				doSomeVibration(50)
				showToast(safeActivityRef, msgId = R.string.title_invalid_url)
				onBackPressActivity()
				return
			}

			// If no URL is provided, exit the activity gracefully
			if (intentUrl.isNullOrEmpty()) {
				logger.d("onAfterLayoutRender: No URL found in the intent.")
				onBackPressActivity()
				return
			}

			// If user is not premium or ultimate version is not unlocked, forward to the main activity
			if (IS_PREMIUM_USER == false || IS_ULTIMATE_VERSION_UNLOCKED == false) {
				logger.d("onAfterLayoutRender: Non-premium user detected. Forwarding to main activity.")
				forwardIntentToMotherActivity()
				return
			}

			// Wait for full app initialization before processing URLs
			lifecycleScope.launch {
				val checkingDialog = WaitingDialog(
					isCancelable = false,
					baseActivityInf = safeActivityRef,
					loadingMessage = getString(R.string.title_wait_till_apps_loads_up),
				)

				// Setup dialog cancellation handler
				checkingDialog.dialogBuilder?.setOnClickForPositiveButton {
					checkingDialog.close()
					closeActivityWithFadeAnimation(shouldAnimate = true)
				}

				// Wait loop until app is fully loaded
				while (downloadSystem.isInitializing) {
					checkingDialog.show()
					delay(200) // Small delay to prevent tight loop
				}

				checkingDialog.close()
				startParsingTheIntentURL(intentUrl, safeActivityRef)
			}
			return
		}
	}

	/**
	 * Initiates the URL parsing process based on URL type classification.
	 *
	 * This method determines whether the URL is from a social media platform
	 * and routes it to the appropriate processing pipeline:
	 * - Social media URLs: Advanced parsing with metadata extraction
	 * - Generic URLs: Basic interception with fallback to browser
	 *
	 * @param intentUrl The URL to be processed and analyzed
	 * @param activityRef Reference to the current activity for UI operations
	 */
	private fun startParsingTheIntentURL(intentUrl: String, activityRef: IntentInterceptActivity) {
		logger.d("startParsingTheIntentURL: Premium user detected. Processing URL.")

		// Route to appropriate processor based on URL type
		if (isSocialMediaUrl(intentUrl) == false) {
			logger.d("startParsingTheIntentURL: Non-social media URL detected. Using generic interceptor.")
			interceptNonSocialMediaUrl(activityRef, intentUrl)
			return
		}

		logger.d("startParsingTheIntentURL: Social media URL detected. Starting advanced parsing.")

		// Show "analyzing URL" waiting dialog for social media processing
		val waitingDialog = WaitingDialog(
			isCancelable = false,
			baseActivityInf = activityRef,
			loadingMessage = getString(R.string.title_analyzing_url_please_wait),
		)

		// Setup waiting dialog "Okay" button click handler
		waitingDialog.dialogBuilder?.setOnClickForPositiveButton {
			logger.d("WaitingDialog: User cancelled analyzing process.")
			waitingDialog.close()
			isParsingTitleFromUrlAborted = true
			closeActivityWithFadeAnimation(shouldAnimate = true)
		}

		waitingDialog.show()
		logger.d("WaitingDialog: Displayed analyzing message.")

		// Perform background parsing for social media URLs
		ThreadsUtility.executeInBackground(codeBlock = {
			logger.d("Background Task: Fetching HTML content for URL.")
			val htmlBody = fetchWebPageContent(url = intentUrl, retry = true, numOfRetry = 3)

			logger.d("Background Task: Parsing thumbnail URL.")
			val thumbnailUrl = startParsingVideoThumbUrl(videoUrl = intentUrl, userGivenHtmlBody = htmlBody)

			logger.d("Background Task: Extracting webpage title or description.")
			getWebpageTitleOrDescription(
				websiteUrl = intentUrl, userGivenHtmlBody = htmlBody
			) { resultedTitle ->

				// Close the waiting dialog in UI thread
				logger.d("Background Task: Parsing completed. Closing waiting dialog.")
				executeOnMainThread { waitingDialog.close() }

				val validIntentUrl = !resultedTitle.isNullOrEmpty()
				logger.d(
					"Background Task: Parsing result: " +
							"validTitle=$validIntentUrl, cancelled=$isParsingTitleFromUrlAborted"
				)

				// Show download prompt if parsing was successful and not cancelled
				if (validIntentUrl && isParsingTitleFromUrlAborted == false) {
					logger.d("Background Task: Showing SingleResolutionPrompter dialog.")
					executeOnMainThread {
						val resolutionName = getText(R.string.title_high_quality).toString()
						SingleResolutionPrompter(
							baseActivity = activityRef,
							isDialogCancelable = true,
							singleResolutionName = resolutionName,
							extractedVideoLink = intentUrl,
							currentWebUrl = intentUrl,
							videoTitle = resultedTitle,
							videoUrlReferer = intentUrl,
							dontParseFBTitle = true,
							thumbnailUrlProvided = thumbnailUrl,
							isSocialMediaUrl = true,
							isDownloadFromBrowser = false,
							closeActivityOnSuccessfulDownload = true
						).show()
					}
				} else {
					// Fallback for busy server or cancelled execution
					logger.d("Background Task: Invalid result or cancelled. Forwarding to browser.")
					executeOnMainThread {
						activityRef.doSomeVibration(50)

						val stringResId = R.string.title_server_busy_opening_browser
						showToast(activityInf = activityRef, msgId = stringResId)
						forwardIntentToMotherActivity(dontParseURLAnymore = true)
					}
				}
			}
		})
	}

	/**
	 * Handles interception and processing of non-social media URLs.
	 *
	 * For generic video URLs that don't belong to social media platforms,
	 * this method uses the SharedVideoURLIntercept utility to process the URL.
	 * This includes basic validation and fallback to browser if needed.
	 *
	 * @param safeActivityRef Reference to the current activity for UI operations
	 * @param intentUrl The URL to be processed (non-social media)
	 */
	private fun interceptNonSocialMediaUrl(
		safeActivityRef: IntentInterceptActivity,
		intentUrl: String
	) {
		logger.d("interceptNonSocialMediaUrl: Starting interception for URL: $intentUrl")

		// Initialize the generic URL interceptor for non-social media links
		val interceptor = SharedVideoURLIntercept(
			baseActivity = safeActivityRef,
			closeActivityOnSuccessfulDownload = true,
			onOpeningBuiltInBrowser = {
				logger.d("interceptNonSocialMediaUrl: Opening browser as fallback.")
				forwardIntentToMotherActivity()
			}
		)

		// Process the given URL through the interceptor
		logger.d("interceptNonSocialMediaUrl: Passing URL to interceptor.")
		interceptor.interceptIntentURI(
			targetUrl = intentUrl, shouldOpenBrowserAsFallback = true
		)
		logger.d("interceptNonSocialMediaUrl: Interception process initiated.")
	}

	/**
	 * Clears the weak reference to this activity to prevent memory leaks.
	 *
	 * This method is called as part of the activity cleanup process and ensures
	 * that the WeakReference doesn't prevent the activity from being garbage collected.
	 * It's an important step in the memory management lifecycle.
	 */
	override fun clearWeakActivityReference() {
		logger.d("clearWeakActivityReference: Clearing weak reference to activity.")
		weakSelfReference.clear()
		super.clearWeakActivityReference()
		logger.d("clearWeakActivityReference: Weak reference cleared successfully.")
	}

	/**
	 * Forwards the intercepted intent to the MotherActivity with proper flags and extras.
	 *
	 * This method is used when:
	 * - The user is not premium (limited functionality)
	 * - URL processing fails or is cancelled
	 * - Fallback to browser is required
	 *
	 * It preserves the original intent data and adds appropriate flags for
	 * proper activity stack management.
	 *
	 * @param dontParseURLAnymore Flag indicating that no further URL parsing
	 *                           should be attempted by the receiving activity
	 */
	private fun forwardIntentToMotherActivity(dontParseURLAnymore: Boolean = false) {
		logger.d(
			"forwardIntentToMotherActivity: Preparing to forward intent. " +
					"dontParseURLAnymore=$dontParseURLAnymore"
		)

		try {
			val originalIntent = intent
			logger.d(
				"forwardIntentToMotherActivity: Retrieved original intent: " +
						"action=${originalIntent.action}, data=${originalIntent.data}"
			)

			// Create intent for MotherActivity with necessary flags and extras
			val targetIntent = Intent(getActivity(), MotherActivity::class.java).apply {
				action = originalIntent.action
				setDataAndType(originalIntent.data, originalIntent.type)
				putExtras(originalIntent)
				putExtra(DONT_PARSE_URL_ANYMORE, dontParseURLAnymore)
				flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
			}

			// Launch MotherActivity with the processed intent
			logger.d("forwardIntentToMotherActivity: Launching MotherActivity with preserved intent.")
			startActivity(targetIntent)

			// Close current activity with fade animation
			closeActivityWithFadeAnimation(true)
			logger.d("forwardIntentToMotherActivity: Activity forwarded and closed with fade animation.")

		} catch (error: Exception) {
			logger.e("forwardIntentToMotherActivity: Error occurred while launching MotherActivity", error)

			// Fallback: open MotherActivity with default animation if forwarding fails
			openActivity(targetActivity = MotherActivity::class.java, shouldAnimate = true)
			closeActivityWithFadeAnimation(shouldAnimate = true)
			logger.d("forwardIntentToMotherActivity: Fallback - opened MotherActivity and closed current activity.")
		}
	}
}