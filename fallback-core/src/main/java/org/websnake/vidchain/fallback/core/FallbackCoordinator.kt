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
import org.websnake.vidchain.fallback.classifier.Engine
import org.websnake.vidchain.fallback.classifier.FailureClass
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
 * - The current method runs first and is otherwise left alone. Two owner decisions (T8.12) act on it AFTER it failed:
 *   a yt-dlp download stuck connecting (row 5a2) is paused before the fallbacks take over, and an expired link may be
 *   swapped for the same file's fresh address and resumed through the app's own resume.
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
	/** expired links: the same file asked again and the app's download resumed (T8.12); null = always the chain */
	private val refresher: LinkRefresher? = null,
) {
	/** refresh attempts per download (at most [MAX_REFRESH], so an endlessly expiring link still reaches the chain) */
	private val refreshes = java.util.concurrent.ConcurrentHashMap<String, Int>()
	class Config(
		val includeStub: Boolean = true,
		val methodOn: (String) -> Boolean = { true },
		/** wraps executor attempts (in the app: an EngineHost lease keeps the process alive while the method works) */
		val keepAlive: suspend (reason: String, block: suspend () -> MethodOutcome) -> MethodOutcome = { _, block -> block() },
		/** T9 decision 1: how many methods run at once (default: one at a time) */
		val race: org.websnake.vidchain.fallback.race.BatchPlanner.Config = org.websnake.vidchain.fallback.race.BatchPlanner.Config(batchSize = 1, maxLight = 1, staggerMs = 0),
		val weightOf: (String) -> org.websnake.vidchain.fallback.race.BatchPlanner.Weight = { org.websnake.vidchain.fallback.race.BatchPlanner.Weight.LIGHT },
		/** a method running this long is cancelled (its own time limits come first; this is the watchdog's) */
		val limitMs: Long = 30 * 60_000L,
		/** T9: the learned per-site order and results; null = the chain order */
		val scorecard: org.websnake.vidchain.fallback.race.Scorecard? = null,
		/** T9: proves a resolver's candidate before it wins (first bytes); null = trusted as before */
		val probe: (suspend (Candidate, FallbackContext) -> Boolean)? = null,
		/** T9: a chain ended (the runtime keeps the scorecard) */
		val onChainEnd: () -> Unit = {},
		/** T9: start the methods beside a slow own download (the hedged start); off = only after it failed */
		val hedge: Boolean = false,
	)

	private val methods = methods.associateBy { it.id }
	private class Seen(var bytes: Long, var status: Int, var lastChangeMs: Long, var armed: Boolean, var intentSeq: Long, var done: Boolean = false,
		var armedAtMs: Long = 0, var startedSinceArm: Boolean = true,
		// T9 hedged start: when this look first saw the app's own path running, whether it proved its first bytes,
		// whether the other methods were started beside it, and whether its result went into the scorecard
		var runningSinceMs: Long = 0, var ownProven: Boolean = false, var hedged: Boolean = false, var ownScored: Boolean = false,
		// T9 full pass (trap-truncated): when an unexplained stop was first seen, and whether this download's id was
		// checked for rows of an earlier download that had the same id
		var unexplainedSinceMs: Long = 0, var reuseChecked: Boolean = false)
	private val seen = HashMap<String, Seen>()
	private var baselined = false
	/** downloads the app created after this moment are new, also when the first look (the baseline) finds them failed */
	private val startedAtMs = clock()
	private val running = java.util.concurrent.ConcurrentHashMap<String, Job>()   // review run 25: read off the lock too
	private val lock = Mutex()
	/** T9 review: the hedged (LATE) chains running now, the ones whose win paused the own download, and the ones whose
	 *  own download failed while they ran (that failure was not a chain of its own) */
	private val lateChains = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
	private val hedgeWins = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
	private val lateOwnFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
	/** T9 review (P0b): true while a chain runs, so the app keeps its foreground service through a rescue */
	@Volatile var busy = false
		private set

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
			busy = running.isNotEmpty()
			baselined = true
			running.isNotEmpty() || seen.values.any { it.armed }
		}
	}

	private fun observe(d: HostDownload, list: List<HostDownload>, now: Long) {
		val prev = seen[d.id]
		// a download id the ledger knows as deleted / cleared, now on a new download: the app reused the id
		if (prev == null && ledger.intentOf(d.id) in REMOVAL && running[d.id]?.isActive != true) forgetLocked(d.id)
		// the app also reuses an id when its record went away without a user action (pass 11: a failed download's
		// record dropped on restart): a download created since VidChain started cannot have chain history of its own
		else if (prev == null && d.startedAtMs != null && d.startedAtMs >= startedAtMs && ledger.parentOf(d.id) == null &&
			running[d.id]?.isActive != true && ledger.attempts(d.id).isNotEmpty()) forgetLocked(d.id, "id reused by a new download")
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
		// T9 full pass: the app reuses download ids; rows written before this download existed belong to the earlier one
		// (they made a new download's chain "exhausted" at once). Checked once, as soon as the start time is known.
		if (!st.reuseChecked && d.startedAtMs != null) {
			st.reuseChecked = true
			val rows = ledger.attempts(d.id)
			if (rows.isNotEmpty() && ledger.parentOf(d.id) == null && running[d.id]?.isActive != true &&
				rows.none { it.state == AttemptState.DELIVERED || it.state == AttemptState.CHILD } &&
				rows.all { it.startedMs < d.startedAtMs - REUSE_SLACK_MS }) {
				forgetLocked(d.id, "rows of an earlier download with this id")
				seen[d.id] = st                                              // review run 25: this look's state stays
			}
		}
		val snap = d.snapshot.copy(
			userIntent = ledger.intentOf(d.id),
			msSinceLastProgress = if (prev == null) null else now - st.lastChangeMs,
			bytesSoFar = d.bytes,
		)
		val v = TerminalClassifier.classify(snap)
		if (v != Verdict.Success) st.done = false
		// T9 full pass (trap-truncated) + review run 25: a stop with no reason at all can be the app passing through
		// CLOSE while it starts the download; it counts (also for the hedge's score) only when a later look still sees it
		if (v is Verdict.Failure && v.cls == FailureClass.CRASH_OR_UNKNOWN && !isExplicit(snap) && st.armed) {
			if (st.unexplainedSinceMs == 0L) st.unexplainedSinceMs = now
			if (now - st.unexplainedSinceMs < UNEXPLAINED_CONFIRM_MS) return
		}
		hedge(d, st, v, now)
		if (v !is Verdict.Failure) st.unexplainedSinceMs = 0L
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

	/**
	 * T9 decision 1, the hedged start (adhd 2026-10-14): the app's own path runs alone; when it has not proven its first
	 * bytes within its site's usual time (the scorecard's p90, else [OwnPath.defaultProofMs]), VidChain's methods start
	 * beside it. The first proven one wins and the own download is paused; when the own path proves first, the race is
	 * stopped (its methods still count as not tried). The own path's results go into the scorecard like any method's.
	 */
	private fun hedge(d: HostDownload, st: Seen, v: Verdict, now: Long) {
		val own = d.ownPath ?: return
		if (ledger.parentOf(d.id) != null) return                                     // a child download is a method's work
		val site = org.websnake.vidchain.fallback.race.Scorecard.siteOf(d.url)
		val card = config.scorecard
		when (v) {
			Verdict.InProgress -> {
				if (d.snapshot.status == DownloadSnapshot.DOWNLOADING && st.runningSinceMs == 0L) st.runningSinceMs = now
				if (st.runningSinceMs == 0L || st.ownProven) return
				if (d.bytes >= OWN_PROOF_BYTES) {
					st.ownProven = true
					if (!st.ownScored) { st.ownScored = true; card?.record(site, own, org.websnake.vidchain.fallback.race.Scorecard.Cause.OK, now - st.runningSinceMs) }
					if (st.hedged && running[d.id]?.isActive == true) {
						// the own path proved first: the methods beside it stop and stay untried
						ledger.attempts(d.id).filter { it.state == AttemptState.RUNNING }
							.forEach { ledger.update(d.id, it.attemptNo, AttemptState.FAILED, null, CANCELLED_FOR_WINNER, now) }
						running[d.id]?.cancel()
						TrailBoard.remove(d.id)
						Trace.event { TraceEvent("hedge.stopped", downloadId = d.id, method = own, reason = "the app's own path proved first") }
					}
					return
				}
				if (!config.hedge || st.hedged || running[d.id]?.isActive == true || ledger.intentOf(d.id) in PAUSES_AND_REMOVAL) return
				val wait = card?.proofTimeMs(site, own, OwnPath.defaultProofMs(own)) ?: OwnPath.defaultProofMs(own)
				if (now - st.runningSinceMs < wait || !chainCanRun(d)) return
				st.hedged = true
				Trace.event { TraceEvent("hedge.start", downloadId = d.id, method = own, reason = "no first bytes after ${now - st.runningSinceMs} ms (usual ${wait} ms)") }
				lateChains += d.id; busy = true
				running[d.id] = scope.launch { runChain(d, LATE) }
			}
			is Verdict.Failure -> if (!st.ownScored && st.runningSinceMs > 0) {
				st.ownScored = true
				val cause = when (v.cls) {
					FailureClass.STALLED -> org.websnake.vidchain.fallback.race.Scorecard.Cause.TIMEOUT
					FailureClass.EXTRACTOR_LOGIN -> org.websnake.vidchain.fallback.race.Scorecard.Cause.LOGIN
					FailureClass.EXTRACTOR_UNAVAILABLE -> org.websnake.vidchain.fallback.race.Scorecard.Cause.GEO
					FailureClass.STORAGE -> null
					else -> org.websnake.vidchain.fallback.race.Scorecard.Cause.NOT_UNDERSTOOD
				}
				cause?.let { card?.record(site, own, it, now - st.runningSinceMs) }
			}
			// T9 review H3: the own download finished (or the user paused it) between two looks: the methods beside it stop
			Verdict.Success, is Verdict.UserStopped -> if (st.hedged && running[d.id]?.isActive == true && !(v is Verdict.UserStopped && d.id in hedgeWins)) {
				ledger.attempts(d.id).filter { it.state == AttemptState.RUNNING }
					.forEach { ledger.update(d.id, it.attemptNo, AttemptState.FAILED, null, CANCELLED_FOR_WINNER, now) }
				running[d.id]?.cancel()
				TrailBoard.remove(d.id)
				Trace.event { TraceEvent("hedge.stopped", downloadId = d.id, method = own,
					reason = if (v == Verdict.Success) "the app's own path finished" else "the own download was paused") }
			}
			else -> Unit
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
			if (parentId == null && rootId in lateChains) lateOwnFailed += rootId         // T9 review M1: its card shows the end
			Trace.event { TraceEvent("decision", downloadId = rootId, result = "chain-busy") }
			return
		}
		// row 5a2: the app's own yt-dlp download is still running but never connected; it is paused (recorded as a pause,
		// so it is not seen as a new failure) before the fallbacks start, so the two never deliver the same video twice
		// (only when a fallback can really take over: never a pause with nothing behind it - T8.12 review)
		val stuck = parentId == null && v.cls == FailureClass.STALLED && d.snapshot.isRunning && d.snapshot.engine == Engine.M3U8 && chainCanRun(root)
		if (stuck) ledger.recordIntent(d.id, UserIntent.PAUSE, now)
		// an expired link after part of the file was saved: ask the page for the same file and resume the app's download
		val refresh = refresher != null && parentId == null && v.cls == FailureClass.EXPIRED_URL && d.snapshot.engine == Engine.REGULAR &&
			d.bytes > 0 && (refreshes[d.id] ?: 0) < MAX_REFRESH
		busy = true
		running[rootId] = scope.launch {
			if (refresh) {
				refreshes.merge(d.id, 1, Int::plus)
				val fresh = try { refresher?.fresh(root) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
				val resumed = fresh != null && try { withContext(hostContext) { host.resumeWithUrl(root, fresh) } } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
				Trace.event { TraceEvent("decision", downloadId = rootId, failureClass = v.cls.name, result = if (resumed) "expired-link-refreshed" else "expired-link-not-refreshed") }
				if (resumed) { ledger.recordIntent(d.id, UserIntent.RESUME, clock()); return@launch }
			}
			if (stuck) {
				val stopped = try { withContext(hostContext) { host.stopStuck(root) } } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
				Trace.event { TraceEvent("decision", downloadId = rootId, failureClass = v.cls.name, result = if (stopped) "stuck-parent-stopped" else "stuck-parent-not-stopped") }
				// not paused after all: its next real failure must count as one (T8.12 review)
				if (!stopped) ledger.recordIntent(d.id, UserIntent.RESUME, clock())
			}
			runChain(root, v.cls.name)
		}
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

	/** would [runChain] start a method now: nothing delivered or in progress, and a method left that is on */
	private fun chainCanRun(root: HostDownload): Boolean {
		val done = ledger.attempts(root.id)
		if (done.any { it.state == AttemptState.CHILD || it.state == AttemptState.DELIVERED }) return false
		val steps = ChainSpec.stepsFor(UrlClass.of(withEffectiveUrl(root).url), config.includeStub)
		return ChainSpec.nextMethod(steps, tried(done)) { it in methods && config.methodOn(it) } != null   // T9 review M3
	}

	private suspend fun runChain(original: HostDownload, failureClass: String) {
		try { runChainInner(original, failureClass) } finally {
			if (failureClass == LATE) { lateChains.remove(original.id); lateOwnFailed.remove(original.id) }
			runCatching { config.onChainEnd() }
		}
	}

	private suspend fun runChainInner(original: HostDownload, failureClass: String) {
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
			val next = ChainSpec.nextMethod(steps, tried(done)) { it in methods && config.methodOn(it) }
			if (next == null && done.isEmpty() && MethodSwitches.noCapableMethod(steps, methods.keys) { config.methodOn(it) }) {
				TrailBoard.put(Trail(root.id, urlClass.chain, 0, 0, "-", Trail.State.NO_METHOD))
				Trace.event { TraceEvent("chain.exhausted", downloadId = root.id, chain = urlClass.chain, failureClass = failureClass,
					result = "nothing", reason = "no switched-on method for this kind of link") }
				return
			}
			if (next == null && failureClass == LATE && root.id !in lateOwnFailed) {
				// the methods beside a slow own download found nothing: it goes on alone (its own failure, if any, ends the chain)
				TrailBoard.remove(root.id)
				Trace.event { TraceEvent("hedge.exhausted", downloadId = root.id, chain = urlClass.chain, reason = "${done.size} attempts") }
				// T9 review H4: an early winner paused it and then failed: the own download goes on
				if (hedgeWins.remove(root.id)) {
					ledger.recordIntent(root.id, UserIntent.RESUME, clock())
					val resumed = try { withContext(hostContext) { host.resumePaused(root) } } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
					Trace.event { TraceEvent("decision", downloadId = root.id, result = if (resumed) "own-path-resumed" else "own-path-not-resumed", reason = "the hedge found nothing") }
				}
				return
			}
			if (next == null) {
				val best = done.lastOrNull { it.state == AttemptState.UNSURE }
				val available = steps.count { it in methods && config.methodOn(it) }
				if (best != null) TrailBoard.put(Trail(root.id, urlClass.chain, available, available, best.method, Trail.State.BEST_SO_FAR))
				else tried(done).distinct().size.let { n -> TrailBoard.put(Trail(root.id, urlClass.chain, n, n, done.lastOrNull()?.method ?: "-", Trail.State.EXHAUSTED)) }
				Trace.event { TraceEvent("chain.exhausted", downloadId = root.id, chain = urlClass.chain, failureClass = failureClass,
					result = if (best != null) "best-so-far" else "nothing", reason = "${done.size} attempts" + (best?.let { ", keeping attempt ${it.attemptNo} (${it.method}, unsure)" } ?: "")) }
				return
			}
			when (runBatches(root, urlClass, steps, done, failureClass)) {
				RaceEnd.STOP -> return
				RaceEnd.AGAIN -> Unit
				RaceEnd.REDIRECTED -> {
					root = withEffectiveUrl(original)
					urlClass = UrlClass.of(root.url)
					steps = ChainSpec.stepsFor(urlClass, config.includeStub)
				}
			}
		}
	}

	private enum class RaceEnd { STOP, AGAIN, REDIRECTED }

	/** how one attempt ended (T9: several run at once) */
	private enum class AttemptEnd { Delivered, Failed, Unsure, Redirect }

	/** T9: a method that was cancelled because another one seemed to win is not counted as tried */
	private fun tried(done: List<org.websnake.vidchain.fallback.ledger.AttemptRow>) =
		done.filterNot { it.state == AttemptState.FAILED && it.reason?.startsWith(CANCELLED_FOR_WINNER) == true }.map { it.method }

	/**
	 * T9 decision 1: the methods left run in batches (BatchPlanner: weights, staggered start, cancel only after a proven
	 * success, losers stopped by the watchdog). [Config.race] sets the batch size; 1 = one at a time.
	 */
	private suspend fun runBatches(root: HostDownload, urlClass: UrlClass, steps: List<String>,
								   done: List<org.websnake.vidchain.fallback.ledger.AttemptRow>, failureClass: String): RaceEnd = kotlinx.coroutines.coroutineScope {
		val triedIds = tried(done)
		val left = steps.filter { it in methods && config.methodOn(it) && it !in triedIds }
		val site = org.websnake.vidchain.fallback.race.Scorecard.siteOf(root.url)
		val ranked = config.scorecard?.rank(site, left) ?: left
		val planner = org.websnake.vidchain.fallback.race.BatchPlanner(
			// T9 review D4: an executor downloading (a torrent can take hours) is never cut by the watchdog: its engine has its own limits
			ranked.map { org.websnake.vidchain.fallback.race.BatchPlanner.Step(it, config.weightOf(it),
				if (methods[it]?.kind == FallbackMethod.Kind.EXECUTOR) Long.MAX_VALUE else config.limitMs) }, config.race, clock())
		val plannedBatches = org.websnake.vidchain.fallback.race.BatchPlanner.batchesFor(ranked.map { org.websnake.vidchain.fallback.race.BatchPlanner.Step(it, config.weightOf(it)) }, config.race)
		val events = kotlinx.coroutines.channels.Channel<Ev>(kotlinx.coroutines.channels.Channel.UNLIMITED)
		val jobs = HashMap<String, Job>()
		val nos = HashMap<String, Int>()
		val starts = HashMap<String, Long>()
		val ctxs = HashMap<String, FallbackContext>()
		val resolved = HashMap<String, MethodOutcome.Resolved>()
		var nextNo = (done.maxOfOrNull { it.attemptNo } ?: 0) + 1
		var winner: String? = null
		var end: RaceEnd? = null

		fun trail() {
			val now = planner.running()
			if (now.isEmpty()) return
			if (config.race.batchSize <= 1) {                                     // one at a time: "trying X (n/k)" as before
				val runnable = steps.filter { it in methods && config.methodOn(it) }
				TrailBoard.put(Trail(root.id, urlClass.chain, runnable.indexOf(now.first()) + 1, runnable.size, now.first(), Trail.State.RUNNING))
			} else TrailBoard.put(Trail(root.id, urlClass.chain, planner.batches, plannedBatches, now.first(), Trail.State.RUNNING, running = now))
		}

		suspend fun perform(actions: List<org.websnake.vidchain.fallback.race.BatchPlanner.Action>) {
			for (a in actions) when (a) {
				is org.websnake.vidchain.fallback.race.BatchPlanner.Action.Start -> {
					val id = a.id
					val no = nextNo++
					val step = steps.indexOf(id)
					if (!ledger.claim(root.id, no, id, clock())) {
						Trace.event { TraceEvent("decision", downloadId = root.id, chain = urlClass.chain, step = step, method = id, result = "claim-lost") }
						end = RaceEnd.STOP
						return
					}
					val ctx = FallbackContext(parentId = root.id, url = root.url, mediaUrl = root.mediaUrl, urlClass = urlClass, referer = root.referer,
						userAgent = root.userAgent, fileName = root.fileName, failureClass = failureClass, attemptNo = no,
						destPath = root.filePath, expectMedia = root.expectMedia, preferredHeight = root.preferredHeight, audioOnly = root.audioOnly,
						onProven = { events.trySend(Ev.Proven(id)) })
					if (failureClass == LATE) ledger.update(root.id, no, AttemptState.RUNNING, null, HEDGE_ROW, clock())   // T9 review M2
					nos[id] = no; starts[id] = clock(); ctxs[id] = ctx
					Trace.event { TraceEvent("attempt.start", downloadId = root.id, chain = urlClass.chain, step = step, method = id, failureClass = failureClass,
						reason = if (config.race.batchSize > 1) "batch ${planner.batches}" else null) }
					jobs[id] = launch {
						val o = execute(root, urlClass, step, id, no, ctx)
						events.send(Ev.Done(id, o))
					}
					trail()
				}
				is org.websnake.vidchain.fallback.race.BatchPlanner.Action.Probe -> {
					val id = a.id
					val c = resolved[id]?.candidate
					val probe = config.probe
					val ctx = ctxs[id]                                   // read here: the maps belong to this loop only
					launch {
						val ok = c == null || probe == null || ctx == null || try { probe(c, ctx) } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
						events.send(Ev.Proof(id, ok))
					}
				}
				is org.websnake.vidchain.fallback.race.BatchPlanner.Action.Cancel -> {
					val id = a.id
					jobs[id]?.cancel()
					val why = if (a.why == "time limit") "time limit" else CANCELLED_FOR_WINNER
					nos[id]?.let { no -> ledger.update(root.id, no, AttemptState.FAILED, null, why, clock()) }
					Trace.event { TraceEvent("attempt.end", downloadId = root.id, chain = urlClass.chain, method = id, result = "cancelled", reason = a.why,
						durationMs = starts[id]?.let { clock() - it }) }
					score(site, id, if (a.why == "time limit") org.websnake.vidchain.fallback.race.Scorecard.Cause.TIMEOUT else org.websnake.vidchain.fallback.race.Scorecard.Cause.CANCELLED, starts[id])
					trail()
				}
				is org.websnake.vidchain.fallback.race.BatchPlanner.Action.Kill ->
					Trace.event { TraceEvent("watchdog.kill", downloadId = root.id, method = a.id, reason = "still running after the cancel") }
				is org.websnake.vidchain.fallback.race.BatchPlanner.Action.Win -> {
					winner = a.id
					Trace.event { TraceEvent("race.win", downloadId = root.id, chain = urlClass.chain, method = a.id, reason = "batch ${planner.batches}") }
					if (failureClass == LATE) {
						// the hedged start: the app's own download (still without bytes) is paused, recorded as a pause
						hedgeWins += root.id
						ledger.recordIntent(root.id, UserIntent.PAUSE, clock())
						val stopped = try { withContext(hostContext) { host.stopStuck(root) } } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
						// T9 review H5: not paused after all (it had already ended): no pause intent left behind
						if (!stopped) { hedgeWins.remove(root.id); ledger.recordIntent(root.id, UserIntent.RESUME, clock()) }
						Trace.event { TraceEvent("decision", downloadId = root.id, result = if (stopped) "own-path-paused" else "own-path-not-paused", reason = "hedge won by ${a.id}") }
					}
				}
				org.websnake.vidchain.fallback.race.BatchPlanner.Action.Exhausted -> if (end == null) end = RaceEnd.AGAIN
			}
		}

		// the clock ticks into the same channel: a receive with a time-out could drop an event that arrives as it ends
		// (kotlinx prompt cancellation; a lost probe result left a race waiting for good)
		val ticker = launch { while (true) { kotlinx.coroutines.delay(RACE_TICK_MS); events.send(Ev.Tick) } }
		perform(planner.tick(clock()))
		while (end == null) {
			if (ledger.intentOf(root.id) in REMOVAL) {
				Trace.event { TraceEvent("decision", downloadId = root.id, chain = urlClass.chain, result = "parent-removed") }
				end = RaceEnd.STOP; break
			}
			val ev = events.receive()
			val now = clock()
			when (ev) {
				Ev.Tick -> perform(if (winner != null) planner.reap(now) else planner.tick(now))
				is Ev.Proven -> if (winner == null && ev.id in jobs) {
					// an executor's first bytes are media: it wins now, the others stop; it goes on to finish its file
					Trace.event { TraceEvent("race.proven", downloadId = root.id, method = ev.id, reason = "first bytes are media") }
					perform(planner.onCandidate(ev.id, now)); perform(planner.onProof(ev.id, true, now))
				}
				is Ev.Done -> {
					val id = ev.id
					jobs.remove(id)
					val o = ev.outcome
					if (winner != null && id != winner) {
						// another method already won: this late result is dropped (never a second download or file)
						if (o is MethodOutcome.Delivered) { File(o.path).delete(); o.extras.forEach { File(it).delete() } }
						nos[id]?.let { no -> ledger.update(root.id, no, AttemptState.FAILED, null, CANCELLED_FOR_WINNER, clock()) }
						continue
					}
					if (o is MethodOutcome.Resolved && config.probe != null && winner == null) {
						resolved[id] = o
						perform(planner.onCandidate(id, now))                       // the probe decides
						continue
					}
					val e = settle(root, urlClass, steps.indexOf(id), id, nos.getValue(id), o, now - (starts[id] ?: now))
					score(site, id, causeOf(o, e), starts[id])
					when (e) {
						AttemptEnd.Delivered -> {
							if (winner == null) { perform(planner.onCandidate(id, now)); perform(planner.onProof(id, true, now)) }
							end = RaceEnd.STOP
						}
						AttemptEnd.Redirect -> end = RaceEnd.REDIRECTED
						else -> if (winner == id) end = RaceEnd.AGAIN                // the early winner's file did not pass: the others get their turn again
							else perform(planner.onEnded(id, now))
					}
				}
				is Ev.Proof -> {
					val id = ev.id
					val o = resolved.remove(id) ?: continue
					if (winner != null && id != winner) {                                 // T9 review: a late probe after a win
						nos[id]?.let { no -> ledger.update(root.id, no, AttemptState.FAILED, null, CANCELLED_FOR_WINNER, clock()) }
						continue
					}
					if (!ev.ok) {
						nos[id]?.let { no -> ledger.update(root.id, no, AttemptState.FAILED, null, "its candidate is not a media file (probe)", clock()) }
						Trace.event { TraceEvent("attempt.end", downloadId = root.id, chain = urlClass.chain, method = id, result = "not-proven", reason = "probe") }
						score(site, id, org.websnake.vidchain.fallback.race.Scorecard.Cause.NOT_MEDIA, starts[id])
						perform(planner.onProof(id, false, now))
						continue
					}
					perform(planner.onProof(id, true, now))
					val e = settle(root, urlClass, steps.indexOf(id), id, nos.getValue(id), o, now - (starts[id] ?: now))
					score(site, id, causeOf(o, e), starts[id])
					end = if (e == AttemptEnd.Delivered) RaceEnd.STOP else RaceEnd.AGAIN
				}
			}
		}
		// whatever still runs has lost (or the parent was removed): stop it. T9 review H6: it stays untried (after a
		// redirect it runs again on the new address)
		ticker.cancel()
		val now = clock()
		for (id in jobs.keys) nos[id]?.let { no ->
			if (ledger.attempts(root.id).firstOrNull { it.attemptNo == no }?.state == AttemptState.RUNNING)
				ledger.update(root.id, no, AttemptState.FAILED, null, CANCELLED_FOR_WINNER, now)
		}
		jobs.values.forEach { it.cancel() }
		end ?: RaceEnd.AGAIN
	}

	private sealed class Ev {
		object Tick : Ev()
		class Done(val id: String, val outcome: MethodOutcome) : Ev()
		class Proven(val id: String) : Ev()
		class Proof(val id: String, val ok: Boolean) : Ev()
	}

	private fun score(site: String, method: String, cause: org.websnake.vidchain.fallback.race.Scorecard.Cause?, startedAt: Long?) {
		val c = cause ?: return
		val card = config.scorecard ?: return
		card.record(site, method, c, startedAt?.let { clock() - it } ?: 0L)
	}

	/** the scorecard's cause for a method's result (T9 decision 1); null = not the method's doing (unsupported) */
	private fun causeOf(o: MethodOutcome, e: AttemptEnd): org.websnake.vidchain.fallback.race.Scorecard.Cause? {
		return when {
			e == AttemptEnd.Delivered -> org.websnake.vidchain.fallback.race.Scorecard.Cause.OK
			o is MethodOutcome.Unsupported || o is MethodOutcome.Redirected -> null
			o is MethodOutcome.Failed -> org.websnake.vidchain.fallback.race.Scorecard.Cause.fromReason(o.reason)
			else -> org.websnake.vidchain.fallback.race.Scorecard.Cause.NOT_MEDIA
		}
	}

	/** runs [next] once, and once more when it asks for a retry (the per-attempt part of the one-at-a-time chain) */
	private suspend fun execute(root: HostDownload, urlClass: UrlClass, step: Int, next: String, no: Int, ctx: FallbackContext): MethodOutcome =
		try {
			val m = methods.getValue(next)
			suspend fun once() = if (m.kind == FallbackMethod.Kind.EXECUTOR) config.keepAlive("method $next") { m.attempt(ctx) } else m.attempt(ctx)
			val first = once()
			if (first is MethodOutcome.Failed && first.retryable) {
				Trace.event { TraceEvent("attempt.retry", downloadId = root.id, chain = urlClass.chain, step = step, method = next, reason = first.reason) }
				once()
			} else first
		} catch (e: CancellationException) {
			// a race loser was already recorded by the canceller (T9); anything else that stops it is recorded here
			if (ledger.attempts(root.id).firstOrNull { it.attemptNo == no }?.state == AttemptState.RUNNING)
				ledger.update(root.id, no, AttemptState.FAILED, null, "cancelled", clock())
			throw e
		} catch (e: Throwable) {
			MethodOutcome.Failed("method crashed: ${e.javaClass.simpleName}: ${e.message}")
		}

	/** what one attempt's result means for the parent: verify and commit a file, queue a child, follow a redirect */
	private suspend fun settle(root: HostDownload, urlClass: UrlClass, step: Int, next: String, no: Int, outcome: MethodOutcome, dur: Long): AttemptEnd {
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
				if (childId != null) { end("child-queued", "child $childId"); return AttemptEnd.Delivered }
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
					return AttemptEnd.Delivered
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
						if (state == AttemptState.DELIVERED) return AttemptEnd.Delivered
						if (state == AttemptState.UNSURE) return AttemptEnd.Unsure
					}
				}
			}
			is MethodOutcome.Failed -> { ledger.update(root.id, no, AttemptState.FAILED, null, outcome.reason, clock()); end("failed", outcome.reason) }
			is MethodOutcome.Redirected -> {
				ledger.setEffectiveUrl(root.id, outcome.url)
				val to = UrlClass.of(outcome.url).chain
				val note = "unwrapped${if (outcome.note.isNotEmpty()) " (${outcome.note})" else ""}; chain ${urlClass.chain} -> $to"
				ledger.update(root.id, no, AttemptState.REDIRECTED, null, note, clock())
				end("redirected", note)
				return AttemptEnd.Redirect
			}
			is MethodOutcome.Unsupported -> { ledger.update(root.id, no, AttemptState.UNSUPPORTED, null, outcome.reason, clock()); end("unsupported", outcome.reason) }
		}
		return AttemptEnd.Failed
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
			// T9 review M2: a hedged chain belongs beside its own download; after a restart that download goes on alone
			if (wasRunning && rows.any { it.state == AttemptState.RUNNING && it.reason == HEDGE_ROW }) {
				rows.filter { it.state == AttemptState.RUNNING }.forEach { ledger.update(parentId, it.attemptNo, AttemptState.FAILED, null, CANCELLED_FOR_WINNER, clock()) }
				TrailBoard.remove(parentId)
				continue
			}
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
			if (running[id]?.isActive == true || ledger.intentOf(id) in REMOVAL) continue
			// T9 review M2: the app's own download finished or runs again since: nothing to rescue (no second copy)
			if (root.snapshot.status == DownloadSnapshot.COMPLETE || root.snapshot.isRunning) {
				Trace.event { TraceEvent("decision", downloadId = id, result = "not-resumed", reason = "its own download finished or runs") }
				continue
			}
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
	private fun forgetLocked(id: String, why: String = "removed by the user") {
		ledger.forget(id)
		refreshes.remove(id)                                                     // the app reuses download ids
		hedgeWins.remove(id); lateOwnFailed.remove(id)
		TrailBoard.remove(id)
		seen.remove(id)
		Trace.event { TraceEvent("decision", downloadId = id, result = "forgotten", reason = why) }
	}

	/**
	 * Runs [root]'s chain for something the app has no download for (a shared magnet link). The root is not in the
	 * upstream lists; delivered files are listed in the app's finished downloads. false = already running.
	 */
	suspend fun startStandalone(root: HostDownload, fresh: Boolean = false, alreadyTried: Map<String, String> = emptyMap()): Boolean = lock.withLock {
		if (running[root.id]?.isActive == true) return@withLock false
		// review run 25: a shared page starts again each time it is shared (an earlier run that found nothing, or whose
		// file the user deleted, must not answer for it); methods the share already tried are recorded as tried
		if (fresh && ledger.attempts(root.id).isNotEmpty()) forgetLocked(root.id, "shared again")
		var no = (ledger.attempts(root.id).maxOfOrNull { it.attemptNo } ?: 0) + 1
		for ((m, why) in alreadyTried) if (ledger.claim(root.id, no, m, clock())) ledger.update(root.id, no++, AttemptState.FAILED, null, why, clock())
		Trace.event { TraceEvent("decision", downloadId = root.id, chain = UrlClass.of(root.url).chain, result = "standalone") }
		running[root.id] = scope.launch { runChain(root, "SHARED") }
		true
	}

	enum class Standalone { RUNNING, DELIVERED, BEST_SO_FAR, NOTHING }

	/** T9: how a standalone chain (a shared torrent or page) stands (review run 25: an unsure file is listed too) */
	fun standalone(id: String): Standalone = runCatching {
		val rows = ledger.attempts(id)
		when {
			running[id]?.isActive == true || rows.lastOrNull()?.state == AttemptState.CHILD -> Standalone.RUNNING
			rows.any { it.state == AttemptState.DELIVERED } -> Standalone.DELIVERED
			rows.any { it.state == AttemptState.UNSURE && it.childId != null } -> Standalone.BEST_SO_FAR
			else -> Standalone.NOTHING
		}
	}.getOrDefault(Standalone.RUNNING)

	enum class Manual { STARTED, BUSY, ALREADY_DELIVERED, NOTHING_LEFT, NOT_FOUND }

	/**
	 * The user asked for another method ("Try another method"): runs the next method of the chain now, whether or not
	 * the current method has failed (the current download is left alone). A child still downloading is given up as an
	 * attempt.
	 */
	suspend fun tryAnother(downloadId: String): Manual = lock.withLock {
		val rootId = ledger.parentOf(downloadId) ?: downloadId
		val list = try { withContext(hostContext) { host.downloads() } } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
		val root = list.firstOrNull { it.id == rootId } ?: return@withLock Manual.NOT_FOUND
		if (running[rootId]?.isActive == true) return@withLock Manual.BUSY
		val rows = ledger.attempts(rootId)
		if (rows.any { it.state == AttemptState.DELIVERED }) return@withLock Manual.ALREADY_DELIVERED
		val steps = ChainSpec.stepsFor(UrlClass.of(withEffectiveUrl(root).url), config.includeStub)
		if (ChainSpec.nextMethod(steps, tried(rows)) { it in methods && config.methodOn(it) } == null) return@withLock Manual.NOTHING_LEFT   // T9 review M3
		rows.filter { it.state == AttemptState.CHILD }.forEach {
			ledger.update(rootId, it.attemptNo, AttemptState.FAILED, null, "user asked for another method", clock())
		}
		Trace.event { TraceEvent("decision", downloadId = rootId, result = "user-try-another") }
		busy = true
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
		const val MAX_REFRESH = 2
		val REMOVAL = setOf(UserIntent.CANCEL, UserIntent.CLEAR, UserIntent.DELETE)
		val RESTART = setOf(UserIntent.START, UserIntent.RESUME)
		val TEMP_EXT = setOf("part", "tmp", "meta", "ytdl", "bin")
		const val INTERRUPTED = "interrupted (the app was closed)"
		/** T9 review M2: the reason a running row of a hedged chain carries until it ends */
		const val HEDGE_ROW = "running beside the app's own download"
		const val MAX_INTERRUPTIONS = 2
		/** T9: the reason of a race loser's row; such a method still counts as not tried */
		const val CANCELLED_FOR_WINNER = "cancelled: another method seemed to win"
		const val RACE_TICK_MS = 100L
		/** T9: the chain started beside the app's own download (the hedged start), not after its failure */
		const val LATE = "LATE"
		/** T9: the app's own download proved its first bytes (a page of 64 KB is not a video yet) */
		const val OWN_PROOF_BYTES = 64 * 1024L
		val PAUSES_AND_REMOVAL = setOf(UserIntent.PAUSE, UserIntent.RENAME_PAUSE, UserIntent.APP_SHUTDOWN, UserIntent.CANCEL, UserIntent.CLEAR, UserIntent.DELETE)
		/** a resumed download has this long to start before a CLOSE / paused state counts as its failure */
		const val RESUME_GRACE_MS = 90_000L
		/** an unexplained stop must still be there this long after it was first seen (the observer looks every 2 s) */
		const val UNEXPLAINED_CONFIRM_MS = 1_500L
		/** clock slack between the app's start time and a ledger row's start time */
		const val REUSE_SLACK_MS = 2_000L
	}
}
