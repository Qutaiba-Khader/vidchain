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
	/** T9 decision 4: the longest the idle observer sleeps without a wake-up (a safety look, not a poll) */
	const val IDLE_MAX_MS = 15 * 60_000L
	private val wakeups = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

	/** T9 decision 4: a download was added, resumed or runs, or the user recorded an intent: look now */
	@JvmStatic fun wake() { wakeups.trySend(Unit) }

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
		}, ::poTokens),
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
			{ url, ua -> appContext?.let { org.websnake.vidchain.web.catcher.WebViewCatcher(it).catch(url, ua) } }, stream = { ctx, c -> ytdlpMethod.run(ctx, null, c.headers) }),
		org.websnake.vidchain.fallback.methods.PageScrapeMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe),
			{ ua -> org.websnake.vidchain.hosts.FileHosts.fetcher(http, ua) }, stream = { ctx, _ -> ytdlpMethod.run(ctx, null) }),
		org.websnake.vidchain.fallback.methods.FileHostMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe),
			{ url, ua -> org.websnake.vidchain.hosts.FileHosts.resolve(url, org.websnake.vidchain.hosts.FileHosts.fetcher(http, ua)) }),
		org.websnake.vidchain.fallback.methods.SessionMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe), ::sessionFor,
			withExtractor = { ctx, session -> ytdlpMethod.run(ctx, session) }),
		org.websnake.vidchain.fallback.methods.PlainGetMethod(org.websnake.vidchain.http.PlainGetFetcher(http), DeliveryVerifier(AndroidDurationProbe)),
	))

	/** T8.12: YouTube PO tokens minted in a hidden WebView for method C; the trace says only whether it worked */
	private suspend fun poTokens(videoId: String): org.websnake.vidchain.ytdlp.youtube.PoTokens? {
		val minter = poMinter ?: appContext?.let { org.websnake.vidchain.web.po.PoTokenMinter(it) }?.also { poMinter = it } ?: return null
		val m = minter.mint(videoId)
		val t = m?.let { org.websnake.vidchain.ytdlp.youtube.PoTokens.of(it.visitorData, it.gvs, it.player) }
		Trace.event { TraceEvent("po.mint", method = "C", reason = if (t != null) "minted" else "not minted: ${minter.lastProblem ?: "refused"}") }
		return t
	}
	@Volatile private var poMinter: org.websnake.vidchain.web.po.PoTokenMinter? = null

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
		// decision 10: the removed master switch is forgotten and a stored "every method off" is repaired
		runCatching { FallbackSettings.repair(app, methods.map { it.id } + OwnPath.ALL) }
		val c = FallbackCoordinator(host, l, methods.toList(), s,
			FallbackCoordinator.Config(INCLUDE_STUB, methodOn = { FallbackSettings.methodOn(app, it) },
				keepAlive = { reason, block -> kit.keptAlive(reason) { block() } },
				// T9 decision 1: batches of about 3 (up to 5 light methods), one heavy method at a time, staggered
				race = org.websnake.vidchain.fallback.race.BatchPlanner.Config(batchSize = 3, maxLight = 5, staggerMs = 400, killGraceMs = 5_000),
				weightOf = { if (it in HEAVY) org.websnake.vidchain.fallback.race.BatchPlanner.Weight.HEAVY else org.websnake.vidchain.fallback.race.BatchPlanner.Weight.LIGHT },
				scorecard = scorecardFor(app),
				probe = ::probe,
				onChainEnd = { saveScorecard(app) },
				hedge = true),
			hostContext = Dispatchers.Main, verifier = DeliveryVerifier(AndroidDurationProbe), refresher = linkRefresher(app))
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
				// T9 decision 4: with nothing to watch the observer sleeps until a download starts (wake), with one
				// safety look every IDLE_MAX_MS; it used to look every 10 s for as long as the app lived
				if (busy) delay(TICK_MS) else kotlinx.coroutines.withTimeoutOrNull(IDLE_MAX_MS) { wakeups.receive() }
			}
		}
		Trace.event { TraceEvent("runtime.started", reason = "methods=${methods.joinToString(",") { it.id }}") }
	}

	/**
	 * T8.12: the same file asked again for an expired link - yt-dlp on the page (method Y's engine), then the hidden page
	 * catcher (method W's); each only when the user has that method on (T8.12 review)
	 */
	private fun linkRefresher(app: android.content.Context) = LinkRefresher(
		sources = listOf(
			{ page -> if (!FallbackSettings.methodOn(app, "Y")) null
				else (ytdlp?.info(page, timeoutMs = 90_000) as? org.websnake.vidchain.ytdlp.YtDlpEngine.Result.Ok)?.value?.let { i -> i.formats.mapNotNull { it.url } + listOfNotNull(i.directUrl) } },
			{ page -> if (!FallbackSettings.methodOn(app, "W")) null
				else org.websnake.vidchain.web.catcher.WebViewCatcher(app).catch(page, null).candidates.map { it.url } },
		),
		sizeOf = { url, referer -> totalSize(url, referer) },
	)

	/**
	 * the total size of [url] from a one-byte range request (no body is read); null when the server does not say, and
	 * null when it ignores the range (a 200): the app's resume needs ranges, or it would write the file's start at the
	 * saved offset (T8.12 review)
	 */
	/**
	 * T9 decision 1: methods that start a process (yt-dlp, gallery-dl, you-get, Streamlink, lux, ffmpeg, aria2c), a
	 * WebView (W) or a media pipeline (NewPipe's and Media3's merges): never two of them in one batch on a 4 GB phone
	 */
	private val HEAVY = setOf("C", "S", "Y", "N", "G", "U", "T", "X", "W", "F", "M", "P", "A")

	/** T9: the learned per-site order (site, method, cause and time only), kept in the app's private files */
	@Volatile private var scorecard: org.websnake.vidchain.fallback.race.Scorecard? = null
	private fun scorecardFile(app: android.content.Context) = java.io.File(app.filesDir, "vidchain-scorecard.json")
	private fun scorecardFor(app: android.content.Context): org.websnake.vidchain.fallback.race.Scorecard =
		org.websnake.vidchain.fallback.race.Scorecard(seeds = org.websnake.vidchain.fallback.race.SeedOrder.SEEDS).also { card ->
			runCatching { scorecardFile(app).takeIf { it.isFile }?.readText()?.let(card::load) }
			scorecard = card
		}
	private fun saveScorecard(app: android.content.Context) {
		val card = scorecard ?: return
		runCatching {
			val f = scorecardFile(app); val tmp = java.io.File(f.path + ".tmp")
			tmp.writeText(card.toJson()); tmp.renameTo(f)
		}
	}

	/**
	 * T9 decision 1: a resolver's candidate wins only when its first bytes prove it: one ranged read of the first 64 KB
	 * with the candidate's headers (never a cookie), judged by ProofRule. Only a clear "not media" refuses it; an
	 * answer it cannot judge (a timeout, an unknown type) lets it through to the download's own checks.
	 */
	/** T9 stage 4c (MatrixReceiver): the runtime's scope, the probe and the keep-alive for one method run */
	fun scopeOrNull(): CoroutineScope? = scope
	suspend fun probeCandidate(c: Candidate, ctx: FallbackContext): Boolean = probe(c, ctx)
	suspend fun keepAliveFor(id: String, block: suspend () -> MethodOutcome): MethodOutcome =
		engines?.let { k -> k.keptAlive("matrix $id") { block() } } ?: block()

	private suspend fun probe(c: Candidate, ctx: FallbackContext): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
		if (c.kind == "magnet" || !c.url.startsWith("http", ignoreCase = true)) return@withContext true
		if (org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(c.url) && !org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(ctx.url)) return@withContext false
		val req = okhttp3.Request.Builder().url(c.url).header("Range", "bytes=0-${org.websnake.vidchain.fallback.race.ProofRule.PROBE_BYTES - 1}").apply {
			c.headers.filterKeys { !it.equals("Cookie", true) && !it.equals("Range", true) }.forEach { (k, v) -> runCatching { header(k, v) } }
			if (c.headers.keys.none { it.equals("User-Agent", true) }) ctx.userAgent?.let { header("User-Agent", it) }
			if (c.headers.keys.none { it.equals("Referer", true) }) ctx.referer?.takeIf { it.startsWith("http") }?.let { header("Referer", it) }
		}.build()
		val verdict = runCatching {
			http.newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
				val head = r.body?.source()?.let { src -> src.request(org.websnake.vidchain.fallback.race.ProofRule.PROBE_BYTES); src.buffer.readByteArray(minOf(src.buffer.size, org.websnake.vidchain.fallback.race.ProofRule.PROBE_BYTES)) } ?: ByteArray(0)
				val total = r.header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
					?: r.header("Content-Length")?.toLongOrNull()?.takeIf { r.code == 200 }
				org.websnake.vidchain.fallback.race.ProofRule.judge(r.code, r.header("Content-Type"), head, total, c.kind)
			}
		}.getOrDefault(org.websnake.vidchain.fallback.race.ProofRule.Verdict.UNSURE)
		Trace.event { TraceEvent("probe", result = verdict.name.lowercase(), reason = c.kind) }
		verdict != org.websnake.vidchain.fallback.race.ProofRule.Verdict.NOT_MEDIA
	}

	private suspend fun totalSize(url: String, referer: String?): Long? = kotlinx.coroutines.withContext(Dispatchers.IO) {
		if (org.websnake.vidchain.http.redirect.RedirectUnwrapper.isLocal(url)) return@withContext null   // never the user's own network
		val req = okhttp3.Request.Builder().url(url).header("Range", "bytes=0-0").apply { referer?.let { header("Referer", it) } }.build()
		runCatching {
			http.newCall(req).execute().use { r ->
				when (r.code) {
					206 -> r.header("Content-Range")?.trim()?.let { cr -> Regex("""^bytes\s+0-\d+/(\d+)$""", RegexOption.IGNORE_CASE).find(cr)?.groupValues?.get(1)?.toLongOrNull() }
					else -> null
				}
			}
		}.getOrNull()
	}

	fun recordIntent(downloadId: String, intent: UserIntent) {
		ledger?.recordIntent(downloadId, intent, System.currentTimeMillis())
		Trace.event { TraceEvent("intent", downloadId = downloadId, result = intent.name) }
		wake() // T9 decision 4
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
