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
	/** T9: asked as a phone's browser when the page showed nothing to the download's own User-Agent */
	private val mobileUserAgent: String? = MOBILE_UA,
) : FallbackMethod {
	override val id = "H"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val page = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val startLocal = org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(page)
		val refuse = { u: String -> !allowLocalTargets && !startLocal && org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(u) }
		// T9 (owner's Facebook reel, 2026-10-14): some sites show their video data only to a phone's browser (Facebook:
		// a desktop User-Agent gets a page with no video at all, a phone's gets og:video and often the player's HD file)
		var found = PageScraper.discover(page, pages(ctx.userAgent), refuse = refuse)
		if (found.isEmpty() && mobileUserAgent != null && mobileUserAgent != ctx.userAgent)
			found = PageScraper.discover(page, pages(mobileUserAgent), refuse = refuse)
		if (found.isEmpty()) return MethodOutcome.Failed("no media on the page")
		return MediaSaver.save(ctx, id, found, fetcher, verifier, stream, allowLocalTargets, maxCandidates)
	}

	companion object {
		const val MOBILE_UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0.0.0 Mobile Safari/537.36"
	}
}
