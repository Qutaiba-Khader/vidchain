package app.core.engines.video_parser.parsers

import app.core.AIOApp.Companion.INSTANCE
import com.aio.R
import lib.device.DateTimeUtils.calculateTime
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.util.Locale

/**
 * Utility class for processing video format information from yt-dlp output.
 *
 * This singleton object provides comprehensive functionality for handling all aspects
 * of video format processing, from parsing yt-dlp output to managing download operations.
 * It serves as the central processing unit for video metadata extraction, format selection,
 * and download progress management.
 *
 * ## Usage Examples:
 *
 * ### Parsing Format Lists
 * ```kotlin
 * val formats = VideoFormatsUtils.getVideoFormatsList(ytdlpOutput)
 * formats.forEach { format ->
 *     println("${format.formatResolution} - ${format.formatExtension}")
 * }
 * ```
 *
 * ### Processing Download Progress
 * ```kotlin
 * val cleanProgress = VideoFormatsUtils.cleanYtdlpLoggingSting(rawLogLine)
 * progressTextView.text = cleanProgress
 * ```
 *
 * ### Calculating File Sizes
 * ```kotlin
 * val totalSize = VideoFormatsUtils.calculateTotalFileSize(videoSize, audioSize)
 * println("Total download size: $totalSize")
 * ```
 *
 * ## Error Handling:
 * - Functions return empty collections instead of null for safe consumption
 * - Invalid input strings are handled gracefully with default values
 * - Logging is provided for debugging parsing and processing issues
 * - Performance timing is included for optimization monitoring
 *
 * ## Thread Safety:
 * - All operations are stateless and thread-safe
 * - No shared mutable state between function calls
 * - Suitable for use across multiple coroutines and threads
 */
object VideoFormatsUtils {

	private val logger: LogHelperUtils = LogHelperUtils.from(javaClass)

	/**
	 * Parses yt-dlp format listing output and returns structured video format information.
	 *
	 * This function processes the raw text output from yt-dlp's format listing command,
	 * which displays available video formats in a columnar table. It performs OCR-style
	 * column detection, text extraction, and parsing to convert the textual table into
	 * structured VideoFormat objects. The function also filters formats to keep only the
	 * highest quality version for each resolution.
	 *
	 * @param ytdlFormatsResponse Raw yt-dlp format listing output as multi-line string
	 * @return List of parsed and filtered VideoFormat objects representing available video formats
	 * @throws Exception If parsing fails, returns empty list and logs error details
	 *
	 * @example
	 * val formats = getVideoFormatsList(ytdlOutput)
	 * // Returns: List<VideoFormat> with id="137", extension="mp4", resolution="1080p", etc.
	 */
	fun getVideoFormatsList(ytdlFormatsResponse: String): List<VideoFormat> {
		val startTime = System.currentTimeMillis()
		val inputData = ytdlFormatsResponse.trimIndent()
		logger.d("YT-DLP Response\n${inputData}")

		// Extract and clean the formats table from yt-dlp output
		val formatTable = extractFormatsFromInfoLine(inputData)
		val cleanFormatTable = clearEmptyLines(formatTable)
		val lines = cleanFormatTable.split("\n").map { it.trim() }
			.filterIndexed { index, _ -> index != 1 }.toTypedArray() // Remove separator line

		// Find longest line for proper column alignment
		var maxLength = 0
		var longestLineIndex = -1

		for (index in lines.indices) {
			if (lines[index].length > maxLength) {
				maxLength = lines[index].length
				longestLineIndex = index
			}
		}

		// Ensure consistent line lengths for accurate column parsing
		if (longestLineIndex != -1) {
			lines[longestLineIndex] += " "
		}

		maxLength = lines.maxOfOrNull { it.length } ?: 0
		logger.d("YT-DLP formats list lines max length=$maxLength")

		// Pad all lines to max length to create uniform column structure
		for (index in lines.indices) {
			val line = lines[index]
			if (line.length != maxLength) {
				val needChars = maxLength - line.length
				val updatedLine = line + " ".repeat(needChars)
				lines[index] = updatedLine
			}
		}

		// Detect column boundaries by finding vertical whitespace gaps
		val columnPositionMap: MutableList<Pair<Int, Int>> = mutableListOf()
		var startingIndex = 0

		var currentLoopingIndex = 0
		while (currentLoopingIndex < maxLength) {
			var whiteSpaceEncountered = 0
			for (line in lines)
				if (line[currentLoopingIndex].isWhitespace())
					whiteSpaceEncountered++

			// Column boundary found when all lines have whitespace at this position
			if (whiteSpaceEncountered == lines.size) {
				columnPositionMap.add(Pair(startingIndex, currentLoopingIndex))
				logger.d(
					"YT-DLP formats column found starting" +
							"=$startingIndex ending=$currentLoopingIndex"
				)
				startingIndex = currentLoopingIndex + 1
			}

			currentLoopingIndex++
		}

		// Extract text from each detected column
		val extractedTexts: MutableList<String> = mutableListOf()
		for ((start, end) in columnPositionMap) {
			val extractedText = StringBuilder()
			for (line in lines) {
				if (start < line.length) {
					extractedText.append(
						line.substring(start, minOf(end + 1, line.length))
					).append("\n")
				}
			}
			logger.d("YT-DLP parsing on extracted formats text=\n${extractedText}")
			extractedTexts.add(extractedText.toString().trim())
		}

		logger.d("============================")
		// Parse extracted column data into VideoFormat objects
		val extractedVideoFormats = extractVideoFormats(extractedTexts)
		logger.d("Total yt-dlp video formats found=${extractedVideoFormats.size}")
		logger.d("============================")

		// Filter formats to keep only highest quality (TBR) per resolution
		val filteredVideoFormats: ArrayList<VideoFormat> = ArrayList()
		val formatsByResolution = extractedVideoFormats.groupBy { it.formatResolution }
		for ((resolution, formats) in formatsByResolution) {
			var highestTBRFormat: VideoFormat? = null
			var maxTBRValue = 0.0

			for (format in formats) {
				// Extract numeric TBR value for comparison
				val numericTBR = format.formatTBR
					.replace(Regex("[^0-9.]"), "")
					.toDoubleOrNull() ?: 0.0

				if (numericTBR > maxTBRValue) {
					maxTBRValue = numericTBR
					highestTBRFormat = format
				}
			}

			highestTBRFormat?.let {
				filteredVideoFormats.add(it)
				logger.d(
					"Added format with Resolution" +
							"=$resolution and Highest TBR=$maxTBRValue"
				)
			}
		}

		// Fallback if no filtered formats found - use all valid formats
		if (filteredVideoFormats.isEmpty() && extractedVideoFormats.isNotEmpty()) {
			filteredVideoFormats.addAll(extractedVideoFormats)
			filteredVideoFormats.removeAll { it.formatId.isEmpty() }
		}

		// Post-process format information for consistency
		for (format in filteredVideoFormats) {
			// Handle unknown resolution by using format ID
			if (format.formatResolution.lowercase() == "unknown") {
				format.formatResolution = format.formatId
			}

			// Provide default value for missing file sizes
			if (format.formatFileSize.isEmpty()) {
				format.formatFileSize = getText(R.string.title_not_available)
			}
		}

		val endTime = System.currentTimeMillis()
		val timeTaken = endTime - startTime
		logger.d(
			"Yt-dlp video formats text parsing time:" +
					" ${calculateTime(timeTaken.toFloat())}"
		)
		return filteredVideoFormats
	}

