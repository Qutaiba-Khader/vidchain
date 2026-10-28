package org.websnake.vidchain.fallback.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.websnake.vidchain.fallback.core.FallbackContext
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.core.MethodOutcome
import org.websnake.vidchain.fallback.core.UrlClass
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.fallback.verify.AndroidDurationProbe
import org.websnake.vidchain.fallback.verify.Check
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.fallback.verify.Expectation
import java.io.File

/**
 * T9 stage 4c: the site x method test matrix (tools/phone/matrix.py) runs one method on one link and reads the result
 * from the trace ("matrix" event: ok / failed / unsupported / not-media / timeout, and the time). Only `adb shell` can
 * send it (the receiver needs android.permission.DUMP). The file a method saves is checked like a real delivery,
 * then deleted; nothing is added to the user's downloads.
 *
 *   am broadcast -a org.websnake.vidchain.MATRIX -n org.websnake.vidchain/org.websnake.vidchain.fallback.ui.MatrixReceiver \
 *     --es url <link> --es method <id> [--es run <tag>]
 *
 * Owner 2026-10-28: `--es test crash` sends a test crash report instead (the same as the settings button).
 */
class MatrixReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.getStringExtra("test") == "crash") { org.websnake.vidchain.fallback.core.CrashReporting.hooks?.testCrash(); return }
		val url = intent.getStringExtra("url")?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return
		val id = intent.getStringExtra("method") ?: return
		val run = intent.getStringExtra("run") ?: System.currentTimeMillis().toString()
		val m = FallbackRuntime.methods.firstOrNull { it.id == id }
		val scope = FallbackRuntime.scopeOrNull() ?: return
		if (m == null) { Trace.event { TraceEvent("matrix", method = id, result = "unknown-method", reason = "run=$run") }; return }
		val dir = File(context.cacheDir, "vidchain-matrix").apply { mkdirs() }
		scope.launch {
			val site = org.websnake.vidchain.fallback.race.Scorecard.siteOf(url)
			val ctx = FallbackContext(parentId = "matrix-$run", url = url, mediaUrl = url, urlClass = UrlClass.of(url), destPath = File(dir, "matrix-$run.mp4").path)
			val t0 = System.currentTimeMillis()
			val o = withTimeoutOrNull(LIMIT_MS) {
				runCatching { FallbackRuntime.keepAliveFor(id) { m.attempt(ctx) } }.getOrElse { MethodOutcome.Failed("crashed: ${it.javaClass.simpleName}") }
			}
			val ms = System.currentTimeMillis() - t0
			val (result, why) = when (o) {
				null -> "timeout" to "over ${LIMIT_MS / 1000} s"
				is MethodOutcome.Delivered -> {
					val f = File(o.path)
					val r = DeliveryVerifier(AndroidDurationProbe).verify(f, Expectation(expectMedia = true))
					f.delete(); o.extras.forEach { File(it).delete() }
					(if (r.check == Check.FAIL) "not-media" else "ok") to "${r.kind.name}: ${r.reason}"
				}
				is MethodOutcome.Resolved -> (if (FallbackRuntime.probeCandidate(o.candidate, ctx)) "ok" else "not-media") to "candidate ${o.candidate.kind}"
				is MethodOutcome.Failed -> "failed" to o.reason.take(160)
				is MethodOutcome.Unsupported -> "unsupported" to o.reason.take(160)
				is MethodOutcome.Redirected -> "redirected" to UrlClass.of(o.url).chain
			}
			dir.listFiles()?.filter { it.name.startsWith("matrix-$run") }?.forEach { it.delete() }
			Trace.event { TraceEvent("matrix", method = id, result = result, reason = "run=$run site=$site $why", durationMs = ms) }
		}
	}

	private companion object {
		const val LIMIT_MS = 150_000L
	}
}
