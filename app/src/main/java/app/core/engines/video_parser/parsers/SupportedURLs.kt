package app.core.engines.video_parser.parsers

import androidx.core.net.*
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.core.engines.video_parser.parsers.SupportedURLs.isYtdlpSupportedUrl
import app.core.engines.video_parser.parsers.SupportedURLs.isYtdlpSupportedUrlPattern
import lib.networks.URLUtilityKT.getBaseDomain
import lib.process.*
import java.net.*
import kotlin.text.RegexOption.*

/**
 * A utility object that provides functions for identifying, normalizing, and filtering
 * video URLs from various online platforms.
 *
 * This object is responsible for:
 * - Detecting URLs from major social media and media-sharing platforms like YouTube, Instagram,
 *   Facebook, Twitter/X, TikTok, and more.
 * - Normalizing specific URL formats, such as converting `youtu.be` links to a standard
 *   `youtube.com` format and removing playlist parameters.
 * - Validating whether a URL is supported for parsing by either `yt-dlp` or internal logic,
 *   based on its domain or by matching specific URL patterns.
 * - Identifying HLS stream URLs (`.m3u8`), which are common in web video players.
 */
object SupportedURLs {

	/**
	 * Logger instance for debugging and logging errors within the [SupportedURLs] object.
	 * Helps in tracing URL normalization, validation, and parsing steps.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * A curated set of base domains that are known to be supported for video parsing,
	 * either through yt-dlp or custom extraction logic within the app.
	 *
	 * This list is used for a quick, preliminary check to determine if a given URL
	 * is likely to contain a downloadable video. It includes major video hosting sites,
	 * social media platforms, and music services. This check is broader than the
	 * more specific regex patterns in [isYtdlpSupportedUrlPattern] and serves as a
	 * first-pass filter.
	 *
	 * The domains are stored without TLDs (e.g., "youtube" instead of "youtube.com")
	 * to simplify matching against the output of `URLUtilityKT.getBaseDomain`.
	 *
	 * @see isYtdlpSupportedUrl
	 */
	private val supportedBaseDomains = setOf(
		"youtube", "youtu", "facebook", "instagram", "twitter", "x",
		"tiktok", "reddit", "tumblr", "soundcloud", "bandcamp", "9gag",
		"vk", "imdb", "dailymotion", "bilibili", "twitch", "likee", "vimeo",
		"snapchat", "pinterest", "linkedin", "mixcloud", "audiomack",
		"periscope", "jiosaavn", "hotstar", "youku", "rumble", "odysee",
		"peertube", "bitchute", "liveleak"
	)

	/**
	 * Normalizes a YouTube URL to a standard `youtube.com/watch?v=<VIDEO_ID>` format.
	 *
	 * This function handles various YouTube URL formats:
	 * - Standard watch URLs (`youtube.com/watch?v=...`)
	 * - Shortened URLs (`youtu.be/...`)
	 * - Mobile URLs (`m.youtube.com/...`)
	 * - Music URLs (`music.youtube.com/watch?v=...`)
	 *
	 * It extracts the video ID and rebuilds the URL, removing any extra query parameters
	 * like `list`, `index`, `t`, `si`, or `feature`. This ensures that only the intended
	 * video is processed, preventing unintended playlist downloads.
	 *
	 * @param url The original YouTube URL, which can be in any common format.
	 * @return A clean, normalized YouTube watch URL. If the input is not a valid YouTube URL
	 *         or if the video ID cannot be extracted, the original URL is returned.
	 */
	@JvmStatic
	fun filterYoutubeUrlWithoutPlaylist(url: String): String {
		return try {
			// Ensure only YouTube links are processed
			if (!isYouTubeUrl(url)) {
				logger.d("URL is not YouTube: $url")
				return url
			}

			val uri = url.toUri()
			val host = uri.host ?: return url

			// Handle both youtu.be and youtube.com cases
			val normalizedUrl = when {
				host.contains("youtu.be") -> {
					// Extract video ID from the last path segment
					val videoId = uri.lastPathSegment ?: return url
					"https://www.youtube.com/watch?v=$videoId"
				}

				host.contains("youtube.com") -> {
					// Extract video ID from query parameter ?v=
					val videoId = uri.getQueryParameter("v") ?: return url
					"https://www.youtube.com/watch?v=$videoId"
				}

				else -> url
			}

			logger.d("Normalized YouTube URL: $normalizedUrl")
			normalizedUrl
		} catch (error: Exception) {
			logger.e("Error normalizing YouTube URL: ${error.message}", error)
			url
		}
	}

