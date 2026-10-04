package org.websnake.vidchain.fallback.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.websnake.vidchain.fallback.classifier.DownloadSnapshot
import org.websnake.vidchain.fallback.classifier.StatusKey
import org.websnake.vidchain.fallback.classifier.TerminalClassifier
import org.websnake.vidchain.fallback.classifier.UserIntent
import org.websnake.vidchain.fallback.classifier.Verdict
import org.websnake.vidchain.fallback.ledger.AttemptLedger
import org.websnake.vidchain.fallback.ledger.AttemptState
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent
import org.websnake.vidchain.fallback.ui.Trail
import org.websnake.vidchain.fallback.ui.TrailBoard
import org.websnake.vidchain.fallback.verify.Check
import org.websnake.vidchain.fallback.verify.Delivery
import org.websnake.vidchain.fallback.verify.DeliveryVerifier
import org.websnake.vidchain.fallback.verify.Expectation
import java.io.File
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Watches the upstream downloads and, when the current method fails, walks the URL class's fallback chain.
 *
 * - Edge-triggered: a failure only counts when this process saw the download running (or waiting) before, or when a
 *   download that appeared after the first tick already carries an explicit failure. States found at app start
 *   (downloads paused by a restart) never start a chain.
 * - Exactly once: each (download, failure class, intent sequence) is handled once, recorded in the ledger.
 * - One chain per parent at a time; a resolver's candidate becomes a child download in the existing queue and the chain
 *   waits for it. A failing child continues its parent's chain; a finished child ends it (verified in T1.8).
 * - The current method is never touched: nothing here pauses, resumes or edits an upstream download.
 *
 * [scope] runs the chains; it must not use Dispatchers.Unconfined (a chain takes [lock] to queue a child).
 */
