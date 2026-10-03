package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.YtDlpFormats
import org.websnake.vidchain.ytdlp.youtube.YouTubeClients

/**
 * C - YouTube player_client retries (T2.5): asks yt-dlp for the video as different YouTube clients; the first client
 * whose info has a usable format also does the download (same client, so the stream URLs match).
 */
class YouTubeClientMethod(
	private val engine: () -> YtDlpEngine?,
	private val clients: () -> YouTubeClients?,
) : FallbackMethod {
	override val id = "C"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome {
		if (ctx.urlClass != UrlClass.YOUTUBE) return MethodOutcome.Unsupported("not YouTube")
		val e = engine() ?: return MethodOutcome.Unsupported("engine runtime not started")
		if (!e.ready) return MethodOutcome.Unsupported("yt-dlp runtime not extracted yet")
		val rotation = clients() ?: return MethodOutcome.Unsupported("no client rotation")
		val dir = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val base = "${ctx.parentId}-$id"
		val selector = YtDlpFormats.selector(ctx.preferredHeight, ctx.audioOnly)
		val tried = ArrayList<String>()
		for (client in rotation.plan()) {
			val o = YtDlpEngine.Options(userAgent = null, extraArgs = YouTubeClients.args(client))
			val info = e.info(ctx.url, o, timeoutMs = 90_000)
			if (info is YtDlpEngine.Result.Failed && info.problem == YtDlpEngine.Problem.KILLED) return MethodOutcome.Failed("stopped by the system", retryable = true)
			val error = when (info) {
				is YtDlpEngine.Result.Ok -> if (info.value.formats.isEmpty()) "no video formats" else null
				is YtDlpEngine.Result.Failed -> info.reason
			}
			if (error == null) {
				when (val d = e.download(ctx.url, selector, dir, base, o)) {
					is YtDlpEngine.Result.Ok -> { rotation.won(client); return MethodOutcome.Delivered(d.value.path) }
					is YtDlpEngine.Result.Failed -> {
						dir.listFiles { f -> f.name.startsWith("$base.") }?.forEach { it.delete() }
						if (d.problem == YtDlpEngine.Problem.KILLED) return MethodOutcome.Failed("stopped by the system", retryable = true)
						tried += "$client: download ${d.problem.name.lowercase()}"
						if (YouTubeClients.judge(d.reason) == YouTubeClients.Verdict.GIVE_UP) break
					}
				}
				continue
			}
			tried += "$client: ${error.take(80)}"
			when (YouTubeClients.judge(error)) {
				YouTubeClients.Verdict.NEEDS_PO_TOKEN -> rotation.needsPoToken(client)
				YouTubeClients.Verdict.GIVE_UP -> return MethodOutcome.Failed("$client: $error")
				YouTubeClients.Verdict.NEXT_CLIENT -> Unit
			}
		}
		return if (tried.isEmpty()) MethodOutcome.Unsupported("every client needs a PO token") else MethodOutcome.Failed(tried.joinToString("; "))
	}
}
