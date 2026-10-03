package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.lux.LuxEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import java.io.File

/**
 * X - lux (T5.6): a second, independent extractor for the sites lux knows (bilibili, douyin, kuaishou, weibo, tiktok,
 * twitter, vimeo, ...). Only pages lux has its own extractor for; its generic fetcher would save HTML pages.
 * arm64-v8a and x86_64 only (Q8). No cookies.
 */
class LuxMethod(private val engine: () -> LuxEngine?) : FallbackMethod {
	override val id = "X"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val extractor = LuxEngine.extractorFor(url) ?: return MethodOutcome.Unsupported("lux has no extractor for this site")
		val e = engine()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("lux not available on this phone")
		val base = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val dir = File(base, "${ctx.parentId}-$id").apply { deleteRecursively(); mkdirs() }
		val job = LuxEngine.Job(url, dir, "${ctx.parentId}-$id", ctx.userAgent, ctx.referer?.takeIf { it.startsWith("http") }, ctx.audioOnly)
		return when (val r = e.run(job)) {
			is LuxEngine.Result.Ok -> MethodOutcome.Delivered(r.files.first().path, extras = r.files.drop(1).map { it.path })
			is LuxEngine.Result.Failed -> {
				dir.deleteRecursively()
				if (r.outcome is EngineOutcome.OsKilled) MethodOutcome.Failed("stopped by the system", retryable = true) else MethodOutcome.Failed("lux ($extractor): ${r.reason}")
			}
		}
	}
}
