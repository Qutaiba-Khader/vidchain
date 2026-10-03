package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.context.SessionContext
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.YtDlpFormats
import java.io.File

/**
 * Y - yt-dlp for any URL (T2.4): runs the library's own yt-dlp (generic extractor included) on the page and downloads
 * the format closest to what the user picked. Executor: the coordinator verifies, commits and lists the file.
 * Method S calls [run] with the browsing session (cookies as a private Netscape file in the app's cache, deleted after).
 */
class YtDlpMethod(
	private val engine: () -> YtDlpEngine?,
	private val cookieDir: () -> File?,
	/** crash bench (T1.9 quarantine) for this engine version and ABI; null in tests */
	private val bench: Bench? = null,
) : FallbackMethod {
	interface Bench {
		fun benched(method: String): Boolean
		fun record(method: String, outcome: org.websnake.vidchain.engine.EngineOutcome?)
	}

	override val id = "Y"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome = run(ctx, null)

	suspend fun run(ctx: FallbackContext, session: SessionContext?): MethodOutcome {
		val e = engine() ?: return MethodOutcome.Unsupported("engine runtime not started")
		if (!e.ready) return MethodOutcome.Unsupported("yt-dlp runtime not extracted yet")
		if (bench?.benched(id) == true) return MethodOutcome.Unsupported("benched after repeated crashes of this yt-dlp build")
		if (ctx.urlClass == UrlClass.MAGNET_TORRENT) return MethodOutcome.Unsupported("torrent")
		val url = ctx.url.takeIf { HttpFileExecutor.isHttp(it) } ?: ctx.mediaUrl.takeIf { HttpFileExecutor.isHttp(it) }
			?: return MethodOutcome.Unsupported("not an http(s) URL")
		val dir = HttpFileExecutor.partialFile(ctx, id)?.parentFile ?: return MethodOutcome.Unsupported("destination folder unknown")
		val base = "${ctx.parentId}-$id"
		val cookies = session?.takeIf { it.cookies.isNotEmpty() }?.let { s -> cookieDir()?.let { d -> s.writeNetscape(File(d, "$base.cookies.txt")) } }
		try {
			val options = YtDlpEngine.Options(
				userAgent = session?.userAgent ?: ctx.userAgent,
				referer = session?.referer ?: ctx.referer?.takeIf { it.startsWith("http") },
				cookiesFile = cookies,
			)
			return when (val r = e.download(url, YtDlpFormats.selector(ctx.preferredHeight, ctx.audioOnly), dir, base, options)) {
				is YtDlpEngine.Result.Ok -> { bench?.record(id, org.websnake.vidchain.engine.EngineOutcome.Success); MethodOutcome.Delivered(r.value.path) }
				is YtDlpEngine.Result.Failed -> {
					bench?.record(id, r.outcome)
					dir.listFiles { f -> f.name.startsWith("$base.") }?.forEach { it.delete() }   // .part / .ytdl leftovers
					when (r.problem) {
						YtDlpEngine.Problem.UNSUPPORTED, YtDlpEngine.Problem.NOT_READY -> MethodOutcome.Unsupported(r.reason)
						YtDlpEngine.Problem.KILLED -> MethodOutcome.Failed("stopped by the system: ${r.reason}", retryable = true)
						else -> MethodOutcome.Failed("${r.problem.name.lowercase()}: ${r.reason}")
					}
				}
			}
		} finally {
			cookies?.delete()
		}
	}
}
