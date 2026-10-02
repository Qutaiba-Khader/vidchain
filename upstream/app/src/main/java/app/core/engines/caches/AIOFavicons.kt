package app.core.engines.caches

import app.core.AIOApp.Companion.INSTANCE
import lib.networks.URLUtilityKT.getBaseDomain
import lib.networks.URLUtilityKT.getGoogleFaviconUrl
import lib.process.LogHelperUtils
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * AIOFavicons is responsible for downloading and caching website favicons locally.
 *
 * It uses Google's favicon service to fetch favicons and stores them in the app's internal file directory.
 * Favicons are saved as PNG files under the "favicons" directory inside the app's private storage.
 */
class AIOFavicons {
	private val logger = LogHelperUtils.from(javaClass)

	/** Directory for storing cached favicon images. */
	private val faviconDir = File(INSTANCE.filesDir, "favicons")
		.apply {
			if (!exists()) {
				logger.d("Favicon directory not found. Creating: $absolutePath")
				mkdirs()
			} else {
				logger.d("Favicon directory already exists: $absolutePath")
			}
		}

	/**
	 * Downloads and saves the favicon for the given URL to the local favicon directory.
	 *
	 * @param url The URL of the website whose favicon is to be saved.
	 * @return The absolute path to the saved favicon file, or null if the download fails.
	 */
	private fun saveFavicon(url: String): String? {
		val baseDomain = getBaseDomain(url) ?: run {
			logger.d("Failed to extract base domain from URL: $url")
			return null
		}

		val faviconFile = File(faviconDir, "$baseDomain.png")
		if (faviconFile.exists()) {
			logger.d("Favicon already cached for $baseDomain at: ${faviconFile.absolutePath}")
			return faviconFile.absolutePath
		}

		val faviconUrl = getGoogleFaviconUrl(url)
		logger.d("Attempting to download favicon for $baseDomain from: $faviconUrl")

		return try {
			val connection = URL(faviconUrl).openConnection() as HttpsURLConnection
			connection.inputStream.use { input ->
				FileOutputStream(faviconFile).use { output ->
					input.copyTo(output)
				}
			}
			logger.d("Successfully saved favicon for $baseDomain at: ${faviconFile.absolutePath}")
			faviconFile.absolutePath
		} catch (error: Exception) {
			logger.d("Error downloading favicon for $baseDomain: ${error.localizedMessage}")
			error.printStackTrace()
			null
		}
	}

	/**
	 * Returns the path to the cached favicon image for the given URL.
	 * If the favicon is not cached, it attempts to download and save it.
	 *
	 * @param url The URL of the website.
	 * @return The absolute path to the favicon image, or null if it couldn't be retrieved.
	 */
	fun getFavicon(url: String): String? {
		if (url.isEmpty()) return null

		val baseDomain = getBaseDomain(url) ?: run {
			logger.d("Invalid URL, cannot extract domain: $url")
			return null
		}

		val faviconFile = File(faviconDir, "$baseDomain.png")
		return if (faviconFile.exists()) {
			logger.d("Returning cached favicon for $baseDomain at: ${faviconFile.absolutePath}")
			faviconFile.absolutePath
		} else {
			logger.d("Favicon not cached for $baseDomain, downloading...")
			saveFavicon(url)
		}
	}
}
