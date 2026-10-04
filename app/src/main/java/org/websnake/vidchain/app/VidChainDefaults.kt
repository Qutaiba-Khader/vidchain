package org.websnake.vidchain.app

import android.content.Context
import app.core.AIOApp
import app.core.engines.settings.AIOSettings
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.fallback.ui.DownloadDefaults
import org.websnake.vidchain.fallback.ui.ThemeDefaults
import java.io.File

/** Fresh-install defaults: the public Downloads folder (T7.3, rule in [DownloadDefaults]) and the dark theme (T8.1, [ThemeDefaults]). */
object VidChainDefaults {
	private const val PREFS = "vidchain_defaults"
	private const val APPLIED = "download_location_applied"
	private const val DARK_APPLIED = "dark_ui_applied"

	/** the original app's dark-mode switch: the flag file exists = "Enable Dark UI Mode" on */
	fun darkFlag(context: Context) = File(context.applicationContext.filesDir, AIOSettings.AIO_SETTING_DARK_MODE_FILE_NAME)

	/**
	 * T8.1: runs synchronously in Application.onCreate (the start seam), before the first activity reads the flag,
	 * so a fresh install opens dark from its first screen. One stat + at most one empty file.
	 */
	fun applyThemeNow(context: Context) {
		val app = context.applicationContext
		val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		if (prefs.getBoolean(DARK_APPLIED, false)) return
		val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull() ?: return
		val fresh = pkg.firstInstallTime == pkg.lastUpdateTime
		val flag = darkFlag(app)
		val dark = ThemeDefaults.shouldEnableDark(fresh, false, flag.exists())
		if (dark) runCatching { flag.createNewFile() }
		prefs.edit().putBoolean(DARK_APPLIED, true).apply()
		Trace.event { TraceEvent("defaults", result = if (dark) "dark-ui" else "theme-kept", reason = "fresh install: $fresh") }
	}

	/** VidChain's own screens follow the app's switch, also when Android restores one of them first after process death */
	fun darkUi(context: Context): Boolean = darkFlag(context).exists()

	/**
	 * started once from the host: waits (off the main thread) until the app has loaded its settings, then applies
	 * the T7.3 download-location rule (once) and keeps "daily suggestions" off (T8.3: the setting is removed; the
	 * original app reads it nowhere else, so this only makes the stored value match the removed switch)
	 */
	fun applyWhenReady(context: Context) {
		val app = context.applicationContext
		Thread({
			val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			val applied = prefs.getBoolean(APPLIED, false)
			val pkg = runCatching { app.packageManager.getPackageInfo(app.packageName, 0) }.getOrNull() ?: return@Thread
			val fresh = pkg.firstInstallTime == pkg.lastUpdateTime
			repeat(120) {
				val settings = try { AIOApp.aioSettings } catch (e: UninitializedPropertyAccessException) { null }
				if (settings != null) {
					var changed = false
					if (settings.enableDailyContentSuggestion) { settings.enableDailyContentSuggestion = false; changed = true }
					if (!applied) {
						val switch = DownloadDefaults.shouldSwitch(fresh, false, settings.defaultDownloadLocation == AIOSettings.PRIVATE_FOLDER)
						if (switch) { settings.defaultDownloadLocation = AIOSettings.SYSTEM_GALLERY; changed = true }
						prefs.edit().putBoolean(APPLIED, true).apply()
						Trace.event { TraceEvent("defaults", result = if (switch) "public-downloads" else "kept", reason = "fresh install: $fresh") }
					}
					if (changed) settings.updateInStorage()
					return@Thread
				}
				Thread.sleep(500)
			}
		}, "vidchain-defaults").apply { isDaemon = true }.start()
	}
}
