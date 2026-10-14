package org.websnake.vidchain.app

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T9 (corpus test on the phone, 2026-10-15): yt-dlp's own update, at most once a day, counted only when it worked.
 * The original ran it inside a 12 s job; T9 made it once a day but marked the day before it ran, so the phone kept the
 * yt-dlp the APK shipped (2025.11.12) and sites it no longer read failed (Dailymotion). A failed or cut-off update is
 * tried again on the next launch. The trace says which version runs.
 */
object VidChainYtDlpUpdate {
	private const val KEY = "ytdlp-update-ok"
	private const val EVERY_MS = 24 * 3_600_000L
	private val running = AtomicBoolean(false)
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	@JvmStatic
	fun start(context: Context, online: () -> Boolean) {
		val app = context.applicationContext
		val prefs = app.getSharedPreferences("vidchain_once", Context.MODE_PRIVATE)
		val last = prefs.getLong(KEY, 0L)
		val now = System.currentTimeMillis()
		if (last in 1..now && now - last < EVERY_MS) return
		if (!online() || !running.compareAndSet(false, true)) return
		scope.launch {
			val t0 = System.currentTimeMillis()
			try {
				// a blocking call with its own network time-outs, on this IO coroutine only (nothing waits for it)
				val status = runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
				val ok = status.isSuccess && status.getOrNull() != null
				if (ok) prefs.edit().putLong(KEY, System.currentTimeMillis()).apply()
				val version = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull()
				Trace.event {
					TraceEvent("ytdlp.update", result = if (ok) status.getOrNull()?.name?.lowercase() else "failed",
						reason = if (status.isFailure) status.exceptionOrNull()?.javaClass?.simpleName else "version $version",
						durationMs = System.currentTimeMillis() - t0)
				}
			} finally {
				running.set(false)
			}
		}
	}
}
