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
			.addNetworkInterceptor(LanGuard)
			.build()
	}

	/**
	 * Every hop of every fallback HTTP request: a redirect (or any follow-up) from a public start to ANOTHER host that
	 * is a local-network address (by name, literal, or the address actually connected to) is refused. A start the user
	 * chose on the local network stays allowed, and so does its own host.
	 */
	object LanGuard : okhttp3.Interceptor {
		override fun intercept(chain: okhttp3.Interceptor.Chain): okhttp3.Response {
			val req = chain.request()
			val start = chain.call().request().url
			if (req.url.host != start.host && !org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(start.toString())) {
				val addr = chain.connection()?.route()?.socketAddress?.address
				if (org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(req.url.toString()) ||
					(addr != null && org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocalAddress(addr)))
					throw java.io.IOException("refused: redirect from ${start.host} to a local network address")
			}
			return chain.proceed(req)
		}
	}

	/** yt-dlp engine on the shared engine kit (null until the runtime started) */
	val ytdlp: org.websnake.vidchain.ytdlp.YtDlpEngine? get() = engines?.let { org.websnake.vidchain.ytdlp.YtDlpEngine(it.runner, it.layout) }

	private val ytdlpMethod = org.websnake.vidchain.fallback.methods.YtDlpMethod({ ytdlp }, { engines?.layout?.cacheDir?.let { java.io.File(it, "vidchain-cookies") } },
		bench = object : org.websnake.vidchain.fallback.methods.YtDlpMethod.Bench {
			override fun benched(method: String) = engines?.let { it.bench(it.layout.ytdlp).benched(method) } ?: false
			override fun record(method: String, outcome: org.websnake.vidchain.engine.EngineOutcome?) { engines?.let { it.bench(it.layout.ytdlp).record(method, outcome) } }
		})

	/** the library's ffmpeg on the shared engine kit */
	val ffmpeg: org.websnake.vidchain.engine.ffmpeg.FfmpegEngine? get() = engines?.let { org.websnake.vidchain.engine.ffmpeg.FfmpegEngine(it.runner, it.layout) }

	@Volatile private var ffmpegCapsCache: Pair<String, org.websnake.vidchain.engine.ffmpeg.FfmpegEngine.Caps>? = null

	/** what this ffmpeg build can read, dumped once per build (T3.4) */
	private suspend fun ffmpegCaps(): org.websnake.vidchain.engine.ffmpeg.FfmpegEngine.Caps? {
		val k = engines ?: return null
		val hash = k.layout.engineHash(k.layout.ffmpeg)
		ffmpegCapsCache?.takeIf { it.first == hash }?.let { return it.second }
		val caps = ffmpeg?.capabilities() ?: return null
		ffmpegCapsCache = hash to caps
		Trace.event { TraceEvent("engine.caps", method = "F", reason = "protocols=" + caps.protocols.sorted().joinToString(",") + " demuxers=" + caps.demuxers.size) }
		return caps
	}

	/** registered methods; P2-P5 add theirs here */
	val methods: MutableList<FallbackMethod> = CopyOnWriteArrayList(listOf<FallbackMethod>(
		org.websnake.vidchain.fallback.methods.YouTubeClientMethod({ ytdlp }, {
			engines?.let { k -> org.websnake.vidchain.ytdlp.youtube.YouTubeClients(k.store, k.layout.engineHash(k.layout.ytdlp)) }
		}),
		org.websnake.vidchain.fallback.methods.RedirectMethod(org.websnake.vidchain.http.redirect.RedirectUnwrapper(http)),
		ytdlpMethod,
		org.websnake.vidchain.fallback.methods.StreamlinkMethod({ engines?.let { org.websnake.vidchain.engine.python.PythonEngine(it.runner, it.layout) } },
			{ appContext?.let { org.websnake.vidchain.fallback.methods.PyZip.installed(it) } }, { ffmpeg },
			org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe)),
		org.websnake.vidchain.fallback.methods.YouGetMethod({ engines?.let { org.websnake.vidchain.engine.python.PythonEngine(it.runner, it.layout) } },
			{ appContext?.let { org.websnake.vidchain.fallback.methods.PyZip.installed(it) } }),
		org.websnake.vidchain.fallback.methods.GalleryDlMethod({ engines?.let { org.websnake.vidchain.engine.python.PythonEngine(it.runner, it.layout) } },
			{ appContext?.let { org.websnake.vidchain.fallback.methods.PyZip.installed(it) } }),
		org.websnake.vidchain.fallback.methods.Aria2Method({ engines?.let { org.websnake.vidchain.engine.aria2.Aria2Engine(it.runner, it.layout) } },
			{ engines?.layout?.cacheDir?.let { java.io.File(it, "vidchain-dht.dat") } }),
		org.websnake.vidchain.fallback.methods.LuxMethod({ engines?.let { org.websnake.vidchain.engine.lux.LuxEngine(it.runner, it.layout) } }),
		org.websnake.vidchain.fallback.methods.NightlyYtDlpMethod(
			{ engines?.let { k -> appContext?.let { c -> org.websnake.vidchain.ytdlp.nightly.NightlyToolStore(java.io.File(c.noBackupFilesDir, "vidchain-tools/yt-dlp-nightly"), http, k.store) } } },
			{ file -> engines?.let { k -> org.websnake.vidchain.ytdlp.YtDlpEngine(k.runner, k.layout, ytdlp = file) } },
			{ engines?.layout?.quickJs },
			{ engines?.layout?.cacheDir?.let { java.io.File(it, "vidchain-cookies") } },
			bench = { file -> engines?.bench(file) }),
		org.websnake.vidchain.fallback.methods.DownloadManagerMethod({ appContext?.let { org.websnake.vidchain.fallback.methods.AndroidSystemDownloads(it) } }),
		org.websnake.vidchain.fallback.methods.Media3Method({ appContext?.let { org.websnake.vidchain.media3.Media3Downloader(it) } }, { ffmpeg }, { appContext?.cacheDir }),
		org.websnake.vidchain.fallback.methods.NewPipeMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe),
			{ url -> org.websnake.vidchain.fallback.methods.NewPipeMethod.newPipeSource(url) }, { ffmpeg }),
		org.websnake.vidchain.fallback.methods.FfmpegMethod({ ffmpeg }, ::ffmpegCaps),
		org.websnake.vidchain.fallback.methods.WebCatcherMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe),
			{ url, ua -> appContext?.let { org.websnake.vidchain.web.catcher.WebViewCatcher(it).catch(url, ua) } }, stream = { ctx, _ -> ytdlpMethod.run(ctx, null) }),
		org.websnake.vidchain.fallback.methods.PageScrapeMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe),
			{ ua -> org.websnake.vidchain.hosts.FileHosts.fetcher(http, ua) }, stream = { ctx, _ -> ytdlpMethod.run(ctx, null) }),
		org.websnake.vidchain.fallback.methods.FileHostMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe),
			{ url, ua -> org.websnake.vidchain.hosts.FileHosts.resolve(url, org.websnake.vidchain.hosts.FileHosts.fetcher(http, ua)) }),
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

	/** the app's dark-UI switch for VidChain's own screens (null before start or when the app does not say) */
	fun darkUi(): Boolean? = runCatching { host?.darkUi() }.getOrNull()
	@Volatile private var appContext: Context? = null
	private var scope: CoroutineScope? = null

	@Synchronized
	fun start(context: Context, host: FallbackHost) {
		if (scope != null) return
		val app = context.applicationContext
		appContext = app
		this.host = host
		// the database is opened once here; if it cannot be (storage full, corrupt file) the chains still run, unrecorded
		val l: AttemptLedger = runCatching { SqliteLedger(app).also { it.recentParents(1) } }.getOrElse { e ->
			Trace.event { TraceEvent("runtime.error", reason = "ledger database unavailable, using memory: ${e.javaClass.simpleName}: ${e.message}") }
			org.websnake.vidchain.fallback.ledger.InMemoryLedger()
		}
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
		// aria2c (method A) is unpacked by its module like the library's other runtimes; the app itself never does it
		s.launch(Dispatchers.IO) { runCatching { com.yausername.aria2c.Aria2c.getInstance().init(app) } }
		// a card whose fallback line changed is re-drawn by the app (on the main thread)
		org.websnake.vidchain.fallback.ui.TrailBoard.onChange = { id -> s.launch(Dispatchers.Main) { runCatching { host.refresh(id) } } }
		s.launch {
			runCatching { c.restoreBoard() }
			delay(TICK_MS)
			var resumed = false
			while (isActive) {
				val busy = try { c.tick() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
					Trace.event { TraceEvent("observer.error", reason = "${e.javaClass.simpleName}: ${e.message}") }
					true
				}
				// chains the OS stopped: continue once the app's lists are loaded (the first tick that saw them)
				if (!resumed && runCatching { kotlinx.coroutines.withContext(Dispatchers.Main) { host.ready() } }.getOrDefault(false)) {
					resumed = true
					runCatching { c.resumeInterrupted() }
				}
				delay(if (busy) TICK_MS else IDLE_TICK_MS)
			}
		}
		Trace.event { TraceEvent("runtime.started", reason = "methods=${methods.joinToString(",") { it.id }}") }
	}

	fun recordIntent(downloadId: String, intent: UserIntent) {
		ledger?.recordIntent(downloadId, intent, System.currentTimeMillis())
		Trace.event { TraceEvent("intent", downloadId = downloadId, result = intent.name) }
		// deleted / cleared: its running chain stops now (engines killed), nothing more is saved for it
		if (intent == UserIntent.DELETE || intent == UserIntent.CLEAR || intent == UserIntent.CANCEL) {
			val c = coordinator; val s = scope
			if (c != null && s != null) s.launch { runCatching { c.cancelChain(downloadId) } }
		}
	}

	/** app shutdown: running downloads are paused by the app, not by an error */
	@Synchronized
	fun shutdown() {
		host?.downloads()?.filter { it.snapshot.status == DownloadSnapshot.DOWNLOADING }?.forEach { recordIntent(it.id, UserIntent.APP_SHUTDOWN) }
		scope?.cancel()
		scope = null; coordinator = null
	}
}
