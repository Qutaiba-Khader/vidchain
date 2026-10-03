package org.websnake.vidchain.fallback.methods

import org.websnake.vidchain.fallback.context.SessionContext
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackMethod
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.ytdlp.YtDlpEngine
import org.websnake.vidchain.ytdlp.nightly.NightlyToolStore
import java.io.File

/**
 * N - isolated nightly yt-dlp (T4.1): the newest yt-dlp from the official nightly builds (fixes for sites change
 * daily), checksum-verified, in its own folder, on the library's Python with the library's QuickJS as JavaScript
 * runtime. The bundled yt-dlp and its updater are never touched. Same download path as Y.
 */
class NightlyYtDlpMethod(
	private val store: () -> NightlyToolStore?,
	private val engineFor: (File) -> YtDlpEngine?,
	private val quickJs: () -> File?,
	private val cookieDir: () -> File?,
	/** crash bench per nightly build (T1.9 quarantine) */
	private val bench: (File) -> YtDlpMethod.Bench? = { null },
) : FallbackMethod {
	override val id = "N"
	override val kind = FallbackMethod.Kind.EXECUTOR

	override suspend fun attempt(ctx: FallbackContext): MethodOutcome = run(ctx, null)

	suspend fun run(ctx: FallbackContext, session: SessionContext?): MethodOutcome {
		val s = store() ?: return MethodOutcome.Unsupported("nightly tool store not available")
		val tool = when (val r = s.current()) {
			is NightlyToolStore.Result.Ready -> r
			is NightlyToolStore.Result.Failed -> return MethodOutcome.Unsupported("no nightly yt-dlp: ${r.reason}")
		}
		val engine = engineFor(tool.file) ?: return MethodOutcome.Unsupported("engine runtime not started")
		val js = quickJs()?.takeIf { it.isFile }?.let { listOf("--js-runtimes", "quickjs:${it.absolutePath}") }.orEmpty()
		val y = YtDlpMethod({ engine }, cookieDir, bench(tool.file), extraArgs = js, methodId = id)
		return when (val r = y.run(ctx, session)) {
			is MethodOutcome.Failed -> r.copy(reason = "nightly ${tool.version}: ${r.reason}")
			else -> r
		}
	}
}
