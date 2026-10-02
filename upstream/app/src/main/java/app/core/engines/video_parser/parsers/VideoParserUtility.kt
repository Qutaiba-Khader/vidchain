package app.core.engines.video_parser.parsers

import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.internalDataFolder
import app.core.AIOApp.Companion.ytdlpInstance
import app.core.engines.video_parser.parsers.SupportedURLs.filterYoutubeUrlWithoutPlaylist
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import com.aio.R
import com.anggrayudi.storage.file.*
import com.yausername.youtubedl_android.*
import com.yausername.youtubedl_android.YoutubeDL.version
import lib.device.DateTimeUtils.calculateTime
import lib.files.FileSystemUtility.sanitizeFileNameExtreme
import lib.files.FileSystemUtility.sanitizeFileNameNormal
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.networks.DownloaderUtils.generateNetscapeFormattedCookieString
import lib.networks.URLUtilityKT.getBaseDomain
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.*
import lib.process.UniqueNumberUtils.getUniqueNumberForDownloadModels
import lib.texts.CommonTextUtils.getText
import lib.texts.CommonTextUtils.removeEmptyLines
import lib.texts.CommonTextUtils.safeCutString
import java.io.*

/**
 * A utility object that provides functions for interacting with the `yt-dlp` library
 * via its `youtubedl-android` wrapper. It is primarily used for extracting video
 * information, such as available formats and direct stream URLs.
 *
 * This object centralizes the logic for executing `yt-dlp` commands, handling
 * arguments like cookies and user-agents, and parsing the output.
 */
object VideoParserUtility {
	
	/**
	 * Logger for this class, used for logging various parsing activities and errors.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Fetches a list of available video formats for a given URL using yt-dlp, with a retry mechanism.
	 *
	 * This function attempts to extract video format information. If the initial attempt fails
	 * (returns an empty list), it will retry the operation up to a specified number of times.
	 *
	 * @param url The URL of the video to analyze.
	 * @param cookie Optional cookie string for authenticated requests.
	 * @param retryLimit The maximum number of times to attempt the fetch. Defaults to 1 (no retries).
	 * @return A list of [VideoFormat] objects. Returns an empty list if all attempts fail.
	 */
	@JvmStatic
	fun getYtdlpVideoFormatsWithRetry(
		url: String,
		cookie: String? = null, retryLimit: Int = 1
	): List<VideoFormat> {
		var attempts = 0 // Initialize attempt counter
		var videoFormats: List<VideoFormat>
		// Loop to attempt fetching video formats
		do {
			// Fetch the list of video formats
			videoFormats = getYtdlpVideoFormatsList(url, cookie)
			attempts++ // Increment the attempt counter
			// Continue retrying if the list is empty and the attempt limit has not been reached
		} while (videoFormats.isEmpty() && attempts < retryLimit)
		return videoFormats
	}
	
	/**
	 * Fetches the direct URL for a specific video format using yt-dlp, with a retry mechanism.
	 *
	 * This function attempts to resolve a format ID (e.g., "137") into a direct, streamable URL.
	 * If the initial attempt fails (e.g., due to a network timeout or temporary server issue),
	 * it will automatically retry the operation up to `maxRetries` times.
	 *
	 * @param videoFormatId The specific format ID to resolve (e.g., "137", "22").
	 * @param videoURL The URL of the video page (e.g., a YouTube watch page).
	 * @param videoCookie Optional cookie string for accessing private or members-only content.
	 * @param maxRetries The total number of attempts to make before giving up. Defaults to 1 (no retries).
	 * @return A [String] containing the direct streamable URL for the requested format,
	 *         or `null` if the URL could not be obtained after all retry attempts.
	 */
	@JvmStatic
	fun getYtdlpVideoFormatsUrlWithRetry(
		videoFormatId: String,
		url: String, cookie: String? = null, retryLimit: Int = 1
	): String? {
		var attempts = 0 // Initialize attempt counter
		var videoFormatUrl: String?
		// Loop to attempt fetching the video format URL
		do {
			// Fetch the direct URL for the specified format
			videoFormatUrl = getYtdlpVideoFormatUrl(
				videoFormatId = videoFormatId,
				videoURL = url,
				videoCookie = cookie
			); attempts++ // Increment the attempt counter
			// Continue retrying if the URL is null/empty and the attempt limit has not been reached
		} while (videoFormatUrl.isNullOrEmpty() && attempts < retryLimit)
		return videoFormatUrl
	}
	
