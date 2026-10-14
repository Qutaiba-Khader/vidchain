package org.websnake.vidchain.app

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ConcurrentHashMap

/**
 * T9 decision 7: progress of a running download is saved at most every [EVERY_MS]; a status change saves at once.
 * A save that is held back is done by one delayed save, so the newest progress always reaches storage.
 */
object VidChainSaveThrottle {
	const val EVERY_MS = 2_500L
	private class Last(@Volatile var atMs: Long, @Volatile var status: Int, @Volatile var pending: Boolean = false)
	private val last = ConcurrentHashMap<Int, Last>()
	private val main = Handler(Looper.getMainLooper())

	/** true = save now; false = held back ([later] runs once, after [EVERY_MS]) */
	@JvmStatic
	fun saveNow(id: Int, status: Int, running: Boolean, later: () -> Unit): Boolean {
		val now = System.currentTimeMillis()
		val l = last[id]
		if (l == null || !running || status != l.status || now - l.atMs >= EVERY_MS) {
			last[id] = Last(now, status)
			if (!running) last.remove(id)
			return true
		}
		if (!l.pending) {
			l.pending = true
			// T9 review: skipped when a newer save (a status change, the end, a delete) came first: it already wrote the
			// newest state, and a late save would write a deleted download back to disk
			main.postDelayed({ l.pending = false; l.atMs = 0; if (last[id] === l) runCatching { later() } }, EVERY_MS - (now - l.atMs))
		}
		return false
	}

	private val shown = ConcurrentHashMap<Int, Last>()

	/**
	 * T9 decision 7: a running download's notification is redrawn at most once a second (it was rebuilt on every
	 * progress tick); a status change, a pause or the end is shown at once
	 */
	@JvmStatic
	fun notifyNow(id: Int, status: Int, running: Boolean): Boolean {
		val now = System.currentTimeMillis()
		val l = shown[id]
		if (l == null || !running || status != l.status || now - l.atMs >= 1_000L) {
			if (running) shown[id] = Last(now, status) else shown.remove(id)
			return true
		}
		return false
	}
}
