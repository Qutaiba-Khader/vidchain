package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.aria2.Aria2Engine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import java.io.File

/**
 * A - aria2c (T5.2): magnet links, .torrent and Metalink files (chain B6) and plain files with several connections
 * (B4). A torrent with several files delivers its largest file and the others as extras; pieces were checked by
 * their hashes, no seeding after the download completes.
 */
class Aria2Method(
	private val engine: () -> Aria2Engine?,
	private val dhtFile: () -> File? = { null },
) : FallbackMethod {
	override val id = "A"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val torrent = ctx.urlClass == UrlClass.MAGNET_TORRENT
		val uri = (if (torrent) ctx.url else ctx.mediaUrl.ifEmpty { ctx.url })
		val ok = uri.startsWith("magnet:", true) || HttpFileExecutor.isHttp(uri)
		if (!ok) return MethodOutcome.Unsupported("not a magnet, torrent or http(s) link")
		if (!torrent && ctx.urlClass != UrlClass.DIRECT_FILE) return MethodOutcome.Unsupported("${ctx.urlClass.name}: not a plain file")
		val e = engine()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("aria2c not available on this phone")
		val base = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val dir = File(base, "${ctx.parentId}-$id").apply { deleteRecursively(); mkdirs() }
		val job = Aria2Engine.Job(
			uri = uri, dir = dir,
			outName = if (torrent) null else "${ctx.parentId}-$id.bin",
			userAgent = ctx.userAgent,
			headers = buildMap { ctx.referer?.takeIf { it.startsWith("http") }?.let { put("Referer", it) } },
			dhtFile = dhtFile(),
		)
		return when (val r = e.run(job)) {
			is Aria2Engine.Result.Ok -> MethodOutcome.Delivered(r.files.first().path, extras = r.files.drop(1).map { it.path })
			is Aria2Engine.Result.Failed -> {
				dir.deleteRecursively()
				if (r.outcome is EngineOutcome.OsKilled) MethodOutcome.Failed("stopped by the system", retryable = true) else MethodOutcome.Failed(r.reason)
			}
		}
	}
}
