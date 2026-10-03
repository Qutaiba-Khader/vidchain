package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.http.redirect.RedirectUnwrapper

/**
 * R - redirect and short-link unwrap (T2.2): resolves wrappers, shorteners, HTTP and page redirects. The coordinator
 * then continues with the unwrapped URL, in the chain of its own URL class (a t.co link to a YouTube video moves to B1).
 */
class RedirectMethod(private val unwrapper: RedirectUnwrapper) : FallbackMethod {
	override val id = "R"
	override val kind = FallbackMethod.Kind.RESOLVER

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val headers = buildMap { ctx.userAgent?.let { put("User-Agent", it) } }
		val candidates = listOf(ctx.url, ctx.mediaUrl).filter { it.startsWith("http", ignoreCase = true) }.distinct()
		if (candidates.isEmpty()) return MethodOutcome.Unsupported("not an http(s) URL")
		var lastReason = "nothing to unwrap"
		for (u in candidates) {
			when (val r = unwrapper.unwrap(u, headers)) {
				is RedirectUnwrapper.Result.Unwrapped -> return MethodOutcome.Redirected(r.url, "${r.hops.size} hop(s)")
				is RedirectUnwrapper.Result.Failed -> lastReason = r.reason
				is RedirectUnwrapper.Result.Unchanged -> Unit
			}
		}
		return if (lastReason == "nothing to unwrap") MethodOutcome.Unsupported(lastReason) else MethodOutcome.Failed(lastReason)
	}
}
