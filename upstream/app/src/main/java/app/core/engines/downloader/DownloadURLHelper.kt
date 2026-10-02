package app.core.engines.downloader

import app.core.AIOApp.Companion.INSTANCE
import com.aio.R
import lib.networks.HttpClientProvider.okHttpClient
import lib.networks.URLUtilityKT.extractHostUrl
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URL
import java.net.URLEncoder.encode

/**
 * Singleton helper object for managing and processing download URLs.
 *
 * This object centralizes URL-related functionality used by the download system.
 * It is designed to handle common tasks related to remote file downloads,
 * providing a consistent and reusable interface across the application.
 *
 * Primary responsibilities include:
 * 1. **Validating and formatting URLs**
 *    Ensures that URLs are properly encoded, use supported schemes (HTTP/HTTPS),
 *    and are safe for use with the HTTP client.
 *
 * 2. **Extracting metadata from URLs**
 *    Provides utility functions to parse information such as file names,
 *    query parameters, or identifiers embedded in the URL. Useful when
 *    the server does not provide metadata in HTTP headers.
 *
 * 3. **Supporting download-specific URL transformations**
 *    For example, constructing byte-range requests, generating
 *    temporary signed URLs, or appending query parameters required
 *    by certain hosts or CDNs.
 *
 * This object is designed to be **stateless** aside from the logger,
 * making it safe to call from multiple threads or coroutines.
 * Future download-related URL utilities should be added here to
 * centralize URL handling logic and reduce duplication across the codebase.
 */
object DownloadURLHelper {

	/**
	 * Logger instance for this class.
	 *
	 * Used to log debug, info, warning, and error messages related
	 * to URL processing and download tasks.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	// Standard HTTP headers related to content and range
	private const val CONTENT_LENGTH = "Content-Length"         // Total size of the resource in bytes
	private const val ACCEPT_RANGE = "Accept-Ranges"           // Indicates if server supports partial downloads
	private const val BYTES = "bytes"                          // Value used in Range headers for byte ranges
	private const val E_TAG = "ETag"                           // Unique identifier for a specific version of the resource
	private const val LAST_MODIFIED = "Last-Modified"          // Timestamp of the last modification on the server
	private const val CONTENT_DISPOSITION = "Content-Disposition" // Suggests filename and content type when downloading
	private const val FILE_NAME = "filename="                  // Key used in Content-Disposition header to extract file name

	// Checksum algorithm constants
	private const val SHA_256 = "SHA-256"                      // Default checksum algorithm for file verification

	// Standard HTTP request headers
	private const val USER_AGENT = "User-Agent"               // Identifies the client application making the request
	private const val HOST = "Host"                           // Specifies the host being requested (required by HTTP/1.1)
	private const val REFERER = "Referer"                     // Indicates the page making the request (used for hotlink protection)
	private const val RANGE = "Range"                         // Requests a specific byte range for partial downloads
	private const val COOKIE = "Cookie"                       // Sends stored cookies to the server to maintain session

	/**
	 * Retrieves metadata about a remote file without downloading the entire file.
	 *
	 * This function performs an HTTP HEAD request (or a custom browser-style request) to gather:
	 * - File size
	 * - Support for multipart downloads
	 * - Resume capability (based on ETag or Last-Modified headers)
	 * - File name (from Content-Disposition header or URL path)
	 *
	 * Optionally, it can use a [DownloadDataModel] to customize headers such as User-Agent or Referer.
	 *
	 * @param fileUrl The URL of the remote file.
	 * @param downloadDataModel Optional [DownloadDataModel] containing global settings and site-specific info.
	 *
	 * @return A [RemoteFileInfo] object populated with the file metadata and error status if any.
	 */
	@JvmStatic
	fun getFileInfoFromSever(fileUrl: URL, downloadDataModel: DownloadDataModel? = null): RemoteFileInfo {
		val fileInfo = RemoteFileInfo()

		try {
			// Custom cookie jar for session persistence across requests
			val cookieJar = object : CookieJar {
				private val cookies = mutableMapOf<String, List<Cookie>>()

				override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
					this.cookies[url.host] = cookies
				}

				override fun loadForRequest(url: HttpUrl): List<Cookie> {
					return cookies[url.host] ?: emptyList()
				}
			}

			// Configure OkHttp client with cookie handling, redirects, and an interceptor to log redirects
			val httpClient: OkHttpClient = okHttpClient.newBuilder()
				.cookieJar(cookieJar = cookieJar)
				.followRedirects(followRedirects = true)
				.followSslRedirects(followProtocolRedirects = true)
				.addInterceptor { interceptorChain ->
					val httpRequest = interceptorChain.request()
					val httpResponse = interceptorChain.proceed(httpRequest)

					// Log redirect location if the response is a redirect
					if (httpResponse.isRedirect) {
						val location = httpResponse.header("Location")
						logger.d("Redirected to: $location")
					}
					httpResponse
				}
				.build()

			// Build request based on whether the download is from the browser
			val browserDownloadRequest = createHttpRequestBuilder(
				fileUrl.toString(),
				downloadDataModel?.globalSettings?.downloadHttpUserAgent,
				downloadDataModel?.siteReferrer
			).build()

			// Default HEAD request
			val normalDownloadRequest: Request = Request.Builder().url(fileUrl).head().build()

			// Choose the appropriate request
			val request = if (downloadDataModel != null && downloadDataModel.isDownloadFromBrowser)
				browserDownloadRequest else normalDownloadRequest

			// Execute the HTTP request and parse the response
			httpClient.newCall(request).execute().use { response ->
				if (response.isSuccessful) {
					// Extract file size from Content-Length header
					val contentLength = response.header(CONTENT_LENGTH)
					fileInfo.fileSize = contentLength?.toLong() ?: -1

					// Check if server supports multipart downloads
					val acceptRanges = response.header(ACCEPT_RANGE)
					if (acceptRanges == BYTES) fileInfo.isSupportsMultipart = true

					// Determine if the download can be resumed using ETag or Last-Modified
					val eTag = response.header(E_TAG)
					val lastModified = response.header(LAST_MODIFIED)
					if (fileInfo.isSupportsMultipart || eTag != null || lastModified != null) {
						fileInfo.isSupportsResume = true
					}

					// Attempt to extract file name from Content-Disposition header
					var fileName: String? = null
					val contentDisposition = response.header(CONTENT_DISPOSITION)
					if (contentDisposition != null && contentDisposition.contains(FILE_NAME)) {
						fileName = contentDisposition.split(FILE_NAME.toRegex())
							.dropLastWhile { it.isEmpty() }
							.toTypedArray()[1].replace("\"", "").trim()
					}

					// Fallback to file name from URL path if header not available
					if (fileName == null) {
						val path = fileUrl.path
						fileName = path.substring(path.lastIndexOf('/') + 1)
					}

					fileInfo.fileName = fileName.ifEmpty { getText(R.string.title_unknown_filename) }
				} else {
					// Handle unsuccessful responses
					fileInfo.isFileForbidden = true
					fileInfo.fileSize = -1
					fileInfo.errorMessage =
						"Failed to fetch file details: ${response.message} (HTTP ${response.code})"
				}
			}
		} catch (error: Exception) {
			// Catch all exceptions and populate error info
			fileInfo.isFileForbidden = true
			fileInfo.fileSize = -1
			fileInfo.errorMessage =
				"Error fetching file details: ${error.message ?: "Unknown error"}"
			logger.e(error)
		}

