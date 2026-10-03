package org.websnake.vidchain.fallback.methods

import org.json.JSONObject
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.ExitCodes
import org.websnake.vidchain.engine.ffmpeg.FfmpegEngine
import org.websnake.vidchain.engine.python.PythonEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

/**
 * T - Streamlink (T5.5): its plugins (live and on-demand sites) resolve the stream on the bundled Python - with the
 * lxml and pycountry stand-ins - and hand it over: HLS/DASH and muxed video+audio go through the library's ffmpeg,
 * plain HTTP streams are fetched like O. Streamlink itself never downloads.
 */
class StreamlinkMethod(
	private val engine: () -> PythonEngine?,
	private val zip: () -> File?,
	private val ffmpeg: () -> FfmpegEngine?,
	private val fetcher: PlainGetFetcher,
	private val verifier: DeliveryVerifier,
	private val extraEnv: Map<String, String> = emptyMap(),
) : FallbackMethod {
	override val id = "T"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val page = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val e = engine()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("Python runtime not available")
		val z = zip()?.takeIf { it.isFile } ?: return MethodOutcome.Unsupported("Python engines not installed")
		val args = buildList {
			add("streamlink")
			ctx.userAgent?.let { add("--ua"); add(it) }
			ctx.referer?.takeIf { it.startsWith("http") }?.let { add("--referer"); add(it) }
			ctx.preferredHeight?.let { add("--height"); add(it.toString()) }
			if (ctx.audioOnly) add("--audio-only")
			add("--"); add(page)
		}
		val r = e.aio(args, z, timeoutMs = 120_000, extraEnv = extraEnv)
		val events = r.lines.mapNotNull { l -> runCatching { JSONObject(l) }.getOrNull() }
		val stream = events.lastOrNull { it.optString("event") == "stream" }
		if (stream == null || r.outcome != EngineOutcome.Success) {
			val reason = events.lastOrNull { it.optString("event") == "error" }?.optString("reason")
			return when (val o = ExitCodes.classify(r.exitCode, false, false, reason ?: r.stderrTail.joinToString("\n"))) {
				is EngineOutcome.Unsupported -> MethodOutcome.Unsupported("Streamlink: ${o.reason}")
				is EngineOutcome.OsKilled -> MethodOutcome.Failed("stopped by the system", retryable = true)
				else -> MethodOutcome.Failed("Streamlink: ${reason ?: o}")
			}
		}
		val urls = stream.getJSONArray("urls").let { a -> (0 until a.length()).map { a.getString(it) } }
		val h = stream.optJSONObject("headers")
		val ua = h?.optString("User-Agent")?.ifEmpty { null } ?: ctx.userAgent
		val referer = h?.optString("Referer")?.ifEmpty { null } ?: ctx.referer
		val type = stream.optString("type")
		val dir = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		if (type == "HTTPStream" && urls.size == 1) {
			val temp = HttpFileExecutor.partialFile(ctx, id)!!
			return HttpFileExecutor.fetch(fetcher, verifier, ctx, temp, urls[0], buildMap { ua?.let { put("User-Agent", it) }; referer?.let { put("Referer", it) } })
		}
		val f = ffmpeg()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("ffmpeg needed for $type")
		val inputs = urls.take(2).map { FfmpegEngine.Input(it, ua, referer) }
		val out = File(dir, "${ctx.parentId}-$id.mp4")
		var s = f.save(inputs, out)
		if (s is FfmpegEngine.Result.Failed && FfmpegEngine.needsOtherContainer(s.reason)) s = f.save(inputs, File(dir, "${ctx.parentId}-$id.ts"), format = "mpegts")
		return when (s) {
			is FfmpegEngine.Result.Ok -> MethodOutcome.Delivered(s.file.path)
			is FfmpegEngine.Result.Failed -> if (s.outcome is EngineOutcome.OsKilled) MethodOutcome.Failed("stopped by the system", retryable = true) else MethodOutcome.Failed("Streamlink $type: ${s.reason}")
		}
	}
}
