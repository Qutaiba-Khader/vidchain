package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.ffmpeg.FfmpegEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import java.io.File

/**
 * F - ffmpeg runner (T3.4): saves an HLS (also AES-128), DASH or plain media URL with the library's ffmpeg as an MP4
 * by stream copy, after checking that this ffmpeg build has the protocols and demuxers the URL needs.
 */
class FfmpegMethod(
	private val engine: () -> FfmpegEngine?,
	private val caps: suspend () -> FfmpegEngine.Caps?,
	private val allowLocalTargets: Boolean = false,
) : FallbackMethod {
	override val id = "F"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.mediaUrl.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("no stream URL")
		val streamy = ctx.urlClass == UrlClass.HLS_DASH || Regex("""\.(m3u8|mpd)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
		if (!streamy) return MethodOutcome.Unsupported("not an HLS/DASH stream")
		if (!allowLocalTargets && org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(url) && !org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(ctx.url))
			return MethodOutcome.Failed("a public page's stream pointed into the local network: refused")
		val e = engine()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("ffmpeg not available")
		val c = caps() ?: return MethodOutcome.Unsupported("ffmpeg capabilities unknown")
		val needs = FfmpegEngine.needs(url)
		if (!c.can(needs)) return MethodOutcome.Unsupported("this ffmpeg build lacks ${needs.filter { it !in c.protocols && it !in c.demuxers }}")
		val dir = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val out = File(dir, "${ctx.parentId}-$id.mp4")
		val input = FfmpegEngine.Input(url, userAgent = ctx.userAgent, referer = ctx.referer?.takeIf { it.startsWith("http") })
		var r = e.save(listOf(input), out)
		if (r is FfmpegEngine.Result.Failed && FfmpegEngine.needsOtherContainer(r.reason)) {
			r = e.save(listOf(input), File(dir, "${ctx.parentId}-$id.ts"), format = "mpegts")   // stream copy into TS, no re-encoding
		}
		return when (r) {
			is FfmpegEngine.Result.Ok -> MethodOutcome.Delivered(r.file.path)
			is FfmpegEngine.Result.Failed -> when (r.outcome) {
				is EngineOutcome.OsKilled -> MethodOutcome.Failed("stopped by the system", retryable = true)
				else -> MethodOutcome.Failed(r.reason)
			}
		}
	}
}
