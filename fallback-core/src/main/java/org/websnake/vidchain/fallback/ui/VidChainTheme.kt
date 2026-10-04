package org.websnake.vidchain.fallback.ui

import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import org.websnake.vidchain.fallback.core.FallbackRuntime

/** T8.1: VidChain's screens follow the app's "Enable Dark UI Mode" switch, also when Android restores one of them first. Call before super.onCreate. */
object VidChainTheme {
	fun apply(activity: AppCompatActivity) {
		val dark = FallbackRuntime.darkUi() ?: return
		activity.delegate.localNightMode = if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
	}
}
