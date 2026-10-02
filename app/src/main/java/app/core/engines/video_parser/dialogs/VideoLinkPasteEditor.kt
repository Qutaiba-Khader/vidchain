package app.core.engines.video_parser.dialogs

import android.view.View
import android.widget.EditText
import app.core.engines.video_parser.parsers.SupportedURLs.isSocialMediaUrl
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.main.MotherActivity
import app.ui.main.fragments.browser.webengine.SingleResolutionPrompter
import app.ui.main.fragments.downloads.intercepter.SharedVideoURLIntercept
import com.aio.R
import lib.networks.URLUtility
import lib.networks.URLUtilityKT.fetchWebPageContent
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import lib.ui.builders.WaitingDialog
import java.lang.ref.WeakReference

/**
 * A dialog that allows users to paste and process video URLs manually.
 *
 * Supports:
 * - Direct video URLs.
 * - Social media URLs with thumbnail/title extraction.
 * - Graceful fallback to in-app browser if parsing fails.
 *
 * @property motherActivity The hosting activity.
 * @property passOnUrl Optional pre-filled URL.
 * @property autoStart Whether parsing should begin automatically on show.
 */
class VideoLinkPasteEditor(
	val motherActivity: MotherActivity,
	val passOnUrl: String? = null,
	val autoStart: Boolean = false
) {
	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to avoid leaks
	private val safeMotherActivityRef = WeakReference(motherActivity).get()
	private var dialogBuilder: DialogBuilder? = DialogBuilder(safeMotherActivityRef)

	// UI components
	private lateinit var buttonDownload: View
	private lateinit var editFieldFileURL: EditText
	private lateinit var editFieldContainer: View

	private var userGivenURL: String = ""
	private var isParsingTitleFromUrlAborted = false

	/**
	 * Initializes the dialog, inflates its layout, and sets up click listeners.
	 */
	init {
		safeMotherActivityRef?.let {
			dialogBuilder?.let { builder ->
				builder.setView(R.layout.dialog_video_link_editor_1)
				builder.view.apply {
					buttonDownload = findViewById(R.id.btn_dialog_positive_container)
					editFieldContainer = findViewById(R.id.edit_url_container)
					editFieldFileURL = findViewById(R.id.edit_file_url)
					passOnUrl?.let { url ->
						editFieldFileURL.setText(url)
						logger.d("Pre-filled URL in editor: $url")
					}

					val clickActions = mapOf(
						editFieldContainer to { focusEditTextField() },
						buttonDownload to { downloadVideo() }
					)

					clickActions.forEach { (view, action) -> view.setOnClickListener { action() } }
					logger.d("Dialog initialized with click listeners.")
				}
			}
		} ?: logger.d("Dialog initialization failed: activity reference is null.")
	}

	/**
	 * Displays the dialog and focuses input. If [autoStart] and [passOnUrl] are provided,
	 * begins parsing immediately.
	 */
	fun show() {
		if (!passOnUrl.isNullOrEmpty() && autoStart) {
			logger.d("Auto-start enabled. Starting download for pre-filled URL: $passOnUrl")
			downloadVideo()
		} else {
			dialogBuilder?.show()
			logger.d("Dialog shown to user.")
			delay(200, object : OnTaskFinishListener {
				override fun afterDelay() {
					focusEditTextField()
					editFieldFileURL.selectAll()
					showOnScreenKeyboard(safeMotherActivityRef, editFieldFileURL)
					logger.d("Input field focused and keyboard shown.")
				}
			})
		}
	}

	/**
	 * Closes the dialog if open.
	 */
	fun close() {
		logger.d("Closing dialog.")
		dialogBuilder?.close()
	}

	/**
	 * Requests focus on the input field.
	 */
	private fun focusEditTextField() {
		logger.d("Focusing input field.")
		editFieldFileURL.requestFocus()
	}

	/**
	 * Handles user’s download action. Validates the URL and decides parsing strategy.
	 */
	private fun downloadVideo() {
		safeMotherActivityRef?.let { safeActivity ->
			userGivenURL = editFieldFileURL.text.toString()
			logger.d("Download button clicked with URL: $userGivenURL")

			if (!URLUtility.isValidURL(userGivenURL)) {
				logger.d("Invalid URL entered: $userGivenURL")
				safeActivity.doSomeVibration(50)
				showToast(activityInf = safeActivity, msg = getText(R.string.title_file_url_not_valid))
				return
			} else {
				logger.d("Valid URL detected. Closing dialog and processing.")
				close()

				if (isSocialMediaUrl(userGivenURL)) {
					logger.d("URL identified as social media link. Starting analysis.")
					val waitingDialog = WaitingDialog(
						isCancelable = false,
						baseActivityInf = motherActivity,
						loadingMessage = getText(R.string.title_analyzing_url_please_wait),
						dialogCancelListener = { dialog ->
							isParsingTitleFromUrlAborted = true
							logger.d("Parsing aborted by user.")
							dialog.dismiss()
						}
					); waitingDialog.show()

					ThreadsUtility.executeInBackground(codeBlock = {
						val htmlBody = fetchWebPageContent(userGivenURL, true)
						logger.d("Fetched HTML content for URL: $userGivenURL")

						val thumbnailUrl = startParsingVideoThumbUrl(userGivenURL, htmlBody)
						logger.d("Parsed thumbnail URL: ${thumbnailUrl ?: "none"}")

						getWebpageTitleOrDescription(
							userGivenURL,
							userGivenHtmlBody = htmlBody
						) { resultedTitle ->
							waitingDialog.close()
							if (!resultedTitle.isNullOrEmpty() && !isParsingTitleFromUrlAborted) {
								logger.d("Extracted title: $resultedTitle")
								executeOnMainThread {
									SingleResolutionPrompter(
										baseActivity = motherActivity,
										singleResolutionName = getText(R.string.title_high_quality),
										extractedVideoLink = userGivenURL,
										currentWebUrl = userGivenURL,
										videoTitle = resultedTitle,
										videoUrlReferer = userGivenURL,
										isSocialMediaUrl = true,
										isDownloadFromBrowser = false,
										dontParseFBTitle = true,
										thumbnailUrlProvided = thumbnailUrl
									).show()
								}
							} else {
								logger.d("Failed to extract title. Opening browser fallback.")
								executeOnMainThread {
									safeMotherActivityRef.doSomeVibration(50)
									showToast(
										activityInf = safeActivity,
										msgId = R.string.title_server_busy_opening_browser
									)

									safeMotherActivityRef.browserFragment?.getBrowserWebEngine()?.let {
										safeMotherActivityRef.sideNavigation?.addNewBrowsingTab(userGivenURL, it)
										safeMotherActivityRef.openBrowserFragment()
									}
								}
							}
						}
					})
				} else {
					logger.d("Direct video URL detected. Starting interception.")
					startParingVideoURL(safeActivity)
				}
			}
		} ?: logger.d("downloadVideo() invoked with null activity reference.")
	}

	/**
	 * Triggers interception for direct video URLs.
	 */
	private fun startParingVideoURL(safeActivity: MotherActivity) {
		logger.d("Intercepting direct video URL: $userGivenURL")
		close()
		val videoInterceptor = SharedVideoURLIntercept(safeActivity)
		videoInterceptor.interceptIntentURI(userGivenURL)
	}
}