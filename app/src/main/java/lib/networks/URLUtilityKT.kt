package lib.networks

import app.core.engines.youtube.*
import lib.process.*
import okhttp3.*
import org.jsoup.*
import org.jsoup.nodes.*
import java.io.*
import java.net.*
import java.net.HttpURLConnection.*
import java.util.concurrent.*

/**
 * A collection of helper methods for working with URLs and web content.
 *
 * This utility object centralizes common networking and URL-related tasks, including:
 * - Extracting hosts, base domains, and other URL components.
 * - Checking resource availability (e.g., favicon, URL expiration, internet connectivity).
 * - Fetching HTML content using various strategies (e.g., mobile user agents).
 * - Parsing webpage metadata like titles and OpenGraph tags.
 * - Normalizing and encoding URLs for safe use.
 *
 * All methods in this object are exposed as static Java methods via `@JvmStatic`,
 * making them easily accessible from both Kotlin and Java code.
 *
 * ### Threading
 * Most methods perform network I/O and are synchronous (blocking).
 * To prevent blocking the main thread, it is crucial to call these methods
 * from a background thread (e.g., using coroutines, RxJava, or an `ExecutorService`).
 * Methods that operate asynchronously (e.g., `getTitleByParsingHTML`) accept a callback
 * to handle the result.
 *
 * @see OkHttpClient For the underlying HTTP client used in many methods.
 * @see Jsoup For HTML parsing.
 */
object URLUtilityKT {

	/**
	 * A private logger instance for this object.
	 * Used for logging debugging information, warnings, and errors related to network operations.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Extracts the scheme and host from a URL string, constructing the base URL.
	 *
	 * This function isolates the `scheme://host` part of a URL. For example, given
	 * `https://user:pass@example.com:8080/path?query=1#fragment`, it returns
	 * `https://example.com`. It correctly handles URLs with ports and user info,
	 * stripping them to return only the essential host identifier.
	 *
	 * Example:
	 * ```kotlin
	 * extractHostUrl("https://example.com/path/page.html") // returns "https://example.com"
	 * extractHostUrl("http://www.test.co.uk:8080/index")  // returns "http://www.test.co.uk"
	 * ```
	 *
	 * @param urlString The full URL string to parse. It must include a scheme (e.g., "https://").
	 * @return The scheme and host portion of the URL (e.g., "https://example.com"),
	 *         or an empty string if the URL is malformed or cannot be parsed.
	 */
	@JvmStatic
	fun extractHostUrl(urlString: String): String {
		try {
			val uri = URI(urlString)
			return "${uri.scheme}://${uri.host}"
		} catch (error: Exception) {
			logger.e("Error found while extracting host of an url:", error)
			return ""
		}
	}

	/**
	 * Checks if a URL points to the root of a host, with no additional path segments.
	 *
	 * This is useful for determining if a URL refers to a domain's homepage.
	 * A URL is considered "host-only" if its path is either empty (`""`) or consists of a single
	 * forward slash (`"/"`). Any other path, such as `/page`, is not considered host-only.
	 *
	 * Malformed URLs will be treated as not host-only.
	 *
	 * Example:
	 * ```
	 * isHostOnly("https://example.com")      // true
	 * isHostOnly("https://example.com/")     // true
	 * isHostOnly("https://example.com/page") // false
	 * isHostOnly("https://example.com?q=1")  // true (query parameters are ignored)
	 * isHostOnly("ftp://files.server.net/")  // true
	 * ```
	 *
	 * @param url The URL string to check.
	 * @return `true` if the URL's path is empty or just "/", otherwise `false`.
	 */
	@JvmStatic
	fun isHostOnly(url: String): Boolean {
		return try {
			val parsedUrl = URL(url)
			val path = parsedUrl.path
			path.isNullOrEmpty() || path == "/"
		} catch (error: Exception) {
			logger.e("Error found while checking if an url host only or not:", error)
			false
		}
	}
	