	/**
	 * Logs detailed video information for debugging and analysis purposes.
	 *
	 * Creates a comprehensive log message containing all video metadata and
	 * available formats with their technical specifications. Useful for
	 * troubleshooting format selection and download issues.
	 *
	 * @param videoInfo VideoInfo object containing all video metadata and formats
	 *
	 * @example
	 * logVideoInfo(videoInfo)
	 * // Logs: "Video URL: https://youtube.com/watch?v=abc123\nAvailable Formats:\nFormat 1:\n  Format ID: 137\n  Extension: mp4\n  ..."
	 */
	fun logVideoInfo(videoInfo: VideoInfo) {
		val logMessage = StringBuilder()
		logMessage.append("Video URL: ${videoInfo.videoUrl}\n")
		logMessage.append("Available Formats:\n")

		videoInfo.videoFormats.forEachIndexed { index, format ->
			logMessage.append("Format ${index + 1}:\n")
			logMessage.append("  Format ID: ${format.formatId}\n")
			logMessage.append("  Extension: ${format.formatExtension}\n")
			logMessage.append("  Resolution: ${format.formatResolution}\n")
			logMessage.append("  File Size: ${format.formatFileSize}\n")
			logMessage.append("  Video Codec: ${format.formatVcodec}\n")
			logMessage.append("  Audio Codec: ${format.formatAcodec}\n")
			logMessage.append("  Total Bitrate (tbr): ${format.formatTBR}\n")
			logMessage.append("------------------------\n")
		}
		logger.d(logMessage.toString())
	}

	/**
	 * Checks if a speed string is in valid format for yt-dlp command line arguments.
	 *
	 * Validates that speed strings follow the pattern of digits followed by unit
	 * (G, M, K, B) without any other characters.
	 *
	 * @param speed Speed string to validate (e.g., "10M", "100K", "1G")
	 * @return true if string matches valid speed format, false otherwise
	 *
	 * @example
	 * isValidSpeedFormat("10M")  // Returns: true
	 * isValidSpeedFormat("100K") // Returns: true
	 * isValidSpeedFormat("1G")   // Returns: true
	 * isValidSpeedFormat("10MB") // Returns: false (invalid unit)
	 * isValidSpeedFormat("abc")  // Returns: false (non-numeric)
	 */
	fun isValidSpeedFormat(speed: String): Boolean {
		val regex = Regex("^\\d+([GMKB])$")
		return regex.matches(speed)
	}

	/**
	 * Formats download speed in bytes for yt-dlp command line argument.
	 *
	 * Converts byte counts to human-readable speed units (G, M, K, B) that
	 * yt-dlp accepts for rate limiting. Uses decimal units (1000-based) for
	 * network speed representation.
	 *
	 * @param downloadMaxNetworkSpeed Speed in bytes per second as Long value
	 * @return Formatted speed string suitable for yt-dlp --limit-rate option
	 *
	 * @example
	 * formatDownloadSpeedForYtDlp(10_000_000) // Returns: "10M"
	 * formatDownloadSpeedForYtDlp(256_000)    // Returns: "256K"
	 * formatDownloadSpeedForYtDlp(1_000_000_000) // Returns: "1G"
	 * formatDownloadSpeedForYtDlp(500)        // Returns: "500B"
	 */
	fun formatDownloadSpeedForYtDlp(downloadMaxNetworkSpeed: Long): String {
		return when {
			downloadMaxNetworkSpeed >= 1_000_000_000 -> "${downloadMaxNetworkSpeed / 1_000_000_000}G"
			downloadMaxNetworkSpeed >= 1_000_000 -> "${downloadMaxNetworkSpeed / 1_000_000}M"
			downloadMaxNetworkSpeed >= 1_000 -> "${downloadMaxNetworkSpeed / 1_000}K"
			else -> "${downloadMaxNetworkSpeed}B"
		}
	}

