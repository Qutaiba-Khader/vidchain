package org.websnake.vidchain.fallback.core

import kotlinx.coroutines.CancellationException
import java.net.URI
import java.util.Locale

/**
 * Expired links (T8.12, owner decision; idea from Tubular's download recovery): the app's own download stopped with
 * "link expired" after part of the file was saved. Instead of starting over with another method, the page is asked
 * again for the SAME file and the app's download resumes from where it stopped.
 *
 * "The same file" must be proven, because resuming splices two responses into one file: the fresh address must report
 * exactly the size the first one announced; when that size was never known, it must be the same host and path with
 * the same query apart from signature / expiry / routing keys (T8.12 review: a googlevideo itag or a quality
 * parameter is a different file). [sources] are asked in order (yt-dlp on the page, then the hidden page
 * catcher); [sizeOf] reads a total size without downloading (a one-byte range request).
 */
class LinkRefresher(
	private val sources: List<suspend (page: String) -> List<String>?>,
	private val sizeOf: suspend (url: String, referer: String?) -> Long?,
	private val maxProbes: Int = 8,
) {
	suspend fun fresh(d: HostDownload): String? {
		val old = d.mediaUrl.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
		val page = listOfNotNull(d.referer, d.url).firstOrNull { it.startsWith("http", ignoreCase = true) && it != old } ?: return null
		val want = d.expectedBytes?.takeIf { it > 0 }
		for (source in sources) {
			val found = try { source(page) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
				.orEmpty().filter { it.startsWith("http", ignoreCase = true) && it != old }.distinct()
			val ordered = found.sortedByDescending { samePlace(it, old) }.filter { want != null || sameFile(it, old) }.take(maxProbes)
			for (u in ordered) {
				val size = (try { sizeOf(u, page) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }) ?: continue
				if (if (want != null) size == want else size > 0) return u
			}
		}
		return null
	}

	companion object {
		/** query keys that change with every signing of the same file */
		private val VOLATILE = Regex("""^(?:sig|signature|lsig|sparams|lsparams|expire|expires|exp|e|ip|ipbits|ei|initcwndbps|mh|mm|mn|ms|mv|mvi|pl|pcm2|rms|n|c|txp|token|t|st|hdnts|hdnea|policy|key-pair-id|hash|auth|nonce|x-amz-.*|x-goog-.*|_.*)$""")

		/** same place and the same query apart from [VOLATILE] keys */
		fun sameFile(a: String, b: String): Boolean {
			if (!samePlace(a, b)) return false
			fun stable(u: String) = (runCatching { URI(u).rawQuery }.getOrNull() ?: "").split('&').filter { it.isNotEmpty() }
				.map { it.substringBefore('=').lowercase(Locale.ROOT) to it.substringAfter('=', "") }
				.filter { !VOLATILE.matches(it.first) }.sortedBy { it.first + "=" + it.second }
			return stable(a) == stable(b)
		}

		/** same host and same path: a re-signed address of the same file */
		fun samePlace(a: String, b: String): Boolean {
			val x = runCatching { URI(a) }.getOrNull() ?: return false
			val y = runCatching { URI(b) }.getOrNull() ?: return false
			return x.host != null && x.host.lowercase(Locale.ROOT) == y.host?.lowercase(Locale.ROOT) && x.rawPath == y.rawPath
		}
	}
}
