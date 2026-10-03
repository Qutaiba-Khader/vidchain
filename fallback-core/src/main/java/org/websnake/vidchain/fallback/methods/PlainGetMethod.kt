package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.fallback.verify.Expectation
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

/**
 * O - plain GET (T2.1): fetches the URL the current method was downloading as one GET stream, with none of the current
 * method's preconditions (HEAD probe, Range support, known length). Executor: the coordinator verifies the temp file,
 * commits it next to the parent's destination and lists it in the app's finished downloads.
 */
class PlainGetMethod(private val fetcher: PlainGetFetcher, private val verifier: DeliveryVerifier) : FallbackMethod {
	override val id = "O"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.mediaUrl.ifEmpty { ctx.url }
		if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) return MethodOutcome.Unsupported("not an http(s) URL")
		if (ctx.urlClass == UrlClass.HLS_DASH || ctx.urlClass == UrlClass.MAGNET_TORRENT) return MethodOutcome.Unsupported("${ctx.urlClass.name}: not a single file")
		val dest = ctx.destPath?.let(::File)?.takeIf { it.parentFile != null } ?: return MethodOutcome.Unsupported("destination folder unknown")
		val dir = File(dest.parentFile, PARTIAL_DIR)
		if (dir.mkdirs()) runCatching { File(dir, ".nomedia").createNewFile() }   // keep partial files out of the gallery
		val temp = File(dir, "${ctx.parentId}-$id.part")
		val headers = buildMap {
			ctx.userAgent?.let { put("User-Agent", it) }
			ctx.referer?.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
		}
		val exp = Expectation(expectMedia = ctx.expectMedia)
		return when (val r = fetcher.fetch(PlainGetFetcher.Fetch(url, headers, temp, early = { head -> verifier.early(head, exp)?.reason }))) {
			is PlainGetFetcher.Result.Done -> MethodOutcome.Delivered(r.file.path)
			is PlainGetFetcher.Result.Failed -> {
				temp.delete(); File(temp.path + ".meta").delete()
				MethodOutcome.Failed(r.reason)
			}
		}
	}

	companion object {
		/** hidden folder next to the download for fallback partial files; committed files leave it */
		const val PARTIAL_DIR = ".vidchain-partial"
	}
}
