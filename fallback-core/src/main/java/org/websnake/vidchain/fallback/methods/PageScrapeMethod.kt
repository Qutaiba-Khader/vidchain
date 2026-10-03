package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.hosts.Page
import org.websnake.vidchain.http.PlainGetFetcher
import org.websnake.vidchain.web.scrape.MediaCandidate
import org.websnake.vidchain.web.scrape.PageScraper

/**
 * H - page scraping (T3.2): finds the media on the page (and in its player iframes) and saves it. Files are fetched
 * with the page as Referer; HLS/DASH streams go to [stream] (yt-dlp downloads a stream URL directly).
 */
class PageScrapeMethod(
	private val fetcher: PlainGetFetcher,
	private val verifier: DeliveryVerifier,
	private val pages: (userAgent: String?) -> suspend (String) -> Page,
	private val stream: (suspend (FallbackContext, MediaCandidate) -> MethodOutcome)? = null,
	private val allowLocalTargets: Boolean = false,
	private val maxCandidates: Int = 3,
) : FallbackMethod {
	override val id = "H"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val page = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val startLocal = org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(page)
		val found = PageScraper.discover(page, pages(ctx.userAgent), refuse = { u -> !allowLocalTargets && !startLocal && org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(u) })
		if (found.isEmpty()) return MethodOutcome.Failed("no media on the page")
		return MediaSaver.save(ctx, id, found, fetcher, verifier, stream, allowLocalTargets, maxCandidates)
	}
}
