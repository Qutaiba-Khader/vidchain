package org.websnake.vidchain.fallback.ui

import android.content.Context

/**
 * T7.2, owner's phone test: the app asked to turn off battery optimization on every return to its main screen, also
 * after the owner had done it. The rule now: never when Android already reports the app as exempt; otherwise once,
 * and never again whatever the answer (the Settings screen of the phone stays the place to change it later).
 */
object BatteryAsk {
	private const val PREFS = "vidchain_battery"
	private const val ASKED = "asked"

	/** pure rule (JVM-tested) */
	fun mayAsk(exempt: Boolean, askedBefore: Boolean): Boolean = !exempt && !askedBefore

	/** true = show the request now (and it is recorded as asked); false = stay quiet */
	fun claim(context: Context, exempt: Boolean): Boolean {
		val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		if (!mayAsk(exempt, p.getBoolean(ASKED, false))) return false
		p.edit().putBoolean(ASKED, true).apply()
		return true
	}
}
