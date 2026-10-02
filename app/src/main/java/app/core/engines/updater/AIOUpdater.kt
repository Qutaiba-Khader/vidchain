package app.core.engines.updater

import com.aio.R
import lib.device.AppVersionUtility.versionName
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * AIOUpdater is responsible for checking the latest version of the app
 * by fetching version information from a remote GitHub-hosted text file.
 *
 * The remote file must be in the format:
 * ```
 * latest_version=1.2.3
 * latest_apk_url=https://example.com/app.apk
 * changelog_url=https://example.com/changelog
 * published_date=2025-08-16
 * ```
 *
 * Features:
 * - Retrieves the latest version information, APK URL, changelog URL, and published date.
 * - Compares local app version with the latest version to determine if an update is available.
 * - Uses OkHttp for network requests.
 * - Supports resuming interrupted downloads.
 *
 * Example usage:
 * ```
 * val updater = AIOUpdater()
 * if (updater.isNewUpdateAvailable()) {
 *     val apkUrl = updater.getLatestApkUrl()
 *     // Prompt user to update
 * }
 * ```
 */
class AIOUpdater {

	/** Logger instance for debugging and error tracking. */
	private val logger by lazy { LogHelperUtils.from(javaClass) }

	companion object {
		const val APK_FILE_NAME = ".aio_update.apk"
		const val TEMP_APK_FILE_NAME = ".aio_update.apk.temp"
		const val APK_DOWNLOAD_INFO_FILE = ".aio_update_download_info.txt"

		/**
		 * URL pointing to the version information file on GitHub.
		 * This file is read to determine if a new update is available.
		 */
		const val GITHUB_UPDATE_INFO_URL =
			"https://raw.githubusercontent.com/shibaFoss/AIO-Video-Downloader" +
					"/refs/heads/master/others/version_info.txt"
	}

	/**
	 * Data model representing all relevant update information.
	 *
	 * @property latestVersion The latest version name (e.g., "1.2.3").
	 * @property latestApkUrl Direct download link for the latest APK.
	 * @property changelogUrl Link to the changelog or release notes.
	 * @property publishedDate Date the update was published.
	 */
	data class UpdateInfo(
		val latestVersion: String?,
		val latestApkUrl: String?,
		val changelogUrl: String?,
		val publishedDate: String?,
		val versionHash: String?
	)

	/**
	 * Fetches and parses the update information file from the given URL.
	 *
	 * @param url The URL of the version information text file.
	 * @return [UpdateInfo] object if successful, or null if the request fails.
	 */
	fun fetchUpdateInfo(
		maxRetries: Int = 3,
		initialDelayMillis: Long = 1000L
	): UpdateInfo? {
		val url = GITHUB_UPDATE_INFO_URL
		logger.d("Fetching update info from URL: $url")

		// OkHttp client with timeouts
		val client = OkHttpClient.Builder()
			.connectTimeout(15, TimeUnit.SECONDS)
			.readTimeout(20, TimeUnit.SECONDS)
			.writeTimeout(20, TimeUnit.SECONDS)
			.build()

		val request = Request.Builder().url(url).build()

		var attempt = 0
		var delay = initialDelayMillis

		while (attempt < maxRetries) {
			try {
				attempt++
				logger.d("Attempt $attempt of $maxRetries...")

				client.newCall(request).execute().use { response ->
					if (!response.isSuccessful) {
						logger.d("Request failed (code=${response.code}, msg=${response.message})")
					} else {
						val lines = response.body.string().lines().associate {
							val parts = it.split("=", limit = 2)
							if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to ""
						}

						val updateInfo = UpdateInfo(
							latestVersion = lines["latest_version"],
							latestApkUrl = lines["latest_apk_url"],
							changelogUrl = lines["changelog_url"],
							publishedDate = lines["published_date"],
							versionHash = lines["version_hash"]
						)
						logger.d("Successfully parsed update info: $updateInfo")
						return updateInfo
					}
				}
			} catch (error: IOException) {
				logger.d("Network error on attempt $attempt: ${error.localizedMessage}")
			} catch (error: Exception) {
				logger.d("Unexpected error on attempt $attempt: ${error.localizedMessage}")
			}

			// Exponential backoff before retrying
			if (attempt < maxRetries) {
				logger.d("Retrying in ${delay}ms...")
				Thread.sleep(delay)
				delay *= 2
			}
		}

		logger.d("Failed to fetch update info after $maxRetries attempts.")
		return null
	}

