package org.websnake.vidchain.fallback.trace

import android.content.Context
import android.util.Log
import java.io.File

/** Android wiring: logcat sink, the file sink in the app's private files, and the persisted Settings switch. */
object TraceAndroid {
	@Volatile
	private var fileSink: TraceFileSink? = null

	/** Call once at app start (from the one-line app seam). Reads the Settings switch; default = [TraceConfig.DEFAULT_ENABLED]. */
	fun install(context: Context) {
		val prefs = context.getSharedPreferences(TraceConfig.PREFS, Context.MODE_PRIVATE)
		Trace.setEnabled(prefs.getBoolean(TraceConfig.PREF_ENABLED, TraceConfig.DEFAULT_ENABLED))
		Trace.clearSinks()
		Trace.addSink { line -> Log.i(TraceConfig.TAG, line) }
		val sink = TraceFileSink(File(context.filesDir, "trace"))
		fileSink = sink
		Trace.addSink(sink)
	}

	/** The Settings switch: persists the owner's choice and applies it at once. */
	fun setEnabled(context: Context, on: Boolean) {
		context.getSharedPreferences(TraceConfig.PREFS, Context.MODE_PRIVATE).edit().putBoolean(TraceConfig.PREF_ENABLED, on).apply()
		Trace.setEnabled(on)
	}

	/** the trace files for "open / share trace log" (newest first) */
	fun files(): List<File> = fileSink?.files().orEmpty()
}