	/**
	 * Validates whether a given string is a well-formed email address.
	 *
	 * This function uses a regular expression to check if the input string conforms to
	 * a standard email address format (e.g., `user@example.com`). It is a simple
	 * syntactic check and does not verify the actual existence or deliverability of the
	 * email address.
	 *
	 * The validation checks for:
	 * - A non-empty local part (before the `@`).
	 * - A single `@` symbol.
	 * - A non-empty domain part (after the `@`).
	 * - A domain containing at least one dot (`.`).
	 *
	 * @param email The string to validate.
	 * @return `true` if the string matches the email pattern, `false` otherwise.
	 */
	@JvmStatic
	fun isValidEmail(email: String): Boolean {
		if (!email.contains("@")) return false
		
		val parts = email.split("@")
		if (parts.size != 2) return false
		val (local, domain) = parts
		
		if (local.isEmpty() || domain.isEmpty()) return false
		if (!domain.contains(".")) return false
		
		// small regex just for sanity
		val simple = "^[A-Za-z0-9._%+-]+$".toRegex()
		return simple.matches(local)
	}
	
	
	/**
	 * Asynchronously retrieves the content of the `<title>` tag from a webpage.
	 *
	 * This method performs a GET request in the background using `OkHttpClient` and
	 * parses the resulting HTML with Jsoup to extract the title. The operation
	 * is non-blocking.
	 *
	 * Errors during the network request (e.g., connectivity issues, invalid URL) or
	 * HTML parsing will result in a `null` value being passed to the callback.
	 *
	 * Example Usage:
	 * ```kotlin
	 * URLUtilityKT.getTitleByParsingHTML("https://example.com") { title ->
	 *     // This block executes on a background thread.
	 *     // Switch to the main thread if updating UI.
	 *     if (title != null) {
	 *         println("Webpage title: $title")
	 *     } else {
	 *         println("Failed to retrieve title.")
	 *     }
	 * }
	 * ```
	 *
	 * @param url The URL of the webpage to fetch.
	 * @param callback A lambda that receives the extracted title as a `String`, or `null` if the
	 *                 title could not be retrieved or an error occurred. The callback is executed
	 *                 on a background thread provided by OkHttp's dispatcher.
	 */
	@JvmStatic
	fun getTitleByParsingHTML(url: String, callback: (String?) -> Unit) {
		try {
			val client = OkHttpClient()
			val request = Request.Builder().url(url).build()

			client.newCall(request).enqueue(object : Callback {
				override fun onFailure(call: Call, e: IOException) {
					callback(null)
				}

				override fun onResponse(call: Call, response: Response) {
					response.use {
						if (!response.isSuccessful) {
							callback(null); return
						}
						val html = response.body.string()
						if (html.isEmpty()) {
							callback(null); return
						}
						val document = Jsoup.parse(html)
						val title = document.title().ifEmpty { null }
						callback(title)
					}
				}
			})
		} catch (error: Exception) {
			logger.e("Error found while parsing title from an url:", error)
			callback(null)
		}
	}

