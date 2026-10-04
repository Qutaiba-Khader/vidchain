package org.websnake.vidchain.app

import android.content.Context
import app.core.AIOApp
import app.core.engines.settings.AIOSettings
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.fallback.ui.DownloadDefaults

/** T7.3: a fresh install downloads to the public Downloads folder (rule in [DownloadDefaults]). */
object VidChainDefaults {
	private const val PREFS = "vidchain_defaults"
	private const val APPLIED = "download_location_applied"

	/** started once from the host: waits (off the main thread) until the app has loaded its settings, then applies the rule */
	fun applyWhenReady(context: Context) {
		val app = context.applicationContext
		Thread({
			val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			if (prefs.getBoolean(APPLIED, false)) return@Thread
			val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull() ?: return@Thread
			val fresh = pkg.firstInstallTime == pkg.lastUpdateTime
			repeat(120) {
				val settings = try { AIOApp.aioSettings } catch (e: UninitializedPropertyAccessException) { null }
				if (settings != null) {
					val switch = DownloadDefaults.shouldSwitch(fresh, false, settings.defaultDownloadLocation == AIOSettings.PRIVATE_FOLDER)
					if (switch) {
						settings.defaultDownloadLocation = AIOSettings.SYSTEM_GALLERY
						settings.updateInStorage()
					}
					prefs.edit().putBoolean(APPLIED, true).apply()
					Trace.event { TraceEvent("defaults", result = if (switch) "public-downloads" else "kept", reason = "fresh install: $fresh") }
					return@Thread
				}
				Thread.sleep(500)
			}
		}, "vidchain-defaults").apply { isDaemon = true }.start()
	}
}
