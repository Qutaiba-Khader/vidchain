package app.ui.main.fragments.browser.webengine

import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import app.core.AIOApp.Companion.aioFavicons
import app.core.AIOApp.Companion.aioHistory
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.BaseActivity
import app.core.engines.browser.history.HistoryModel
import app.ui.main.MotherActivity
import com.aio.R
import com.bumptech.glide.Glide
import lib.networks.URLUtilityKT.normalizeEncodedUrl
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.closeAnyAnimation
import java.io.File
import java.util.Date

/**
 * Custom [WebChromeClient] implementation for handling advanced browser events
 * such as progress updates, favicon/title handling, file chooser dialogs,
 * custom video views, and popup window creation.
 *
 * This client is tightly coupled to [WebViewEngine] and provides the bridge between
 * browser UI elements and WebView lifecycle events.
 *
 * @property webviewEngine The [WebViewEngine] that owns this Chrome client instance.
 */
class BrowserWebChromeClient(val webviewEngine: WebViewEngine) : WebChromeClient() {

	/** Callback for file uploads initiated by the WebView's file chooser. */
	private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

	/** Currently displayed custom full-screen view (e.g., video). */
	private var customView: View? = null

	/** Video view reference when displaying full-screen videos. */
	private var videoView: View? = null

	/** Callback invoked when the custom view should be hidden. */
	private var customViewCallback: CustomViewCallback? = null

	/**
	 * Called when the loading progress of the current WebView changes.
	 *
	 * This method is triggered frequently during page load to provide
	 * real-time progress updates. The progress value ranges from 0 to 100.
	 *
	 * @param webView The WebView whose loading progress has changed.
	 * @param progress The current load progress as an integer (0–100).
	 */
	override fun onProgressChanged(webView: WebView?, progress: Int) {
		// Ensure we only update progress for the currently active WebView
		if (webviewEngine.currentWebView != webView) return

		// Update UI elements (progress bar, etc.) via WebViewEngine
		webviewEngine.updateWebViewProgress(webView, progress)
	}

	/**
	 * Handles the creation of new browser windows (e.g., when a website tries to open a new tab or popup).
	 *
	 * This method is called when JavaScript code such as `window.open()` is executed
	 * or when a link is set to open in a new window. Depending on settings, it may
	 * block unwanted popups or open them in a new WebView instance.
	 *
	 * @param view The originating WebView that requested the new window.
	 * @param isDialog Whether the new window should be displayed as a dialog.
	 * @param isUserGesture True if the request was initiated by a user action (e.g., a click).
	 * @param resultMsg A message containing a WebView.WebViewTransport object to set the new WebView.
	 * @return True if a new window is created or handled; False if ignored.
	 */
	override fun onCreateWindow(
		view: WebView?,
		isDialog: Boolean,
		isUserGesture: Boolean,
		resultMsg: Message?
	): Boolean {
		// Only handle the request if it's from the currently active WebView
		if (webviewEngine.currentWebView != view) return false

		// Extract the transport object from the message to attach a new WebView
		val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false

		// Create a temporary WebView to handle the new content
		val tempWebView = webviewEngine.generateNewWebview() as WebView
		tempWebView.apply {
			layoutParams = LayoutParams(MATCH_PARENT, MATCH_PARENT)

			// Temporary WebView client to capture the page finish event
			webViewClient = object : WebViewClient() {
				override fun onPageFinished(view: WebView, url: String) {
					// Once loaded, add the new tab to the browser UI and remove the temp WebView
					webviewEngine.safeMotherActivityRef.sideNavigation
						?.addNewBrowsingTab(url, webviewEngine)
					(parent as? ViewGroup)?.removeView(this@apply)
				}
			}
		}

		// Assign the new WebView to the transport for WebView handling
		transport.webView = tempWebView

		// Check popup blocker setting before allowing the popup to load
		if (!aioSettings.browserEnablePopupBlocker) {
			resultMsg.sendToTarget() // Allow popup
		} else {
			// Block popup and show user feedback
			val messageResId = getText(R.string.title_blocked_unwanted_popup_links)
			webviewEngine.showQuickBrowserInfo(messageResId)
		}

		return true
	}