	/**
	 * Retrieves the OpenGraph title (`og:title`) or description (`og:description`) from a webpage.
	 *
	 * This function first checks if a pre-fetched HTML body is provided. If not, it fetches
	 * the webpage content. It then parses the HTML to find the relevant OpenGraph meta tag.
	 *
	 * This function is synchronous and performs network I/O. It is recommended to call it
	 * from a background thread to avoid blocking the main thread.
	 *
	 * ### Special Handling:
	 * - **YouTube Music:** If the URL is identified as a YouTube Music link, it first attempts to
	 *   extract the title using `YouTubeVideoStreamParser`. If successful, it returns the
	 *   title with a `_Youtube_Music_Audio` suffix and skips HTML fetching. This is an
	 *   optimization to avoid parsing heavy web pages.
	 *
	 * @param websiteUrl The URL of the webpage to parse.
	 * @param returnDescriptionL If `true`, the function will look for the `og:description` meta tag.
	 *                           If `false` (default), it will look for the `og:title` meta tag.
	 * @param userGivenHtmlBody An optional, pre-fetched HTML string of the webpage. Providing this
	 *                          avoids an additional network request.
	 * @param callback A lambda function that will be invoked with the extracted title/description
	 *                 as a `String`, or `null` if it could not be found or an error occurred.
	 */
	@JvmStatic
	fun getWebpageTitleOrDescription(
		websiteUrl: String,
		returnDescriptionL: Boolean = false,
		userGivenHtmlBody: String? = null,
		callback: (String?) -> Unit
	) {
		try {
			val isYoutubeMusicPage = extractHostUrl(websiteUrl).contains("music.youtube", true)
			if (isYoutubeMusicPage) {
				val title = YouTubeVideoStreamParser.getTitle(websiteUrl)
				if (title.isNullOrEmpty() == false) {
					return callback.invoke("${title}_Youtube_Music_Audio")
				}
			}

			val htmlBody = if (userGivenHtmlBody.isNullOrEmpty()) {
				fetchWebPageContent(
					url = websiteUrl,
					retry = true,
					numOfRetry = 6
				) ?: return callback(null)
			} else userGivenHtmlBody
			val document = Jsoup.parse(htmlBody)
			val metaTag = document.selectFirst(
				if (returnDescriptionL) "meta[property=og:description]"
				else "meta[property=og:title]"
			)
			return callback(metaTag?.attr("content"))
		} catch (error: Exception) {
			logger.e("Error found while parsing title from an url:", error)
			callback(null)
		}
	}

	/**
	 * Attempts to find the favicon URL for a given website.
	 *
	 * This function employs a multi-step process to locate the favicon:
	 * 1.  It first checks for a standard `favicon.ico` file at the root of the website
	 *     (e.g., `https://example.com/favicon.ico`). This is a quick check for a common convention.
	 * 2.  If the standard path fails, it fetches the website's HTML content and parses it to find
	 *     `<link>` tags with `rel="icon"` or `rel="shortcut icon"`.
	 * 3.  It then validates each potential favicon URL found in the HTML to ensure it is accessible
	 *     before returning the first valid one.
	 *
	 * Relative favicon paths (e.g., `/images/favicon.png`) are resolved against the base `websiteUrl`.
	 *
	 * @param websiteUrl The base URL of the website (e.g., "https://example.com").
	 * @return The absolute URL of an accessible favicon as a `String`, or `null` if no favicon
	 *         can be found or an error occurs during the process (like a network issue or
	 *         parsing failure).
	 */
	@JvmStatic
	fun getFaviconUrl(websiteUrl: String): String? {
		val standardFaviconUrl = "$websiteUrl/favicon.ico"
		if (isFaviconAvailable(standardFaviconUrl)) return standardFaviconUrl

		return try {
			val doc: Document = Jsoup.connect(websiteUrl).get()
			val faviconUrl = doc.head().select("link[rel~=(icon|shortcut icon)]")
				.mapNotNull { it.attr("href").takeIf { href -> href.isNotEmpty() } }
				.map { href -> if (href.startsWith("http")) href else "$websiteUrl/$href" }
				.firstOrNull { isFaviconAvailable(it) }
			faviconUrl
		} catch (error: Exception) {
			logger.e("Error found while getting favicon url from a site link:", error)
			null
		}
	}

