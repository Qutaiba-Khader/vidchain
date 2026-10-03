package org.websnake.vidchain.fallback.core

import android.content.Context
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.UserIntent
import org.websnake.vidchain.fallback.ledger.AttemptLedger
import org.websnake.vidchain.fallback.ledger.SqliteLedger
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import java.util.concurrent.CopyOnWriteArrayList

/** The running fallback system: one ledger, one coordinator, one observer loop. Started from the app seam. */
object FallbackRuntime {
	const val TICK_MS = 2_000L
	/** nothing running or waiting: look less often (main-thread read of the lists) */
	const val IDLE_TICK_MS = 10_000L

	/** P1 test path: the always-failing stub runs first in every chain until real methods exist (removed in P2). */
	const val INCLUDE_STUB = true

	/** registered methods; P2-P5 add theirs here */
	val methods: MutableList<FallbackMethod> = CopyOnWriteArrayList(listOf<FallbackMethod>(StubAlwaysFailMethod))

	@Volatile var ledger: AttemptLedger? = null
		private set
	@Volatile var coordinator: FallbackCoordinator? = null
		private set
	@Volatile private var host: FallbackHost? = null
	private var scope: CoroutineScope? = null

	@Synchronized
	fun start(context: Context, host: FallbackHost) {
		if (scope != null) return
		val app = context.applicationContext
		val l = SqliteLedger(app)
		val s = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
			Trace.event { TraceEvent("runtime.error", reason = "${e.javaClass.simpleName}: ${e.message}") }
		})
		val c = FallbackCoordinator(host, l, methods.toList(), s,
			FallbackCoordinator.Config(INCLUDE_STUB, enabled = { FallbackSettings.enabled(app) }, methodOn = { FallbackSettings.methodOn(app, it) }),
			hostContext = Dispatchers.Main)
		ledger = l; coordinator = c; this.host = host; scope = s
		s.launch {
			delay(TICK_MS)
			while (isActive) {
				val busy = try { c.tick() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
					Trace.event { TraceEvent("observer.error", reason = "${e.javaClass.simpleName}: ${e.message}") }
					true
				}
				delay(if (busy) TICK_MS else IDLE_TICK_MS)
			}
		}
		Trace.event { TraceEvent("runtime.started", reason = "methods=${methods.joinToString(",") { it.id }}") }
	}

	fun recordIntent(downloadId: String, intent: UserIntent) {
		ledger?.recordIntent(downloadId, intent, System.currentTimeMillis())
		Trace.event { TraceEvent("intent", downloadId = downloadId, result = intent.name) }
	}

	/** app shutdown: running downloads are paused by the app, not by an error */
	@Synchronized
	fun shutdown() {
		host?.downloads()?.filter { it.snapshot.status == DownloadSnapshot.DOWNLOADING }?.forEach { recordIntent(it.id, UserIntent.APP_SHUTDOWN) }
		scope?.cancel()
		scope = null; coordinator = null
	}
}
