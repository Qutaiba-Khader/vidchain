package app.ui.main.fragments.browser

import android.util.Patterns.WEB_URL
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import app.ui.main.MotherActivity
import app.ui.main.fragments.browser.suggestions.SuggestionWatcher
import com.aio.R
import com.bumptech.glide.Glide
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.animateFadInOutAnim
import lib.ui.ViewUtility.closeAnyAnimation
import lib.ui.ViewUtility.hideOnScreenKeyboard
import lib.ui.ViewUtility.showOnScreenKeyboard
import java.net.URLEncoder.encode
import java.nio.charset.StandardCharsets.UTF_8

/**
 * Handles the top section of the browser UI (address bar, favicon, reload, etc.).
 *
 * Responsibilities:
 * - Manage visibility and input of the URL edit field.
 * - Animate favicon and progress bar.
 * - Handle search suggestions and user input events.
 * - Provide reload, options popup, and navigation actions.
 */
class BrowserFragmentTop(val browserFragment: BrowserFragment) {
	private val logger = LogHelperUtils.from(javaClass)

	// Safe reference to the hosting activity
	val safeMotherActivityRef = browserFragment.safeBaseActivityRef!! as MotherActivity

	// Current active WebView instance
	val currentWebView by lazy { browserFragment.browserFragmentBody.webviewEngine.currentWebView }

	// UI elements for browser top bar
	lateinit var webViewReloadButton: ImageView
	lateinit var webviewTitle: TextView
	lateinit var webViewFavicon: ImageView
	lateinit var webViewProgress: ProgressBar
	lateinit var webviewOptionPopup: View
	lateinit var browserOptionsPopup: BrowserOptionsPopup
	lateinit var browserTopTitleSection: View
	lateinit var browserUrlEditFieldContainer: View
	lateinit var browserUrlEditField: EditText

	init {
		logger.d("Initializing BrowserFragmentTop...")
		initializeViews(browserFragment.safeFragmentLayoutRef)
		//setupWebSuggestions(browserFragment.safeFragmentLayoutRef)
		setupClicksEvents(browserFragment.safeFragmentLayoutRef)
	}

	/**
	 * Animate default favicon if no favicon is available.
	 * Stops animation when [shouldStopAnimation] is true.
	 */
	fun animateDefaultFaviconLoading(shouldStopAnimation: Boolean = false) {
		if (shouldStopAnimation) {
			logger.d("Stopping favicon animation")
			closeAnyAnimation(webViewFavicon)
			return
		}
		logger.d("Animating default favicon")
		val defaultFaviconResId = R.drawable.ic_button_browser_favicon
		Glide.with(safeMotherActivityRef).load(defaultFaviconResId).into(webViewFavicon)
		animateFadInOutAnim(webViewFavicon)
	}

	/**
	 * Initializes top bar views from the layout.
	 */
	private fun initializeViews(layoutView: View?) {
		logger.d("Initializing views for BrowserFragmentTop")
		layoutView?.let {
			browserTopTitleSection = layoutView.findViewById(R.id.top_actionbar_section)
			browserUrlEditFieldContainer = layoutView.findViewById(R.id.top_url_edit_section)
			browserUrlEditField = layoutView.findViewById(R.id.edit_field_url)
			webViewReloadButton = layoutView.findViewById(R.id.btn_browser_reload)
			webViewFavicon = layoutView.findViewById(R.id.img_browser_favicon)
			webviewTitle = layoutView.findViewById(R.id.edit_search_suggestion)
			webViewProgress = layoutView.findViewById(R.id.webview_progress_bar)
			webviewOptionPopup = layoutView.findViewById(R.id.btn_browser_options)
		}
	}

	/**
	 * Setup search suggestion watcher for the URL edit field.
	 */
	private fun setupWebSuggestions(layoutView: View?) {
		logger.d("Setting up web suggestions")
		layoutView?.let {
			SuggestionWatcher(browserUrlEditField, onClickItem = { searchQuery ->
				logger.d("Suggestion clicked: $searchQuery")
				browserUrlEditField.setText(searchQuery)
				loadUrlToBrowser()
			})
		}
	}

