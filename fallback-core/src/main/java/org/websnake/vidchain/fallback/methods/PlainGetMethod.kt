package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher

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
		if (!HttpFileExecutor.isHttp(url)) return MethodOutcome.Unsupported("not an http(s) URL")
		if (ctx.urlClass == UrlClass.HLS_DASH || ctx.urlClass == UrlClass.MAGNET_TORRENT) return MethodOutcome.Unsupported("${ctx.urlClass.name}: not a single file")
		val temp = HttpFileExecutor.partialFile(ctx, id) ?: return MethodOutcome.Unsupported("destination folder unknown")
		val headers = buildMap {
			ctx.userAgent?.let { put("User-Agent", it) }
			ctx.referer?.takeIf { it.startsWith("http") }?.let { put("Referer", it) }
		}
		return HttpFileExecutor.fetch(fetcher, verifier, ctx, temp, url, headers)
	}

	companion object {
		const val PARTIAL_DIR = HttpFileExecutor.PARTIAL_DIR
	}
}
