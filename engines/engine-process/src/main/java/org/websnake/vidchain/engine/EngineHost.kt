package org.websnake.vidchain.engine

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the app process alive while fallback engines run. Leases are counted; the keep-alive starts with the first
 * lease and stops with the last one.
 * - Android 14+ (API 34) with the app visible: a user-initiated data transfer job (no 6-hour dataSync limit).
 * - Otherwise: a dataSync foreground service (allowed from the background only below Android 12 or with an exemption).
 * - If neither may start, the engine runs anyway; if Android then kills it, the outcome is OsKilled (re-queued, not a crash).
 */
object EngineHost {
	private val leases = AtomicInteger()
	@Volatile private var mode: String = "none"
	@Volatile var log: (String) -> Unit = {}

	const val CHANNEL_ID = "vidchain_engines"
	internal const val NOTIFICATION_ID = 0x7651
	internal const val JOB_ID = 0x7652

	class Lease internal constructor(private val context: Context) : AutoCloseable {
		private var open = true
		@Synchronized override fun close() {
			if (!open) return
			open = false
			if (leases.decrementAndGet() == 0) stop(context)
		}
	}

	fun acquire(context: Context, reason: String): Lease {
		val app = context.applicationContext
		if (leases.getAndIncrement() == 0) start(app, reason)
		return Lease(app)
	}

	val activeLeases: Int get() = leases.get()

	private fun appVisible(): Boolean =
		runCatching { ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }.getOrDefault(false)

	private fun start(context: Context, reason: String) {
		ensureChannel(context)
		mode = try {
			if (Build.VERSION.SDK_INT >= 34 && appVisible() && runCatching { scheduleUserInitiatedJob(context) }.getOrDefault(false)) "user-initiated-job"
			else {
				context.startForegroundService(Intent(context, EngineForegroundService::class.java))
				"foreground-service"
			}
		} catch (e: Exception) {
			// ForegroundServiceStartNotAllowedException (Android 12+, app in background) and friends
			"none (${e.javaClass.simpleName})"
		}
		log("engine keep-alive: $mode for $reason")
	}

	private fun stop(context: Context) {
		when {
			mode == "user-initiated-job" -> EngineJobService.finishAll()
			// never stopService(): a service stopped before it called startForeground() crashes the app on Android 12+.
			// A running service stops itself; one that has not started yet stops itself right after startForeground().
			mode == "foreground-service" -> EngineForegroundService.stopIfRunning()
		}
		log("engine keep-alive stopped ($mode)")
		mode = "none"
	}

	@RequiresApi(34)
	@SuppressLint("MissingPermission")   // RUN_USER_INITIATED_JOBS is declared in this module's manifest
	private fun scheduleUserInitiatedJob(context: Context): Boolean {
		val js = context.getSystemService(JobScheduler::class.java) ?: return false
		val job = JobInfo.Builder(JOB_ID, ComponentName(context, EngineJobService::class.java))
			.setUserInitiated(true)
			.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
			.build()
		return js.schedule(job) == JobScheduler.RESULT_SUCCESS
	}

	internal fun notification(context: Context): Notification =
		Notification.Builder(context, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_sys_download)
			.setContentTitle("VidChain")
			.setContentText("Trying another download method…")
			.setOngoing(true)
			.build()

	private fun ensureChannel(context: Context) {
		val nm = context.getSystemService(NotificationManager::class.java) ?: return
		if (nm.getNotificationChannel(CHANNEL_ID) == null)
			nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Fallback downloads", NotificationManager.IMPORTANCE_LOW))
	}
}

/** dataSync foreground service: only holds the process in the foreground state while leases are open. */
class EngineForegroundService : Service() {
	override fun onBind(intent: Intent?): IBinder? = null

	override fun onDestroy() {
		if (instance === this) instance = null
		super.onDestroy()
	}

	companion object {
		@Volatile private var instance: EngineForegroundService? = null
		@Volatile private var foreground = false

		internal fun stopIfRunning() {
			val s = instance ?: return
			if (foreground) s.stopSelf()
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		val n = EngineHost.notification(this)
		if (Build.VERSION.SDK_INT >= 29) startForeground(EngineHost.NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
		else startForeground(EngineHost.NOTIFICATION_ID, n)
		instance = this; foreground = true
		if (EngineHost.activeLeases == 0) stopSelf()
		return START_NOT_STICKY
	}
}

/** user-initiated data transfer job (API 34+): runs while leases are open, then finishes. */
class EngineJobService : JobService() {
	override fun onStartJob(params: JobParameters): Boolean {
		if (Build.VERSION.SDK_INT >= 34) {
			setNotification(params, EngineHost.NOTIFICATION_ID, EngineHost.notification(this), JobService.JOB_END_NOTIFICATION_POLICY_REMOVE)
		}
		if (EngineHost.activeLeases == 0) return false
		synchronized(running) { running += this to params }
		return true
	}

	override fun onStopJob(params: JobParameters): Boolean {
		synchronized(running) { running.removeAll { it.second === params } }
		return false   // engines killed by the system end as OsKilled and are re-queued by the coordinator
	}

	companion object {
		private val running = ArrayList<Pair<EngineJobService, JobParameters>>()

		fun finishAll() {
			val all = synchronized(running) { running.toList().also { running.clear() } }
			for ((service, params) in all) runCatching { service.jobFinished(params, false) }
		}
	}
}
