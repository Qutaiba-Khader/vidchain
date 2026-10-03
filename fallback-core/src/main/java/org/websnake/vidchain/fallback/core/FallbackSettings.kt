package org.websnake.vidchain.fallback.core

import android.content.Context

/** The owner's switches (UI in T1.10): all fallbacks on/off and one switch per method. Default: on. */
object FallbackSettings {
	const val PREFS = "vidchain_fallback"
	private const val KEY_ENABLED = "fallbacks_enabled"
	private fun methodKey(id: String) = "method_on_$id"

	fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)
	fun setEnabled(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
	fun methodOn(context: Context, id: String): Boolean = prefs(context).getBoolean(methodKey(id), true)
	fun setMethodOn(context: Context, id: String, on: Boolean) = prefs(context).edit().putBoolean(methodKey(id), on).apply()

	private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