	/**
	 * Executes a yt-dlp command to extract the direct stream URL for a specific video format.
	 *
	 * This function constructs and runs a yt-dlp request with options to get the URL (`--get-url`)
	 * for a given format ID. It handles cookies for authenticated sessions by writing them to a
	 * temporary file. It also sanitizes YouTube URLs to remove playlist parameters, ensuring
	 * only the single video is processed.
	 *
	 * @param videoFormatId The specific format ID (e.g., "22", "137+140") for which to retrieve the URL.
	 * @param videoURL The URL of the video.
	 * @param videoCookie Optional cookie string for handling authenticated or private videos.
	 * @return The direct stream URL for the requested format as a [String], or `null` if the
	 * extraction fails or an error occurs.
	 */
	@JvmStatic
	private fun getYtdlpVideoFormatUrl(
		videoFormatId: String,
		videoURL: String, videoCookie: String? = null
	): String? {
		try {
			logger.d(
				"Attempting to get direct URL for format '$videoFormatId' from '$videoURL'." +
					" Cookie is ${if (videoCookie.isNullOrEmpty()) "not provided" else "provided"}."
			)
			
			println(
				"Retrieving video format's ($videoFormatId) url : $videoURL " +
					"YT-DLP Version: ${version(INSTANCE)}"
			)
			
			// Filter out playlist parameters from YouTube URLs to process only the single video.
			var filteredUrl = videoURL
			if (isYouTubeUrl(videoURL)) {
				filteredUrl = filterYoutubeUrlWithoutPlaylist(videoURL)
			}
			
			val startTime = System.currentTimeMillis()
			// Create a temporary file to store cookies.
			val cookieTempFile = File(
				internalDataFolder.getAbsolutePath(INSTANCE),
				"${getUniqueNumberForDownloadModels()}.txt"
			)
			
			// If a cookie is provided, format it and save it to the temporary file.
			if (!videoCookie.isNullOrEmpty()) {
				val cookieString = generateNetscapeFormattedCookieString(videoCookie)
				if (saveStringToInternalStorage(cookieTempFile.name, cookieString)) {
					logger.d("Cookie file created successfully at '${cookieTempFile.absolutePath}'.")
				}
			}
			
			// Create a yt-dlp request to get the direct stream URL.
			val streamUrlRequest = YoutubeDLRequest(filteredUrl)
			streamUrlRequest.addOption("-f", videoFormatId) // Specify the format ID.
			streamUrlRequest.addOption("--get-url") // Command to retrieve only the URL.
			streamUrlRequest.addOption("--no-check-certificate") // Bypass SSL certificate validation.
			streamUrlRequest.addOption("--no-cache-dir") // Disable caching.
			streamUrlRequest.addOption("--skip-download") // Do not download the video.
			streamUrlRequest.addOption("--playlist-items", "1") // Process only the first item in a playlist.
			streamUrlRequest.addOption("--user-agent", aioSettings.downloadHttpUserAgent)
			
			// Add the cookie file to the request if it exists.
			if (cookieTempFile.exists() && cookieTempFile.canWrite()) {
				logger.d("Adding cookie file to yt-dlp request: ${cookieTempFile.absolutePath}")
				streamUrlRequest.addOption("--cookies", cookieTempFile.absolutePath)
			}
			
			// Execute the yt-dlp command and capture the output.
			val streamUrl = ytdlpInstance.execute(streamUrlRequest).out
			logger.i("Extracted Format URL =$streamUrl")
			logger.i("yt-dlp executed successfully. Output URL length: ${streamUrl.length}")
			
			// Clean up the temporary cookie file.
			if (cookieTempFile.exists()) {
				if (cookieTempFile.delete()) {
					logger.d("Temporary cookie file deleted.")
				}
			}
			val endTime = System.currentTimeMillis()
			val timeTaken = endTime - startTime
			
			logger.d("Yt-dlp execution time: ${calculateTime(timeTaken.toFloat())}")
			// Return the extracted direct URL.
			return streamUrl
			
		} catch (exception: Exception) {
			logger.e("Failed to get video format URL due to an exception.", exception)
			return null
		}
	}
	
