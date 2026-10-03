package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.hosts.Page
import org.websnake.vidchain.http.PlainGetFetcher
import org.websnake.vidchain.http.redirect.RedirectUnwrapper
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
		val found = PageScraper.discover(page, pages(ctx.userAgent))
			.filter { allowLocalTargets || !RedirectUnwrapper.isLocal(it.url) || RedirectUnwrapper.isLocal(page) }
		if (found.isEmpty()) return MethodOutcome.Failed("no media on the page")
		val reasons = ArrayList<String>()
		for (c in found.take(maxCandidates)) {
			val r = when (c.kind) {
				MediaCandidate.Kind.FILE -> {
					val temp = HttpFileExecutor.partialFile(ctx, id) ?: return MethodOutcome.Unsupported("destination folder unknown")
					val headers = buildMap { ctx.userAgent?.let { put("User-Agent", it) }; put("Referer", c.page) }
					HttpFileExecutor.fetch(fetcher, verifier, ctx, temp, c.url, headers)
				}
				else -> stream?.invoke(ctx.copy(url = c.url, mediaUrl = c.url, urlClass = UrlClass.HLS_DASH, referer = c.page), c)
					?: MethodOutcome.Unsupported("${c.kind} stream found; no stream downloader")
			}
			if (r is MethodOutcome.Delivered) return r
			reasons += "${c.source}: ${(r as? MethodOutcome.Failed)?.reason ?: (r as? MethodOutcome.Unsupported)?.reason ?: r}"
		}
		return MethodOutcome.Failed("found ${found.size} candidate(s), none saved: " + reasons.joinToString("; ").take(300))
	}
}