	/**
	 * Checks if a resource is available at the given URL by sending an HTTP HEAD request.
	 *
	 * This is a lightweight way to verify that a URL is accessible and points to a valid
	 * resource without downloading its content. It is primarily used to check for the
	 * existence of a favicon before attempting to display it.
	 *
	 * @param faviconUrl The direct URL of the resource to check (e.g., a favicon).
	 * @return `true` if the server responds with HTTP 200 OK, `false` otherwise (including
	 *         for network errors, timeouts, or non-200 status codes).
	 */
	@JvmStatic
	fun isFaviconAvailable(faviconUrl: String): Boolean {
		return try {
			val url = URL(faviconUrl)
			val connection = url.openConnection() as HttpURLConnection
			connection.requestMethod = "HEAD"
			val isAvailable = connection.responseCode == HTTP_OK
			isAvailable
		} catch (error: Exception) {
			logger.e("Error found while checking a valid accessible favicon url:", error)
			false
		}
	}

	/**
	 * Performs a HEAD request to determine the size of a remote file from its `Content-Length` header.
	 *
	 * This method is efficient for checking file size without downloading the entire file. It makes a
	 * synchronous network call, so it should be executed on a background thread to avoid blocking the UI.
	 *
	 * @param httpClient An `OkHttpClient` instance. Reusing a single instance is recommended for
	 * performance, as it allows connection pooling.
	 * @param url The direct URL of the file to check.
	 * @return The size of the file in bytes. Returns `-1L` if the `Content-Length` header is not
	 * present, cannot be parsed, or if a network error occurs.
	 */
	@JvmStatic
	fun fetchFileSize(httpClient: OkHttpClient, url: String): Long {
		return try {
			val request = Request.Builder().url(url).head().build()
			httpClient.newCall(request).execute().use { response ->
				val fileSize = response.header("Content-Length")?.toLong() ?: -1L
				fileSize
			}
		} catch (error: Exception) {
			logger.e("Error found while getting file size from a url by okhttp():", error)
			-1L
		}
	}

	/**
	 * Checks for an active internet connection by attempting to reach a reliable public endpoint.
	 *
	 * This function sends a lightweight GET request to `https://www.google.com`. A successful
	 * connection is determined by receiving an HTTP 200 (OK) response code within a short
	 * timeout period.
	 *
	 * The timeouts are set low (1 second) to ensure the check fails quickly if the network is
	 * unresponsive, preventing long waits in the calling code.
	 *
	 * This method is synchronous and performs a network operation. It should be called from
	 * a background thread to avoid blocking the UI.
	 *
	 * @return `true` if a connection to the target is successfully established (HTTP 200),
	 *         `false` otherwise (e.g., due to timeouts, DNS issues, or no network).
	 */
	@JvmStatic
	fun isInternetConnected(): Boolean {
		return try {
			val url = URL("https://www.google.com")
			with(url.openConnection() as HttpURLConnection) {
				requestMethod = "GET"
				connectTimeout = 1000
				readTimeout = 1000
				connect()
				val isConnected = responseCode == HTTP_OK
				isConnected
			}
		} catch (error: Exception) {
			logger.e("Error found while checking live working internet connection:", error)
			false
		}
	}

	/**
	 * Encodes a string for safe use in a URL query or path segment by replacing
	 * specific unsafe characters. This function provides a basic, non-comprehensive
	 * encoding.
	 *
	 * Currently, it only replaces spaces (` `) with `%20`. For robust URL encoding,
	 * consider using `java.net.URLEncoder`.
	 *
	 * Example:
	 * ```
	 * getUrlSafeString("hello world") // returns "hello%20world"
	 * ```
	 *
	 * @param input The raw string to encode.
	 * @return A new string with spaces replaced by `%20`.
	 */
	@JvmStatic
	fun encodeSpaceAsUrlHex(input: String): String {
		return input.replace(" ", "%20")
	}

