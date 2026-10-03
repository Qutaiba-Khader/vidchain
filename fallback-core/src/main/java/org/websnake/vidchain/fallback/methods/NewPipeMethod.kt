package org.websnake.vidchain.fallback.methods

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.ffmpeg.FfmpegEngine
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.http.PlainGetFetcher
import java.io.File

/**
 * P - NewPipe as a stream source (T3.5): for YouTube, SoundCloud, Bandcamp, PeerTube and media.ccc.de the app already
 * uses NewPipe for titles and thumbnails; P asks it for the streams and downloads them itself: one progressive file
 * (like O), video-only + audio joined by ffmpeg (stream copy), or HLS through ffmpeg.
 */
class NewPipeMethod(
	private val fetcher: PlainGetFetcher,
	private val verifier: DeliveryVerifier,
	private val source: suspend (url: String) -> StreamSource?,
	private val ffmpeg: () -> FfmpegEngine?,
) : FallbackMethod {
	override val id = "P"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		val url = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: return MethodOutcome.Unsupported("not an http(s) page")
		val s = try { source(url) } catch (e: Exception) { return MethodOutcome.Failed("NewPipe: ${e.javaClass.simpleName}: ${e.message?.take(160)}") }
			?: return MethodOutcome.Unsupported("not a NewPipe service")
		val dir = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val ua = ctx.userAgent
		return when (val plan = StreamPlan.pick(s, ctx.preferredHeight, ctx.audioOnly)) {
			is StreamPlan.File -> {
				val temp = HttpFileExecutor.partialFile(ctx, id)!!
				HttpFileExecutor.fetch(fetcher, verifier, ctx, temp, plan.stream.url, buildMap { ua?.let { put("User-Agent", it) } })
			}
			is StreamPlan.Join -> runFfmpeg(listOf(FfmpegEngine.Input(plan.video.url, ua), FfmpegEngine.Input(plan.audio.url, ua)), File(dir, "${ctx.parentId}-$id.mp4"), s.durationMs)
			is StreamPlan.Hls -> runFfmpeg(listOf(FfmpegEngine.Input(plan.url, ua)), File(dir, "${ctx.parentId}-$id.mp4"), s.durationMs)
			StreamPlan.Nothing -> MethodOutcome.Failed("NewPipe found no downloadable stream")
		}
	}

	private suspend fun runFfmpeg(inputs: List<FfmpegEngine.Input>, out: File, durationMs: Long?): MethodOutcome {
		val e = ffmpeg()?.takeIf { it.ready } ?: return MethodOutcome.Unsupported("ffmpeg not available to join the streams")
		var r = e.save(inputs, out, durationMs)
		if (r is FfmpegEngine.Result.Failed && FfmpegEngine.needsOtherContainer(r.reason)) {
			r = e.save(inputs, File(out.parentFile, out.nameWithoutExtension + ".mkv"), durationMs, format = "matroska")   // WebM/Opus pairs fit Matroska
		}
		return when (r) {
			is FfmpegEngine.Result.Ok -> MethodOutcome.Delivered(r.file.path)
			is FfmpegEngine.Result.Failed -> if (r.outcome is EngineOutcome.OsKilled) MethodOutcome.Failed("stopped by the system", retryable = true) else MethodOutcome.Failed(r.reason)
		}
	}

	companion object {
		/** NewPipe's StreamInfo for [url] reduced to a StreamSource; null when no NewPipe service handles the URL */
		suspend fun newPipeSource(url: String): StreamSource? = withContext(Dispatchers.IO) {
			val service = runCatching { org.schabi.newpipe.extractor.NewPipe.getServiceByUrl(url) }.getOrNull() ?: return@withContext null
			val info = org.schabi.newpipe.extractor.stream.StreamInfo.getInfo(service, url)
			fun progressive(d: org.schabi.newpipe.extractor.stream.DeliveryMethod) = d == org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP
			StreamSource(
				title = info.name,
				durationMs = info.duration.takeIf { it > 0 }?.times(1000),
				progressive = info.videoStreams.filter { it.isUrl }.map { StreamSource.Stream(it.content, it.height.takeIf { h -> h > 0 }, it.bitrate.takeIf { b -> b > 0 }, it.format?.suffix, progressive(it.deliveryMethod)) },
				videoOnly = info.videoOnlyStreams.filter { it.isUrl }.map { StreamSource.Stream(it.content, it.height.takeIf { h -> h > 0 }, it.bitrate.takeIf { b -> b > 0 }, it.format?.suffix, progressive(it.deliveryMethod)) },
				audio = info.audioStreams.filter { it.isUrl }.map { StreamSource.Stream(it.content, null, it.averageBitrate.takeIf { b -> b > 0 }, it.format?.suffix, progressive(it.deliveryMethod)) },
				hlsUrl = info.hlsUrl?.takeIf { it.isNotEmpty() },
			)
		}
	}
}