	/**
	 * Executes a yt-dlp command to retrieve the list of available video formats for a given URL.
	 *
	 * This function prepares and executes a `YoutubeDLRequest` with the `-F` flag to list all
	 * available formats. It handles cookies for authenticated sessions by creating a temporary
	 * cookie file. The function also ensures that for YouTube URLs, any playlist parameters are
	 * stripped to analyze only the single video. It processes the raw text output from yt-dlp
	 * to produce a structured list of `VideoFormat` objects.
	 *
	 * @param url The URL of the video to analyze.
	 * @param cookie An optional cookie string for accessing private or login-protected content.
	 * @return A list of [VideoFormat] objects representing the available formats. Returns an empty
	 *         list if the extraction fails or no formats are found.
	 */
	@JvmStatic
	private fun getYtdlpVideoFormatsList(url: String, cookie: String? = null): List<VideoFormat> {
		try {
			logger.d(
				"Retrieving video formats list for URL: '$url'. " +
					"Cookie is ${if (cookie.isNullOrEmpty()) "not provided" else "provided"}."
			)
			logger.d("YT-DLP Version: ${version(INSTANCE)}")
			
			// Sanitize YouTube URLs to remove playlist parameters.
			var sanitizedUrl = url
			if (isYouTubeUrl(url)) sanitizedUrl = filterYoutubeUrlWithoutPlaylist(url)
			
			val startTime = System.currentTimeMillis()
			
			// Create a temporary file for cookies.
			val temporaryCookieFile = File(
				internalDataFolder.getAbsolutePath(INSTANCE),
				"${getUniqueNumberForDownloadModels()}.txt"
			)
			
			// If a cookie is provided, format and save it to the file.
			if (!cookie.isNullOrEmpty()) {
				val cookieString = generateNetscapeFormattedCookieString(cookie)
				saveStringToInternalStorage(temporaryCookieFile.name, cookieString)
				logger.d("Cookie file created at '${temporaryCookieFile.absolutePath}'.")
			}
			
			// Build the yt-dlp request.
			val request = YoutubeDLRequest(sanitizedUrl)
			request.addOption("-F") // List all available formats.
			request.addOption("--no-check-certificate") // Bypass SSL certificate validation.
			request.addOption("--no-cache-dir") // Disable caching.
			request.addOption("--skip-download") // Do not download the video.
			request.addOption("--user-agent", aioSettings.downloadHttpUserAgent) // Set user agent.
			
			// Add the cookie file to the request if it exists.
			if (temporaryCookieFile.exists() && temporaryCookieFile.canWrite()) {
				logger.d("Adding cookie file to yt-dlp request: ${temporaryCookieFile.absolutePath}")
				request.addOption("--cookies", temporaryCookieFile.absolutePath)
			}
			request.addOption("--playlist-items", "1") // Process only the first item in a playlist.
			
			// Execute the command and get the raw output.
			val response = ytdlpInstance.execute(request).out
			
			// Parse the raw output into a list of VideoFormat objects.
			val videoFormats = VideoFormatsUtils.getVideoFormatsList(response)
			
			// Clean up the temporary cookie file.
			if (temporaryCookieFile.exists()) {
				if (temporaryCookieFile.delete()) {
					logger.d("Temporary cookie file deleted.")
				}
			}
			
			val endTime = System.currentTimeMillis()
			val executionTimeMillis = endTime - startTime
			
			logger.d("Yt-dlp execution time: ${calculateTime(executionTimeMillis.toFloat())}")
			logger.i("Found ${videoFormats.size} video formats for URL: '$url'.")
			return videoFormats
		} catch (exception: Exception) {
			// Log any exceptions that occur and return an empty list.
			logger.e("Failed to get video formats list due to an exception.", exception)
			return emptyList()
		}
	}
	