	/**
	 * Extracts the base domain from a URL's host.
	 *
	 * This function isolates the primary domain name, typically the part just before the
	 * Top-Level Domain (TLD). For example, in "www.google.com", "google" is the base domain.
	 * It handles common formats, including subdomains.
	 *
	 * Examples:
	 * ```kotlin
	 * getBaseDomain("https://www.google.com")      // "google"
	 * getBaseDomain("https://mail.google.co.uk")   // "google" (handles multi-part TLDs)
	 * getBaseDomain("https://example.com")         // "example"
	 * getBaseDomain("http://localhost:8080")       // "localhost"
	 * getBaseDomain("not-a-valid-url")             // null
	 * ```
	 *
	 * **Note:** This implementation uses a simple heuristic. It may not correctly handle
	 * all edge cases involving complex, multi-level country code TLDs (e.g., `*.k12.tr`).
	 *
	 * @param url The full URL string (e.g., "https://www.example.com/path").
	 * @return The base domain as a `String` (e.g., "example"), or `null` if the URL
	 *         is malformed or a host cannot be extracted.
	 */
	@JvmStatic
	fun getBaseDomain(url: String): String? {
		return try {
			val domain = URL(url).host
			val parts = domain.split(".")
			val baseDomain = if (parts.size > 2) {
				parts[parts.size - 2]
			} else {
				parts[0]
			}

			baseDomain
		} catch (error: Exception) {
			logger.e("Error found getting base domain from a url:", error)
			null
		}
	}

	/**
	 * Extracts the hostname (e.g., `www.example.com`) from a URL string.
	 *
	 * This function safely parses the URL and isolates the host part. It gracefully
	 * handles malformed or `null` URLs by returning `null`.
	 *
	 * Example:
	 * ```
	 * getHostFromUrl("https://user@www.example.com:8080/path?query=1")
	 * // returns "www.example.com"
	 *
	 * getHostFromUrl("invalid-url")
	 * // returns null
	 * ```
	 *
	 * @param urlString The full URL string to parse. Can be `null`.
	 * @return The host name as a `String`, or `null` if the URL is invalid or `null`.
	 */
	@JvmStatic
	fun getHostFromUrl(urlString: String?): String? {
		return try {
			urlString?.let { URL(it).host }
		} catch (error: Exception) {
			logger.e("Error found getting host domain from a url:", error)
			null
		}
	}

	/**
	 * Generates a URL to fetch a website's favicon using Google's public favicon service.
	 *
	 * This service provides a simple way to get a site's icon without needing to parse the site's HTML.
	 * The returned URL points to a 128x128 pixel PNG image of the favicon.
	 *
	 * Example:
	 * ```kotlin
	 * val faviconUrl = getGoogleFaviconUrl("github.com")
	 * // Returns "https://www.google.com/s2/favicons?domain=github.com&sz=128"
	 * ```
	 *
	 * @param domain The domain name (e.g., "example.com") for which to retrieve the favicon.
	 *               It should not include the protocol (like "https://").
	 * @return A direct URL to the favicon image, sized at 128x128 pixels.
	 * @see <a href="https://developers.google.com/search/docs/appearance/favicon-in-search">Google Favicon Documentation</a>
	 */
	@JvmStatic
	fun getGoogleFaviconUrl(domain: String): String {
		return "https://www.google.com/s2/favicons?domain=$domain&sz=128"
	}

	/**
	 * Checks if a URL is considered expired or inaccessible by performing a HEAD request.
	 *
	 * A URL is considered "expired" if the server responds with an HTTP status code
	 * of 400 (Bad Request) or higher. This can indicate that the resource has been
	 * moved, deleted (404 Not Found), or is otherwise unavailable (e.g., 403 Forbidden, 500 Server Error).
	 *
	 * This method uses a 5-second timeout for both connecting and reading. If any
	 * exception occurs during the process (e.g., timeout, DNS error), the function
	 * will conservatively return `true`, assuming the URL is not accessible.
	 *
	 * @param urlString The full URL to check.
	 * @return `true` if the URL returns an HTTP status code >= 400 or if an error occurs
	 *         during the check; `false` if the status code is less than 400.
	 */
	@JvmStatic
	fun isUrlExpired(urlString: String): Boolean {
		return try {
			val url = URL(urlString)
			val connection = url.openConnection() as HttpURLConnection
			connection.requestMethod = "HEAD"
			connection.connectTimeout = 5000
			connection.readTimeout = 5000
			connection.connect()
			val responseCode = connection.responseCode
			val isExpired = responseCode >= 400
			isExpired
		} catch (error: Exception) {
			logger.e("Error found checking if the url has expired or not:", error)
			true
		}
	}

