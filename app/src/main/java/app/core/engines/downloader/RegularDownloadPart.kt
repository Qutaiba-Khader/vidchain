package app.core.engines.downloader

import app.core.engines.downloader.DownloadStatus.CLOSE
import app.core.engines.downloader.DownloadStatus.COMPLETE
import app.core.engines.downloader.DownloadURLHelper.createHttpRequestBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.networks.HttpClientProvider.okHttpClient
import lib.networks.NetworkUtility.isNetworkAvailable
import lib.networks.NetworkUtility.isWifiEnabled
import lib.networks.URLUtilityKT
import lib.networks.URLUtilityKT.extractHostUrl
import lib.process.LogHelperUtils
import okhttp3.OkHttpClient
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Represents a single part of a file being downloaded.
 *
 * Manages byte range, progress, network connection, and status for its segment.
 * Supports resumable and multi-threaded downloads, handles errors,
 * and reports updates to the parent downloader. Uses OkHttp for connections
 * with optional headers and cookies for reliability.
 *
 * @param regularDownloader The parent RegularDownloader managing this part.
 */
open class RegularDownloadPart(private val regularDownloader: RegularDownloader) {

	/** Logger for debug and error messages */
	private val logger = LogHelperUtils.from(javaClass)

	/** The starting byte position of this download part. */
	private var partStartPoint: Long = 0

	/** The ending byte position of this download part. */
	private var partEndingPoint: Long = 0

	/** Indicates if the server connection was terminated unexpectedly for this part. */
	private var isServerConnectionTerminated: Boolean = false

	/** Flag indicating if the part was canceled by the user. */
	open var isPartCanceledByUser: Boolean = false

	/** Index of this part within the parent download task. */
	open var partIndex: Int = 0

	/** Total byte size assigned to this download part. */
	open var partChunkSize: Long = 0

	/** Number of bytes downloaded so far for this part. */
	open var partDownloadedByte: Long = 0

	/** Stores any exception encountered during this part's download. */
	open var partDownloadErrorException: Exception? = null

	/** Current status of this part (e.g., DOWNLOADING, COMPLETE, CLOSE). */
	open var partDownloadStatus: Int = CLOSE

	/** Reference to the global download data model for shared task information. */
	private val downloadDataModel = regularDownloader.downloadDataModel

	/** Reference to global download settings (e.g., threads, retries). */
	private val downloadGlobalSettings = downloadDataModel.globalSettings

	/** Listener to report status updates of this part to the parent downloader. */
	private val partStatusDownloadPartListener: DownloadPartListener = regularDownloader

	/**
	 * Initializes a download part with its assigned index, byte range,
	 * and current downloaded progress. This is essential for resuming
	 * or splitting a file into multiple parts for multi-threaded downloads.
	 *
	 * @param downloadPartIndex Index of this part in the overall download.
	 * @param downloadStartingPoint Starting byte of the part.
	 * @param downloadEndingPoint Ending byte of the part.
	 * @param downloadChunkSize Total size of this part.
	 * @param downloadedByte Bytes already downloaded (for resuming).
	 */
	fun initiate(
		downloadPartIndex: Int,
		downloadStartingPoint: Long,
		downloadEndingPoint: Long,
		downloadChunkSize: Long,
		downloadedByte: Long
	) {
		this.partIndex = downloadPartIndex
		this.partStartPoint = downloadStartingPoint
		this.partEndingPoint = downloadEndingPoint
		this.partChunkSize = downloadChunkSize
		this.partDownloadedByte = downloadedByte
		logger.d(
			"Download part initialized: index=$partIndex," +
					" range=$partStartPoint-$partEndingPoint, downloaded=$partDownloadedByte"
		)
	}

	/**
	 * Starts downloading this part asynchronously using coroutines.
	 *
	 * Responsibilities:
	 * - Prepares the part for download by validating state and resources.
	 * - Starts network operations to fetch the part.
	 * - Logs download start and errors.
	 * - Cancels the part safely if an exception occurs.
	 */
	fun startDownload() {
		CoroutineScope(Dispatchers.IO).launch {
			try {
				prepareForDownloading().let { isReadyToDownload ->
					if (isReadyToDownload) {
						logger.d("Starting download for part $partIndex")
						tryDownloading()
					} else {
						logger.d("Download part $partIndex is not ready to start")
					}
				}
			} catch (error: Exception) {
				logger.e("Error while starting download for part $partIndex", error)
				cancelDownload()
			}
		}
	}

