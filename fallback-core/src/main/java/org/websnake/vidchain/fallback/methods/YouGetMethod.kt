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
 * U - you-get (T5.4) through aio_engine on the bundled Python. Its JavaScript needs (YouTube) run through the
 * dukpy shim in the library's QuickJS; merging uses the library's ffmpeg. Files of the page are delivered (largest
 * first, others as extras).
 */
class YouGetMethod(
	private val engine: () -> PythonEngine?,
	private val zip: () -> File?,
	private val extraEnv: Map<String, String> = emptyMap(),
) : FallbackMethod {
	override val id = "U"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val e = engine()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("Python runtime not available")
		val z = zip()?.takeIf { it.isFile } ?: return MethodOutcome.Unsupported("Python engines not installed")
		val base = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val dir = File(base, "${ctx.parentId}-$id").apply { deleteRecursively(); mkdirs() }
		val args = buildList {
			add("you-get"); add("--dest"); add(dir.absolutePath)
			ctx.userAgent?.let { add("--ua"); add(it) }
			ctx.referer?.takeIf { it.startsWith("http") }?.let { add("--referer"); add(it) }
			add("--"); add(url)
		}
		val r = e.aio(args, z, extraEnv = extraEnv, watchDir = dir)
		val events = r.lines.mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull() }
		val files = events.filter { it.optString("event") == "file" }.map { File(it.optString("path")) }.filter { it.isFile }.sortedByDescending { it.length() }
		if (files.isNotEmpty() && r.outcome == EngineOutcome.Success) return MethodOutcome.Delivered(files.first().path, extras = files.drop(1).map { it.path })
		dir.deleteRecursively()
		val reason = events.lastOrNull { it.optString("event") == "error" }?.optString("reason")
		return when (val o = ExitCodes.classify(r.exitCode, false, false, reason ?: r.stderrTail.joinToString("\n"))) {
			is EngineOutcome.Unsupported -> MethodOutcome.Unsupported("you-get: ${o.reason}")
			is EngineOutcome.OsKilled -> MethodOutcome.Failed("stopped by the system", retryable = true)
			is EngineOutcome.Failed -> MethodOutcome.Failed("you-get: ${reason ?: if (o.code == ExitCodes.NO_MEDIA) "nothing downloaded" else o.reason}")
			else -> MethodOutcome.Failed("you-get: $o")
		}
	}
}