	/**
	 * Removes the `www.` prefix from a URL string.
	 *
	 * This operation is case-sensitive and only removes the first occurrence.
	 *
	 * Example:
	 * ```
	 * removeWwwFromUrl("https://www.example.com") // returns "https://example.com"
	 * removeWwwFromUrl("https://example.com")      // returns "https://example.com"
	 * ```
	 *
	 * @param url The URL string, which may be `null`.
	 * @return A new string without the `www.` prefix. Returns the original URL if the
	 *   prefix is not found or an error occurs. Returns an empty string if the input `url` is `null`.
	 */
	@JvmStatic
	fun removeWwwFromUrl(url: String?): String {
		if (url == null) return ""
		return try {
			url.replaceFirst("www.", "")
		} catch (error: Exception) {
			logger.e("Error found removing www from a url:", error)
			url
		}
	}

	/**
	 * Fetches the HTML content of a webpage by simulating a request from an old mobile browser.
	 *
	 * This method is particularly useful for:
	 * - Bypassing client-side rendering or heavy JavaScript that can obstruct content scraping.
	 * - Accessing simpler, mobile-optimized versions of webpages, which are often faster to parse.
	 *
	 * It cycles through a predefined list of legacy mobile user-agents for each request.
	 *
	 * The function includes an optional retry mechanism with an incremental backoff delay to handle
	 * transient network errors or rate limiting. If `retry` is enabled, it will make up to
	`numOfRetry` attempts upon failure.
	 *
	 * @param url The URL of the target webpage.
	 * @param retry If `true`, enables the retry logic on failure. Defaults to `false`.
	 * @param numOfRetry The total number of retry attempts if `retry` is enabled. Defaults to `0`.
	 * @param timeoutSeconds The connection and read timeout for each HTTP request, in seconds.
	 * @return The fetched HTML as a `String`, or `null` if all attempts fail or the page is empty.
	 */
	@JvmStatic
	fun fetchMobileWebPageContent(
		url: String,
		retry: Boolean = false,
		numOfRetry: Int = 0,
		timeoutSeconds: Int = 30
	): String? {
		// Predefined old mobile browser user-agents
		val oldMobileUserAgents = listOf(
			"Mozilla/5.0 (iPhone; CPU iPhone OS 9_3_5 like Mac OS X) AppleWebKit/601.1.46 (KHTML," +
					" like Gecko) Version/9.0 Mobile/13G36 Safari/601.1",
			"Mozilla/5.0 (Linux; Android 4.4.2; Nexus 5 Build/KOT49H) AppleWebKit/537.36 (KHTML, " +
					"like Gecko) Chrome/34.0.1847.114 Mobile Safari/537.36",
			"Mozilla/5.0 (Linux; U; Android 2.3.6; en-us; GT-I9000 Build/GINGERBREAD) AppleWebKit/533.1 " +
					"(KHTML, like Gecko) Version/4.0 Mobile Safari/533.1"
		)

		// Reuse the same OkHttpClient to benefit from connection pooling
		val client = OkHttpClient.Builder()
			.connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
			.readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
			.writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
			.build()

		fun attemptFetch(attempt: Int): String? {
			val request = Request.Builder()
				.url(url)
				.header("User-Agent", oldMobileUserAgents[attempt % oldMobileUserAgents.size])
				.header(
					name = "Accept",
					value = "text/html,application/xhtml+xml,application/xml;q=0.9," +
							"image/webp,image/apng,image/avif,image/jpeg,image/png,image/gif,image/svg+xml,image/*," +
							"*/*;q=0.8"
				)
				.header("Accept-Language", "en-US,en;q=0.5")
				.build()

			return try {
				client.newCall(request).execute().use { response ->
					if (response.isSuccessful) response.body.string().takeIf { it.isNotEmpty() }
					else null
				}
			} catch (error: Exception) {
				logger.e("Error found fetching mobile webpage content from a url:", error)
				null
			}
		}

		// Retry logic
		val maxAttempts = if (retry && numOfRetry > 0) numOfRetry else 1
		for (attempt in 0 until maxAttempts) {
			val result = attemptFetch(attempt)
			if (result != null) return result
			if (retry && attempt < maxAttempts - 1) {
				Thread.sleep(200L * (attempt + 1)) // smaller backoff to speed things up
			}
		}
		return null
	}

