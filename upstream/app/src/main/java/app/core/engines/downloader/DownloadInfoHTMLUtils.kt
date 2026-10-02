package app.core.engines.downloader

import app.core.AIOApp
import com.aio.R
import lib.files.FileSizeFormatter.humanReadableSizeOf
import lib.networks.DownloaderUtils.formatDownloadSpeedInSimpleForm
import lib.texts.CommonTextUtils.getText

/**
 * Utility class for generating HTML-formatted download information strings.
 * Provides detailed information about download status, progress, and configuration
 * in a structured HTML format suitable for display in dialogs or web views.
 *
 * This class handles both standard file downloads and video downloads with yt-dlp integration,
 * presenting comprehensive technical details in a user-readable format.
 */
object DownloadInfoHTMLUtils {

	/**
	 * Builds an HTML-formatted string containing detailed information about a download.
	 * This method generates a comprehensive overview of download status, including:
	 * - Basic download information (ID, filename, progress)
	 * - File metadata and storage details
	 * - Network and performance statistics
	 * - Configuration settings and technical details
	 * - Multi-threaded download part information (if applicable)
	 *
	 * @param ddm The DownloadDataModel containing all download information
	 * @return HTML-formatted string with download details suitable for display in WebView or dialog
	 */
	@JvmStatic
	fun buildDownloadInfoHtmlString(ddm: DownloadDataModel): String {
		val context = AIOApp.INSTANCE
		val stringBuilder = StringBuilder()

		// Check if this is a video download with special video info
		if (ddm.videoInfo != null && ddm.videoFormat != null) {
			// Video download specific information (yt-dlp based downloads)
			stringBuilder.append("<html><body>")
				.append(context.getString(R.string.title_b_download_id_b_br, "${ddm.downloadId}"))
				.append(context.getString(R.string.title_b_file_name_b_br, ddm.fileName))
				.append(context.getString(R.string.title_b_progress_percentage_b_br, "${ddm.progressPercentage}%"))

			// Show temporary yt-dlp status if download isn't complete
			if (ddm.status != DownloadStatus.COMPLETE) {
				stringBuilder.append(context.getString(R.string.title_b_download_stream_info_b_br, ddm.tempYtdlpStatusInfo))
			} else {
				// Show file size info when complete
				stringBuilder.append(context.getString(R.string.title_b_file_size_b_br, ddm.downloadedByteInFormat))
					.append(context.getString(R.string.title_b_downloaded_bytes_b_bytes_br, "${ddm.downloadedByte}"))
					.append(context.getString(R.string.title_b_downloaded_bytes_in_format_b_br, ddm.downloadedByteInFormat))
			}

			stringBuilder
				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_file_category_b_br, ddm.fileCategoryName))
				.append(context.getString(R.string.title_b_file_directory_b_br, ddm.fileDirectory))
				.append(context.getString(R.string.title_b_file_url_b_br, buildUrlTag(ddm.fileURL)))
				.append(context.getString(R.string.title_b_download_webpage_b_br, buildUrlTag(ddm.siteReferrer)))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_download_status_info_b_br, ddm.statusInfo))
				.append(context.getString(R.string.title_b_download_started_b_br, ddm.startTimeDateInFormat))
				.append(context.getString(R.string.title_b_download_last_modified_b_br, ddm.lastModifiedTimeDateInFormat))
				.append(context.getString(R.string.title_b_time_spent_b_br, ddm.timeSpentInFormat))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_is_file_url_expired_b_br, "${ddm.isFileUrlExpired}"))
				.append(context.getString(R.string.title_b_is_failed_to_access_file_b_br, "${ddm.isFailedToAccessFile}"))
				.append(context.getString(R.string.title_b_is_waiting_for_network_b_br, "${ddm.isWaitingForNetwork}"))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_checksum_validation_b_br, ifChecksumVerified(ddm)))
				.append(context.getString(R.string.title_b_multi_thread_support_b_br, isMultithreadingSupported(ddm)))
				.append(context.getString(R.string.title_b_resume_support_b_br, isResumeSupported(ddm)))
				.append(context.getString(R.string.title_b_unknown_file_size_b_br, isUnknownFile(ddm)))
				.append(context.getString(R.string.title_b_connection_retry_counts_b_times_br, "${ddm.totalTrackedConnectionRetries}"))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_default_parallel_connections_b_br, "${defaultParallelConnection(ddm)}"))
				.append(context.getString(R.string.title_b_default_thread_connections_b_br, "${defaultNumOfThreadsAssigned(ddm)}"))
				.append(context.getString(R.string.title_b_buffer_size_b_br, getBufferSize(ddm)))
				.append(context.getString(R.string.title_b_http_proxy_b_br, getHttpProxy(ddm)))
				.append(context.getString(R.string.title_b_download_speed_limiter_b_br, formatNetworkSpeedLimit(ddm)))
				.append(context.getString(R.string.title_b_user_agent_b_br, ddm.globalSettings.downloadHttpUserAgent))
		} else {
			// Standard file download information (non-video downloads)
			stringBuilder.append("<html><body>")
				.append(context.getString(R.string.title_b_download_id_b_br, "${ddm.downloadId}"))
				.append(context.getString(R.string.title_b_file_name_b_br, ddm.fileName))
				.append(context.getString(R.string.title_b_file_size_b_br, ddm.fileSizeInFormat))
				.append(context.getString(R.string.title_b_downloaded_bytes_b_bytes_br, "${ddm.downloadedByte}"))
				.append(context.getString(R.string.title_b_downloaded_bytes_in_format_b_br, ddm.downloadedByteInFormat))
				.append(context.getString(R.string.title_b_progress_percentage_b_br, "${ddm.progressPercentage}%"))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_file_category_b_br, ddm.fileCategoryName))
				.append(context.getString(R.string.title_b_file_directory_b_br, ddm.fileDirectory))
				.append(context.getString(R.string.title_b_file_url_b_br, buildUrlTag(ddm.fileURL)))
				.append(context.getString(R.string.title_b_download_webpage_b_br, buildUrlTag(ddm.siteReferrer)))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_download_status_info_b_br, ddm.statusInfo))
				.append(context.getString(R.string.title_b_download_started_b_br, ddm.startTimeDateInFormat))
				.append(context.getString(R.string.title_b_download_last_modified_b_br, ddm.lastModifiedTimeDateInFormat))
				.append(context.getString(R.string.title_b_time_spent_b_br, ddm.timeSpentInFormat))
				.append(context.getString(R.string.title_b_remaining_time_b_br, ddm.remainingTimeInFormat))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_is_file_url_expired_b_br, "${ddm.isFileUrlExpired}"))
				.append(context.getString(R.string.title_b_is_failed_to_access_file_b_br, "${ddm.isFailedToAccessFile}"))
				.append(context.getString(R.string.title_b_is_waiting_for_network_b_br, "${ddm.isWaitingForNetwork}"))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_realtime_network_speed_b_br, getRealtimeNetworkSpeed(ddm)))
				.append(context.getString(R.string.title_b_average_network_speed_b_br, ddm.averageSpeedInFormat))
				.append(context.getString(R.string.title_b_max_network_speed_b_br, ddm.maxSpeedInFormat))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_checksum_validation_b_br, ifChecksumVerified(ddm)))
				.append(context.getString(R.string.title_b_multi_thread_support_b_br, isMultithreadingSupported(ddm)))
				.append(context.getString(R.string.title_b_resume_support_b_br, isResumeSupported(ddm)))
				.append(context.getString(R.string.title_b_unknown_file_size_b_br, isUnknownFile(ddm)))
				.append(context.getString(R.string.title_b_connection_retry_counts_b_times_br, "${ddm.totalTrackedConnectionRetries}"))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_default_parallel_connections_b_br, "${defaultParallelConnection(ddm)}"))
				.append(context.getString(R.string.title_b_default_thread_connections_b_br, "${defaultNumOfThreadsAssigned(ddm)}"))
				.append(context.getString(R.string.title_b_buffer_size_b_br, getBufferSize(ddm)))
				.append(context.getString(R.string.title_b_http_proxy_b_br, getHttpProxy(ddm)))
				.append(context.getString(R.string.title_b_download_speed_limiter_b_br, formatNetworkSpeedLimit(ddm)))
				.append(context.getString(R.string.title_b_user_agent_b_br, ddm.globalSettings.downloadHttpUserAgent))
				.append(context.getString(R.string.title_b_est_part_chunk_size_b_br, estPartChunkSize(ddm)))

				.append("---------------------------------<br>")
				.append(context.getString(R.string.title_b_part_progress_percentages_b_br, getDownloadPartPercentage(ddm)))
				.append(context.getString(R.string.title_b_parts_downloaded_bytes_b_br, getPartDownloadedByte(ddm)))

				.append("</body></html>")
		}; return stringBuilder.toString()
	}

	/**
	 * Estimates the size of each download chunk/part for multi-threaded downloads.
	 * This calculation divides the total file size by the number of configured threads
	 * to determine the approximate size of each download segment.
	 *
	 * @param downloadModel The download model containing file size information
	 * @return Human-readable string of estimated chunk size (e.g., "1.5 MB")
	 */
	private fun estPartChunkSize(downloadModel: DownloadDataModel): String {
		return humanReadableSizeOf(
			downloadModel.fileSize /
					downloadModel.globalSettings.downloadDefaultThreadConnections
		)
	}

	/**
	 * Formats the network speed limit setting into a human-readable string.
	 * Converts the raw speed value (in bytes) to a formatted string with appropriate units.
	 *
	 * @param downloadModel The download model containing speed limit settings
	 * @return Formatted speed limit string (e.g., "1.5 MB/s") or "Unlimited"
	 */
	private fun formatNetworkSpeedLimit(downloadModel: DownloadDataModel): String {
		return formatDownloadSpeedInSimpleForm(
			downloadModel.globalSettings.downloadMaxNetworkSpeed.toDouble()
		)
	}

	/**
	 * Gets the downloaded bytes for each part in a multi-threaded download.
	 * This method generates an HTML-formatted list showing the progress of each
	 * individual download thread/part.
	 *
	 * @param downloadModel The download model containing part information
	 * @return HTML-formatted string showing bytes downloaded per part with human-readable sizes
	 */
	private fun getPartDownloadedByte(downloadModel: DownloadDataModel): String {
		val sb = StringBuilder()
		sb.append("<br>")
		downloadModel.partsDownloadedByte.forEachIndexed { index, downloadedByte ->
			sb.append("[${index} = ${humanReadableSizeOf(downloadedByte)}]<br>")
		}
		return sb.toString()
	}

	/**
	 * Gets the progress percentage for each part in a multi-threaded download.
	 * This method generates an HTML-formatted list showing the completion percentage
	 * of each individual download thread/part.
	 *
	 * @param downloadModel The download model containing part information
	 * @return HTML-formatted string showing progress percentage per part
	 */
	private fun getDownloadPartPercentage(downloadModel: DownloadDataModel): String {
		val sb = StringBuilder()
		sb.append("<br>")
		downloadModel.partProgressPercentage.forEachIndexed { index, percent ->
			sb.append("[${index} = ${percent}%]<br>")
		}
		return sb.toString()
	}

	/**
	 * Gets the configured HTTP proxy information.
	 * Returns the proxy server address or a "not configured" message if no proxy is set.
	 *
	 * @param downloadModel The download model containing proxy settings
	 * @return Proxy server string (e.g., "192.168.1.1:8080") or "not configured" message
	 */
	private fun getHttpProxy(downloadModel: DownloadDataModel): String {
		return downloadModel.globalSettings.downloadHttpProxyServer.ifEmpty {
			getText(R.string.title_not_configured)
		}
	}

	/**
	 * Gets the configured buffer size in human-readable format.
	 * Converts the raw buffer size (in bytes) to a formatted string with appropriate units.
	 *
	 * @param downloadModel The download model containing buffer settings
	 * @return Formatted buffer size string (e.g., "8 KB")
	 */
	private fun getBufferSize(downloadModel: DownloadDataModel): String {
		return humanReadableSizeOf(
			downloadModel.globalSettings.downloadBufferSize.toDouble()
		)
	}

	/**
	 * Gets the default number of threads configured for downloads.
	 * This represents how many parallel threads will be used for multi-threaded downloads.
	 *
	 * @param downloadModel The download model containing thread settings
	 * @return Number of threads configured (typically between 1-18)
	 */
	private fun defaultNumOfThreadsAssigned(downloadModel: DownloadDataModel): Int {
		return downloadModel.globalSettings.downloadDefaultThreadConnections
	}

	/**
	 * Gets the default number of parallel connections configured.
	 * This represents how many simultaneous downloads can occur in parallel.
	 *
	 * @param downloadModel The download model containing connection settings
	 * @return Number of parallel connections configured
	 */
	private fun defaultParallelConnection(downloadModel: DownloadDataModel): Int {
		return downloadModel.globalSettings.downloadDefaultParallelConnections
	}

	/**
	 * Checks if the download has unknown file size.
	 * Unknown file size occurs when the server doesn't provide Content-Length header
	 * or when the file size cannot be determined before download starts.
	 *
	 * @param downloadModel The download model to check
	 * @return "Yes" or "No" string indicating unknown file size status
	 */
	private fun isUnknownFile(downloadModel: DownloadDataModel): String {
		return if (downloadModel.isUnknownFileSize)
			getText(R.string.title_yes) else getText(R.string.title_no)
	}

	/**
	 * Checks if the download supports resume functionality.
	 * Resume support depends on server accepting Range requests and having a known file size.
	 *
	 * @param downloadModel The download model to check
	 * @return "Yes" or "No" string indicating resume support
	 */
	private fun isResumeSupported(downloadModel: DownloadDataModel): String {
		return if (downloadModel.isResumeSupported)
			getText(R.string.title_yes) else getText(R.string.title_no)
	}

	/**
	 * Checks if the download supports multi-threading.
	 * Multi-threading requires known file size and server support for partial content.
	 *
	 * @param downloadModel The download model to check
	 * @return "Yes" or "No" string indicating multi-thread support
	 */
	private fun isMultithreadingSupported(downloadModel: DownloadDataModel): String {
		return if (downloadModel.isMultiThreadSupported)
			getText(R.string.title_yes) else getText(R.string.title_no)
	}

	/**
	 * Checks if checksum verification is enabled for the download.
	 * Checksum verification compares the downloaded file's hash against a known value
	 * to ensure file integrity and completeness.
	 *
	 * @param downloadModel The download model to check
	 * @return String indicating whether checksum verification is performed or not performed
	 */
	private fun ifChecksumVerified(downloadModel: DownloadDataModel): String {
		return if (downloadModel.globalSettings.downloadVerifyChecksum)
			getText(R.string.title_performed) else getText(R.string.title_not_performed)
	}

	/**
	 * Gets the real-time network speed of an active download.
	 * Returns the current download speed for active downloads, or "--" for inactive ones.
	 *
	 * @param downloadModel The download model to check
	 * @return Formatted speed string (e.g., "1.5 MB/s") or "--" if download isn't running
	 */
	private fun getRealtimeNetworkSpeed(downloadModel: DownloadDataModel): String {
		return if (!downloadModel.isRunning) "--" else downloadModel.realtimeSpeedInFormat
	}

	/**
	 * Creates an HTML anchor tag for a URL.
	 * Generates a clickable link that opens the URL when tapped in a WebView.
	 *
	 * @param url The URL to link to
	 * @return HTML anchor tag string with "Click here to open" text
	 */
	private fun buildUrlTag(url: String): String {
		return "<a href=\"$url\">${getText(R.string.title_click_here_to_open)}</a>"
	}
}