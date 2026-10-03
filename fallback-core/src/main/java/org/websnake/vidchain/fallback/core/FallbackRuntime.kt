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
import org.websnake.vidchain.fallback.verify.AndroidDurationProbe
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import java.util.concurrent.CopyOnWriteArrayList

/** The running fallback system: one ledger, one coordinator, one observer loop. Started from the app seam. */
object FallbackRuntime {
	const val TICK_MS = 2_000L
	/** nothing running or waiting: look less often (main-thread read of the lists) */
	const val IDLE_TICK_MS = 10_000L

	/** P1 test path: the always-failing stub ran first in every chain; off since the first real method (T2.1). */
	const val INCLUDE_STUB = false

	/** shared HTTP client of the fallback methods (never the app's own client) */
	val http: okhttp3.OkHttpClient by lazy {
		okhttp3.OkHttpClient.Builder()
			.connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
			.readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
			.followRedirects(true).followSslRedirects(true)
			.build()
	}

	/** yt-dlp engine on the shared engine kit (null until the runtime started) */
	val ytdlp: org.websnake.vidchain.ytdlp.YtDlpEngine? get() = engines?.let { org.websnake.vidchain.ytdlp.YtDlpEngine(it.runner, it.layout) }

	private val ytdlpMethod = org.websnake.vidchain.fallback.methods.YtDlpMethod({ ytdlp }, { engines?.layout?.cacheDir?.let { java.io.File(it, "vidchain-cookies") } },
		bench = object : org.websnake.vidchain.fallback.methods.YtDlpMethod.Bench {
			override fun benched(method: String) = engines?.let { it.bench(it.layout.ytdlp).benched(method) } ?: false
			override fun record(method: String, outcome: org.websnake.vidchain.engine.EngineOutcome?) { engines?.let { it.bench(it.layout.ytdlp).record(method, outcome) } }
		})

	/** registered methods; P2-P5 add theirs here */
	val methods: MutableList<FallbackMethod> = CopyOnWriteArrayList(listOf<FallbackMethod>(
		org.websnake.vidchain.fallback.methods.YouTubeClientMethod({ ytdlp }, {
			engines?.let { k -> org.websnake.vidchain.ytdlp.youtube.YouTubeClients(k.store, k.layout.engineHash(k.layout.ytdlp)) }
		}),
		org.websnake.vidchain.fallback.methods.RedirectMethod(org.websnake.vidchain.http.redirect.RedirectUnwrapper(http)),
		ytdlpMethod,
		org.websnake.vidchain.fallback.methods.SessionMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe), ::sessionFor,
			withExtractor = { ctx, session -> ytdlpMethod.run(ctx, session) }),
		org.websnake.vidchain.fallback.methods.PlainGetMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe)),
	))

	/** method S asks the app for the session on the main thread (the browser's cookie store and the download models live there) */
	private suspend fun sessionFor(parentId: String, url: String): org.websnake.vidchain.fallback.context.SessionContext? {
		val h = host ?: return null
		return kotlinx.coroutines.withContext(Dispatchers.Main) { h.downloads().firstOrNull { it.id == parentId }?.let { h.session(it, url) } }
	}

	@Volatile var ledger: AttemptLedger? = null
		private set
	@Volatile var coordinator: FallbackCoordinator? = null
		private set
	/** shared engine runtime for the engine-based methods (T1.9) */
	@Volatile var engines: EngineKit? = null
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
		val kit = EngineKit(app)
		val c = FallbackCoordinator(host, l, methods.toList(), s,
			FallbackCoordinator.Config(INCLUDE_STUB, enabled = { FallbackSettings.enabled(app) }, methodOn = { FallbackSettings.methodOn(app, it) },
				keepAlive = { reason, block -> kit.keptAlive(reason) { block() } }),
			hostContext = Dispatchers.Main, verifier = DeliveryVerifier(AndroidDurationProbe))
		ledger = l; coordinator = c; this.host = host; scope = s
		engines = kit
		s.launch {
			runCatching { c.restoreBoard() }
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
