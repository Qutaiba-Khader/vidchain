package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.hosts.HostResult
import org.websnake.vidchain.http.PlainGetFetcher

/**
 * L - file-host resolvers (T3.1): Dropbox, Pixeldrain, Google Drive, MediaFire and OneDrive share pages turned into the
 * file itself, then fetched like O. A host that wants a sign-in or a captcha ends the attempt with that reason.
 */
class FileHostMethod(
	private val fetcher: PlainGetFetcher,
	private val verifier: DeliveryVerifier,
	private val resolve: suspend (url: String, userAgent: String?) -> HostResult,
	private val allowLocalTargets: Boolean = false,
) : FallbackMethod {
	override val id = "L"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val candidates = listOf(ctx.url, ctx.mediaUrl).filter { HttpFileExecutor.isHttp(it) }.distinct()
		for (u in candidates) {
			when (val r = resolve(u, ctx.userAgent)) {
				is HostResult.Direct -> {
					if (!allowLocalTargets && org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(r.url) && !org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(u))
						return MethodOutcome.Failed("a public share link pointed into the local network: refused")
					val temp = HttpFileExecutor.partialFile(ctx, id) ?: return MethodOutcome.Unsupported("destination folder unknown")
					val headers = buildMap { ctx.userAgent?.let { put("User-Agent", it) }; putAll(r.headers.filterKeys { !it.equals("Cookie", true) }) }
					return HttpFileExecutor.fetch(fetcher, verifier, ctx, temp, r.url, headers)
				}
				is HostResult.NeedsLogin -> return MethodOutcome.Failed("needs sign-in: ${r.reason}")
				is HostResult.Failed -> return MethodOutcome.Failed(r.reason)
				HostResult.NotMine -> Unit
			}
		}
		return MethodOutcome.Unsupported("not a supported file host")
	}
}