		return fileInfo
	}

	/**
	 * Builds an OkHttp [Request.Builder] for making HTTP requests with optional headers.
	 *
	 * This helper sets up common headers for downloading files:
	 * - Adds `Host` header from the URL’s host.
	 * - Adds a custom `User-Agent` if provided.
	 * - Adds a `Referer` header if provided (often required by some servers for access).
	 * - Adds a `Range` header for partial content requests (resumable downloads).
	 * - If the URL query contains a `response-content-disposition` parameter,
	 *   it sets a `Content-Disposition` header to specify the desired filename.
	 *
	 * @param fileUrl The full URL of the file to download.
	 * @param userAgent Optional User-Agent string to identify the client.
	 * @param siteReferer Optional referer URL to satisfy server-side restrictions.
	 * @param byteRange Optional byte range for partial requests (e.g., `"bytes=0-1024"`).
	 *
	 * @return A fully prepared [Request.Builder] ready to be used for a download request.
	 */
	@JvmStatic
	fun createHttpRequestBuilder(
		fileUrl: String,
		userAgent: String? = null,
		siteReferer: String? = null,
		siteCookie: String? = null,
		byteRange: String? = null
	): Request.Builder {
		// Parse the provided file URL
		val uri = URI(fileUrl)
		val host = uri.host
		val query = uri.query

		// Start building the request with the target URL
		val requestBuilder = Request.Builder().url(fileUrl)

		// Host header is often required for proper routing by the server
		requestBuilder.addHeader(HOST, host)

		// Include User-Agent if provided (some servers reject requests without it)
		if (!userAgent.isNullOrEmpty()) {
			requestBuilder.addHeader(USER_AGENT, userAgent)
		}

		// Include Referer if provided (often needed to bypass hotlink protection)
		if (!siteReferer.isNullOrEmpty()) {
			requestBuilder.addHeader(REFERER, extractHostUrl(siteReferer))
		}

		// Include site cookie if provided from the browser webview
		if (!siteCookie.isNullOrEmpty()) {
			requestBuilder.addHeader(COOKIE, siteCookie)
		}

		// If resuming a download, include the Range header to request partial data
		if (!byteRange.isNullOrEmpty()) {
			requestBuilder.addHeader(RANGE, byteRange)
		}

		// Some URLs include a query parameter indicating a desired filename
		// Example: ?response-content-disposition=filename.mp4
		val responseContentDisposition = query?.let { q ->
			q.split("&").find { it.contains("response-content-disposition") }
		}

		// If such a query parameter exists, decode and attach it as a Content-Disposition header
		responseContentDisposition?.let { dispositionParam ->
			val decodedDisposition = dispositionParam.split("=")[1]
			requestBuilder.addHeader(
				CONTENT_DISPOSITION,
				"attachment; filename=${encode(decodedDisposition, "UTF-8")}"
			)
		}

		// Return the fully built request builder
		return requestBuilder
	}

	/**
	 * Attempts to fetch a checksum for a remote file using HTTP response headers.
	 *
	 * Many servers include a checksum in the HTTP headers to let clients verify file integrity
	 * without downloading the full file. This function looks for such headers.
	 *
	 * The function:
	 * 1. Establishes a HEAD request to avoid downloading the file body.
	 * 2. Notifies the caller about the connection progress via [onConnectionStatus].
	 * 3. Checks common header fields (like `X-Checksum-<ALGO>`, `Content-MD5`, `ETag`) for a checksum.
	 * 4. Returns the checksum string if found, otherwise returns `null`.
	 *
	 * @param fileUrl The URL of the remote file.
	 * @param targetAlgorithm The desired checksum algorithm (e.g., `SHA-256`).
	 *                        Used to check algorithm-specific headers first.
	 * @param onConnectionStatus A callback invoked with status updates
	 *                           (e.g., "Connecting…", "Connected", "Found checksum in headers").
	 *
	 * @return A checksum string from the headers if available, otherwise `null`.
	 */
	@JvmStatic
	fun fetchChecksumFromHeaders(
		fileUrl: URL,
		targetAlgorithm: String = SHA_256,
		onConnectionStatus: (String) -> Unit
	): String? {
		try {
			// Notify that we are starting to connect
			var statusString = getText(R.string.title_connecting)
			onConnectionStatus(statusString)

			// Build a HEAD request so only headers are fetched, not the entire file
			val okHttpClient = okHttpClient
			val request = Request.Builder().url(fileUrl).head().build()

			okHttpClient.newCall(request).execute().use { httpResponse ->
				// Notify that we have connected
				statusString = INSTANCE.getString(R.string.title_connected_b_, httpResponse.code)
				onConnectionStatus(statusString)

				// If the response is not successful (e.g., 404, 500), report and return
				if (!httpResponse.isSuccessful) {
					statusString = INSTANCE.getString(R.string.title_failed_b_, httpResponse.code)
					onConnectionStatus(statusString)
					return null
				}

				// List of common HTTP header keys where checksums are often provided
				val commonHeaderKeys = listOf(
					"X-Checksum-$targetAlgorithm",   // custom header for specific algorithm
					"X-Checksum",                    // generic custom checksum header
					"Content-MD5",                   // standard header, usually base64-encoded MD5
					"ETag"                           // sometimes used as a weak checksum (not always reliable)
				)

				// Iterate over known header keys and return the first valid checksum found
				for (key in commonHeaderKeys) {
					httpResponse.header(key)?.let { raw ->
						statusString = INSTANCE.getString(R.string.title_found_in_headers_b_, key)
						onConnectionStatus(statusString)

						// Remove quotes and extra whitespace from header value
						return raw.trim().trim('"')
					}
				}

				// If no checksum header was found, notify and return null
				statusString = getText(R.string.title_no_checksum_header_found)
				onConnectionStatus(statusString)
			}
		} catch (error: Exception) {
			// Log and report any exception that occurs during the request
			logger.e("Error fetching checksum from HTTP headers:", error)
			onConnectionStatus("Error: ${error.message}")
		}

		// Return null if no checksum could be determined
		return null
	}
}
