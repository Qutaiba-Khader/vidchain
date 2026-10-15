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
	private const val KEY_FAILED = "ytdlp-update-failed"
	private const val RETRY_MS = 3_600_000L

	private fun inUse(): Boolean = runCatching {
		app.core.AIOApp.downloadSystem.hasActiveWork() || org.websnake.vidchain.fallback.core.FallbackRuntime.coordinator?.busy == true ||
			VidChainAnalysis.busy() || VidChainShareRescue.busy()
	}.getOrDefault(true)
	private val running = AtomicBoolean(false)

	/** review run 28: set while yt-dlp is replaced; a share's look-up does not start then (checked again after setting it) */
	private val updating = AtomicBoolean(false)

	@JvmStatic
	fun updating(): Boolean = updating.get()
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	@JvmStatic
	fun start(context: Context, online: () -> Boolean) {
		val app = context.applicationContext
		val prefs = app.getSharedPreferences("vidchain_once", Context.MODE_PRIVATE)
		val last = prefs.getLong(KEY, 0L)
		val now = System.currentTimeMillis()
		if (last in 1..now && now - last < EVERY_MS) return
		// review run 25: after a failed try, wait an hour (a rate limit or a full disk must not cost a download per launch)
		val failed = prefs.getLong(KEY_FAILED, 0L)
		if (failed in 1..now && now - failed < RETRY_MS) return
		if (!online() || !running.compareAndSet(false, true)) return
		scope.launch {
			try {
				// review run 25: the library deletes yt-dlp and copies the new one in place; wait until nothing runs it
				// (a download, VidChain's chain, a share's analysis), at most 10 minutes, then try later
				kotlinx.coroutines.delay(120_000)                        // a cold start from a share: its analysis goes first
				val waitUntil = System.currentTimeMillis() + 10 * 60_000L
				while (true) {
					// review run 28: claim first, then check (a share starting between the two sees the claim and waits)
					if (!inUse()) { updating.set(true); if (!inUse()) break; updating.set(false) }
					if (System.currentTimeMillis() > waitUntil) return@launch
					kotlinx.coroutines.delay(15_000)
				}
				val t0 = System.currentTimeMillis()
				// a blocking call with its own network time-outs, on this IO coroutine only (nothing waits for it)
				val status = try {
					runCatching { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) }
				} finally {
					updating.set(false)
				}
				val ok = status.isSuccess && status.getOrNull() != null
				prefs.edit().putLong(if (ok) KEY else KEY_FAILED, System.currentTimeMillis()).apply()
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