	/**
	 * Parses human-readable size string to bytes.
	 *
	 * Converts size strings with units (KiB, MiB, GiB, TiB) to their byte
	 * equivalents. Uses binary units (1024-based) consistent with yt-dlp output.
	 *
	 * @param size Size string to parse (e.g., "10MiB", "1.5GiB", "100KiB")
	 * @return Size in bytes as Long, returns 0L if parsing fails
	 *
	 * @example
	 * parseSize("10MiB")    // Returns: 10485760
	 * parseSize("1.5GiB")   // Returns: 1610612736
	 * parseSize("100KiB")   // Returns: 102400
	 * parseSize("500B")     // Returns: 500
	 * parseSize("invalid")  // Returns: 0
	 */
	fun parseSize(size: String): Long {
		val regex = """(\d+(\.\d+)?)([KMGT]?i?B)""".toRegex()
		val matchResult = regex.matchEntire(size)

		if (matchResult != null) {
			val (value, _, unit) = matchResult.destructured
			val sizeInBytes = value.toDouble()

			return when (unit) {
				"B" -> (sizeInBytes).toLong()
				"KiB" -> (sizeInBytes * 1024).toLong()
				"MiB" -> (sizeInBytes * 1024 * 1024).toLong()
				"GiB" -> (sizeInBytes * 1024 * 1024 * 1024).toLong()
				"TiB" -> (sizeInBytes * 1024 * 1024 * 1024 * 1024).toLong()
				else -> 0L
			}
		}
		return 0L
	}

	/**
	 * Calculates total file size by combining video and audio sizes.
	 *
	 * This function parses individual video and audio size strings, converts them to bytes,
	 * sums the total, and formats the result into a human-readable size string.
	 *
	 * @param videoSize Video size string in format like "15.23 MiB", "2.1 GiB", etc.
	 * @param audioSize Audio size string in same format as videoSize
	 * @return Combined size string formatted to appropriate unit (e.g., "15.23 MiB")
	 * @throws NumberFormatException If size strings cannot be parsed
	 *
	 * @example
	 * calculateTotalFileSize("10.5 MiB", "4.73 MiB") // Returns: "15.23 MiB"
	 * calculateTotalFileSize("1.5 GiB", "256.0 MiB") // Returns: "1.73 GiB"
	 */
	fun calculateTotalFileSize(videoSize: String, audioSize: String): String {
		val videoSizeBytes = parseSize(videoSize)
		val audioSizeBytes = parseSize(audioSize)

		val totalSizeBytes = videoSizeBytes + audioSizeBytes

		return formatSize(totalSizeBytes)
	}

	/**
	 * Checks if a video format has no audio track (video-only format).
	 *
	 * Determines whether a video format lacks audio by checking if the audio codec
	 * is empty or explicitly marked as "video only". Useful for identifying formats
	 * that need separate audio track merging.
	 *
	 * @param videoFormat VideoFormat object to check for audio presence
	 * @return true if format has no audio track, false if audio is present
	 *
	 * @example
	 * val format1 = VideoFormat(formatAcodec = "") // Returns: true
	 * val format2 = VideoFormat(formatAcodec = "video only") // Returns: true
	 * val format3 = VideoFormat(formatAcodec = "mp4a.40.2") // Returns: false
	 */
	fun isFormatVideoOnly(videoFormat: VideoFormat) =
		videoFormat.formatAcodec.isEmpty() ||
				videoFormat.formatAcodec.lowercase().contains("video only", true)

	/**
	 * Cleans file size string by removing non-numeric prefixes and extraneous characters.
	 *
	 * Useful for processing yt-dlp size strings that may contain prefixes like "~" or "approx"
	 * before the actual size value.
	 *
	 * @param input Raw file size string that may contain non-numeric prefixes
	 * @return Cleaned numeric portion of the size string
	 *
	 * @example
	 * cleanFileSize("~15.23 MiB") // Returns: "15.23 MiB"
	 * cleanFileSize("approx 2.1 GiB") // Returns: "2.1 GiB"
	 * cleanFileSize("1.5GiB") // Returns: "1.5GiB" (no change if no prefix)
	 */
	fun cleanFileSize(input: String): String {
		return input.replace(Regex("^\\D+"), "")
	}

