package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.CancellationException
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.ffmpeg.FfmpegEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.media3.LoopbackProxy
import org.websnake.vidchain.media3.StreamDownloader
import java.io.File

/**
 * M - Media3 HLS/DASH download + MP4 export (T3.6): Media3's own downloaders fetch the user's rendition into a
 * private cache (VOD), then the library's ffmpeg reads it back through a loopback proxy and copies it into an MP4.
 * The cache is deleted afterwards.
 */
class Media3Method(
	private val downloader: () -> StreamDownloader?,
	private val ffmpeg: () -> FfmpegEngine?,
	private val workDir: () -> File?,
) : FallbackMethod {
	override val id = "M"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.mediaUrl.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("no stream URL")
		val streamy = ctx.urlClass == UrlClass.HLS_DASH || Regex("""\.(m3u8|mpd)(\?|$)""", RegexOption.IGNORE_CASE).containsMatchIn(url)
		if (!streamy) return MethodOutcome.Unsupported("not an HLS/DASH stream")
		val d = downloader() ?: return MethodOutcome.Unsupported("Media3 not available")
		val e = ffmpeg()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("ffmpeg needed for the MP4 export")
		val work = workDir()?.let { File(it, "media3-${ctx.parentId}") } ?: return MethodOutcome.Unsupported("no work folder")
		val out = HttpFileExecutor.partialFile(ctx, id)?.parentFile?.let { File(it, "${ctx.parentId}-$id.mp4") } ?: return MethodOutcome.Unsupported("destination folder unknown")
		val headers = buildMap { ctx.userAgent?.let { put("User-Agent", it) }; ctx.referer?.takeIf { it.startsWith("http") }?.let { put("Referer", it) } }
		val got = try {
			d.download(url, headers, ctx.preferredHeight, work) { }
		} catch (c: CancellationException) {
			throw c
		} catch (t: Throwable) {
			return MethodOutcome.Failed("Media3: ${t.javaClass.simpleName}: ${t.message?.take(200)}")
		}
		try {
			LoopbackProxy(got.store).use { proxy ->
				val inputs = got.inputs.take(2).map { FfmpegEngine.Input(proxy.proxied(it)) }
				var r = e.save(inputs, out)
				if (r is FfmpegEngine.Result.Failed && FfmpegEngine.needsOtherContainer(r.reason)) r = e.save(inputs, File(out.parentFile, out.nameWithoutExtension + ".ts"), format = "mpegts")
				return when (r) {
					is FfmpegEngine.Result.Ok -> MethodOutcome.Delivered(r.file.path)
					is FfmpegEngine.Result.Failed -> if (r.outcome is EngineOutcome.OsKilled) MethodOutcome.Failed("stopped by the system", retryable = true) else MethodOutcome.Failed("export: ${r.reason}")
				}
			}
		} finally {
			got.close()
		}
	}
}
