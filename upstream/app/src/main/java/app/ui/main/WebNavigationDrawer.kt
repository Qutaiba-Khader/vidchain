package app.ui.main

import android.view.View
import android.webkit.WebView
import android.widget.ListView
import androidx.core.view.isVisible
import app.core.AIOApp.Companion.aioSettings
import app.ui.main.fragments.browser.webengine.WebTabListAdapter
import app.ui.main.fragments.browser.webengine.WebViewEngine
import com.aio.R
import com.bumptech.glide.Glide
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.ui.ViewUtility
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.showView
import lib.ui.builders.ToastView
import java.lang.ref.WeakReference

/**
 * Manages the web navigation drawer functionality.
 *
 * Responsibilities:
 * - Handles creation and management of browser tabs
 * - Controls the navigation drawer UI and state
 * - Maintains the list of active web views
 * - Coordinates tab switching and lifecycle events
 */
class WebNavigationDrawer(motherActivity: MotherActivity?) {

	private val logger = LogHelperUtils.from(javaClass)

	// Weak reference to parent activity to prevent memory leaks
	val safeMotherActivityRef = WeakReference(motherActivity).get()

	// List of all active web views
	val totalWebViews: ArrayList<WebView> = ArrayList()

	// UI Components
	lateinit var sideNavigationDrawer: View
	lateinit var buttonAddNewTab: View
	lateinit var browserTabsListView: ListView
	lateinit var webTabListAdapter: WebTabListAdapter