	/**
	 * Cleans and formats yt-dlp progress output for user interface display.
	 *
	 * Applies a pipeline of transformations to raw yt-dlp log lines to create
	 * user-friendly progress messages. Handles various log formats, error cases,
	 * and applies localization where appropriate.
	 *
	 * @param input Raw yt-dlp progress line from standard output/error
	 * @return Formatted and localized progress information suitable for UI display
	 * @throws Exception Processing continues with original input if any transformation fails
	 *
	 * @example
	 * cleanYtdlpLoggingSting("[download] 45.2% of ~15.23 MiB at 1.2 MiB/s ETA 00:25")
	 * // Returns: "45.2% Of 15.23 MiB | 1.2 MiB/s | 00:25 Left"
	 *
	 * cleanYtdlpLoggingSting("[download] Destination: video.mp4")
	 * // Returns: localized "Setting destination files"
	 */
	fun cleanYtdlpLoggingSting(input: String): String {
		try {
			// Remove yt-dlp log prefixes and clean unknown values
			val a1 = input.replace(Regex("\\[.*?]"), "")
				.replace("Unknown", "--:--").trim()
			// Fix invalid speed values
			val a2 = speedStringFromYtdlpLogs(a1)
			// Handle file deletion messages
			val a3 = processDeletionLine(a2)
			// Handle format merging messages
			val a4 = processMergerLine(a3)
			// Process download-related messages
			val a5 = processDownloadLine(a4)
			// Handle multi-line download progress
			val a6 = getSecondDownloadLineIfExists(a5)
			// Apply final formatting pipeline
			return formatDownloadLine(a6)
		} catch (error: Exception) {
			error.printStackTrace()
			return input // Fallback to original input on error
		}
	}

	/**
	 * Extracts the formats table from yt-dlp info output for parsing available video formats.
	 *
	 * Locates the "[info] Available formats for" section in yt-dlp output and extracts
	 * the subsequent formats table until the next [download] section or end of output.
	 *
	 * @param input Raw yt-dlp info command output containing format information
	 * @return Extracted formats table as multi-line string, or empty string if not found
	 *
	 * @example
	 * extractFormatsFromInfoLine("...\n[info] Available formats for xyz:\nID EXT RESOLUTION...\n...")
	 * // Returns: "ID EXT RESOLUTION..." (the formats table content)
	 */
	private fun extractFormatsFromInfoLine(input: String): String {
		val lines = input.split("\n")
		val startIndex = lines.indexOfFirst { it.contains("[info] Available formats for") }
		val downloadIndex = lines.drop(startIndex + 1).indexOfFirst { it.contains("[download]") }
		return if (startIndex != -1) {
			if (downloadIndex != -1) {
				lines.subList(startIndex + 1, startIndex + 1 + downloadIndex).joinToString("\n")
			} else {
				lines.subList(startIndex + 1, lines.size).joinToString("\n")
			}
		} else {
			""
		}
	}

	/**
	 * Formats size in bytes to human-readable string with appropriate binary unit.
	 *
	 * Converts byte counts to KiB, MiB, GiB, etc. with 2 decimal places precision.
	 * Uses binary units (1024-based) consistent with yt-dlp output format.
	 *
	 * @param sizeInBytes Size in bytes as Long value
	 * @return Formatted size string with unit (e.g., "15.23 MiB", "1.45 GiB")
	 *
	 * @example
	 * formatSize(15978394) // Returns: "15.23 MiB"
	 * formatSize(1024) // Returns: "1.00 KiB"
	 * formatSize(15569256448) // Returns: "14.50 GiB"
	 */
	private fun formatSize(sizeInBytes: Long): String {
		val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
		var size = sizeInBytes.toDouble()
		var unitIndex = 0

		while (size >= 1024 && unitIndex < units.size - 1) {
			size /= 1024
			unitIndex++
		}

		return String.format(Locale.US, "%.2f %s", size, units[unitIndex])
	}

	/**
	 * Replaces invalid speed string values in yt-dlp logs with a default readable value.
	 *
	 * yt-dlp may output "N/A/s" when speed cannot be determined. This function
	 * replaces such values with "0KiB/s" for consistent UI display.
	 *
	 * @param speed Raw speed string from yt-dlp logs
	 * @return Speed string with "N/A/s" replaced by "0KiB/s", or original if valid
	 *
	 * @example
	 * speedStringFromYtdlpLogs("45.2% at N/A/s") // Returns: "45.2% at 0KiB/s"
	 * speedStringFromYtdlpLogs("45.2% at 1.2 MiB/s") // Returns: "45.2% at 1.2 MiB/s"
	 */
	private fun speedStringFromYtdlpLogs(speed: String): String {
		return if (speed.contains("N/A/s")) speed.replace("N/A/s", "0KiB/s") else speed
	}

	/**
	 * Extracts the second "download" line from yt-dlp logs, if available.
	 *
	 * Useful for progress tracking across fragmented downloads where multiple
	 * download lines may be present. Falls back to original input if only one line exists.
	 *
	 * @param input Multi-line yt-dlp log output
	 * @return Second download line if multiple exist, otherwise original input
	 *
	 * @example
	 * getSecondDownloadLineIfExists("[download] Line1\n[download] Line2\n[info] Other")
	 * // Returns: "[download] Line2"
	 *
	 * getSecondDownloadLineIfExists("[download] Single line")
	 * // Returns: "[download] Single line"
	 */
	private fun getSecondDownloadLineIfExists(input: String): String {
		val lines = input.lines()
		val downloadLines = lines.filter { it.startsWith("[download]") }
		return if (downloadLines.size >= 2) downloadLines[1] else input
	}