class FallbackCoordinator(
	private val host: FallbackHost,
	private val ledger: AttemptLedger,
	methods: Collection<FallbackMethod>,
	private val scope: CoroutineScope,
	private val config: Config = Config(),
	private val hostContext: CoroutineContext = EmptyCoroutineContext,
	private val clock: () -> Long = System::currentTimeMillis,
	private val verifier: DeliveryVerifier = DeliveryVerifier(),
) {
	class Config(
		val includeStub: Boolean = true,
		val enabled: () -> Boolean = { true },
		val methodOn: (String) -> Boolean = { true },
		/** wraps executor attempts (in the app: an EngineHost lease keeps the process alive while the method works) */
		val keepAlive: suspend (reason: String, block: suspend () -> MethodOutcome) -> MethodOutcome = { _, block -> block() },
	)

	private val methods = methods.associateBy { it.id }
	private class Seen(var bytes: Long, var status: Int, var lastChangeMs: Long, var armed: Boolean, var intentSeq: Long, var done: Boolean = false,
		var armedAtMs: Long = 0, var startedSinceArm: Boolean = true)
	private val seen = HashMap<String, Seen>()
	private var baselined = false
	/** downloads the app created after this moment are new, also when the first look (the baseline) finds them failed */
	private val startedAtMs = clock()
	private val running = HashMap<String, Job>()
	private val lock = Mutex()

	/** One observation pass over the upstream lists. Returns true while something needs fast observation (a download is running or waiting, or a chain runs). */
	suspend fun tick(): Boolean {
		return lock.withLock {
			val ready = try { withContext(hostContext) { host.ready() } } catch (e: CancellationException) { throw e } catch (e: Exception) { true }
			if (!ready) return@withLock true                      // the app is still loading its lists: no partial baseline
			val list = try {
				withContext(hostContext) { host.downloads() }
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				Trace.event { TraceEvent("observer.error", reason = "${e.javaClass.simpleName}: ${e.message}") }
				return@withLock true
			}
			val now = clock()
			for (d in list) observe(d, list, now)
			val ids = list.mapTo(HashSet()) { it.id }
			// gone after the user deleted or cleared it: forget it (the app gives its id to the next new download)
			for (gone in seen.keys.filter { it !in ids }) if (ledger.intentOf(gone) in REMOVAL && running[gone]?.isActive != true) forgetLocked(gone)
			seen.keys.retainAll(ids)
			running.values.removeAll { !it.isActive }
			baselined = true
			running.isNotEmpty() || seen.values.any { it.armed }
		}
	}

	private fun observe(d: HostDownload, list: List<HostDownload>, now: Long) {
		val prev = seen[d.id]
		// a download id the ledger knows as deleted / cleared, now on a new download: the app reused the id
		if (prev == null && ledger.intentOf(d.id) in REMOVAL && running[d.id]?.isActive != true) forgetLocked(d.id)
		val st = prev ?: Seen(d.bytes, d.snapshot.status, now, armed = false, intentSeq = ledger.intentSeq(d.id)).also { seen[d.id] = it }
		if (d.bytes != st.bytes || d.snapshot.status != st.status) {
			st.bytes = d.bytes; st.status = d.snapshot.status; st.lastChangeMs = now
		}
		// a resume / start recorded since the last look arms the download even if it failed before we saw it running
		val seq = ledger.intentSeq(d.id)
		if (seq != st.intentSeq) {
			st.intentSeq = seq
			if (ledger.intentOf(d.id) in RESTART) { st.armed = true; st.armedAtMs = now; st.startedSinceArm = false }
		}
		if (d.snapshot.status == DownloadSnapshot.DOWNLOADING) st.startedSinceArm = true
		val snap = d.snapshot.copy(
			userIntent = ledger.intentOf(d.id),
			msSinceLastProgress = if (prev == null) null else now - st.lastChangeMs,
		)
		val v = TerminalClassifier.classify(snap)
		if (v != Verdict.Success) st.done = false
		when (v) {
			Verdict.InProgress, is Verdict.Waiting -> st.armed = true
			is Verdict.UserStopped -> st.armed = false
			Verdict.Success -> {
				st.armed = false
				if (!st.done) { st.done = true; onChildSuccess(d, list) }
			}
			is Verdict.Failure -> {
				// resumed but not started yet (queued behind other downloads, or between our ticks): give it time
				if (st.armed && !st.startedSinceArm && !isExplicit(snap) && now - st.armedAtMs < RESUME_GRACE_MS) return
				// first look: a failure found at startup is old state (never a chain), unless the app created the download
				// since VidChain started (it can start and fail while the app is still loading its lists, before the baseline)
				val isNew = baselined || (d.startedAtMs != null && d.startedAtMs >= startedAtMs)
				val trigger = st.armed || (prev == null && isNew && isExplicit(snap))
				st.armed = false
				if (trigger) onFailure(d, v, list, now)
			}
		}
	}

	private fun isExplicit(s: DownloadSnapshot) =
		s.isYtdlpHavingProblem || s.isFileUrlExpired || s.isFailedToAccessFile ||
			(s.statusKey != StatusKey.NONE && s.statusKey != StatusKey.PAUSED && s.statusKey != StatusKey.OTHER)

	private fun onFailure(d: HostDownload, v: Verdict.Failure, list: List<HostDownload>, now: Long) {
		val parentId = ledger.parentOf(d.id)
		val rootId = parentId ?: d.id
		Trace.event { TraceEvent("failure", downloadId = d.id, failureClass = v.cls.name, reason = if (parentId != null) "child of $parentId" else "current method") }
		if (!v.fallbackEligible) {
			Trace.event { TraceEvent("decision", downloadId = d.id, failureClass = v.cls.name, result = "not-eligible") }
			return
		}
		if (!config.enabled()) {
			Trace.event { TraceEvent("decision", downloadId = d.id, failureClass = v.cls.name, result = "fallbacks-off") }
			return
		}
		if (!ledger.markHandled(d.id, "${v.cls.name}#${ledger.intentSeq(d.id)}", now)) {
			Trace.event { TraceEvent("decision", downloadId = d.id, failureClass = v.cls.name, result = "already-handled") }
			return
		}
		if (parentId != null) ledger.attempts(rootId).lastOrNull { it.childId == d.id && it.state == AttemptState.CHILD }?.let {
			ledger.update(rootId, it.attemptNo, AttemptState.FAILED, null, "child download failed: ${v.cls.name}", now)
			Trace.event { TraceEvent("attempt.end", downloadId = rootId, method = it.method, result = "failed", failureClass = v.cls.name, reason = "child ${d.id} failed") }
		}
		val root = if (parentId == null) d else list.firstOrNull { it.id == rootId }
		if (root == null || ledger.intentOf(rootId) in REMOVAL) {
			Trace.event { TraceEvent("decision", downloadId = rootId, result = "parent-gone") }
			return
		}
		if (running[rootId]?.isActive == true) {
			Trace.event { TraceEvent("decision", downloadId = rootId, result = "chain-busy") }
			return
		}
		running[rootId] = scope.launch { runChain(root, v.cls.name) }
	}

	/** A child download finished: the DeliveryVerifier decides (off the observer, in [scope]); FAIL / UNSURE continue the chain. */
	private fun onChildSuccess(d: HostDownload, list: List<HostDownload>) {
		val parentId = ledger.parentOf(d.id) ?: return
		val row = ledger.attempts(parentId).lastOrNull { it.childId == d.id && it.state == AttemptState.CHILD } ?: return
		val root = list.firstOrNull { it.id == parentId }
		val previous = running[parentId]
		TrailBoard.update(parentId) { it.copy(state = Trail.State.VERIFYING) }
		running[parentId] = scope.launch {
			previous?.join()
			val path = d.filePath
			val result = if (path == null) Delivery(Check.UNSURE, org.websnake.vidchain.fallback.verify.FileKind.UNKNOWN, "child file path unknown")
			else verifier.verify(File(path), expectationFor(d, root))
			traceVerify(parentId, row.method, result, "child ${d.id}")
			val state = record(parentId, row.attemptNo, row.method, result, null)
			if (state != AttemptState.DELIVERED && root != null && ledger.intentOf(parentId) !in REMOVAL) runChain(root, "DELIVERY_${result.check.name}")
		}
	}

	private fun expectationFor(d: HostDownload, root: HostDownload?) = Expectation(
		expectMedia = root?.expectMedia ?: d.expectMedia,
		expectedBytes = d.expectedBytes,
		expectedDurationMs = root?.expectedDurationMs,
		fileName = d.fileName,
	)

	/**
	 * Write a verifier result into the ledger (after the file was saved and listed, for executor deliveries).
	 * PASS -> DELIVERED, FAIL -> FAILED, UNSURE -> UNSURE (best so far, the chain goes on).
	 */
	private fun record(parentId: String, attemptNo: Int, method: String, r: Delivery, listedId: String?, note: String = ""): AttemptState {
		val state = when (r.check) { Check.PASS -> AttemptState.DELIVERED; Check.FAIL -> AttemptState.FAILED; Check.UNSURE -> AttemptState.UNSURE }
		when (state) {
			AttemptState.DELIVERED -> TrailBoard.update(parentId) { it.copy(method = method, state = Trail.State.DELIVERED) }
			AttemptState.UNSURE -> TrailBoard.update(parentId) { it.copy(method = method, state = Trail.State.BEST_SO_FAR) }
			else -> Unit
		}
		ledger.update(parentId, attemptNo, state, listedId, "verify ${r.check.name} ${r.kind.name}: ${r.reason}$note", clock())
		return state
	}

	private fun traceVerify(parentId: String, method: String, r: Delivery, what: String) =
		Trace.event { TraceEvent("verify", downloadId = parentId, method = method, result = r.check.name.lowercase(), reason = "$what ${r.kind.name}: ${r.reason}", durationMs = r.durationMs) }

	private suspend fun runChain(original: HostDownload, failureClass: String) {
		var root = withEffectiveUrl(original)
		var urlClass = UrlClass.of(root.url)
		var steps = ChainSpec.stepsFor(urlClass, config.includeStub)
		// a RUNNING row found here belongs to a process that died mid-attempt
		ledger.attempts(root.id).filter { it.state == AttemptState.RUNNING }.forEach {
			ledger.update(root.id, it.attemptNo, AttemptState.FAILED, null, INTERRUPTED, clock())
		}
		while (true) {
			if (ledger.intentOf(root.id) in REMOVAL) {
				Trace.event { TraceEvent("decision", downloadId = root.id, chain = urlClass.chain, result = "parent-removed") }
				return
			}
			val done = ledger.attempts(root.id)
			if (done.any { it.state == AttemptState.CHILD || it.state == AttemptState.DELIVERED }) {
				Trace.event { TraceEvent("decision", downloadId = root.id, chain = urlClass.chain, result = "waiting-for-child-or-delivered") }
				return
			}
			val next = ChainSpec.nextMethod(steps, done.map { it.method }) { it in methods && config.methodOn(it) }
			if (next == null) {
				val best = done.lastOrNull { it.state == AttemptState.UNSURE }
				val available = steps.count { it in methods && config.methodOn(it) }
				if (best != null) TrailBoard.put(Trail(root.id, urlClass.chain, available, available, best.method, Trail.State.BEST_SO_FAR))
				else TrailBoard.put(Trail(root.id, urlClass.chain, done.size, done.size, done.lastOrNull()?.method ?: "-", Trail.State.EXHAUSTED))
				Trace.event { TraceEvent("chain.exhausted", downloadId = root.id, chain = urlClass.chain, failureClass = failureClass,
					result = if (best != null) "best-so-far" else "nothing", reason = "${done.size} attempts" + (best?.let { ", keeping attempt ${it.attemptNo} (${it.method}, unsure)" } ?: "")) }
				return
			}
			val no = (done.maxOfOrNull { it.attemptNo } ?: 0) + 1
			val step = steps.indexOf(next)
			if (!ledger.claim(root.id, no, next, clock())) {
				Trace.event { TraceEvent("decision", downloadId = root.id, chain = urlClass.chain, step = step, method = next, result = "claim-lost") }
				return
			}
			val ctx = FallbackContext(parentId = root.id, url = root.url, mediaUrl = root.mediaUrl, urlClass = urlClass, referer = root.referer,
				userAgent = root.userAgent, fileName = root.fileName, failureClass = failureClass, attemptNo = no,
				destPath = root.filePath, expectMedia = root.expectMedia, preferredHeight = root.preferredHeight, audioOnly = root.audioOnly)
			Trace.event { TraceEvent("attempt.start", downloadId = root.id, chain = urlClass.chain, step = step, method = next, failureClass = failureClass) }
			val runnable = steps.filter { it in methods && config.methodOn(it) }
			TrailBoard.put(Trail(root.id, urlClass.chain, runnable.indexOf(next) + 1, runnable.size, next, Trail.State.RUNNING))
			val t0 = clock()
			val outcome = try {
				val m = methods.getValue(next)
				suspend fun once() = if (m.kind == FallbackMethod.Kind.EXECUTOR) config.keepAlive("method $next") { m.attempt(ctx) } else m.attempt(ctx)
				val first = once()
				if (first is MethodOutcome.Failed && first.retryable) {
					Trace.event { TraceEvent("attempt.retry", downloadId = root.id, chain = urlClass.chain, step = step, method = next, reason = first.reason) }
					once()
				} else first
			} catch (e: CancellationException) {
				ledger.update(root.id, no, AttemptState.FAILED, null, "cancelled", clock())
				throw e
			} catch (e: Throwable) {
				MethodOutcome.Failed("method crashed: ${e.javaClass.simpleName}: ${e.message}")
			}
			val dur = clock() - t0
			fun end(result: String, reason: String?) = Trace.event {
				TraceEvent("attempt.end", downloadId = root.id, chain = urlClass.chain, step = step, method = next, result = result, reason = reason, durationMs = dur)
			}
			when (outcome) {
				is MethodOutcome.Resolved -> {
					val childId = lock.withLock {
						val id = try {
							withContext(hostContext) { host.enqueueChild(root, outcome.candidate, no, next) }
						} catch (e: CancellationException) {
							throw e
						} catch (e: Exception) {
							null
						}
						if (id != null) {
							TrailBoard.linkChild(id, root.id, next)
							TrailBoard.update(root.id) { it.copy(state = Trail.State.WAITING_CHILD) }
							ledger.mapChild(id, root.id)
							ledger.update(root.id, no, AttemptState.CHILD, id, null, clock())
						}
						id
					}
					if (childId != null) { end("child-queued", "child $childId"); return }
					ledger.update(root.id, no, AttemptState.FAILED, null, "could not queue the child download", clock())
					end("failed", "could not queue the child download")
				}
				is MethodOutcome.Delivered -> {
					end("delivered", null)
					var temp = File(outcome.path)
					var extras = outcome.extras.map(::File).filter { it.isFile }
					// a torrent's files were checked piece by piece by the engine: any non-empty file is what was asked for
					val exp = Expectation(root.expectMedia, null, root.expectedDurationMs, root.fileName, trusted = root.keepNames)
					var r = verifier.verify(temp, exp)
					if (r.check == Check.FAIL) {
						// another file of the same delivery (gallery, page with several files) may be the wanted one
						extras.firstNotNullOfOrNull { x -> verifier.verify(x, exp).takeIf { it.check != Check.FAIL }?.let { x to it } }?.let { (x, rx) ->
							extras = extras - x + temp; temp = x; r = rx
						}
					}
					traceVerify(root.id, next, r, "file")
					if (r.check != Check.FAIL && !root.keepNames) extras = extras.filter { x ->
						(verifier.verify(x, exp.copy(expectedDurationMs = null)).check != Check.FAIL).also { ok -> if (!ok) x.delete() }
					}
					if (r.check == Check.FAIL) {
						record(root.id, no, next, r, null)
						temp.delete(); extras.forEach { it.delete() }
					} else if (ledger.intentOf(root.id) in REMOVAL) {
						ledger.update(root.id, no, AttemptState.FAILED, null, "the download was removed meanwhile", clock())
						temp.delete(); extras.forEach { it.delete() }
						return
					} else {
						val planned = root.filePath?.let(::File) ?: File(temp.parentFile, root.fileName ?: temp.name)
						val dest = if (root.keepNames) File(planned.parentFile, temp.name) else deliveredName(planned, temp)
						val final = runCatching { DeliveryVerifier.commit(temp, dest) }.getOrNull()
						Trace.event { TraceEvent("commit", downloadId = root.id, method = next, result = if (final != null) "ok" else "failed", reason = final?.name) }
						val listed = final?.let { f -> try { withContext(hostContext) { host.registerDelivered(root, f, next) } } catch (e: CancellationException) { throw e } catch (e: Exception) { null } }
						if (final == null || listed == null) {
							// the file never reached the user's list: not a delivery, the chain goes on
							ledger.update(root.id, no, AttemptState.FAILED, null, if (final == null) "could not move the file into place" else "could not list the file ${final.name}", clock())
							if (final == null) temp.delete()
							extras.forEach { it.delete() }
						} else {
							val state = record(root.id, no, next, r, listed, "; saved as ${final.name}")
							TrailBoard.linkChild(listed, root.id, next)
							// the other files of a multi-file delivery: next to the first one, each listed
							for (x in extras) {
								val xf = runCatching { DeliveryVerifier.commit(x, File(final.parentFile, x.name)) }.getOrNull() ?: continue
								try { withContext(hostContext) { host.registerDelivered(root, xf, next) } } catch (e: CancellationException) { throw e } catch (e: Exception) { }
							}
							if (extras.isNotEmpty()) Trace.event { TraceEvent("commit", downloadId = root.id, method = next, result = "extras", reason = "${extras.size} more file(s)") }
							if (state == AttemptState.DELIVERED) return
						}
					}
				}
				is MethodOutcome.Failed -> { ledger.update(root.id, no, AttemptState.FAILED, null, outcome.reason, clock()); end("failed", outcome.reason) }
				is MethodOutcome.Redirected -> {
					ledger.setEffectiveUrl(root.id, outcome.url)
					val from = urlClass.chain
					root = withEffectiveUrl(original)
					urlClass = UrlClass.of(root.url)
					steps = ChainSpec.stepsFor(urlClass, config.includeStub)
					val note = "unwrapped${if (outcome.note.isNotEmpty()) " (${outcome.note})" else ""}; chain $from -> ${urlClass.chain}"
					ledger.update(root.id, no, AttemptState.REDIRECTED, null, note, clock())
					end("redirected", note)
				}
				is MethodOutcome.Unsupported -> { ledger.update(root.id, no, AttemptState.UNSUPPORTED, null, outcome.reason, clock()); end("unsupported", outcome.reason) }
			}
		}
	}

	private val interrupted = HashSet<String>()

	/**
	 * After a restart: put the last known state of recent chains back on the card board (from the ledger). A chain whose
	 * last attempt was still running when the process died is marked interrupted; [resumeInterrupted] picks it up.
	 */
	fun restoreBoard(limit: Int = 200) {
		for (parentId in ledger.recentParents(limit)) {
			var rows = ledger.attempts(parentId).ifEmpty { continue }
			val wasRunning = rows.last().state == AttemptState.RUNNING
			if (wasRunning) {
				rows.filter { it.state == AttemptState.RUNNING }.forEach { ledger.update(parentId, it.attemptNo, AttemptState.FAILED, null, INTERRUPTED, clock()) }
				rows = ledger.attempts(parentId)
				synchronized(interrupted) { interrupted += parentId }
			}
			rows.forEach { r -> r.childId?.let { TrailBoard.linkChild(it, parentId, r.method) } }
			val last = rows.last()
			val best = rows.lastOrNull { it.state == AttemptState.UNSURE }
			val delivered = rows.lastOrNull { it.state == AttemptState.DELIVERED }
			val state = when {
				delivered != null -> Trail.State.DELIVERED
				last.state == AttemptState.CHILD -> Trail.State.WAITING_CHILD
				wasRunning -> Trail.State.INTERRUPTED
				best != null -> Trail.State.BEST_SO_FAR
				else -> Trail.State.EXHAUSTED
			}
			val method = (delivered ?: best ?: last).method
			TrailBoard.put(Trail(parentId, "?", rows.size, rows.size, method, state))
		}
	}

	/**
	 * Chains the OS stopped mid-attempt continue with their NEXT untried method (the killed one is not run again).
	 * After [MAX_INTERRUPTIONS] interruptions of the same chain it stays stopped; "Try another method" still works.
	 */
	suspend fun resumeInterrupted(): Int = lock.withLock {
		val ids = synchronized(interrupted) { interrupted.toList().also { interrupted.clear() } }
		if (ids.isEmpty()) return@withLock 0
		val list = try { withContext(hostContext) { host.downloads() } } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
		var resumed = 0
		for (id in ids) {
			val root = list.firstOrNull { it.id == id } ?: continue
			if (running[id]?.isActive == true || ledger.intentOf(id) in REMOVAL || !config.enabled()) continue
			val stops = ledger.attempts(id).count { it.reason == INTERRUPTED }
			if (stops >= MAX_INTERRUPTIONS) {
				Trace.event { TraceEvent("decision", downloadId = id, result = "not-resumed", reason = "interrupted $stops times") }
				continue
			}
			Trace.event { TraceEvent("decision", downloadId = id, result = "resume-interrupted", reason = "interruption $stops") }
			running[id] = scope.launch { runChain(root, "RESUMED") }
			resumed++
		}
		resumed
	}

	/** the user deleted / cleared [downloadId]: stop its chain now (the engines are killed through cancellation) */
	suspend fun cancelChain(downloadId: String) {
		val job = lock.withLock { running[ledger.parentOf(downloadId) ?: downloadId] }
		job?.cancel()
	}

	/** must hold [lock] */
	private fun forgetLocked(id: String) {
		ledger.forget(id)
		TrailBoard.remove(id)
		seen.remove(id)
		Trace.event { TraceEvent("decision", downloadId = id, result = "forgotten", reason = "removed by the user") }
	}

	/**
	 * Runs [root]'s chain for something the app has no download for (a shared magnet link). The root is not in the
	 * upstream lists; delivered files are listed in the app's finished downloads. false = already running.
	 */
	suspend fun startStandalone(root: HostDownload): Boolean = lock.withLock {
		if (running[root.id]?.isActive == true) return@withLock false
		Trace.event { TraceEvent("decision", downloadId = root.id, chain = UrlClass.of(root.url).chain, result = "standalone") }
		running[root.id] = scope.launch { runChain(root, "SHARED") }
		true
	}

	enum class Manual { STARTED, BUSY, ALREADY_DELIVERED, NOTHING_LEFT, NOT_FOUND }

	/**
	 * The user asked for another method ("Try another method"): runs the next method of the chain now, whether or not
	 * the current method has failed (the current download is left alone). A child still downloading is given up as an
	 * attempt. Works even when automatic fallbacks are switched off.
	 */
	suspend fun tryAnother(downloadId: String): Manual = lock.withLock {
		val rootId = ledger.parentOf(downloadId) ?: downloadId
		val list = try { withContext(hostContext) { host.downloads() } } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
		val root = list.firstOrNull { it.id == rootId } ?: return@withLock Manual.NOT_FOUND
		if (running[rootId]?.isActive == true) return@withLock Manual.BUSY
		val rows = ledger.attempts(rootId)
		if (rows.any { it.state == AttemptState.DELIVERED }) return@withLock Manual.ALREADY_DELIVERED
		val steps = ChainSpec.stepsFor(UrlClass.of(withEffectiveUrl(root).url), config.includeStub)
		if (ChainSpec.nextMethod(steps, rows.map { it.method }) { it in methods && config.methodOn(it) } == null) return@withLock Manual.NOTHING_LEFT
		rows.filter { it.state == AttemptState.CHILD }.forEach {
			ledger.update(rootId, it.attemptNo, AttemptState.FAILED, null, "user asked for another method", clock())
		}
		Trace.event { TraceEvent("decision", downloadId = rootId, result = "user-try-another") }
		running[rootId] = scope.launch { runChain(root, "USER_REQUEST") }
		Manual.STARTED
	}

	/** keep the delivered file's real container extension (yt-dlp may produce .webm where the current method planned .mp4) */
	private fun deliveredName(dest: File, temp: File): File {
		val ext = temp.extension.lowercase()
		if (ext.isEmpty() || ext in TEMP_EXT || dest.name.endsWith(".$ext", ignoreCase = true)) return dest
		return File(dest.parentFile, dest.nameWithoutExtension.ifEmpty { dest.name } + "." + ext)
	}

	/** the download as the chain sees it: after a redirect unwrap, the unwrapped URL is both its source and its media URL */
	private fun withEffectiveUrl(d: HostDownload): HostDownload = ledger.effectiveUrl(d.id)?.let { d.copy(url = it, mediaUrl = it) } ?: d

	/** Wait until no chain is running (tests, shutdown). */
	suspend fun drain() {
		while (true) {
			val jobs = lock.withLock { running.values.filter { it.isActive } }
			if (jobs.isEmpty()) return
			jobs.joinAll()
		}
	}

	private companion object {
		val REMOVAL = setOf(UserIntent.CANCEL, UserIntent.CLEAR, UserIntent.DELETE)
		val RESTART = setOf(UserIntent.START, UserIntent.RESUME)
		val TEMP_EXT = setOf("part", "tmp", "meta", "ytdl", "bin")
		const val INTERRUPTED = "interrupted (the app was closed)"
		const val MAX_INTERRUPTIONS = 2
		/** a resumed download has this long to start before a CLOSE / paused state counts as its failure */
		const val RESUME_GRACE_MS = 90_000L
	}
}
