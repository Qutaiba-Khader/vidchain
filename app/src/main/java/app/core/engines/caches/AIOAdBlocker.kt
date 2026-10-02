package app.core.engines.caches

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility.executeInBackground
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * AIOAdBlocker is responsible for managing a list of hostnames used for ad blocking.
 *
 * It retrieves an updated list of ad-blocking hostnames from a remote GitHub raw file.
 * If the fetch fails, it falls back to a default hardcoded list.
 *
 * Features:
 * - Uses OkHttp for network requests.
 * - Uses coroutines for non-blocking I/O.
 * - Automatically falls back to default hosts if remote fetch fails.
 */
class AIOAdBlocker {

	private val logger = LogHelperUtils.from(javaClass)

	companion object {
		/** Remote URL containing ad-block hostnames (one per line). */
		private const val GITHUB_RAW_URL =
			"https://github.com/shibaFoss/AIO-Video-Downloader/raw/refs/" +
					"heads/master/others/adblock_host.txt"
	}

	/** Set of hostnames currently used for ad blocking. */
	private var adBlockHosts: Set<String> = emptySet()

	/** OkHttp client for making network requests. */
	private val client = OkHttpClient()

	/**
	 * Returns the current set of ad-block hostnames.
	 */
	fun getAdBlockHosts(): Set<String> {
		logger.d("Returning ${adBlockHosts.size} ad-block host(s)")
		return adBlockHosts
	}

	/**
	 * Asynchronously fetches ad-block filter hostnames from the remote URL.
	 * Falls back to default hosts if the network request fails.
	 */
	fun fetchAdFilters() {
		logger.d("Fetching ad-block hosts from remote source...")
		executeInBackground(timeOutInMilli = 3000L, codeBlock = {
			try {
				adBlockHosts = fetchHostsFromUrl() ?: run {
					logger.d("Remote fetch failed, falling back to default hosts")
					defaultHosts.toSet()
				}
				logger.d("Ad-block host list updated. Total hosts: ${adBlockHosts.size}")
			} catch (error: IOException) {
				logger.d("IOException while fetching ad-block hosts: ${error.localizedMessage}")
				error.printStackTrace()
				adBlockHosts = defaultHosts.toSet()
				logger.d("Applied default host list. Total hosts: ${adBlockHosts.size}")
			}
		})
	}

	/**
	 * Suspended function to fetch and parse ad-block hostnames from the remote URL.
	 * Skips comments and blank lines in the host file.
	 *
	 * @return A Set of valid hostnames, or null if the request fails.
	 */
	private suspend fun fetchHostsFromUrl(): Set<String>? {
		return withContext(Dispatchers.IO) {
			logger.d("Sending request to: $GITHUB_RAW_URL")

			val request = Request.Builder()
				.url(GITHUB_RAW_URL)
				.build()

			client.newCall(request).execute().use { response ->
				if (!response.isSuccessful) {
					logger.d("Failed to fetch hosts. HTTP code: ${response.code}")
					return@use null
				}

				val body = response.body.string()
				logger.d("Successfully fetched hosts file. Size: ${body.length} bytes")

				val hosts = body.lines()
					.filterNot { it.startsWith("#") || it.isBlank() }
					.map { it.trim() }
					.toSet()

				logger.d("Parsed ${hosts.size} valid host(s) from remote file")
				hosts
			}
		}
	}

	/** Fallback list of hostnames used if remote fetch fails. */
	private val defaultHosts = listOf(
		"afcdn.net",
		"aucdn.net",
		"tsyndicate.com"
	)
}