	/**
	 * Processes lines related to downloading, particularly M3U8 information extraction.
	 *
	 * Handles specific download states like M3U8 manifest downloading and extracts
	 * relevant portions of download messages for cleaner display.
	 *
	 * @param input Raw log line to process
	 * @return Localized M3U8 message or trimmed download message, or original input
	 *
	 * @example
	 * processDownloadLine("Downloading m3u8 information")
	 * // Returns: localized "Downloading m3U8 information"
	 *
	 * processDownloadLine("[download] Downloading fragment 5/10")
	 * // Returns: "Downloading fragment 5/10"
	 */
	private fun processDownloadLine(input: String): String {
		val stage1 = if (input.contains("Downloading m3u8 information")) {
			getText(R.string.title_downloading_m3u8_information)
		} else input

		val stage2 = if (stage1.contains("Downloading")) {
			stage1.substring(stage1.indexOf("Downloading"))
		} else stage1

		return stage2
	}

	/**
	 * Converts merge-related log lines to a localized message for format merging operations.
	 *
	 * Identifies when yt-dlp is merging separate video and audio streams into a single file.
	 *
	 * @param line Raw log line to check for merge operations
	 * @return Localized merging message if pattern matches, otherwise original line
	 *
	 * @example
	 * processMergerLine("Merging formats into video.mp4")
	 * // Returns: localized "Merging video and audio format"
	 *
	 * processMergerLine("Downloading video")
	 * // Returns: "Downloading video"
	 */
	private fun processMergerLine(line: String): String {
		return if (line.contains("Merging formats into"))
			getText(R.string.title_merging_video_and_audio_format) else line
	}

	/**
	 * Converts file deletion-related log lines to a localized finishing message.
	 *
	 * Identifies when yt-dlp is cleaning up temporary files after successful processing,
	 * indicating the download is in its final stages.
	 *
	 * @param line Raw log line to check for deletion operations
	 * @return Localized finishing message if pattern matches, otherwise original line
	 *
	 * @example
	 * processDeletionLine("Deleting original file temp.part")
	 * // Returns: localized "Finishing up the download"
	 *
	 * processDeletionLine("Downloading continues")
	 * // Returns: "Downloading continues"
	 */
	private fun processDeletionLine(line: String): String {
		return if (line.contains("Deleting original file"))
			getText(R.string.title_finishing_up_the_download) else line
	}

	/**
	 * Formats download log line with detailed status including progress, total size, speed and ETA.
	 *
	 * Attempts to parse comprehensive progress information using regex pattern matching.
	 * Falls back to simpler formatting stages if the main pattern doesn't match.
	 *
	 * @param input Raw download progress line to format
	 * @return Formatted progress string with consistent structure, or result from fallback processing
	 *
	 * @example
	 * formatDownloadLine("45.2% of ~15.23 MiB at 1.2 MiB/s ETA 00:25")
	 * // Returns: "45.2% Of 15.23 MiB | 1.2 MiB/s | 00:25 Left"
	 */
	private fun formatDownloadLine(input: String): String {
		val regex = Regex(
			"""(?i)(\d+\.\d+%)\s*of\s*~?\s*([\d.]+[KMGT]iB)(?:\s*(?:at|\|)\s*([\d.]+[KMGT]iB/s))?(?:\s*(?:ETA|\|)\s*([:\-\w]+))?"""
		)

		val match = regex.find(input)
		return if (match != null) {
			val progress = match.groupValues[1]
			val totalSize = match.groupValues[2]
			val speed = match.groupValues.getOrNull(3).takeIf { !it.isNullOrEmpty() } ?: "0B/s"
			val eta = match.groupValues.getOrNull(4).takeIf { !it.isNullOrEmpty() } ?: "--:--"
			"$progress Of $totalSize | $speed | $eta Left"
		} else {
			formatDownloadLineStage4(input)
		}
	}

	/**
	 * Detects and replaces download session initialization message with localized string.
	 *
	 * Identifies when yt-dlp is setting up a new download session and provides
	 * user-friendly localized message for this initialization phase.
	 *
	 * @param input Raw log line to check for session setup
	 * @return Localized session setup message if pattern matches, otherwise proceeds to next stage
	 *
	 * @example
	 * formatDownloadLineStage4("Setting up download session...")
	 * // Returns: localized "Setting up download session"
	 *
	 * formatDownloadLineStage4("Downloading video...")
	 * // Returns: result from formatDownloadLineStage5("Downloading video...")
	 */
	private fun formatDownloadLineStage4(input: String): String {
		val targetPhrase = getText(R.string.title_setting_up_download_session)
		return if (input.contains(other = "session", ignoreCase = true)) {
			targetPhrase
		} else {
			formatDownloadLineStage5(input)
		}
	}

	/**
	 * Detects and replaces destination file setup message with localized string.
	 *
	 * Identifies when yt-dlp is configuring output file paths and provides
	 * localized message for this file setup phase.
	 *
	 * @param input Raw log line to check for destination configuration
	 * @return Localized destination setup message if pattern matches, otherwise proceeds to next stage
	 *
	 * @example
	 * formatDownloadLineStage5("Destination: /path/to/video.mp4")
	 * // Returns: localized "Setting destination files"
	 *
	 * formatDownloadLineStage5("45.2% of 15.23 MiB")
	 * // Returns: result from formatDownloadingLineStage6("45.2% of 15.23 MiB")
	 */
	private fun formatDownloadLineStage5(input: String): String {
		return if (input.startsWith("Destination:")) {
			getText(R.string.title_setting_destination_files)
		} else {
			formatDownloadingLineStage6(input)
		}
	}