	/**
	 * Stops the download for this part silently without throwing errors.
	 * Marks the part as canceled by the user and terminates the server connection.
	 *
	 * @param isCanceledByUser Flag indicating whether the cancellation was initiated by the user.
	 */
	fun stopDownloadSilently(isCanceledByUser: Boolean = false) {
		isPartCanceledByUser = isCanceledByUser
		isServerConnectionTerminated = true
		logger.d("Part $partIndex stopped silently. User canceled: $isCanceledByUser")
	}

	/**
	 * Verifies network availability for this download part.
	 * Cancels the download if the network is not available or Wi-Fi-only mode is active and Wi-Fi is disabled.
	 *
	 * @return `true` if the network is available and download can proceed, otherwise `false`.
	 */
	fun verifyNetworkConnection(): Boolean {
		val isWifiOnly = downloadGlobalSettings.downloadWifiOnly
		if (!isNetworkAvailable() || (isWifiOnly && !isWifiEnabled())) {
			logger.d("Network unavailable or Wi-Fi required but disabled. Canceling part $partIndex.")
			cancelDownload()
			return false
		}
		return true
	}

	/**
	 * Checks whether the device has an active internet connection.
	 *
	 * @return `true` if the internet is connected, otherwise `false`.
	 */
	fun isInternetConnected(): Boolean = URLUtilityKT.isInternetConnected()

	/**
	 * Cancels the download part and marks its status as closed.
	 * Internal function used when network fails or download needs termination.
	 */
	private fun cancelDownload() {
		isServerConnectionTerminated = true
		updateDownloadPartStatus(status = CLOSE)
		logger.d("Part $partIndex canceled. Status set to CLOSE.")
	}

	/**
	 * Prepares the part for downloading.
	 * Resets flags and ensures the network is ready before starting.
	 *
	 * @return `true` if the part is ready to download, otherwise `false`.
	 */
	private fun prepareForDownloading(): Boolean {
		isServerConnectionTerminated = false
		isPartCanceledByUser = false
		val isNetworkReady = verifyNetworkConnection()
		logger.d("Part $partIndex preparation complete. Network ready: $isNetworkReady")
		return isNetworkReady
	}

	/**
	 * Attempts to start downloading this part.
	 * Checks if the part is already completed, and if not, starts the download if ready.
	 */
	private fun tryDownloading() {
		if (isPartCompleted()) {
			updateDownloadPartStatus(status = COMPLETE)
			logger.d("Part $partIndex already completed. Status set to COMPLETE.")
			return
		}

		isPartReadyToDownload().let { isReady ->
			if (isReady) {
				logger.d("Part $partIndex ready. Starting download from server.")
				downloadFromServer()
			}
		}
	}

	/**
	 * Checks whether this part has been fully downloaded.
	 *
	 * @return `true` if the downloaded bytes have reached or exceeded the part's chunk size.
	 */
	private fun isPartCompleted(): Boolean {
		val isCompleted = partDownloadedByte >= partChunkSize
		logger.d("Part $partIndex completed check: $isCompleted ($partDownloadedByte/$partChunkSize bytes)")
		return isCompleted
	}

	/**
	 * Checks whether this part is ready to start downloading.
	 * A part is ready if it is not canceled/terminated and its status is not COMPLETE.
	 *
	 * @return `true` if the part can be downloaded, otherwise `false`.
	 */
	private fun isPartReadyToDownload(): Boolean {
		val isReady = !isServerConnectionTerminated && partDownloadStatus != COMPLETE
		logger.d(
			"Part $partIndex ready to download check: $isReady " +
					"(Status: $partDownloadStatus, Terminated: $isServerConnectionTerminated)"
		)
		return isReady
	}

