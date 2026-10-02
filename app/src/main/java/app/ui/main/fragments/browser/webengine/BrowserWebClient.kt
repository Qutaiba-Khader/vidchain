package app.ui.main.fragments.browser.webengine

import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.net.toUri
import app.core.AIOApp.Companion.aioSettings
import app.core.engines.downloader.DownloadURLHelper.getFileInfoFromSever
import app.core.engines.video_parser.parsers.SupportedURLs.isM3U8Url
import app.ui.main.fragments.browser.webengine.WebVideoParser.resetVideoGrabbingButton
import lib.files.FileExtensions.ALL_DOWNLOADABLE_FORMATS
import lib.files.FileExtensions.ONLINE_VIDEO_FORMATS
import lib.files.FileSystemUtility
import lib.files.FileSystemUtility.decodeURLFileName
import lib.networks.URLUtility.isValidURL
import lib.networks.URLUtilityKT.normalizeEncodedUrl
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import java.io.ByteArrayInputStream
import java.net.URL

/**
 * Custom WebViewClient implementation that handles:
 * - Download detection for supported file formats
 * - URL interception and special case handling
 * - Favicon updates
 * - Video URL collection for in-page videos
 *
 * @property webviewEngine The parent WebViewEngine instance
 */
class BrowserWebClient(val webviewEngine: WebViewEngine) : WebViewClient() {

	/** Logger instance for debug messages and error tracking */
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to parent activity to prevent memory leaks */
	val safeMotherActivityRef = webviewEngine.safeMotherActivityRef

	/**
	 * Called when a new page starts loading in the WebView.
	 * Initiates analysis for downloadable links and resets UI components accordingly.
	 *
	 * @param webView The WebView that initiated the callback
	 * @param url The URL being loaded
	 * @param favicon The favicon for this page if available
	 */
	override fun onPageStarted(webView: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(webView, url, favicon)
		logger.d("Page started loading: $url")
		analyzeDownloadableLink(url)
		resetVideoGrabbingButton(webviewEngine)
	}

	/**
	 * Called when a page finishes loading in the WebView.
	 * Ensures any downloadable links on the page are analyzed.
	 *
	 * @param webView The WebView that initiated the callback
	 * @param url The URL of the page
	 */
	override fun onPageFinished(webView: WebView?, url: String?) {
		super.onPageFinished(webView, url)
		logger.d("Page finished loading: $url")
		analyzeDownloadableLink(url)
	}

	/**
	 * Intercepts URL loading requests from the WebView.
	 * Decides whether to handle the URL in the app or let WebView handle it.
	 *
	 * @param view The WebView initiating the request
	 * @param request The resource request containing the URL
	 * @return true if the host app handles the URL, false to let WebView proceed
	 */
	override fun shouldOverrideUrlLoading(
		view: WebView?,
		request: WebResourceRequest?
	): Boolean {
		val url = request?.url?.toString() ?: return true
		logger.d("Intercepted URL loading: $url")

		// Skip invalid schemes
		if (isInvalidScheme(url)) {
			logger.d("Ignoring invalid scheme for URL: $url")
			return true
		}

		// Handle special URLs like custom schemes or downloads
		if (isSpecialCaseUrl(url)) {
			logger.d("Handling special URL: $url")
			return handleSpecialUrl(url)
		}

		// Analyze other URLs for downloadable content
		analyzeDownloadableLink(url)
		return false
	}

	/**
	 * Intercepts resource requests from the WebView to detect downloadable video URLs.
	 *
	 * This method inspects each network request and:
	 * - Analyzes the URL if it is downloadable
	 * - Detects video file formats (like mp4, webm) and updates the WebView's video library
	 *
	 * @param view The WebView making the request
	 * @param request The resource request containing the URL and metadata
	 * @return A WebResourceResponse if the host app wants to handle it, or null to let WebView process normally
	 */
	override fun shouldInterceptRequest(
		view: WebView?,
		request: WebResourceRequest?
	): WebResourceResponse? {
		val url = request?.url.toString()
		if (url.isNotEmpty()) {
			logger.d("Intercepting resource request: $url")

			// Only process main frame requests to avoid analyzing CSS, JS, or image files
			if (request?.isForMainFrame == true && isDownloadableUrl(url)) {
				logger.d("Detected downloadable URL: $url")
				analyzeDownloadableLink(url)
			}

			// Detect known video file formats
			ONLINE_VIDEO_FORMATS.find { fileFormat ->
				url.endsWith(".$fileFormat", true) ||
						url.contains(".$fileFormat?", true) ||
						url.contains(".$fileFormat&", true)
			}?.let {
				webviewEngine.currentWebView?.let { currentWebView ->
					val webViewLists = webviewEngine
						.getListOfWebViewOnTheSystem()
						.filter { webView -> webView.id == currentWebView.id }

					if (webViewLists.isNotEmpty()) {
						val webViewId = webViewLists[0].id
						val listOfWebVideosLibrary = webviewEngine.listOfWebVideosLibrary
						synchronized(listOfWebVideosLibrary){
							listOfWebVideosLibrary
								.find { webVideosLibrary -> webVideosLibrary.webViewId == webViewId }
								?.addVideoUrlInfo(
									VideoUrlInfo(
										fileUrl = url,
										fileResolution = "",
										isM3U8 = isM3U8Url(url),
										totalResolutions = 0,     // Default until resolved
										fileDuration = 0L         // Default until resolved
									)
								)
							logger.d("Added video URL info for detected format: $it")
						}
					}
				}
			}
		}

		return super.shouldInterceptRequest(view, request)
	}

