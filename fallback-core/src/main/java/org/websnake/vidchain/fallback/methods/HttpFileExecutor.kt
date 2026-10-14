package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.fallback.verify.Expectation
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

/** Shared by the executors that save one HTTP file (O, S): partial file next to the destination, early sniff, cleanup on failure. */
internal object HttpFileExecutor {
	const val PARTIAL_DIR = ".vidchain-partial"

	fun partialFile(ctx: FallbackContext, methodId: String): File? {
		val dest = ctx.destPath?.let(::File)?.takeIf { it.parentFile != null } ?: return null
		val dir = File(dest.parentFile, PARTIAL_DIR)
		if (dir.mkdirs()) runCatching { File(dir, ".nomedia").createNewFile() }   // keep partial files out of the gallery
		return File(dir, "${ctx.parentId}-$methodId.part")
	}

	suspend fun fetch(fetcher: PlainGetFetcher, verifier: DeliveryVerifier, ctx: FallbackContext, temp: File, url: String, headers: Map<String, String>,
					  cookieFor: ((String) -> String?)? = null): MethodOutcome {
		val exp = Expectation(expectMedia = ctx.expectMedia, fileName = ctx.fileName)      // the same view as the final check
		// T9 decision 1: first bytes that are media prove this method's success to the race (the others stop)
		val early = { head: ByteArray ->
			verifier.early(head, exp)?.reason.also { bad -> if (bad == null && org.websnake.vidchain.fallback.verify.Sniffer.sniff(head).media) ctx.onProven?.invoke() }
		}
		return when (val r = fetcher.fetch(PlainGetFetcher.Fetch(url, headers, temp, early = early, cookieFor = cookieFor))) {
			is PlainGetFetcher.Result.Done -> MethodOutcome.Delivered(r.file.path)
			is PlainGetFetcher.Result.Failed -> {
				temp.delete(); File(temp.path + ".meta").delete()
				MethodOutcome.Failed(r.reason)
			}
		}
	}

	fun isHttp(url: String) = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
}