	/**
	 * Determines whether the download should run in single-threaded mode.
	 * Single-threaded mode is used for unknown file sizes or when only 1 thread is configured.
	 *
	 * @return `true` if the download is single-threaded, otherwise `false`.
	 */
	private fun isSingleThreaded(): Boolean {
		val singleThreaded = downloadDataModel.isUnknownFileSize
				|| downloadGlobalSettings.downloadDefaultThreadConnections == 1
		logger.d("Part $partIndex single-threaded check: $singleThreaded")
		return singleThreaded
	}

	/**
	 * Updates the download status of this part and notifies the listener accordingly.
	 *
	 * @param status New status for the download part (e.g., CLOSE or COMPLETE)
	 */
	private fun updateDownloadPartStatus(status: Int) {
		partDownloadStatus = status
		logger.d("Part $partIndex status updated to $status")
		when (status) {
			CLOSE -> partStatusDownloadPartListener.onPartCanceled(this)
			COMPLETE -> partStatusDownloadPartListener.onPartCompleted(this)
		}
	}

	/**
	 * Handles downloading a portion of the file from the server for this download part.
	 *
	 * Responsibilities:
	 * - Establish a secure connection to the file URL
	 * - Seek to the correct file offset for multi-threaded downloads
	 * - Read data from the server in buffered chunks
	 * - Write data to the local file safely
	 * - Track progress and handle speed limiting if configured
	 * - Detect and handle network interruptions or cancellations
	 * - Update the part's status to COMPLETE when finished
	 */
	private fun downloadFromServer() {
		lateinit var inputStream: InputStream

		CoroutineScope(Dispatchers.IO).launch {
			try {
				val downloadDataModel = regularDownloader.downloadDataModel
				val destinationFile = downloadDataModel.getDestinationFile()
				logger.d("Starting download for part $partIndex of file ${destinationFile.name}")

				// Initialize file for single-threaded downloads
				if (isSingleThreaded() && !destinationFile.exists()) {
					RandomAccessFile(destinationFile, "rw").setLength(0)
					logger.d("Initialized empty file for single-threaded download")
				}

				val randomAccessFile = RandomAccessFile(destinationFile, "rw")
				val fileOutputPosition = calculateFileOutputPosition(randomAccessFile)
				randomAccessFile.seek(fileOutputPosition)
				logger.d("Seeked to position $fileOutputPosition for part $partIndex")

				// Open connection
				val fileByteRange: String = calculateRange()

				inputStream = openRemoteInputStream(fileByteRange) ?: throw Exception("Input stream Error")
				logger.d("Connected to URL ${downloadDataModel.fileURL} with range $fileByteRange")

				// Download loop
				val bufferSize = downloadDataModel.globalSettings.downloadBufferSize
				val buffer = ByteArray(bufferSize)
				var fetchedBytes = 0
				val startTime = System.currentTimeMillis()

				while (
					!isServerConnectionTerminated && destinationFile.exists() &&
					inputStream.read(buffer).also { fetchedBytes = it } != -1
				) {
					limitDownloadSpeed(startTime, fetchedBytes)

					if (isSingleThreaded()) {
						randomAccessFile.write(buffer, 0, fetchedBytes)
						partDownloadedByte += fetchedBytes
					} else {
						val bytesToWrite = minOf(fetchedBytes.toLong(), getRemainingByteToWrite())
						randomAccessFile.write(buffer, 0, bytesToWrite.toInt())
						partDownloadedByte += bytesToWrite
						if (partDownloadedByte >= partChunkSize) break
					}

					logger.d("Part $partIndex downloaded $partDownloadedByte / $partChunkSize bytes")
				}

				inputStream.close()
				randomAccessFile.close()
				logger.d("Part $partIndex download finished or stopped")

				// Check file existence
				if (!destinationFile.exists()) {
					partDownloadErrorException = Exception("Destination file is not found")
					logger.e("Destination file missing during part $partIndex download")
					cancelDownload()
					return@launch
				}

				// Complete part if not terminated
				if (!isServerConnectionTerminated) {
					updateDownloadPartStatus(COMPLETE)
					logger.d("Part $partIndex marked as COMPLETE")
				}
			} catch (error: Exception) {
				logger.e("Error while downloading file in download part $partIndex:", error)
				partDownloadErrorException = error
				cancelDownload()
			}
		}
	}