	/**
	 * Retrieves the latest APK download URL from the remote version file.
	 *
	 * @return The latest APK URL as a String, or null if unavailable.
	 */
	fun getLatestApkUrl(): String? {
		logger.d("Getting latest APK URL")
		return fetchUpdateInfo()?.latestApkUrl
	}

	/**
	 * Checks if a new app update is available by comparing the local version
	 * with the remote version from the GitHub file.
	 *
	 * @return True if a newer version is available, false otherwise.
	 */
	fun isNewUpdateAvailable(): Boolean {
		logger.d("Checking for new update")
		val updateInfo = fetchUpdateInfo() ?: return false
		val onlineVersion = updateInfo.latestVersion?.replace(".", "")
		val localVersion = versionName?.replace(".", "") ?: return false

		return try {
			onlineVersion?.toInt()?.let { onlineVersionNumber ->
				val isUpdateAvailable = localVersion.toInt() < onlineVersionNumber
				logger.d("Update check result: $isUpdateAvailable (Local: $localVersion, Remote: $onlineVersion)")
				isUpdateAvailable
			} ?: false
		} catch (error: NumberFormatException) {
			logger.d("Error parsing version numbers: ${error.localizedMessage}")
			false
		}
	}

	/**
	 * Silently downloads a file from the given URL and saves it as "update.apk"
	 * in the app's private files directory with resume support.
	 *
	 * @param url The direct URL to the APK file.
	 * @return The File object pointing to the saved APK, or null if download fails.
	 */
	fun downloadUpdateApkSilently(url: String, version: String): File? {
		logger.d("Starting silent APK download from URL: $url")
		val externalDataFolderPath = getText(R.string.text_default_aio_download_folder_path)
		val directoryPath = "$externalDataFolderPath/${getText(R.string.title_aio_others)}/.configs"
		val downloadDestination = File(directoryPath)
		// Ensure directory exists
		if (!downloadDestination.exists()) {
			downloadDestination.mkdirs()
			logger.d("Directory created at: ${downloadDestination.absolutePath}")
		}

		val outputFile = File(downloadDestination, "$version.$APK_FILE_NAME")
		val tempFile = File(downloadDestination, "$version.$TEMP_APK_FILE_NAME")
		val infoFile = File(downloadDestination, "$version.$APK_DOWNLOAD_INFO_FILE")
		if (outputFile.isFile && outputFile.exists()) return outputFile

		try {
			// Check for existing download progress
			val downloadedBytes = if (tempFile.exists()) {
				logger.d("Found existing temp file, attempting to resume download")
				val lastUrl = if (infoFile.exists()) infoFile.readText() else ""
				if (lastUrl != url) {
					logger.d("URL changed, deleting previous download")
					tempFile.delete()
					infoFile.delete()
					0L
				} else {
					tempFile.length().also { size ->
						logger.d("Resuming download from $size bytes")
					}
				}
			} else {
				0L
			}

			// Save current URL to info file
			infoFile.writeText(url)

			val client = OkHttpClient()
			val request = Request.Builder()
				.url(url)
				.header("Range", "bytes=$downloadedBytes-")
				.build()

			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful && response.code != 206) {
					logger.d("Download failed with code: ${response.code}")
					throw IOException("Unexpected code $response")
				}

				val input = response.body.byteStream()
				RandomAccessFile(tempFile, "rw").use { output ->
					output.seek(downloadedBytes)
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					var bytesRead: Int
					while (input.read(buffer).also { bytesRead = it } != -1) {
						output.write(buffer, 0, bytesRead)
					}
				}

				// Rename temp file to final name when download completes
				if (tempFile.renameTo(outputFile)) {
					infoFile.delete()
					logger.d("APK downloaded successfully to: ${outputFile.absolutePath}")
					return outputFile
				} else {
					logger.d("Failed to rename temp file to final name")
					return null
				}
			}
		} catch (error: Exception) {
			logger.d("Error during APK download: ${error.localizedMessage}")
			// Keep temp file for resume
			return null
		}
	}
}