	/**
	 * Analyzes a given URL to determine if it points to downloadable content.
	 * If the URL is recognized as a downloadable file, triggers the download flow.
	 *
	 * @param url The URL to analyze, may be null
	 */
	private fun analyzeDownloadableLink(url: String?) {
		url?.let {
			val normalizedLink = normalizeEncodedUrl(url)
			if (!isDownloadableUrl(normalizedLink)) return
			logger.d("Downloadable link detected: $normalizedLink")
			triggerDownloadManually(normalizedLink)
		}
	}

	/**
	 * Performs the actual download preparation for a given URL.
	 * Checks for supported file formats, fetches server file info, and
	 * shows a download prompt dialog to the user.
	 *
	 * @param url The downloadable file URL
	 */
	private fun triggerDownloadManually(url: String) {
		try {
			ALL_DOWNLOADABLE_FORMATS.find { fileFormat ->
				url.endsWith(".$fileFormat", true) ||
						url.contains(".$fileFormat?", true) ||
						url.contains(".$fileFormat&", true)
			}?.let { detectedFormat ->
				logger.d("Detected downloadable file format: $detectedFormat")
				ThreadsUtility.executeInBackground(codeBlock = {
					if (isValidURL(url)) {
						val urlFileInfo = getFileInfoFromSever(fileUrl = URL(url))
						if (urlFileInfo.fileSize > 0) {
							val filename = decodeURLFileName(urlFileInfo.fileName)
							logger.d("File info retrieved: $filename, size: ${urlFileInfo.fileSize}")
							ThreadsUtility.executeOnMain {
								webviewEngine.webViewDownloadHandler.showDownloadAvailableDialog(
									url = url,
									contentLength = urlFileInfo.fileSize,
									userAgent = aioSettings.downloadHttpUserAgent,
									contentDisposition = null,
									safeWebEngineRef = webviewEngine,
									mimetype = FileSystemUtility.getMimeType(urlFileInfo.fileName),
									userGivenFileName = filename
								)
							}
						}
					}
				})
			}
		} catch (error: Exception) {
			logger.e("Error triggering download manually: ${error.message}", error)
		}
	}

	/**
	 * Checks whether a URL points to a supported downloadable file type.
	 *
	 * @param url The URL to check
	 * @return true if the URL matches known downloadable formats, false otherwise
	 */
	private fun isDownloadableUrl(url: String): Boolean {
		return ALL_DOWNLOADABLE_FORMATS.any { ext ->
			url.endsWith(".$ext", ignoreCase = true) ||
					url.contains(".$ext?", ignoreCase = true) ||
					url.contains(".$ext&", ignoreCase = true)
		}
	}

	/**
	 * Creates an empty WebResourceResponse
	 * @return Empty response with plain text type
	 */
	private fun createEmptyResponse(): WebResourceResponse {
		val byteArrayInputStream = ByteArrayInputStream(byteArrayOf())
		return WebResourceResponse("text/plain", "UTF-8", byteArrayInputStream)
	}

	/**
	 * Checks if URL scheme should be blocked
	 * @param url The URL to check
	 * @return true if scheme is invalid, false otherwise
	 */
	private fun isInvalidScheme(url: String): Boolean {
		val invalidSchemes = listOf(
			"sfbth://",
			"fb://",
			"intent://",
			"market://",
			"whatsapp://"
		)

		// Block if URL starts with any invalid scheme
		return invalidSchemes.any { url.startsWith(it) } ||
				// Also block malformed URLs without proper scheme
				!url.startsWith("http://") && !url.startsWith("https://")
	}

	/**
	 * Checks if URL matches special cases that need custom handling
	 * @param url The URL to check
	 * @return true if URL matches special patterns
	 */
	private fun isSpecialCaseUrl(url: String): Boolean {
		// Define patterns for special URLs that need custom handling
		val specialPatterns = listOf(
			Regex("facebook\\.com/.*app_link"),
			Regex("twitter\\.com/.*intent"),
			Regex("instagram\\.com/.*direct")
		)

		return specialPatterns.any { it.containsMatchIn(url) }
	}

	/**
	 * Handles special or deep link URLs that require custom behavior.
	 *
	 * For recognized schemes (e.g., Facebook), attempts to open the native app.
	 * Falls back to loading the corresponding web URL if the native app is unavailable.
	 *
	 * @param url The URL to handle
	 * @return true if the URL was handled by this method, false to allow normal WebView processing
	 */
	private fun handleSpecialUrl(url: String): Boolean {
		val context = webviewEngine.safeMotherActivityRef
		return when {
			url.startsWith("fb://") -> {
				logger.d("Handling Facebook deep link: $url")
				try {
					context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
					true
				} catch (error: Exception) {
					logger.e("Facebook app not available, falling back to web URL: ${error.message}", error)
					val webUrl = url.replace("fb://", "https://www.facebook.com/")
					webviewEngine.currentWebView?.loadUrl(webUrl)
					true
				}
			}
			// TODO: Add other app-specific deep links (Instagram, Twitter, etc.)
			else -> {
				logger.d("No special handling for URL: $url")
				false
			}
		}
	}
}