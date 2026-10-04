package org.websnake.vidchain.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.websnake.vidchain.fallback.ui.BatteryAsk

/** The two battery seams in BaseActivity (T7.2): ask at most once, and ask Android directly for this app. */
object VidChainBattery {
	private fun exempt(context: Context): Boolean =
		(context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isIgnoringBatteryOptimizations(context.packageName) == true

	/** seam battery-once: false = the app's request is not shown (already exempt, or asked once before) */
	@JvmStatic
	fun mayAsk(context: Context): Boolean = runCatching { BatteryAsk.claim(context, exempt(context)) }.getOrDefault(false)

	/** seam battery-direct: Android's own "allow this app to run in the background" request; false = use the app's way */
	@JvmStatic
	fun requestDirect(context: Context): Boolean = runCatching {
		context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)))
		true
	}.getOrDefault(false)
}