	/**
	 * Sanitizes a video title for use as a filename or display text.
	 *
	 * If the original title from [videoInfo] is null or empty, it constructs a
	 * fallback title using details from the [videoFormat] and the video's domain.
	 * If even that fails, it returns a generic "unknown title" string.
	 *
	 * If a title exists, it is sanitized to remove illegal filename characters.
	 * It is then trimmed to a maximum length of 60 characters.
	 *
	 * @param videoInfo The [VideoInfo] object containing the original title and URL.
	 * @param videoFormat The selected [VideoFormat], used for generating a fallback title if
	 * the original is missing.
	 * @param useExtremeNameSanitizer If true, applies a more aggressive sanitization
	 * to remove a wider range of special characters. Defaults to `false`.
	 * @return A [CharSequence] representing the cleaned, sanitized, and trimmed title.
	 */
	@JvmStatic
	fun getSanitizedTitle(
		videoInfo: VideoInfo,
		videoFormat: VideoFormat,
		useExtremeNameSanitizer: Boolean = false
	): CharSequence {
		// Check if the video title is null or empty.
		if (videoInfo.videoTitle.isNullOrEmpty()) {
			logger.w("Video title is null or empty for URL '${videoInfo.videoUrl}'. Creating a fallback title.")
			
			// Construct a fallback title using format details and domain.
			val madeUpTitle = "${videoFormat.formatId}_" +
				"${videoFormat.formatResolution}_" +
				"${videoFormat.formatVcodec}_" +
				"${getBaseDomain(videoInfo.videoUrl)}"
			videoInfo.videoTitle = madeUpTitle
			logger.d("Generated fallback title: '$madeUpTitle'")
			
			// If the generated title is still empty, return a generic "unknown" string.
			return if (videoInfo.videoTitle.isNullOrEmpty()) {
				logger.e("Fallback title is also null or empty. Returning generic unknown title.")
				getText(R.string.title_unknown_video_title)
			} else videoInfo.videoTitle!!
			
		} else {
			// If a title exists, sanitize it.
			logger.d("Original title: '${videoInfo.videoTitle}', " +
				"Sanitizer: ${if (useExtremeNameSanitizer) "Extreme" else "Normal"}")
			
			val sanitizedName = if (useExtremeNameSanitizer) {
				// Use aggressive sanitization.
				sanitizeFileNameExtreme(videoInfo.videoTitle!!)
			} else sanitizeFileNameNormal(videoInfo.videoTitle!!)
			
			// Remove any empty lines/double slashes that might result from sanitization.
			val removedDoubleSlashes = removeEmptyLines(sanitizedName)
			
			// Trim the title to a maximum length.
			val finalVideoTitle = safeCutString(
				input = removedDoubleSlashes ?: "N/A",
				maxLength = 60
			) ?: "N/A"
			
			logger.i("Sanitized and trimmed title: '$finalVideoTitle'")
			// Return the final, cleaned title.
			return finalVideoTitle
		}
	}
	
	/**
	 * Fetches the title of a web page from a given URL.
	 *
	 * This function performs a network request to the specified URL and attempts to parse
	 * the `<title>` tag or the `og:title` / `description` meta tags from the HTML content.
	 * It serves as a best-effort attempt to get a human-readable title for a video link
	 * before running more intensive extraction processes.
	 *
	 * @param videoUrl The URL of the web page to fetch the title from.
	 * @return The extracted page title as a `String`, or an empty string if the title
	 * cannot be found or an error occurs.
	 */
	@JvmStatic
	fun getVideoTitleFromURL(videoUrl: String): String {
		val title = getWebpageTitleOrDescription(videoUrl) { result -> result.toString() }
		return title.toString()
	}
}