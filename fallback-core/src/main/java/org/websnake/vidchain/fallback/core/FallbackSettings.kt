package org.websnake.vidchain.fallback.core

import android.content.Context

/**
 * The owner's switches (UI in T1.10): one switch per method, default on. T9 (owner, 2026-10-14, decision 10): there is
 * no master switch any more, and at least one built method always stays on ([MethodSwitches]).
 */
object FallbackSettings {
	const val PREFS = "vidchain_fallback"
	/** the removed master switch: deleted once by [repair] (a user who had it off gets the normal behaviour) */
	internal const val OLD_MASTER = "fallbacks_enabled"
	private fun methodKey(id: String) = "method_on_$id"

	fun methodOn(context: Context, id: String): Boolean = prefs(context).getBoolean(methodKey(id), true)

	/** false = refused: [id] is the last method of [built] that is still on (decision 10) */
	fun setMethodOn(context: Context, id: String, on: Boolean, built: Collection<String>): Boolean {
		if (!on && !MethodSwitches.canTurnOff(id, built) { methodOn(context, it) }) return false
		prefs(context).edit().putBoolean(methodKey(id), on).apply()
		return true
	}

	/** once at start: drop the old master switch, and turn every built method back on if all of them were off */
	fun repair(context: Context, built: Collection<String>) {
		val p = prefs(context)
		val e = p.edit()
		if (p.contains(OLD_MASTER)) e.remove(OLD_MASTER)
		for (id in MethodSwitches.repair(built) { methodOn(context, it) }) e.putBoolean(methodKey(id), true)
		e.apply()
	}

	private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Pure rules for the method switches (decision 10), JVM-tested. Only methods built into this APK count. */
object MethodSwitches {
	/** [id] may be switched off only while another built method stays on */
	fun canTurnOff(id: String, built: Collection<String>, isOn: (String) -> Boolean): Boolean =
		built.any { it != id && isOn(it) }

	/** the methods to switch back on: all built ones when none is on (a stored "all off"), else none */
	fun repair(built: Collection<String>, isOn: (String) -> Boolean): List<String> =
		if (built.isNotEmpty() && built.none(isOn)) built.toList() else emptyList()

	/** true when [steps] holds no built method that is on: the link has no capable method (the user is told) */
	fun noCapableMethod(steps: List<String>, built: Collection<String>, isOn: (String) -> Boolean): Boolean =
		steps.none { it in built && isOn(it) }
}