	/**
	 * Limits the download speed for this part to the configured maximum network speed.
	 *
	 * @param startTime The timestamp when the download chunk started.
	 * @param fetchedBytes The number of bytes read in the current iteration.
	 *
	 * Responsibilities:
	 * - Calculate elapsed time since download started.
	 * - Delay the coroutine if the current speed exceeds the configured limit.
	 */
	private suspend fun limitDownloadSpeed(startTime: Long, fetchedBytes: Int) {
		val elapsedTime = System.currentTimeMillis() - startTime
		val speedLimit = downloadDataModel.globalSettings.downloadMaxNetworkSpeed
		if (speedLimit > 0) {
			val expectedTime = (fetchedBytes * 1000L) / speedLimit
			if (elapsedTime < expectedTime) {
				val delayTime = expectedTime - elapsedTime
				logger.d("Limiting part $partIndex speed: delaying for $delayTime ms")
				delay(delayTime)
			}
		}
	}

	/**
	 * Calculates the remaining bytes to write for this download part.
	 *
	 * @return The remaining byte count.
	 */
	private fun getRemainingByteToWrite(): Long = partChunkSize - partDownloadedByte

	/**
	 * Calculates the file offset to write the next bytes for this download part.
	 *
	 * @param file The file being written to.
	 * @return The position in the file to seek to before writing.
	 */
	private fun calculateFileOutputPosition(file: RandomAccessFile): Long {
		return if (isResumeNotSupported()) resetDataModelForSingleThread(file)
		else partStartPoint + partDownloadedByte
	}

	/**
	 * Resets the download state for single-threaded downloads when resume is not supported.
	 *
	 * @param file The destination file to reset.
	 * @return The starting byte position (always 0 in this case).
	 */
	private fun resetDataModelForSingleThread(file: RandomAccessFile): Long {
		logger.d("Resetting download data model for part $partIndex (single-threaded, no resume)")
		partDownloadedByte = 0
		downloadDataModel.partsDownloadedByte[partIndex] = 0
		downloadDataModel.partStartingPoint[partIndex] = 0
		downloadDataModel.partProgressPercentage[partIndex] = 0
		file.setLength(partDownloadedByte)
		return partDownloadedByte
	}

	/**
	 * Checks if resume is not supported for this download part.
	 *
	 * @return `true` if resume is not supported, otherwise `false`.
	 */
	private fun isResumeNotSupported(): Boolean {
		return !regularDownloader.downloadDataModel.isResumeSupported
	}

	/**
	 * Configures the HTTP connection for this download part.
	 *
	 * @param urlConnection The HttpsURLConnection instance to configure.
	 * @param range The byte range to request from the server.
	 *
	 * Responsibilities:
	 * - Set standard HTTP headers (Accept, User-Agent, Range).
	 * - Apply browser-specific headers if downloading from a browser source.
	 * - Configure timeouts and caching behavior.
	 */
	@Deprecated(message = "Some sites may reject headers; use OkHttp with cookies for reliable downloads.")
	private fun configureConnection(urlConnection: HttpsURLConnection, range: String) {
		val settings = regularDownloader.downloadDataModel.globalSettings
		logger.d("Configuring connection for part $partIndex with range: $range")

		urlConnection.instanceFollowRedirects = true
		urlConnection.useCaches = false
		urlConnection.setRequestProperty("Accept", "*/*")
		urlConnection.setRequestProperty("Range", range)
		urlConnection.setRequestProperty(
			"User-Agent",
			settings.downloadHttpUserAgent.ifEmpty { settings.browserHttpUserAgent }
		)

		if (downloadDataModel.isDownloadFromBrowser) {
			with(downloadDataModel) {
				URI(fileURL).host?.let { urlConnection.setRequestProperty("Host", it) }
				siteReferrer.takeIf { it.isNotEmpty() }?.let {
					urlConnection.setRequestProperty("Referer", extractHostUrl(it))
				}
				fileContentDisposition.takeIf { it.isNotEmpty() }?.let {
					urlConnection.setRequestProperty("Content-Disposition", it)
				}
				siteCookieString.takeIf { it.isNotEmpty() }?.let {
					urlConnection.setRequestProperty("Cookie", it)
				}
				urlConnection.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
				urlConnection.setRequestProperty("Sec-Fetch-Dest", "document")
				urlConnection.setRequestProperty("Sec-Fetch-Mode", "navigate")
			}
		}

		urlConnection.setReadTimeout(settings.downloadMaxHttpReadingTimeout)
		urlConnection.setConnectTimeout(settings.downloadMaxHttpReadingTimeout)
	}