	/**
	 * Setup all top bar button click events (reload, navigation, etc.).
	 */
	private fun setupClicksEvents(layoutView: View?) {
		logger.d("Setting up click events for top bar")
		val slideNavigation = safeMotherActivityRef.sideNavigation

		layoutView?.apply {
			val clickActions = mapOf(
				findViewById<View>(R.id.btn_actionbar_back) to { invisibleUrlEditSection() },
				findViewById<View>(R.id.btn_clear_url_edit_field) to { clearEditTextField() },
				findViewById<View>(R.id.btn_load_url_to_browser) to { loadUrlToBrowser() },
				findViewById<View>(R.id.btn_open_navigation) to { slideNavigation?.openDrawerNavigation() },
				findViewById<View>(R.id.container_edit_browser_url) to { visibleUrlEditSection() },
				findViewById<View>(R.id.btn_browser_reload) to { toggleWebviewLoading() },
				findViewById<View>(R.id.btn_browser_options) to { openBrowserPopupOptions() }
			)

			clickActions.forEach { (view, clickAction) ->
				view.setOnClickListener { clickAction() }
			}
		}
	}

	/**
	 * Opens the browser options popup (lazy initialized).
	 */
	private fun openBrowserPopupOptions() {
		logger.d("Opening browser options popup")
		if (!::browserOptionsPopup.isInitialized) {
			browserOptionsPopup = BrowserOptionsPopup(browserFragment)
		}; browserOptionsPopup.show()
	}

	/**
	 * Toggles the loading state of the current WebView.
	 */
	private fun toggleWebviewLoading() {
		logger.d("Toggling WebView loading state")
		browserFragment.browserFragmentBody.webviewEngine.toggleCurrentWebViewLoading()
	}

	/**
	 * Shows the URL edit section and hides the title bar.
	 * Auto-fills current URL and shows the keyboard.
	 */
	fun visibleUrlEditSection() {
		logger.d("Showing URL edit section")
		browserTopTitleSection.visibility = View.INVISIBLE
		browserUrlEditFieldContainer.visibility = View.VISIBLE

		val browserWebEngine = browserFragment.getBrowserWebEngine()
		val currentWebviewUrl = browserWebEngine.currentWebView?.url
		currentWebviewUrl?.let {
			browserUrlEditField.setText(currentWebviewUrl)
			focusEditTextFieldAndShowKeyboard()
			browserUrlEditField.selectAll()
		}

		// Handle IME action (Search / Done)
		browserUrlEditField.setOnEditorActionListener { _, actionId, _ ->
			if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_SEARCH) {
				logger.d("Editor action triggered, loading URL")
				loadUrlToBrowser()
				true
			} else false
		}
	}

	/**
	 * Hides the URL edit section and shows the title bar.
	 */
	fun invisibleUrlEditSection() {
		logger.d("Hiding URL edit section")
		browserTopTitleSection.visibility = View.VISIBLE
		browserUrlEditFieldContainer.visibility = View.GONE
	}

	/**
	 * Focuses on the URL edit field and shows the keyboard.
	 */
	fun focusEditTextFieldAndShowKeyboard() {
		logger.d("Focusing on URL edit field and showing keyboard")
		browserUrlEditField.requestFocus()
		showOnScreenKeyboard(safeMotherActivityRef, browserUrlEditField)
	}

	/**
	 * Clears the text in the URL edit field and refocuses it.
	 */
	private fun clearEditTextField() {
		logger.d("Clearing URL edit field")
		browserUrlEditField.setText("")
		focusEditTextFieldAndShowKeyboard()
	}

	/**
	 * Loads the URL from the edit field into the current WebView.
	 * If input is not a valid URL, performs a Google search instead.
	 */
	private fun loadUrlToBrowser() {
		logger.d("Loading URL into WebView")
		hideOnScreenKeyboard(safeMotherActivityRef, browserUrlEditField)

		var urlToLoad = browserUrlEditField.text.toString()

		// Validate input, if not a URL → treat as search query
		urlToLoad = if (WEB_URL.matcher(urlToLoad).matches()) {
			logger.d("Valid URL entered: $urlToLoad")
			urlToLoad
		} else {
			logger.d("Invalid URL, treating as search query: $urlToLoad")
			"https://www.google.com/search?q=${encode(urlToLoad, UTF_8.toString())}"
		}

		val browserWebEngine = browserFragment.getBrowserWebEngine()
		browserWebEngine.loadURLIntoCurrentWebview(urlToLoad)

		invisibleUrlEditSection()
	}
}