	/**
	 * Detects format-check messages in download logs and returns localized text for user interface.
	 *
	 * This function identifies when yt-dlp is checking available formats before downloading
	 * and replaces the technical message with a user-friendly localized version.
	 *
	 * @param input The raw log line from yt-dlp download process
	 * @return Localized "checking formats" message if pattern matches, otherwise proceeds to next processing stage
	 *
	 * @example
	 * // Input with format check pattern:
	 * formatDownloadingLineStage6("Downloading 3 format(s): 137+140, 248+251, 399")
	 * // Returns: localized "Checking formats to download"
	 *
	 * // Input without format check pattern:
	 * formatDownloadingLineStage6("[download] Destination: video.mp4")
	 * // Returns: result from formatDownloadLineStage7("[download] Destination: video.mp4")
	 */
	private fun formatDownloadingLineStage6(input: String): String {
		return if (input.startsWith("Downloading") && input.contains("format(s):")) {
			getText(R.string.title_checking_formats_to_download)
		} else {
			formatDownloadLineStage7(input)
		}
	}

	/**
	 * Formats generic progress lines showing percentage, size, duration, and speed information.
	 *
	 * This function parses yt-dlp progress updates that follow the pattern of percentage completed,
	 * file size, elapsed time, and download speed. It reformats the information into a more
	 * structured and consistent display format for the user interface.
	 *
	 * @param input The raw progress line from yt-dlp download process
	 * @return Formatted progress string with consistent spacing and separators, or proceeds to next stage if no match
	 *
	 * @example
	 * // Input with progress pattern:
	 * formatDownloadLineStage7("45% of 15.2MiB in 00:25 at 625.5KiB/s")
	 * // Returns: "45% Of 15.2MiB  |  00:25  |  625.5KiB/s"
	 *
	 * // Input without progress pattern:
	 * formatDownloadLineStage7("[download] Resuming download at byte 1024")
	 * // Returns: result from formatDownloadLineStage8("[download] Resuming download at byte 1024")
	 */
	private fun formatDownloadLineStage7(input: String): String {
		val regex =
			"""(\d+%)\s+of\s+([\d.]+[KMGT]?iB)\s+in\s+([\d:]+)\s+at\s+([\d.]+[KMGT]?iB/s)""".toRegex()
		val matchResult = regex.find(input)

		return if (matchResult != null) {
			val (percentage, size, time, speed) = matchResult.destructured
			"$percentage Of $size  |  $time  |  $speed"
		} else {
			formatDownloadLineStage8(input)
		}
	}

	/**
	 * Detects already downloaded part messages and replaces with localized validation message.
	 *
	 * This function identifies when yt-dlp detects that a download fragment has already been
	 * partially downloaded, indicating validation of existing download parts during resumption.
	 *
	 * @param input The raw log line from yt-dlp download process
	 * @return Localized validation message if pattern matches, otherwise proceeds to next processing stage
	 *
	 * @example
	 * // Input with already downloaded pattern:
	 * formatDownloadLineStage8("[download] /path/to/video.part-Frag5 has already been downloaded")
	 * // Returns: localized "Validating already downloaded part"
	 *
	 * // Input without pattern:
	 * formatDownloadLineStage8("[download] Downloading fragment 6/10")
	 * // Returns: result from formatDownloadLineStage9("[download] Downloading fragment 6/10")
	 */
	private fun formatDownloadLineStage8(input: String): String {
		val regex = """.*/.*\.part-Frag\d+ has already been downloaded""".toRegex()

		return if (regex.matches(input)) {
			getText(R.string.title_validating_already_downloaded_part)
		} else {
			formatDownloadLineStage9(input)
		}
	}

	/**
	 * Parses and formats full download progress lines including fragment information.
	 *
	 * This function handles detailed progress updates that include fragment-based downloading
	 * information commonly seen with HLS/m3u8 streams. It extracts percentage, total size,
	 * download speed, ETA, and fragment counts, then formats them for consistent UI display.
	 *
	 * @param input The raw progress line with fragment information from yt-dlp
	 * @return Formatted progress string with fragment info condensed, or proceeds to next stage if no match
	 *
	 * @example
	 * // Input with fragment progress pattern:
	 * formatDownloadLineStage9("23.5% of ~ 45.7MiB at 1.2MiB/s ETA 00:45 (frag 12/50)")
	 * // Returns: "23.5% Of 45.7MiB  |  1.2MiB/s  |  00:45 Left"
	 *
	 * // Input without fragment pattern:
	 * formatDownloadLineStage9("[info] Writing video metadata")
	 * // Returns: result from formatDownloadLineStage10("[info] Writing video metadata")
	 */
	private fun formatDownloadLineStage9(input: String): String {
		val regex =
			"""(\d+\.\d+%) of ~?\s+([\d.]+[A-Z]iB) at\s+([\d.]+[A-Z]?B/s) ETA ([\d:]+|--:--) \(frag (\d+)/(\d+)\)""".toRegex()
		val matchResult = regex.find(input)

		return if (matchResult != null) {
			val (percentage, size, speed, eta, _, _) = matchResult.destructured
			"$percentage Of $size  |  $speed  |  $eta Left  "
		} else {
			formatDownloadLineStage10(input)
		}
	}