	/**
	 * Initializes the navigation drawer components.
	 * Must be called after activity layout is rendered.
	 */
	fun initialize() {
		logger.d("Initializing WebNavigationDrawer")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			safeMotherActivityRef.apply {
				logger.d("Finding navigation drawer views")
				sideNavigationDrawer = findViewById(R.id.navigation_drawer)
				buttonAddNewTab = findViewById(R.id.btn_create_new_tab)
				browserTabsListView = findViewById(R.id.list_browser_tabs)

				logger.d("Setting up event listeners")
				initializeClickEvents()
				initializeDrawerListener()
			}
		}
	}

	/**
	 * Initializes the web tab list adapter if not already initialized.
	 */
	private fun initializeWebListAdapter() {
		logger.d("Initializing web list adapter")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			safeMotherActivityRef.browserFragment?.let {
				if (browserTabsListView.adapter == null) {
					logger.d("Creating new WebTabListAdapter")
					browserTabsListView.adapter =
						if (!::webTabListAdapter.isInitialized) {
							// Create new adapter if needed
							webTabListAdapter = WebTabListAdapter(it, totalWebViews)
							webTabListAdapter
						} else webTabListAdapter
				}
			}
		}
	}

	/**
	 * Initializes drawer state listener and updates adapter.
	 */
	private fun initializeDrawerListener() {
		logger.d("Initializing drawer listener")
		safeMotherActivityRef?.let { _ ->
			if (::webTabListAdapter.isInitialized) {
				logger.d("Notifying adapter of data changes")
				webTabListAdapter.notifyDataSetChanged()
			}
		}
	}

	/**
	 * Sets up click event listeners for drawer components.
	 */
	private fun initializeClickEvents() {
		logger.d("Initializing click events")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			buttonAddNewTab.setOnClickListener {
				logger.d("Add new tab button clicked")
				val browserFragment = safeMotherActivityRef.browserFragment
				val browserFragmentBody = browserFragment?.browserFragmentBody
				val webviewEngine = browserFragmentBody?.webviewEngine

				webviewEngine?.apply {
					logger.d("Adding new tab with homepage: ${aioSettings.browserDefaultHomepage}")
					addNewBrowsingTab(aioSettings.browserDefaultHomepage, this)
					this.safeMotherActivityRef.openBrowserFragment()
				}
			}
		}
	}

	/**
	 * Opens the navigation drawer with animation.
	 */
	fun openDrawerNavigation() {
		logger.d("Opening navigation drawer")
		if (sideNavigationDrawer.isVisible) {
			logger.d("Drawer already visible, hiding it first")
			hideView(sideNavigationDrawer, true, 100)
		} else {
			showView(sideNavigationDrawer, true, 100)
		}
	}

	/**
	 * Closes the navigation drawer with animation.
	 */
	fun closeDrawerNavigation() {
		logger.d("Closing navigation drawer")
		hideView(sideNavigationDrawer, true, 100)
	}

	/**
	 * Checks if the drawer is currently open.
	 * @return true if drawer is visible, false otherwise
	 */
	fun isDrawerOpened(): Boolean {
		val isOpen = sideNavigationDrawer.isVisible
		logger.d("Checking if drawer is opened: $isOpen")
		return isOpen
	}

	/**
	 * Adds a new browsing tab with the specified URL.
	 * @param url The URL to load in the new tab
	 * @param webviewEngine The web view engine to use for the new tab
	 */
	fun addNewBrowsingTab(url: String, webviewEngine: WebViewEngine) {
		logger.d("Adding new browsing tab with URL: $url")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			initializeWebListAdapter()

			webviewEngine.generateNewWebview()?.let { generatedWebView ->
				logger.d("Generated new WebView, configuring it")
				webviewEngine.currentWebView = (generatedWebView as WebView)

				val browserFragment = safeMotherActivityRef.browserFragment
				val webViewContainer = browserFragment?.getBrowserWebViewContainer()

				logger.d("Updating UI with new WebView")
				webViewContainer?.removeAllViews()
				webViewContainer?.addView(webviewEngine.currentWebView)

				logger.d("Pausing other tabs and activating new one")
				totalWebViews.forEach { webView -> webView.onPause() }
				webviewEngine.updateEngineOfWebView(webviewEngine.currentWebView!!)
				webviewEngine.loadURLIntoCurrentWebview(url)

				logger.d("Adding new tab to list and updating UI")
				totalWebViews.add(0, webviewEngine.currentWebView!!)
				webTabListAdapter.notifyDataSetChanged()
				closeDrawerNavigation()
			}
		}
	}

	/**
	 * Closes a web view tab at the specified position.
	 * @param position The position of the tab to close
	 * @param correspondingWebView The web view to close
	 */
	fun closeWebViewTab(position: Int, correspondingWebView: WebView) {
		logger.d("Closing web view tab at position: $position")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			initializeWebListAdapter()

			try {
				val browserFragment = safeMotherActivityRef.browserFragment
				val browserWebviewEngine = browserFragment?.getBrowserWebEngine()

				// Handle case when closing last tab
				if (position == 0 && totalWebViews.size == 1) {
					logger.d("Closing last remaining tab")
					totalWebViews.remove(correspondingWebView)
					webTabListAdapter.notifyDataSetChanged()

					browserWebviewEngine?.let {
						logger.d("Creating new default tab")
						addNewBrowsingTab(
							webviewEngine = it,
							url = aioSettings.browserDefaultHomepage
						)
					}
					closeDrawerNavigation()
					return
				}

				logger.d("Removing tab from list")
				totalWebViews.remove(correspondingWebView)
				webTabListAdapter.notifyDataSetChanged()
				totalWebViews.forEach { webView -> webView.onPause() }

				// Determine which tab to show next
				if (position == 0) {
					val nextPosition = if (totalWebViews.isNotEmpty()) 0 else -1
					if (nextPosition != -1) {
						logger.d("Opening next tab at position: $nextPosition")
						openWebViewTab(totalWebViews[nextPosition])
					} else {
						browserWebviewEngine?.let {
							logger.d("No tabs left, creating new default tab")
							addNewBrowsingTab(
								webviewEngine = it,
								url = aioSettings.browserDefaultHomepage
							)
						}
						delay(
							timeInMile = 200,
							listener = object : OnTaskFinishListener {
								override fun afterDelay() = closeDrawerNavigation()
							})
					}
				} else {
					logger.d("Opening previous tab at position: ${position - 1}")
					openWebViewTab(totalWebViews[position - 1])
				}

				logger.d("Cleaning up closed web view")
				correspondingWebView.clearHistory()
				correspondingWebView.onPause()
				correspondingWebView.loadUrl("about:blank")
				ViewUtility.unbindDrawables(correspondingWebView)
				System.gc()
			} catch (error: Exception) {
				logger.e("Error closing web view tab: ${error.message}", error)
				safeMotherActivityRef.doSomeVibration(50)
				ToastView.showToast(
					activityInf = safeMotherActivityRef,
					msgId = R.string.title_something_went_wrong
				)
			}
		}
	}

	/**
	 * Opens and activates the specified web view tab.
	 * @param targetWebview The web view to activate
	 */
	fun openWebViewTab(targetWebview: WebView) {
		logger.d("Opening web view tab: ${targetWebview.url}")
		safeMotherActivityRef?.let { safeMotherActivityRef ->
			initializeWebListAdapter()
			try {
				val browserFragment = safeMotherActivityRef.browserFragment
				val browserWebviewEngine = browserFragment?.getBrowserWebEngine()
				val browserWebChromeClient = browserWebviewEngine?.browserWebChromeClient
				val browserWebViewContainer = browserFragment?.getBrowserWebViewContainer()
				val browserWebviewFavicon = browserFragment?.browserFragmentTop?.webViewFavicon

				logger.d("Updating current web view reference")
				browserWebviewEngine?.currentWebView = targetWebview

				logger.d("Updating UI with target web view")
				browserWebViewContainer?.removeAllViews()
				browserWebViewContainer?.addView(targetWebview)

				logger.d("Configuring web view")
				browserWebviewEngine?.updateEngineOfWebView(targetWebview)
				browserFragment?.browserFragmentTop?.webviewTitle?.text = if (
					targetWebview.title.isNullOrEmpty()
				) targetWebview.url else targetWebview.title

				targetWebview.requestFocus()
				browserWebviewEngine?.resumeCurrentWebView()

				logger.d("Updating progress and favicon")
				browserWebChromeClient?.onProgressChanged(targetWebview, targetWebview.progress)

				// Update favicon
				browserFragment?.browserFragmentTop?.animateDefaultFaviconLoading(true)
				targetWebview.favicon?.let { favicon ->
					browserWebviewFavicon?.let { imageView ->
						Glide.with(safeMotherActivityRef).load(favicon).into(imageView)
					}
				}
			} catch (error: Exception) {
				logger.e("Error opening web view tab: ${error.message}", error)
				safeMotherActivityRef.doSomeVibration(50)
				ToastView.showToast(
					activityInf = safeMotherActivityRef,
					msgId = R.string.title_something_went_wrong
				)
			}
		}
	}
}