package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher
import org.websnake.vidchain.web.catcher.CatchResult
import org.websnake.vidchain.web.scrape.MediaCandidate

/**
 * W - hidden WebView catcher (T3.3): lets the page's own player run in a hidden WebView and saves what it loads.
 * A page that only plays from blob: (MediaSource fed by script) cannot be captured from outside: Unsupported.
 */
class WebCatcherMethod(
	private val fetcher: PlainGetFetcher,
	private val verifier: DeliveryVerifier,
	private val catcher: suspend (url: String, userAgent: String?) -> CatchResult?,
	private val stream: (suspend (FallbackContext, MediaCandidate) -> MethodOutcome)? = null,
	private val allowLocalTargets: Boolean = false,
) : FallbackMethod {
	override val id = "W"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val page = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val caught = catcher(page, ctx.userAgent) ?: return MethodOutcome.Unsupported("no WebView available")
		if (caught.candidates.isEmpty()) {
			return if (caught.sawBlob) MethodOutcome.Unsupported("the player streams from blob: (MediaSource) - not capturable")
			else MethodOutcome.Failed("the page loaded no media within the time limit")
		}
		return MediaSaver.save(ctx, id, caught.candidates, fetcher, verifier, stream, allowLocalTargets, maxCandidates = 3)
	}
}