	/**
	 * Opens a remote InputStream for the file URL using OkHttp, with support for:
	 * - Byte-range requests (for partial/resumable downloads)
	 * - Browser headers including User-Agent, Referer, and cookies
	 * - Redirect logging
	 *
	 * This method is intended for downloading file segments or full files while preserving
	 * authentication cookies from WebView or browser sessions.
	 *
	 * @param connectionByteRange The byte range to request from the server, e.g., "bytes=0-1024".
	 * @return An InputStream for reading the remote file content, or `null` if the request fails.
	 */
	private fun openRemoteInputStream(connectionByteRange: String): InputStream? {
		logger.d("Preparing OkHttp client for remote input stream")

		// Configure OkHttp client with redirects and logging
		val httpClient: OkHttpClient = okHttpClient.newBuilder()
			.followRedirects(followRedirects = true)
			.followSslRedirects(followProtocolRedirects = true)
			.addInterceptor { chain ->
				val httpRequest = chain.request()
				val httpResponse = chain.proceed(httpRequest)

				// Log redirect URL if response is a redirect
				if (httpResponse.isRedirect) {
					val location = httpResponse.header("Location")
					logger.d("Redirected to: $location")
				}
				httpResponse
			}.build()

		logger.d(
			"Building HTTP request for file: ${downloadDataModel.fileURL} " +
					"(byte range: $connectionByteRange)"
		)

		// Build request with browser headers and optional cookies
		val request = createHttpRequestBuilder(
			fileUrl = downloadDataModel.fileURL,
			userAgent = downloadDataModel.globalSettings.downloadHttpUserAgent,
			siteReferer = downloadDataModel.siteReferrer,
			byteRange = connectionByteRange,
			siteCookie = downloadDataModel.siteCookieString
		).build()

		logger.d("Executing HTTP request...")
		val response = httpClient.newCall(request).execute()

		// Handle failed responses
		if (!response.isSuccessful) {
			logger.e("Failed to open stream, HTTP ${response.code} (${response.message})")
			response.close()
			return null
		}

		logger.d("Opened remote input stream successfully for file: ${downloadDataModel.fileURL}")
		return response.body.byteStream()
	}

	/**
	 * Calculates the HTTP Range header value for this download part.
	 *
	 * @return A string representing the byte range to request.
	 */
	private fun calculateRange(): String {
		val range = when {
			isSingleThreaded() -> "bytes=$partDownloadedByte-"
			downloadDataModel.isMultiThreadSupported ->
				"bytes=${partStartPoint + partDownloadedByte}-${partEndingPoint}"

			else -> "bytes=${partStartPoint + partDownloadedByte}-"
		}
		logger.d("Calculated range for part $partIndex: $range")
		return range
	}

	/**
	 * Verifies whether the remote file supports HTTP range requests.
	 *
	 * @return `true` if range requests are supported, otherwise `false`.
	 */
	private fun verifyRangeSupport(): Boolean {
		return try {
			val urlConnection = URL(downloadDataModel.fileURL).openConnection() as HttpsURLConnection
			urlConnection.requestMethod = "HEAD"
			urlConnection.connect()
			val acceptsRanges = urlConnection.getHeaderField("Accept-Ranges") == "bytes"
			urlConnection.disconnect()
			logger.d("Range support for file: $acceptsRanges")
			acceptsRanges
		} catch (error: Exception) {
			logger.e("Error verifying range support of remote file:", error)
			false
		}
	}

	/**
	 * Listener interface for receiving updates about download part status.
	 */
	interface DownloadPartListener {
		/**
		 * Called when a download part is canceled.
		 *
		 * @param downloadPart The part that was canceled.
		 */
		fun onPartCanceled(downloadPart: RegularDownloadPart)

		/**
		 * Called when a download part is completed successfully.
		 *
		 * @param downloadPart The part that completed.
		 */
		fun onPartCompleted(downloadPart: RegularDownloadPart)
	}

}