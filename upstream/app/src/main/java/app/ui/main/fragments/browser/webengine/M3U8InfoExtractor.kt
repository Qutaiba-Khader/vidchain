package app.ui.main.fragments.browser.webengine

import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.aio.R
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.process.ThreadsUtility.executeOnMain
import lib.texts.CommonTextUtils.getText
import java.util.regex.Pattern

/**
 * Handles asynchronous extraction of video metadata such as resolutions and duration.
 *
 * Each extraction task runs in a background thread using [ThreadsUtility],
 * ensuring that UI responsiveness is maintained. Results or errors are returned
 * through the [InfoCallback] interface on the main thread.
 */
class M3U8InfoExtractor {

	/** Logger instance for debug messages and error tracking */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Fetches and parses an HLS (.m3u8) playlist to extract available video resolutions
	 * and stream duration using ExoPlayer’s [HlsPlaylistParser].
	 *
	 * This function runs asynchronously using [ThreadsUtility.executeInBackground] to avoid
	 * blocking the UI thread. The extracted data (resolutions and duration) are delivered
	 * back via the provided [InfoCallback].
	 *
	 * **Workflow:**
	 * 1. Initializes a custom HTTP data source with a mobile user agent.
	 * 2. Opens the playlist URL and parses it using [HlsPlaylistParser].
	 * 3. Extracts both resolution variants and total duration.
	 * 4. Returns results to the main thread through callback functions.
	 *
	 * @param m3u8Url The remote HLS playlist URL (e.g., “https://example.com/video.m3u8”).
	 * @param callback The callback interface for delivering results or errors.
	 *
	 * @throws IOException If network or stream reading fails.
	 * @throws ParserException If the M3U8 file is malformed or incompatible.
	 */
	@OptIn(UnstableApi::class)
	fun getDurationAndResolutionFrom(m3u8Url: String, callback: InfoCallback) {
		logger.d("Start extraction for: $m3u8Url")

		ThreadsUtility.executeInBackground(codeBlock = {
			// Step 1: Configure HTTP data source with user-agent
			val factory = DefaultHttpDataSource.Factory()
			val ua = getText(R.string.title_mobile_user_agent)
			val dataSourceFactory = factory.setUserAgent(ua)
			val parser = HlsPlaylistParser()

			try {
				// --- Extract Resolutions ---
				val dataSource1 = dataSourceFactory.createDataSource()
				val spec1 = DataSpec(m3u8Url.toUri())
				dataSource1.open(spec1)
				val resolutions = DataSourceInputStream(dataSource1, spec1).use { stream ->
					extractResolution(parser, spec1, stream, m3u8Url)
				}

				// --- Extract Duration (new stream) ---
				val dataSource2 = dataSourceFactory.createDataSource()
				val spec2 = DataSpec(m3u8Url.toUri())
				dataSource2.open(spec2)
				val duration = DataSourceInputStream(dataSource2, spec2).use { stream ->
					extractDuration(parser, spec2, stream, m3u8Url)
				}

				logger.d("Done: resolutions=$resolutions, duration=${duration}ms")
				executeOnMain { callback.onResolutionsAndDuration(resolutions, duration) }
			} catch (err: Exception) {
				logger.e("Extraction failed for: $m3u8Url", err)
				executeOnMain { callback.onError("Extraction failed: ${err.message}") }
			}
		})
	}

	/**
	 * Extracts available video resolutions from an HLS `.m3u8` playlist.
	 *
	 * Supports both master and media playlists using ExoPlayer’s [HlsPlaylistParser].
	 * - **Master playlists**: Extracts resolutions from each variant entry (e.g., `1920×1080`, `720p`).
	 * - **Media playlists**: Attempts to infer resolution from the URL or defaults to a fallback string.
	 *
	 * @param hlsPlaylistParser The parser used to interpret HLS playlists.
	 * @param dataSpec The [DataSpec] representing the media request metadata.
	 * @param sourceInputStream The [DataSourceInputStream] to read the playlist contents.
	 * @param m3u8FileLink The URL of the target HLS playlist file.
	 * @return A list of available video resolutions, or an empty list if none are found.
	 *
	 * @throws IOException If unable to read the playlist data.
	 * @throws ParserException If the playlist content is invalid.
	 */
	@OptIn(UnstableApi::class)
	private fun extractResolution(
		hlsPlaylistParser: HlsPlaylistParser,
		dataSpec: DataSpec,
		sourceInputStream: DataSourceInputStream,
		m3u8FileLink: String
	): List<String> {
		logger.d("Starting M3U8 resolution extraction for: $m3u8FileLink")

		return try {
			val hlsPlaylist = hlsPlaylistParser.parse(dataSpec.uri, sourceInputStream)
			when (val playlist = hlsPlaylist) {
				is HlsMultivariantPlaylist -> {
					logger.d("Master playlist detected with ${playlist.variants.size} variants.")
					val resolutions = playlist.variants.mapNotNull { variant ->
						when {
							variant.format.width > 0 && variant.format.height > 0 ->
								"${variant.format.width}×${variant.format.height}"

							variant.format.height > 0 -> "${variant.format.height}p"
							else -> null
						}
					}.distinct()

					logger.d("Extracted distinct resolutions: $resolutions")
					resolutions
				}

				is HlsMediaPlaylist -> {
					logger.d("Media playlist detected. Attempting resolution extraction.")
					val inferredResolution = extractResolutionFromUrl(m3u8FileLink)
					val resultList = inferredResolution?.let { listOf(it) }
						?: listOf(getText(R.string.title_player_default))
					logger.d("Extracted media playlist resolution(s): $resultList")
					resultList
				}

				else -> {
					logger.d("Unknown playlist type encountered for: $m3u8FileLink")
					emptyList()
				}
			}
		} catch (error: Exception) {
			logger.d("Error while parsing resolutions from: $m3u8FileLink -> ${error.localizedMessage}")
			logger.e("Failed to extract resolutions from M3U8: $m3u8FileLink", error)
			emptyList()
		}
	}

	/**
	 * Extracts total duration (in milliseconds) from an HLS `.m3u8` playlist.
	 *
	 * Supports both master and media playlists using ExoPlayer’s [HlsPlaylistParser].
	 * - For **master playlists**, it retrieves the first available variant and fetches its duration recursively.
	 * - For **media playlists**, it computes total playback time directly from segment metadata.
	 *
	 * @param hlsPlaylistParser The parser instance for interpreting HLS playlist content.
	 * @param dataSpec The [DataSpec] describing the data request.
	 * @param sourceInputStream The [DataSourceInputStream] providing playlist input.
	 * @param m3u8UrlFileLink The source URL of the HLS playlist.
	 * @return The total duration in milliseconds, or `0L` if extraction fails.
	 *
	 * @throws IOException If playlist content cannot be read.
	 * @throws ParserException If the playlist format is invalid or corrupted.
	 */
	@OptIn(UnstableApi::class)
	private fun extractDuration(
		hlsPlaylistParser: HlsPlaylistParser,
		dataSpec: DataSpec,
		sourceInputStream: DataSourceInputStream,
		m3u8UrlFileLink: String
	): Long {
		logger.d("Starting M3U8 duration extraction for: $m3u8UrlFileLink")

		return try {
			when (val playlist = hlsPlaylistParser.parse(dataSpec.uri, sourceInputStream)) {

				// Master playlist: contains variants; pick first and fetch its duration
				is HlsMultivariantPlaylist -> {
					val variant = playlist.variants.firstOrNull()
					if (variant != null) {
						logger.d("Master playlist detected — fetching duration from variant: ${variant.url}")
						try {
							// Open a new stream for the variant
							val factory = DefaultHttpDataSource.Factory()
							val userAgent = getText(R.string.title_mobile_user_agent)
							val dataSource = factory.setUserAgent(userAgent).createDataSource()
							val variantSpec = DataSpec(variant.url)
							dataSource.open(variantSpec)

							DataSourceInputStream(dataSource, variantSpec).use { variantStream ->
								val variantPlaylist = hlsPlaylistParser.parse(variantSpec.uri, variantStream)
								if (variantPlaylist is HlsMediaPlaylist) {
									val durationMs = variantPlaylist.durationUs / 1000
									logger.d("Extracted variant duration: ${durationMs}ms")
									durationMs
								} else {
									logger.d("Variant is not a media playlist.")
									0L
								}
							}
						} catch (variantError: Exception) {
							logger.e("Failed to fetch duration from variant: ${variant.url}", variantError)
							0L
						}
					} else {
						logger.d("No variant found in master playlist.")
						0L
					}
				}

				// Media playlist: directly compute duration
				is HlsMediaPlaylist -> {
					val durationMs = playlist.durationUs / 1000
					logger.d("Extracted media playlist duration: ${durationMs}ms")
					durationMs
				}

				else -> {
					logger.d("Unknown playlist type for: $m3u8UrlFileLink")
					0L
				}
			}
		} catch (error: Exception) {
			logger.d("Error parsing M3U8 for: $m3u8UrlFileLink -> ${error.localizedMessage}")
			logger.e("Failed to extract duration from M3U8: $m3u8UrlFileLink", error)
			0L
		}
	}

	/**
	 * Extracts resolution information from the URL pattern.
	 *
	 * Example supports three pattern types:
	 * 1. Direct resolution in path (e.g., "480p.av1.mp4.m3u8")
	 * 2. Multi-resolution declarations (e.g., "multi=256x144:144p:,...")
	 * 3. Traditional patterns (e.g., "/1280x720/", "_720p")
	 *
	 * @param url The M3U8 URL to analyze
	 * @return Extracted resolution string or null if no match found
	 */
	private fun extractResolutionFromUrl(url: String): String? {
		// Pattern 1: Direct resolution in filename (e.g., "480p.av1.mp4.m3u8")
		"/(\\d{3,4})[pP]\\.".toRegex().find(url)?.let { match ->
			return "${match.groupValues[1]}p"
		}

		// Pattern 2: Multi-resolution declaration (e.g., "multi=256x144:144p:,...")
		"multi=(.*?):(.*?):/".toRegex().find(url)?.let { match ->
			return match.groupValues[1]
				.split(",")
				.last()
				.substringBefore(":")
				.let { if (it.contains("x")) it else "${it}p" }
		}

		// Pattern 3: Direct resolution in filename (e.g., "440x250.mp4.m3u8")
		"(\\d{3,4})[xX×](\\d{3,4})\\.mp4\\.m3u8".toRegex().find(url)?.let { match ->
			return "${match.groupValues[1]}×${match.groupValues[2]}"
		}

		// Pattern 4: Multi-resolution declaration (new format)
		"multi=(\\d+x\\d+):(\\d+x\\d+)/".toRegex().find(url)?.let { match ->
			// Return the highest resolution (last one)
			return match.groupValues.last().replace("x", "×")
		}

		// Pattern 5: Resolution in path (e.g., "/720p/stream.m3u8")
		"/(\\d{3,4})[pP]/".toRegex().find(url)?.let { match ->
			return "${match.groupValues[1]}p"
		}

		// Pattern 6. Quality suffix filename (e.g., "stream_720p.m3u8")
		"[_\\-](\\d{3,4})[pP]\\.m3u8".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 7. Compact multi-resolution (e.g., "multi=444x250:440x250/")
		"multi=([^:]+)".toRegex().find(url)?.let {
			return it.groupValues[1].split(":").last().replace("x", "×")
		}

		// Pattern 8. Dimensions in sub-path (e.g., "/1280x720/stream.m3u8")
		"/(\\d{3,4})[xX×](\\d{3,4})/".toRegex().find(url)?.let {
			return "${it.groupValues[1]}×${it.groupValues[2]}"
		}

		// Pattern 9. Bitrate-quality suffix (e.g., "stream_3000k_1080p.m3u8")
		"[_\\-]\\d+k[_\\-](\\d{3,4})[pP]\\.m3u8".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 10. CDN-style format (e.g., "stream_1080p_h264.m3u8")
		"[_\\-](\\d{3,4})[pP]_[a-z0-9]+\\.m3u8".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 11. Quality prefix (e.g., "hls_720p_stream.m3u8")
		"[_\\-](\\d{3,4})[pP][_\\-]".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"
		}

		// Pattern 12: Traditional resolution patterns
		val patterns = listOf(
			"/(\\d{3,4})[xX×](\\d{3,4})/",  // e.g., /1280x720/
			"[_\\-](\\d{3,4})[pP]",          // e.g., _720p
			"[_\\-](\\d{3,4})[xX×](\\d{3,4})" // e.g., -1280x720
		)

		// Pattern 13: for PHNCDN-style URLs (e.g., "720P_4000K_465426665.mp4")
		"/(\\d{3,4})[pP]_(\\d+)K_\\d+\\.mp4/".toRegex().find(url)?.let {
			return "${it.groupValues[1]}p"  // Returns "720p" for the example
		}

		patterns.forEach { pattern ->
			Pattern.compile(pattern).matcher(url).let { matcher ->
				if (matcher.find()) {
					return when (matcher.groupCount()) {
						1 -> "${matcher.group(1)}p"
						2 -> "${matcher.group(1)}×${matcher.group(2)}"
						else -> null
					}
				}
			}
		}

		return null
	}

	/**
	 * Callback interface for delivering extracted media metadata asynchronously.
	 *
	 * This interface is primarily used to return parsed video information such as
	 * available resolutions and total duration obtained from network or local sources,
	 * typically during HLS (.m3u8) or MP4 metadata extraction.
	 *
	 * It enables background parsers to communicate results or failures
	 * back to the UI thread or business logic layer without blocking.
	 */
	interface InfoCallback {

		/**
		 * Invoked when both video resolutions and duration have been successfully extracted.
		 *
		 * @param resolutions A list of available video resolutions (e.g., ["1920×1080", "1280×720"]).
		 * @param durationMs The total duration of the media in milliseconds.
		 */
		fun onResolutionsAndDuration(resolutions: List<String>, durationMs: Long)

		/**
		 * Invoked when an error occurs during metadata extraction.
		 *
		 * @param errorMessage A descriptive message explaining the reason for the failure.
		 */
		fun onError(errorMessage: String)
	}

}