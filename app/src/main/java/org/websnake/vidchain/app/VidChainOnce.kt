package org.websnake.vidchain.app

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * T9 decision 7 (once per launch, not on every screen resume): the original app re-ran its yt-dlp/FFmpeg set-up, the
 * yt-dlp network update and the ad-filter download every time any screen came back to the front.
 */
object VidChainOnce {
	private val done = ConcurrentHashMap.newKeySet<String>()

	/** true the first time [key] is asked in this process, false after */
	@JvmStatic
	fun first(key: String): Boolean = done.add(key)

	/** [key] failed: the next caller tries again */
	@JvmStatic
	fun reset(key: String) { done.remove(key) }

	/** true when [key] last ran more than [everyMs] ago (remembered across launches); marks it as run now */
	@JvmStatic
	fun due(context: Context, key: String, everyMs: Long): Boolean {
		val p = context.getSharedPreferences("vidchain_once", Context.MODE_PRIVATE)
		val now = System.currentTimeMillis()
		val last = p.getLong(key, 0L)
		if (last in 1..now && now - last < everyMs) return false
		p.edit().putLong(key, now).apply()
		return true
	}
}
