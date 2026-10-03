package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.context.SessionContext
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher

/**
 * S - session context retry (T2.3): the same file again, now with the browsing session the current method leaves out:
 * cookies scoped to the media host (from the in-app browser), the full Referer, Origin for cross-site media and the
 * browser's User-Agent. Pages that need an extractor get the session through [withExtractor] (yt-dlp, T2.4).
 */
class SessionMethod(
	private val fetcher: PlainGetFetcher,
	private val verifier: DeliveryVerifier,
	private val sessionFor: suspend (parentId: String, url: String) -> SessionContext?,
	private val withExtractor: (suspend (FallbackContext, SessionContext) -> MethodOutcome)? = null,
) : FallbackMethod {
	override val id = "S"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.mediaUrl.ifEmpty { ctx.url }
		if (!HttpFileExecutor.isHttp(url)) return MethodOutcome.Unsupported("not an http(s) URL")
		val session = sessionFor(ctx.parentId, url) ?: return MethodOutcome.Unsupported("no browsing session for this download")
		if (session.cookies.isEmpty() && session.referer == null) return MethodOutcome.Unsupported("the session adds nothing (no cookies, no page)")
		val fileLike = ctx.urlClass == UrlClass.DIRECT_FILE || (ctx.mediaUrl.isNotEmpty() && ctx.mediaUrl != ctx.url && ctx.urlClass != UrlClass.HLS_DASH)
		if (!fileLike) {
			val extractor = withExtractor ?: return MethodOutcome.Unsupported("${ctx.urlClass.name} needs an extractor with the session (yt-dlp, T2.4)")
			return extractor(ctx, session)
		}
		val temp = HttpFileExecutor.partialFile(ctx, id) ?: return MethodOutcome.Unsupported("destination folder unknown")
		return HttpFileExecutor.fetch(fetcher, verifier, ctx, temp, url, session.headersFor(url, withCookie = false), cookieFor = session::cookieHeader)
	}
}