	/**
	 * Called when the favicon (website icon) of the current page has been received.
	 *
	 * This method is triggered by the WebView when it retrieves the site's favicon.
	 * The favicon is then loaded into the browser's top bar using Glide.
	 *
	 * @param webview The WebView that received the icon.
	 * @param icon The favicon bitmap, or null if none is available.
	 */
	override fun onReceivedIcon(webview: WebView, icon: Bitmap?) {
		super.onReceivedIcon(webview, icon)
		try {
			val motherActivity = webviewEngine.safeMotherActivityRef
			val totalWebViews = motherActivity.sideNavigation?.totalWebViews
			totalWebViews?.forEach { webView ->
				// Only update the icon for the currently active WebView
				if ((webviewEngine.currentWebView?.url == webview.url) && (icon != null)) {
					// Start loading animation for the favicon
					val browserFragmentTop = webviewEngine.browserFragment.browserFragmentTop
					browserFragmentTop.animateDefaultFaviconLoading(shouldStopAnimation = true)

					// Load the favicon bitmap into the UI
					val webViewFavicon = browserFragmentTop.webViewFavicon
					Glide.with(webview.context).load(icon).into(webViewFavicon)
				}
			}
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/**
	 * Called when the WebView receives a new page title.
	 *
	 * This method:
	 * 1. Ensures the event belongs to the currently active WebView.
	 * 2. Updates the browser UI (title bar and favicon).
	 * 3. Caches or falls back to a default favicon.
	 * 4. Records the page visit into the history library (if not an about:blank page).
	 *
	 * Threading:
	 * - UI changes are posted to the main thread.
	 * - Favicon lookup and file existence checks run in a background thread.
	 *
	 * Safety:
	 * - Wrapped in try/catch to prevent unexpected crashes from casting or null values.
	 *
	 * @param webView The WebView sending the page title update.
	 * @param title   The page title, or null if unavailable.
	 */
	override fun onReceivedTitle(webView: WebView?, title: String?) {
		// Only handle the event if it belongs to the current WebView
		if (webviewEngine.currentWebView != webView) return

		try {
			// Get a reference to the hosting MotherActivity
			val motherActivity = webView?.context as MotherActivity

			// Process the title only if it is not null
			title?.let { titleText ->
				// Update the browser UI with the new title
				motherActivity.browserFragment?.let { browserFragment ->
					browserFragment.browserFragmentTop.webviewTitle.text = titleText
					motherActivity.sideNavigation?.webTabListAdapter?.notifyDataSetChanged()

					val correspondingWebViewUrl = webView.url.toString()

					// Fetch favicon in a background thread
					executeInBackground {
						// Show favicon loading animation
						executeOnMainThread {
							browserFragment.browserFragmentTop.animateDefaultFaviconLoading(true)
						}

						// Try to get the cached favicon for the current page
						val faviconCachedPath = aioFavicons.getFavicon(correspondingWebViewUrl)

						if (!faviconCachedPath.isNullOrEmpty()) {
							val faviconImg = File(faviconCachedPath)

							// If cached favicon exists, set it
							if (faviconImg.exists()) {
								executeOnMainThread {
									browserFragment.browserFragmentTop.webViewFavicon.let { faviconViewer ->
										faviconViewer.setImageURI(Uri.fromFile(faviconImg))
										closeAnyAnimation(faviconViewer)
									}
								}
							} else {
								// If no cached favicon, load a default icon
								executeOnMainThread {
									val defaultFaviconResId = R.drawable.ic_button_browser_favicon
									browserFragment.browserFragmentTop.webViewFavicon.let { faviconViewer ->
										Glide.with(motherActivity).load(defaultFaviconResId).into(faviconViewer)
										closeAnyAnimation(faviconViewer)
									}
								}
							}
						}
					}
				}

				// Save the visit to browsing history if it's a real page
				if (webView.url.toString() != "about:blank") {
					aioHistory.getHistoryLibrary().apply {
						// Remove old entry if the same URL already exists
						val existingEntryIndex = indexOfFirst { it.historyUrl == webView.url.toString() }
						if (existingEntryIndex != -1) removeAt(existingEntryIndex)
					}

					// Add the new entry at the top
					aioHistory.getHistoryLibrary().add(0, HistoryModel().apply {
						historyUserAgent = webView.settings.userAgentString.toString()
						historyVisitDateTime = Date()
						historyUrl = normalizeEncodedUrl(webView.url.toString())
						historyTitle = titleText
					})

					// Persist history changes
					aioHistory.updateInStorage()
				}
			}
		} catch (error: Exception) {
			// Prevents crashes from casting errors or null references
			error.printStackTrace()
		}
	}

	/**
	 * Handles file chooser requests from WebView (e.g., <input type="file"> elements).
	 *
	 * Responsibilities:
	 * - Cancels any pending file chooser callback before starting a new one.
	 * - Stores the provided `ValueCallback` for later use.
	 * - Opens the system file picker using Scoped Storage helper with multiple file selection allowed.
	 * - Passes selected file URIs back to the WebView once selection is complete.
	 * - Sends `null` to the callback if the selection is empty or canceled.
	 *
	 * Safety:
	 * - Wrapped in try/catch to avoid crashes if context casting or file picker operations fail.
	 *
	 * @param webView The WebView that initiated the file chooser.
	 * @param filePathCallback Callback to deliver the selected file URIs.
	 * @param fileChooserParams Parameters describing the file chooser request.
	 * @return Always returns `true` to indicate the file chooser event was handled.
	 */
	override fun onShowFileChooser(
		webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?,
		fileChooserParams: FileChooserParams?
	): Boolean {
		try {
			fileUploadCallback?.onReceiveValue(null)
			fileUploadCallback = filePathCallback

			val baseActivity = webView?.context as BaseActivity
			baseActivity.scopedStorageHelper?.openFilePicker(allowMultiple = true)
			baseActivity.scopedStorageHelper?.onFileSelected = { _, files ->
				val uris = files.map { it.uri }.toTypedArray()
				if (uris.isEmpty()) fileUploadCallback?.onReceiveValue(null)
				else fileUploadCallback?.onReceiveValue(uris)
				fileUploadCallback = null
			}
		} catch (error: Exception) {
			error.printStackTrace()
		}; return true
	}

	/**
	 * Called when a WebView requests to display a custom view (e.g., HTML5 video fullscreen).
	 *
	 * Behavior:
	 * 1. Hides the current WebView.
	 * 2. Replaces its container with a fullscreen custom view (video).
	 * 3. Stores the provided callback for later cleanup.
	 * 4. Forces the custom view to display in landscape orientation.
	 *
	 * @param view     The custom view provided by the WebView (usually a video surface).
	 * @param callback The callback to notify when the custom view should be hidden.
	 */
	override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
		// Hide the WebView when switching to fullscreen
		webviewEngine.currentWebView?.visibility = View.GONE

		val browserFragment = webviewEngine.browserFragment
		val browserFragmentBody = browserFragment.browserFragmentBody
		val webViewContainer = browserFragmentBody.webViewContainer

		// Clear any existing views (important when switching multiple times)
		webViewContainer.removeAllViews()

		view?.let { video ->
			videoView = video

			// Wrap the video view in a FrameLayout so it fills the container
			val wrapper = FrameLayout(video.context).apply {
				layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
				addView(video, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
			}

			// Add the wrapper to the container
			webViewContainer.addView(wrapper)

			// Keep references for cleanup later
			customView = wrapper
			customViewCallback = callback

			// Adjust orientation and layout for fullscreen
			forceLandscapeRotation()
		}
	}

	/**
	 * Rotates the fullscreen video view into landscape mode.
	 * This is useful for devices locked in portrait mode but where
	 * fullscreen video should still appear landscape.
	 */
	private fun forceLandscapeRotation() {
		val browserFragment = webviewEngine.browserFragment
		val browserFragmentBody = browserFragment.browserFragmentBody
		val webViewContainer = browserFragmentBody.webViewContainer

		videoView?.let { view ->
			view.post {
				val containerWidth = webViewContainer.width
				val containerHeight = webViewContainer.height
				val params = view.layoutParams

				// Swap width/height for rotation
				params.width = containerHeight
				params.height = containerWidth
				view.layoutParams = params

				// Set pivot for rotation
				view.pivotX = 0f
				view.pivotY = 0f

				// Rotate 90 degrees for landscape
				view.rotation = 90f

				// Translate so the rotated view fills the container
				view.translationX = containerWidth.toFloat()
				view.translationY = 0f
			}
		}
	}

	/**
	 * Called when the WebView should hide the custom view and restore normal browsing mode.
	 *
	 * Behavior:
	 * 1. Resets video view rotation and layout.
	 * 2. Removes the fullscreen wrapper from the container.
	 * 3. Restores the original WebView to its place.
	 * 4. Notifies the WebView that the custom view was hidden.
	 */
	override fun onHideCustomView() {
		// Reset transformations for the video view
		videoView?.let { view ->
			view.rotation = 0f
			view.translationX = 0f
			view.translationY = 0f
			view.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
		}

		// Clear reference
		videoView = null

		val browserFragment = webviewEngine.browserFragment
		val browserFragmentBody = browserFragment.browserFragmentBody
		val webViewContainer = browserFragmentBody.webViewContainer

		// Remove fullscreen video and restore WebView
		webViewContainer.removeAllViews()
		webViewContainer.addView(webviewEngine.currentWebView)
		webviewEngine.currentWebView?.visibility = View.VISIBLE

		// Notify WebView that fullscreen has been closed
		customViewCallback?.onCustomViewHidden()
		customView = null
	}

}