package org.websnake.vidchain.fallback.methods

import org.json.JSONObject
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.ExitCodes
import org.websnake.vidchain.engine.python.PythonEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import java.io.File

/**
 * G - gallery-dl (T5.3) through the aio_engine launcher on the bundled Python: galleries, posts and image/video
 * pages of the sites gallery-dl knows. All files of a post are delivered (the largest first, the others as extras).
 */
class GalleryDlMethod(
	private val engine: () -> PythonEngine?,
	private val zip: () -> File?,
) : FallbackMethod {
	override val id = "G"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val e = engine()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("Python runtime not available")
		val z = zip()?.takeIf { it.isFile } ?: return MethodOutcome.Unsupported("Python engines not installed")
		val base = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val dir = File(base, "${ctx.parentId}-$id").apply { deleteRecursively(); mkdirs() }
		val args = buildList {
			add("gallery-dl"); add("--dest"); add(dir.absolutePath)
			ctx.userAgent?.let { add("--ua"); add(it) }
			ctx.referer?.takeIf { it.startsWith("http") }?.let { add("--referer"); add(it) }
			add("--"); add(url)                                   // a URL is never read as an option
		}
		val r = e.aio(args, z, watchDir = dir)
		val files = r.lines.mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull()?.takeIf { it.optString("event") == "file" }?.optString("path")?.let(::File) }
			.filter { it.isFile }.sortedByDescending { it.length() }
		if (files.isNotEmpty() && r.outcome == EngineOutcome.Success) return MethodOutcome.Delivered(files.first().path, extras = files.drop(1).map { it.path })
		dir.deleteRecursively()
		val reason = r.lines.mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull()?.takeIf { it.optString("event") == "error" }?.optString("reason") }.lastOrNull()
		return when (val o = ExitCodes.classify(r.exitCode, false, false, reason ?: r.stderrTail.joinToString("\n"))) {
			is EngineOutcome.Unsupported -> MethodOutcome.Unsupported("gallery-dl: ${o.reason}")
			is EngineOutcome.OsKilled -> MethodOutcome.Failed("stopped by the system", retryable = true)
			is EngineOutcome.Failed -> MethodOutcome.Failed("gallery-dl: ${if (o.code == ExitCodes.NO_MEDIA) "nothing to download" else o.reason}")
			else -> MethodOutcome.Failed("gallery-dl: $o")
		}
	}
}
