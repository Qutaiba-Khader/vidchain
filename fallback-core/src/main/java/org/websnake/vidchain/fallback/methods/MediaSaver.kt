package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher
import org.websnake.vidchain.http.redirect.RedirectUnwrapper
import org.websnake.vidchain.web.scrape.MediaCandidate

/** Saves the best of several found media URLs (H, W): files with their page as Referer, streams through [stream]. */
internal object MediaSaver {
	suspend fun save(
		ctx: FallbackContext, methodId: String, found: List<MediaCandidate>,
		fetcher: PlainGetFetcher, verifier: DeliveryVerifier,
		stream: (suspend (FallbackContext, MediaCandidate) -> MethodOutcome)?,
		allowLocalTargets: Boolean, maxCandidates: Int,
	): MethodOutcome {
		val usable = found.filter { allowLocalTargets || !RedirectUnwrapper.isLocal(it.url) || RedirectUnwrapper.isLocal(ctx.url) }
		if (usable.isEmpty()) return MethodOutcome.Failed("no media found")
		val reasons = ArrayList<String>()
		for (c in usable.take(maxCandidates)) {
			val r = when (c.kind) {
				MediaCandidate.Kind.FILE -> {
					val temp = HttpFileExecutor.partialFile(ctx, methodId) ?: return MethodOutcome.Unsupported("destination folder unknown")
					val headers = buildMap { ctx.userAgent?.let { put("User-Agent", it) }; put("Referer", c.page) }
					HttpFileExecutor.fetch(fetcher, verifier, ctx, temp, c.url, headers)
				}
				else -> stream?.invoke(ctx.copy(url = c.url, mediaUrl = c.url, urlClass = UrlClass.HLS_DASH, referer = c.page), c)
					?: MethodOutcome.Unsupported("${c.kind} stream found; no stream downloader")
			}
			if (r is MethodOutcome.Delivered) return r
			if (r is MethodOutcome.Failed && r.retryable) return r
			reasons += "${c.source}: ${(r as? MethodOutcome.Failed)?.reason ?: (r as? MethodOutcome.Unsupported)?.reason ?: r}"
		}
		return MethodOutcome.Failed("found ${usable.size} candidate(s), none saved: " + reasons.joinToString("; ").take(300))
	}
}
