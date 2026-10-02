package app.core.engines.video_parser.parsers

import android.webkit.*
import app.core.engines.youtube.*
import lib.networks.URLUtilityKT.extractHostUrl
import lib.networks.URLUtilityKT.fetchWebPageContent
import lib.process.*
import org.jsoup.*

/**
 * Utility object responsible for extracting the thumbnail image URL from a video page.
 *
 * This parser primarily relies on Open Graph meta tags (`og:image`) present in the HTML
 * source of the video URL.
 */
object VideoThumbGrabber {
	
	/**
	 * Optional logger for debugging. Assign a `(String) -> Unit` lambda to receive log messages.
	 * By default, it's `null`, and no logging occurs.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Extracts the thumbnail image URL from a given video page URL.
	 *
	 * This function prioritizes performance by first checking for specialized handlers,
	 * like for YouTube Music, before falling back to a general-purpose HTML parsing method.
	 * The primary fallback strategy is to look for
	 * the Open Graph `og:image` meta tag within the page's HTML.
	 *
	 * ### Extraction Logic:
	 * 1.  **YouTube Music Special Case:** If the `videoUrl` is a YouTube Music URL, it
	 * attempts to fetch the thumbnail directly using a dedicated parser
	 * (`YouTubeVideoStreamParser`). If successful, this result is returned immediately.
	 * 2.  **HTML Fetching:** If not a special case, the function fetches the HTML content
	 * of the page. It can use a pre-fetched `userGivenHtmlBody` or fetch it on its own,
	 * with retries on failure.
	 * 3.  **HTML Parsing:** It then parses the HTML to find `<meta property="og:image" ...>` tags.
	 * 4.  **Validation:** The `content` attribute of the found meta tag is validated to ensure
	 * it is a URL pointing to a common image format (JPEG, JPG, PNG, GIF, WEBP).
	 *
	 * @param videoUrl The URL of the video page to parse.
	 * @param userGivenHtmlBody Optional. If the page's HTML content is already available,
	 * provide it here to prevent an additional network request.
	 * @param maxHtmlFetchRetries The number of times to retry fetching the HTML content if a network
	 * error occurs. This is ignored if `userGivenHtmlBody` is provided.
	 * @return The URL of the thumbnail image as a `String` if found, otherwise `null`.
	 */
	@JvmStatic
	fun startParsingVideoThumbUrl(
		videoUrl: String,
		userGivenHtmlBody: String? = null,
		maxHtmlFetchRetries: Int = 6,
	): String? {
		logger.d("Attempting to grab thumbnail for URL: $videoUrl")
		
		// For YouTube Music, it retrieves the thumbnail directly via [youtubeVidParser]
		val isYoutubeMusicPage = extractHostUrl(videoUrl).contains("music.youtube", true)
		if (isYoutubeMusicPage) {
			logger.d("YouTube Music URL detected. Using YouTubeVideoStreamParser.")
			val thumbnail = YouTubeVideoStreamParser.getThumbnail(videoUrl)
			if (thumbnail.isNullOrEmpty() == false) {
				logger.d("Found thumbnail for YouTube Music: $thumbnail")
				return thumbnail
			}
			logger.w("YouTubeVideoStreamParser failed to get thumbnail for YouTube Music URL.")
		}
		
		// Fetch HTML body either from user input or by making a network call
		val htmlBody = if (userGivenHtmlBody.isNullOrEmpty()) {
			logger.d("No HTML body provided, fetching from web...")
			fetchWebPageContent(videoUrl, true, maxHtmlFetchRetries) ?: return null
		} else {
			logger.d("Using user-provided HTML body.")
			userGivenHtmlBody
		}
		
		// Parse the HTML using Jsoup
		val document = Jsoup.parse(htmlBody)
		
		// Select meta tags with property="og:image"
		val ogImageMetaTags = document.select("meta[property=og:image]")
		logger.d("Found ${ogImageMetaTags.size} 'og:image' meta tags.")
		
		// Loop through each tag to find a valid image URL
		for (metaTag in ogImageMetaTags) {
			val ogImageContentUrl = metaTag.attr("content")
			val decodedOgImageUrl = org.jsoup.parser.Parser.unescapeEntities(ogImageContentUrl, true)
			logger.d("Checking meta tag content: $decodedOgImageUrl")
			
			// Regular expression to match common image formats
			val regexPattern = Regex(
				pattern = """https?://[^\s'"<>]+?\.(jpeg|jpg|png|gif|webp)(\?.*)?""",
				option = RegexOption.IGNORE_CASE
			)
			
			// Return the first valid image URL match
			if (regexPattern.containsMatchIn(decodedOgImageUrl)) {
				logger.i("Valid thumbnail URL found: $decodedOgImageUrl")
				return decodedOgImageUrl
			}
		}
		
		// No valid thumbnail found
		logger.w("No valid thumbnail URL found in meta tags for: $videoUrl")
		return null
	}
	
	/**
	 * Asynchronously extracts the `og:image` URL from the currently loaded page in a WebView.
	 *
	 * This extension function evaluates JavaScript to retrieve the page's full HTML content.
	 * It then parses the HTML using Jsoup to find the `<meta property="og:image" content="...">` tag
	 * and extracts its `content` attribute.
	 *
	 * The operation is performed on the main thread via `post` to ensure safe interaction
	 * with the WebView. The result is delivered through the provided callback.
	 *
	 * **Usage Example:**
	 * ```kotlin
	 * myWebView.getCurrentOgImage { imageUrl ->
	 *     if (imageUrl != null) {
	 *         // Use the extracted image URL
	 *         Log.d("WebViewUtils", "Found og:image: $imageUrl")
	 *     } else {
	 *         Log.w("WebViewUtils", "og:image tag not found.")
	 *     }
	 * }
	 * ```
	 *
	 * @receiver The `WebView` instance to inspect.
	 * @param onResult A callback function that will be invoked on the main thread with the
	 *                 `og:image` URL string if found and not blank, otherwise `null`.
	 */
	@JvmStatic
	fun WebView.getCurrentOgImage(onResult: (String?) -> Unit) {
		try {
			// Ensure this always runs on the main thread
			this.post {
				try {
					evaluateJavascript(
						"(function() { return document.documentElement.outerHTML; })();"
					) { unescapedHtml ->
						val parsedHtml = unescapedHtml
							.replace("\\u003C", "<")
							.replace("\\n", "\n")
							.replace("\\\"", "\"")
							.trim('"')
						
						logger.d("Trying to get og:image from WebView")
						val document = Jsoup.parse(parsedHtml)
						val imageUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
						val finalImageUrl = imageUrl?.takeIf { it.isNotBlank() }
						logger.d("og:image result: $finalImageUrl")
						onResult.invoke(finalImageUrl)
					}
				} catch (exception: Exception) {
					logger.e("Error evaluating JS in WebView", exception)
					onResult.invoke(null)
				}
			}
		} catch (exception: Exception) {
			logger.e("Error posting to WebView", exception)
			onResult.invoke(null)
		}
	}
	
}