	/**
	 * Processes download log lines to detect and localize retry attempt messages.
	 *
	 * This function identifies yt-dlp retry patterns in download logs and converts them
	 * to localized strings with retry count information. If no retry pattern is found,
	 * it delegates to the next stage of log processing.
	 *
	 * @param input The raw log line from yt-dlp download process
	 * @return Localized retry message if retry pattern is detected, otherwise proceeds to next processing stage
	 *
	 * @example
	 * // Input with retry pattern:
	 * formatDownloadLineStage10("Retrying (3/5)...")
	 * // Returns: localized string like "Connection failed, retrying (3 of 5)"
	 *
	 * // Input without retry pattern:
	 * formatDownloadLineStage10("Downloading webpage...")
	 * // Returns: result from formatDownloadLineStage11("Downloading webpage...")
	 */
	private fun formatDownloadLineStage10(input: String): String {
		return if (input.contains("Retrying", ignoreCase = true)) {
			// Regex pattern to extract current retry count and total retries
			val regex = Regex("Retrying \\((\\d+)/(\\d+)\\)")
			val matchResult = regex.find(input)
			matchResult?.let {
				// Format localized string with current and total retry counts
				INSTANCE.getString(
					R.string.title_connection_failed_retrying,
					it.groups[1]?.value, it.groups[2]?.value
				)
			} ?: input // Return original input if regex match fails
		} else {
			// Proceed to next processing stage if no retry pattern found
			formatDownloadLineStage11(input)
		}
	}

	/**
	 * Processes download log lines to detect and localize various yt-dlp extraction and download messages.
	 *
	 * This function maps common yt-dlp log messages to localized string resources for
	 * better user experience. It handles messages related to URL extraction, manifest
	 * downloading, JSON parsing, and other download pipeline steps.
	 *
	 * @param input The raw log line from yt-dlp download process
	 * @return Localized version of the log message if a known pattern is found, otherwise original input
	 *
	 * @example
	 * // Known patterns:
	 * formatDownloadLineStage11("extracting url: https://example.com")
	 * // Returns: localized "Extracting source URL"
	 *
	 * formatDownloadLineStage11("Downloading m3u8 manifest...")
	 * // Returns: localized "Downloading m3u8 manifest"
	 *
	 * formatDownloadLineStage11("Unknown message")
	 * // Returns: "Unknown message" (unchanged)
	 */
	private fun formatDownloadLineStage11(input: String): String {
		// Mapping of yt-dlp log patterns to localized string resources
		val map = mapOf(
			"extracting url" to R.string.title_extracting_source_url,
			"Checking m3u8 live status" to R.string.title_checking_m3u8_live_status,
			"fixing mpeg-ts in mp4 container" to R.string.title_fixing_mpeg_ts_in_mp4_container,
			"webpage" to R.string.title_downloading_webpage,
			"tv client" to R.string.title_downloading_client_config,
			"player" to R.string.title_extracting_player_api,
			"m3u8 manifest" to R.string.title_downloading_m3u8_manifest,
			"media json metadata" to R.string.title_downloading_media_json_metadata,
			"metadata json" to R.string.title_downloading_metadata_json,
			"video info" to R.string.title_downloading_video_info,
			"json" to R.string.title_parsing_json_data
		)

		// Find first matching pattern and return corresponding localized string
		return map.entries.firstOrNull { input.contains(it.key, ignoreCase = true) }
			?.let { getText(it.value) } // Get localized string from resources
			?: input // Return original input if no pattern matches
	}

	/**
	 * Removes all empty or blank lines from a given multi-line string.
	 *
	 * This utility function cleans up log output or multi-line text by filtering out
	 * lines that contain only whitespace or are completely empty. Useful for
	 * presenting cleaner output to users.
	 *
	 * @param input The original string potentially containing empty lines
	 * @return A string with all non-blank lines joined with newline characters
	 *
	 * @example
	 * // Input with empty lines:
	 * clearEmptyLines("Line 1\n\nLine 2\n   \nLine 3")
	 * // Returns: "Line 1\nLine 2\nLine 3"
	 *
	 * // Input with only empty lines:
	 * clearEmptyLines("\n\n   \n\t\n")
	 * // Returns: "" (empty string)
	 *
	 * // Input with no empty lines:
	 * clearEmptyLines("Line 1\nLine 2\nLine 3")
	 * // Returns: "Line 1\nLine 2\nLine 3"
	 */
	private fun clearEmptyLines(input: String): String {
		return input.lines()
			.filter { it.isNotBlank() } // Remove blank and whitespace-only lines
			.joinToString("\n") // Rebuild string with newline separators
	}

