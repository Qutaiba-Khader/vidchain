package org.websnake.vidchain.fallback.core

import android.content.Context
import android.os.Build
import org.websnake.vidchain.engine.CapabilityProbe
import org.websnake.vidchain.engine.EngineHost
import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.KeyValueStore
import org.websnake.vidchain.engine.ProcessEngineRunner
import org.websnake.vidchain.engine.Quarantine
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent

/** What engine-based methods (P2-P5) share: one runner (global slot limit), the library layout, probes, quarantine, keep-alive. */
class EngineKit(context: Context) {
	private val app = context.applicationContext
	val layout = EngineLayout(java.io.File(app.applicationInfo.nativeLibraryDir), app.noBackupFilesDir, app.cacheDir)
	val runner = ProcessEngineRunner(log = { msg -> Trace.event { TraceEvent("engine", reason = msg) } })
	private val store = PrefsStore(app)
	val probe = CapabilityProbe(runner, store)
	val quarantine = Quarantine(store)
	val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

	init {
		EngineHost.log = { msg -> Trace.event { TraceEvent("engine.host", reason = msg) } }
	}

	/** keep the process alive while [block] runs an engine */
	suspend fun <T> keptAlive(reason: String, block: suspend () -> T): T = EngineHost.acquire(app, reason).use { block() }

	private class PrefsStore(context: Context) : KeyValueStore {
		private val prefs = context.getSharedPreferences("vidchain_engines", Context.MODE_PRIVATE)
		override fun get(key: String): String? = prefs.getString(key, null)
		override fun put(key: String, value: String?) { prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply() }
	}
}
