package org.websnake.vidchain.fallback.core

import android.content.Context
import org.websnake.vidchain.fallback.classifier.UserIntent
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceAndroid
import org.websnake.vidchain.fallback.trace.TraceEvent

/**
 * The only entry points the upstream code calls: one line each, marked `// FALLBACK-SEAM:<id>` and listed in seams.lock.
 * Every call is guarded: nothing here can throw into the current download logic.
 */
object FallbackSeams {
	@JvmStatic fun appStarted(context: Context, host: FallbackHost) = guard("start") {
		TraceAndroid.install(context)
		FallbackRuntime.start(context, host)
	}

	@JvmStatic fun appShutdown() = guard("shutdown") { FallbackRuntime.shutdown() }
	@JvmStatic fun userResumed(downloadId: Int) = intent(downloadId, UserIntent.RESUME)
	@JvmStatic fun userPaused(downloadId: Int) = intent(downloadId, UserIntent.PAUSE)
	@JvmStatic fun renamePaused(downloadId: Int) = intent(downloadId, UserIntent.RENAME_PAUSE)
	@JvmStatic fun userCleared(downloadId: Int) = intent(downloadId, UserIntent.CLEAR)
	@JvmStatic fun userDeleted(downloadId: Int) = intent(downloadId, UserIntent.DELETE)

	private fun intent(downloadId: Int, intent: UserIntent) = guard("intent") { FallbackRuntime.recordIntent(downloadId.toString(), intent) }

	private inline fun guard(seam: String, block: () -> Unit) {
		try {
			block()
		} catch (t: Throwable) {
			runCatching { Trace.event { TraceEvent("seam.error", reason = "$seam: ${t.javaClass.simpleName}: ${t.message}") } }
		}
	}
}
