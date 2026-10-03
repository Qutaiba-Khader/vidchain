package org.websnake.vidchain.fallback.core

import android.content.Context
import android.view.View
import org.websnake.vidchain.fallback.classifier.UserIntent
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceAndroid
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.fallback.ui.FallbackUi

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

	/** download card: the fallback trail line (UI thread) */
	@JvmStatic fun decorateRow(row: View, downloadId: Int, statusViewId: Int) = guard("row") { FallbackUi.decorateRow(row, downloadId, statusViewId) }

	/** download options dialog: "Try another method" + "Methods tried" */
	@JvmStatic fun decorateOptions(view: View?, downloadId: Int, anchorId: Int, dismiss: () -> Unit) =
		guard("options") { FallbackUi.decorateOptions(view, downloadId, anchorId, dismiss) }

	/** Settings: the "VidChain fallbacks" row */
	@JvmStatic fun addSettingsEntry(layout: View, templateRowId: Int, templateTextId: Int) =
		guard("settings") { FallbackUi.addSettingsEntry(layout, templateRowId, templateTextId) }

	private fun intent(downloadId: Int, intent: UserIntent) = guard("intent") { FallbackRuntime.recordIntent(downloadId.toString(), intent) }

	private inline fun guard(seam: String, block: () -> Unit) {
		try {
			block()
		} catch (t: Throwable) {
			runCatching { Trace.event { TraceEvent("seam.error", reason = "$seam: ${t.javaClass.simpleName}: ${t.message}") } }
		}
	}
}