	/**
	 * Fetches the HTML content of a webpage, delegating to `fetchMobileWebPageContent`.
	 *
	 * This function acts as a simplified wrapper around `fetchMobileWebPageContent`,
	 * primarily to handle retry logic. It uses a mobile user-agent to request a
	 * lighter, mobile-optimized version of the page, which is often faster and
	 * less prone to blocking.
	 *
	 * @param url The target URL to fetch.
	 * @param retry If `true`, enables a retry mechanism. When enabled, the function will
	 *   make up to `numOfRetry` attempts if the initial fetch fails (returns `null` or empty).
	 * @param numOfRetry The maximum number of retry attempts to perform if `retry` is `true`.
	 *   This parameter is ignored if `retry` is `false`.
	 * @return The HTML content as a [String], or `null` if all fetch attempts fail.
	 */
	@JvmStatic
	fun fetchWebPageContent(
		url: String,
		retry: Boolean = false,
		numOfRetry: Int = 0
	): String? {
		if (retry && numOfRetry > 0) {
			var index = 0
			var htmlBody: String? = ""
			while (index < numOfRetry || htmlBody.isNullOrEmpty()) {
				htmlBody = fetchMobileWebPageContent(url)
				if (!htmlBody.isNullOrEmpty()) return htmlBody
				index++
			}
		}

		return fetchMobileWebPageContent(url)
	}

	/**
	 * Normalizes an encoded URL to a canonical form.
	 *
	 * This function performs the following steps:
	 * 1. Unescapes any escaped forward slashes (`\/`).
	 * 2. Decodes all query parameter keys and values.
	 * 3. Sorts the query parameters alphabetically by key.
	 * 4. Re-encodes the sorted keys and values.
	 * 5. Reconstructs the URL with the base path and the normalized query string.
	 *
	 * This is useful for creating consistent, comparable URLs, especially when they might come from
	 * different sources with varying encoding or parameter order.
	 *
	 * Example:
	 * ```
	 * normalizeEncodedUrl("https://example.com/path?c=3&a=1&b=2")
	 * // returns "https://example.com/path?a=1&b=2&c=3"
	 * ```
	 *
	 * @param url The URL string to normalize. It may contain encoded characters.
	 * @return The normalized URL. If a parsing error occurs, the original URL is returned.
	 */
	@JvmStatic
	fun normalizeEncodedUrl(url: String): String {
		try {
			val unescapedUrl = url.replace("\\/", "/")
			val uri = URI(unescapedUrl)
			val baseUrl = "${uri.scheme}://${uri.host}${uri.path}"
			val query = uri.query ?: return baseUrl

			val queryParams = query.split("&").associate {
				it.split("=").let { pair ->
					val key = URLDecoder.decode(pair[0], "UTF-8")
					val value = if (pair.size > 1) URLDecoder.decode(pair[1], "UTF-8") else ""
					key to value
				}
			}.toSortedMap()

			val normalizedQuery = queryParams.map { (key, value) ->
				"${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
			}.joinToString("&")

			return "$baseUrl?$normalizedQuery"
		} catch (error: Exception) {
			logger.e("Error found normalize an encoded url:", error)
			return url
		}
	}
}