	/**
	 * Checks if the given URL belongs to YouTube.
	 *
	 * This method verifies the URL against standard YouTube domains (`youtube.com`, `youtu.be`)
	 * as well as the YouTube Music subdomain (`music.youtube.com`). It handles potential
	 * URL parsing errors gracefully.
	 *
	 * @param url The URL string to validate.
	 * @return `true` if the URL is identified as a YouTube link, `false` otherwise or if an error occurs.
	 */
	@JvmStatic
	fun isYouTubeUrl(url: String): Boolean {
		return try {
			val parsedUrl = URL(url)
			val host = parsedUrl.host

			host.endsWith("youtube.com", ignoreCase = true) ||
					host.endsWith("youtu.be", ignoreCase = true) ||
					url.contains("music.youtube", ignoreCase = true)
		} catch (error: Exception) {
			logger.e("Error while checking if URL is a YouTube link: ${error.message}", error)
			false
		}
	}

	/**
	 * Checks if the given URL belongs to Instagram by validating its host.
	 *
	 * This function supports the primary domain and common short domains used for sharing:
	 * - `instagram.com`: The main website.
	 * - `instagr.am`: Official short link.
	 * - `ig.me`: Short link often used in direct messages.
	 * - `ig.com`: A redirect domain.
	 *
	 * @param url The URL to check.
	 * @return `true` if the URL is from a recognized Instagram domain, `false` otherwise.
	 */
	@JvmStatic
	fun isInstagramUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("instagram.com") ||
					host.contains("instagr.am") ||        // Official Instagram short URL
					host.contains("ig.me") ||             // Often used in DMs for sharing
					host.contains("ig.com")               // Sometimes used for redirects
		} catch (error: Exception) {
			logger.e("Error while checking Instagram URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Facebook by checking its host.
	 *
	 * This function handles various Facebook domains, including:
	 * - `facebook.com`: The primary domain.
	 * - `m.facebook.com`: The mobile version of the site.
	 * - `fb.me`: Official short links used for sharing.
	 * - `fb.watch`: A dedicated domain for Facebook Watch videos.
	 *
	 * @param url The URL to check.
	 * @return `true` if the URL is from a recognized Facebook domain, `false` otherwise.
	 */
	@JvmStatic
	fun isFacebookUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("facebook.com") ||
					host.contains("m.facebook.com") ||    // Mobile site version
					host.contains("fb.me") ||             // Facebook’s official short links
					host.contains("fb.watch")             // Facebook video/watch pages
		} catch (error: Exception) {
			logger.e("Error while checking Facebook URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Checks if the given URL belongs to TikTok.
	 *
	 * This function recognizes both standard `tiktok.com` domains and the official
	 * short URL format `vm.tiktok.com`, which is commonly used when sharing videos.
	 *
	 * @param url The URL to check.
	 * @return `true` if the URL's host is a known TikTok domain, `false` otherwise.
	 */
	@JvmStatic
	fun isTiktokUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("tiktok.com") ||
					host.contains("vm.tiktok.com")        // TikTok’s shortened links
		} catch (error: Exception) {
			logger.e("Error while checking TikTok URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if a URL belongs to Pinterest.
	 *
	 * This function checks for the standard `pinterest.com` domain and the official `pin.it`
	 * shortener URL, which is commonly used for sharing pins.
	 *
	 * @param url The URL to check.
	 * @return `true` if the URL is from a recognized Pinterest domain, `false` otherwise.
	 */
	@JvmStatic
	fun isPinterestUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("pinterest.com") ||
					host.contains("pin.it")               // Common short domain for pins
		} catch (error: Exception) {
			logger.e("Error while checking Pinterest URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Twitter or its successor, X.
	 *
	 * This function checks for the following domains:
	 * - `twitter.com`: The legacy domain for Twitter.
	 * - `x.com`: The current official domain for X.
	 * - `t.co`: The official URL shortener used by the platform for all shared links.
	 *
	 * @param url The URL to check.
	 * @return True if the URL's host matches any of the known Twitter/X domains, false otherwise.
	 */
	@JvmStatic
	fun isTwitterOrXUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("twitter.com") ||
					host.contains("x.com") ||             // New official Twitter domain
					host.contains("t.co")                 // Twitter/X short URLs for posts
		} catch (error: Exception) {
			logger.e("Error while checking Twitter/X URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Snapchat.
	 *
	 * This check supports the standard `snapchat.com` domain as well as common short
	 * link domains used for sharing content:
	 * - `snp.ac`: Official short link for sharing profiles, Stories, or Spotlights.
	 * - `snap.link`: Often used for promotional content or direct links to Spotlights.
	 *
	 * @param url The URL string to validate.
	 * @return `true` if the URL is identified as a Snapchat link, `false` otherwise.
	 */
	@JvmStatic
	fun isSnapchatUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()
			host.contains("snapchat.com") ||
					host.contains("snp.ac") ||            // Snapchat’s short sharing links
					host.contains("snap.link")            // Promotional or spotlight links
		} catch (error: Exception) {
			logger.e("Error while checking Snapchat URL: ${error.message}", error)
			false
		}
	}

	/**
	 * Determines if the given URL belongs to Vimeo.
	 * Checks for the main `vimeo.com` domain and the `player.vimeo.com` domain used for embeds.
	 *
	 * @param url The URL to check.
	 * @return True if the host matches a known Vimeo domain, false otherwise.
	 */
	@JvmStatic
	fun isVimeoUrl(url: String): Boolean {
		return try {
			val host = URL(url).host.lowercase()

			// Vimeo main + player + shortened hosts
			host.contains("vimeo.com") ||
					host.contains("player.vimeo.com")

		} catch (error: Exception) {
			logger.e("Error while checking Vimeo URL: ${error.message}", error)
			false
		}
	}


	/**
	 * Checks whether the given URL is from a popular social media platform.
	 *
	 * This is a high-level check that aggregates several platform-specific checks.
	 * Supported platforms: Instagram, Facebook, TikTok, Pinterest, Twitter/X, and Snapchat.
	 * Note: YouTube is handled separately in functions like [isYouTubeUrl] and is not included here.
	 *
	 * @param url The URL to test.
	 * @return True if the URL's domain belongs to any of the supported social media platforms.
	 */
	@JvmStatic
	fun isSocialMediaUrl(url: String): Boolean {
		return isInstagramUrl(url) ||
				isFacebookUrl(url) ||
				isTiktokUrl(url) ||
				isPinterestUrl(url) ||
				isTwitterOrXUrl(url) ||
				isSnapchatUrl(url)
	}

	/**
	 * Determines if a URL is likely supported for video parsing.
	 *
	 * This function performs a broad check using two primary methods:
	 * 1.  **Domain Matching:** It extracts the base domain (e.g., "youtube" from "youtube.com")
	 *     and checks it against a predefined set of known supported platforms.
	 * 2.  **Stream Detection:** It checks if the URL is an HLS stream by looking for the `.m3u8`
	 *     extension.
	 *
	 * This method is designed for quick, initial filtering. For a stricter validation based on
	 * specific URL patterns (e.g., ensuring a path like `/watch?v=`), see
	 * [isYtdlpSupportedUrlPattern].
	 *
	 * @param url The URL to check.
	 * @return `true` if the URL's domain is in the supported list or if it's an M3U8 stream,
	 *         `false` otherwise.
	 */
	@JvmStatic
	fun isYtdlpSupportedUrl(url: String): Boolean {
		val baseDomain = getBaseDomain(url)
		val isSupportedUrl = supportedBaseDomains.contains(baseDomain) || isM3U8Url(url)
		return baseDomain != null && isSupportedUrl
	}

	/**
	 * Detects if a URL likely points to an M3U8 (HLS) playlist, a common format for streaming video.
	 *
	 * It performs a case-insensitive search for common M3U8 file names or extensions within the URL string,
	 * such as `.m3u8`, `playlist.m3u8`, or `index.m3u8`. This is a heuristic check and may produce
	 * false positives for URLs that coincidentally contain "m3u8".
	 *
	 * @param url The URL string to inspect.
	 * @return `true` if the URL contains a recognized M3U8 pattern, `false` otherwise.
	 */
	@JvmStatic
	fun isM3U8Url(url: String): Boolean {
		return url.contains("/playlist.m3u8", ignoreCase = true) ||
				url.contains("/index.m3u8", ignoreCase = true) ||
				url.contains(".m3u8", ignoreCase = true) ||
				url.contains("m3u8", ignoreCase = true)
	}

	/**
	 * Performs strict, regex-based pattern matching to validate if a URL points to a specific,
	 * parsable video or media page on supported platforms.
	 *
	 * This method is more precise than [isYtdlpSupportedUrl] because it checks for exact URL
	 * structures (e.g., a YouTube "watch" page, an Instagram "reel", or a Twitter "status")
	 * rather than just matching the base domain. It is used to quickly identify URLs that are
	 * known to be directly downloadable video pages, filtering out links to profiles, channels,
	 * or other unsupported content.
	 *
	 * The list of patterns should be updated as new, specific URL formats are supported.
	 *
	 * @param webpageUrl The full URL of the webpage to validate.
	 * @return `true` if the URL matches a known video page pattern, `false` otherwise.
	 */
	@JvmStatic
	fun isYtdlpSupportedUrlPattern(webpageUrl: String): Boolean {
		val patterns = listOf(
			// Instagram Reel
			Regex("""^https?://(www\.)?instagram\.com/(reel|p|tv|stories)/[A-Za-z0-9_.-]+/?.*""", IGNORE_CASE),

			// YouTube Watch page
			Regex("""^https?://(www\.)?youtube\.com/watch\?v=[A-Za-z0-9_-]+.*""", IGNORE_CASE),

			// YouTube Shorts
			Regex("""^https?://(www\.)?youtube\.com/shorts/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// YouTube Music
			Regex("""^https?://music\.youtube\.com/watch\?v=[A-Za-z0-9_-]+.*""", IGNORE_CASE),

			// YouTube Shortened link (youtu.be)
			Regex("""^https?://youtu\.be/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// Twitter / X status links
			Regex("""^https?://(www\.)?(twitter|x)\.com/[^/]+/status/\d+""", IGNORE_CASE),

			// Twitter status links (with optional query params)
			Regex("""^https?://(www\.)?twitter\.com/[^/]+/status/\d+.*""", IGNORE_CASE),

			// X.com status links (with optional query params)
			Regex("""^https?://(www\.)?x\.com/[^/]+/status/\d+.*""", IGNORE_CASE),

			// Mobile Twitter links (with optional query params)
			Regex("""^https?://(mobile\.)?twitter\.com/[^/]+/status/\d+.*""", IGNORE_CASE),

			// Twitter "i/status" embed links
			Regex("""^https?://(www\.)?(twitter|x)\.com/i/status/\d+.*""", IGNORE_CASE),

			// Pinterest pin
			Regex("""^https?://([a-z]+\.)?pinterest\.com/pin/\d+/?""", IGNORE_CASE),

			// Pinterest short links (pin.it)
			Regex("""^https?://(www\.)?pin\.it/[A-Za-z0-9]+/?""", IGNORE_CASE),

			// TikTok video
			Regex("""^https?://(www\.)?tiktok\.com/@[^/]+/video/\d+""", IGNORE_CASE),

			// Facebook video post
			Regex("""^https?://(www\.)?facebook\.com/.*/videos/\d+/?""", IGNORE_CASE),

			// Facebook watch page
			Regex("""^https?://(www\.)?facebook\.com/watch/\?v=\d+.*""", IGNORE_CASE),

			// Facebook reels
			Regex("""^https?://(www\.)?facebook\.com/reel/\d+/?""", IGNORE_CASE),

			// Facebook short links (fb.watch)
			Regex("""^https?://(www\.)?fb\.watch/[A-Za-z0-9_-]+/?""", IGNORE_CASE),

			// Snapchat Spotlight video
			Regex("""^https?://(www\.)?snapchat\.com/@[^/]+/spotlight/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// Snapchat Spotlight video (optional flexible)
			Regex("""^https?://(www\.)?snapchat\.com/@[^/]+/(spotlight|story|video)/[A-Za-z0-9_-]+""", IGNORE_CASE),

			// Dailymotion video link
			Regex("""^https?://(www\.)?dailymotion\.com/video/[A-Za-z0-9]+/?(\?.*)?$""", IGNORE_CASE),

			// Vimeo normal video: https://vimeo.com/123456789
			Regex("""^https?://(www\.)?vimeo\.com/\d+/?(\?.*)?$""", IGNORE_CASE),

			// Vimeo player links: https://player.vimeo.com/video/123456789
			Regex("""^https?://player\.vimeo\.com/video/\d+/?(\?.*)?$""", IGNORE_CASE),

			// Vimeo channel videos: https://vimeo.com/channels/staffpicks/123456789
			Regex("""^https?://(www\.)?vimeo\.com/channels/[^/]+/\d+/?(\?.*)?$""", IGNORE_CASE),

			// Vimeo album videos: https://vimeo.com/album/12345/video/67890123
			Regex("""^https?://(www\.)?vimeo\.com/album/\d+/video/\d+/?(\?.*)?$""", IGNORE_CASE),
		)

		return patterns.any { it.matches(webpageUrl) }
	}
}