	/**
	 * Extracts video format information from a list of column strings representing tabular data.
	 *
	 * This function processes OCR-extracted column data where each column contains a header
	 * followed by newline-separated values for specific video format metadata. It transforms
	 * the columnar data into a list of structured VideoFormat objects, with each object
	 * representing a row in the original tabular data.
	 *
	 * @param columns A mutable list of strings where each string contains newline-separated values for a column.
	 *                Expected column headers: "ID", "EXT", "RESOLUTION", "FILESIZE", "TBR", "PROTO", "VCODEC", "ACODEC".
	 *                Example: ["ID\n1\n2\n3", "EXT\nmp4\nwebm\nmkv", "RESOLUTION\n1080p\n720p\n480p"]
	 * @return A list of [VideoFormat] objects, each representing a row in the tabular data.
	 *         Returns an empty list if no valid data is found.
	 *
	 * @throws IndexOutOfBoundsException If the columns list is empty when trying to determine row count
	 *
	 * @example
	 * // Sample input:
	 * val columns = mutableListOf(
	 *     "ID\n1\n2\n3",
	 *     "EXT\nmp4\nwebm\nmkv",
	 *     "RESOLUTION\n1080p\n720p\n480p",
	 *     "FILESIZE\n10MB\n5MB\n2MB",
	 *     "TBR\n2000\n1500\n800",
	 *     "PROTO\nhttps\nhttps\nhttp",
	 *     "VCODEC\nh264\nvp9\nh265",
	 *     "ACODEC\nmp3\naac\nopus"
	 * )
	 *
	 * // Usage:
	 * val formats = extractVideoFormats(columns)
	 * // Returns: List of 3 VideoFormat objects with:
	 * // - Format 1: ID="1", EXT="mp4", RESOLUTION="1080p", FILESIZE="10MB", etc.
	 * // - Format 2: ID="2", EXT="webm", RESOLUTION="720p", FILESIZE="5MB", etc.
	 * // - Format 3: ID="3", EXT="mkv", RESOLUTION="480p", FILESIZE="2MB", etc.
	 */
	private fun extractVideoFormats(columns: MutableList<String>): List<VideoFormat> {
		// Get the total number of rows based on the first column (including header)
		val totalRows = columns[0].split("\n").map { it.trim() }

		val videoFormats: ArrayList<VideoFormat> = ArrayList()

		// Extract data from each column using helper function
		val idColumn = getColumnData("ID", columns, totalRows.size)
		val extColumn = getColumnData("EXT", columns, totalRows.size)
		val resolutionColumn = getColumnData("RESOLUTION", columns, totalRows.size)
		val fileSizeColumn = getColumnData("FILESIZE", columns, totalRows.size)
		val tbrColumn = getColumnData("TBR", columns, totalRows.size)
		val protocolColumn = getColumnData("PROTO", columns, totalRows.size)
		val vcodecColumn = getColumnData("VCODEC", columns, totalRows.size)
		val acodecColumn = getColumnData("ACODEC", columns, totalRows.size)

		// Iterate through each row and create VideoFormat objects
		for (index in totalRows.indices) {
			// Safely extract each field with fallback to empty string if missing
			val formatId = idColumn.getOrNull(index) ?: ""
			val formatExtension = extColumn.getOrNull(index) ?: ""
			val formatResolution = resolutionColumn.getOrNull(index) ?: ""
			val formatFileSize = fileSizeColumn.getOrNull(index) ?: ""
			val formatTBR = tbrColumn.getOrNull(index) ?: ""
			val formatProtocol = protocolColumn.getOrNull(index) ?: ""
			val formatVcodec = vcodecColumn.getOrNull(index) ?: ""
			val formatAcodec = acodecColumn.getOrNull(index) ?: ""

			// Create and add VideoFormat object for current row
			videoFormats.add(
				VideoFormat(
					formatId = formatId,
					formatExtension = formatExtension,
					formatResolution = formatResolution,
					formatFileSize = formatFileSize,
					formatTBR = formatTBR,
					formatVcodec = formatVcodec,
					formatAcodec = formatAcodec,
					formatProtocol = formatProtocol
				)
			)
		}

		return videoFormats
	}

	/**
	 * Retrieves column data by its header name from extracted text data.
	 *
	 * This function processes OCR-extracted column data where each column string starts with a header
	 * followed by newline-separated values. It extracts the values for a specific header and ensures
	 * the returned list matches the expected row count through padding when necessary.
	 *
	 * @param header The header name of the column to retrieve (e.g., "ID", "EXT", "RESOLUTION").
	 * @param extractedTexts The list of all column strings where each entry starts with a header
	 *                       followed by newline-separated values. Example: ["ID\n1\n2\n3", "EXT\nmp4\nwebm"]
	 * @param rowCount The expected number of rows for data alignment across all columns.
	 * @return A list of values under the given header, padded with empty strings if fewer values are found.
	 *         Returns a list of empty strings if the header is not found.
	 *
	 * @example
	 * // Sample input:
	 * val extractedTexts = mutableListOf("ID\n1\n2\n3", "EXT\nmp4\nwebm", "RESOLUTION\n1080p\n720p")
	 * val rowCount = 3
	 *
	 * // Usage:
	 * getColumnData("EXT", extractedTexts, rowCount)
	 * // Returns: ["mp4", "webm", ""]
	 *
	 * getColumnData("ID", extractedTexts, rowCount)
	 * // Returns: ["1", "2", "3"]
	 *
	 * getColumnData("SIZE", extractedTexts, rowCount)
	 * // Returns: ["", "", ""] (header not found)
	 */
	private fun getColumnData(header: String, extractedTexts: MutableList<String>, rowCount: Int): List<String> {
		// Find the index of the column that starts with the specified header
		val headerIndex = extractedTexts.indexOfFirst { it.startsWith(header) }

		return if (headerIndex != -1) {
			// Column found - process the data
			val columnData = extractedTexts[headerIndex]
				.split("\n")
				.drop(1) // Remove header line to get only the values
				.map { it.trim() } // Clean up whitespace from each value

			// Pad with empty strings if rows are missing to maintain consistent row count
			if (columnData.size < rowCount) {
				columnData + List(rowCount - columnData.size) { "" }
			} else {
				columnData
			}
		} else {
			// Return a list of empty strings if column header is not found
			List(rowCount) { "" }
		}
	}
}