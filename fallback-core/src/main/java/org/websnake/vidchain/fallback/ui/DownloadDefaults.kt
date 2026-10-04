package org.websnake.vidchain.fallback.ui

/**
 * T7.3, owner's phone test: new downloads go to the phone's public Downloads folder by default (the original app
 * defaults to its private folder). Applied once, only on a fresh install, and only while the setting still holds the
 * original default: a user who already chose a location, or an existing install being updated, keeps what it has.
 */
object DownloadDefaults {
	/** pure rule (JVM-tested) */
	fun shouldSwitch(freshInstall: Boolean, appliedBefore: Boolean, stillOriginalDefault: Boolean): Boolean =
		freshInstall && !appliedBefore && stillOriginalDefault
}

/**
 * T8.1, owner's second round: a fresh install starts with "Enable Dark UI Mode" on (the original app starts light).
 * Same once-only, fresh-install-only rule as [DownloadDefaults]: an existing install keeps the user's choice.
 */
object ThemeDefaults {
	/** pure rule (JVM-tested) */
	fun shouldEnableDark(freshInstall: Boolean, appliedBefore: Boolean, alreadyDark: Boolean): Boolean =
		DownloadDefaults.shouldSwitch(freshInstall, appliedBefore, stillOriginalDefault = !alreadyDark)
}
