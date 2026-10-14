package org.websnake.vidchain.fallback.race

/**
 * T9 decision 1 (owner, 2026-10-14; adhd design): the methods run in batches instead of one at a time.
 *
 * - **Batches by weight.** A batch holds at most [Config.batchSize] methods, or up to [Config.maxLight] when all of
 *   them are light; at most one heavy method (a yt-dlp-style process or a WebView) per batch, so a process and a
 *   WebView never run together on a 4 GB phone. A heavy method that does not fit waits for the next batch.
 * - **Staggered start.** The best-ranked method of a batch starts alone; the next ones join [Config.staggerMs] apart,
 *   and only while no candidate is waiting for its proof or proven.
 * - **Proven success.** A method's candidate counts only when the shared probe proved it ([onProof]); only then are the
 *   others cancelled. A failed proof counts as that method's failure.
 * - **Losers really stop.** A cancelled method that has not stopped after [Config.killGraceMs] is killed (process
 *   tree / WebView); a method that runs past its time limit is cancelled the same way.
 * Pure state machine with explicit time (JVM-tested with simulated time, BatchPlannerTest). The caller performs the
 * returned [Action]s and reports what happened.
 */
class BatchPlanner(steps: List<Step>, private val cfg: Config = Config(), startMs: Long) {
	init { require(cfg.batchSize >= 1) { "batchSize must be at least 1" } }   // T9 review: 0 recursed for ever
	enum class Weight { LIGHT, HEAVY }

	data class Step(val id: String, val weight: Weight, val limitMs: Long = 120_000L)

	data class Config(
		val batchSize: Int = 3,
		val maxLight: Int = 5,
		val staggerMs: Long = 400L,
		val killGraceMs: Long = 5_000L,
	)

	sealed class Action {
		data class Start(val id: String) : Action()
		data class Cancel(val id: String, val why: String) : Action()
		data class Kill(val id: String) : Action()
		data class Probe(val id: String) : Action()
		data class Win(val id: String) : Action()
		object Exhausted : Action()
	}

	companion object {
		/** how many batches [steps] make (for the status text "batch 1/3") */
		fun batchesFor(steps: List<Step>, cfg: Config): Int {
			val p = BatchPlanner(steps, cfg, 0L)
			var n = 0
			while (p.batch.isNotEmpty()) { n++; p.queue.let { if (it.isEmpty()) return n }; p.formBatch(0L) }
			return n
		}
	}

	private enum class State { WAITING, RUNNING, PROBING, DONE, CANCELLED, STOPPED, WON }

	private class Slot(val step: Step) {
		var state = State.WAITING
		var startAt = Long.MAX_VALUE
		var startedAt = 0L
		var cancelledAt = 0L
	}

	private val queue = ArrayDeque(steps)
	private var batch: List<Slot> = emptyList()
	private var batchNo = 0
	private var finished = false
	val batches: Int get() = batchNo

	init { formBatch(startMs) }

	/** the ids of the current batch (for the status text), in their order */
	fun batchIds(): List<String> = batch.map { it.step.id }

	/** the methods running now (started, not stopped) */
	fun running(): List<String> = batch.filter { it.state == State.RUNNING || it.state == State.PROBING }.map { it.step.id }

	private fun formBatch(now: Long) {
		val picked = ArrayList<Step>()
		val deferred = ArrayList<Step>()
		while (queue.isNotEmpty()) {
			val s = queue.first()
			val heavyIn = picked.any { it.weight == Weight.HEAVY }
			if (s.weight == Weight.HEAVY && heavyIn) { deferred += queue.removeFirst(); continue }  // waits for the next batch
			val canTake = picked.size < cfg.batchSize || (!heavyIn && s.weight == Weight.LIGHT && picked.size < cfg.maxLight)
			if (!canTake) break
			picked += queue.removeFirst()
		}
		deferred.asReversed().forEach { queue.addFirst(it) }
		if (picked.isEmpty()) { batch = emptyList(); return }
		batchNo++
		batch = picked.mapIndexed { i, s -> Slot(s).apply { startAt = now + i * cfg.staggerMs } }
	}

	private fun anyResult() = batch.any { it.state == State.PROBING || it.state == State.WON }

	/** what to do at [now]: starts that are due, time limits, kills, the next batch, or the end */
	fun tick(now: Long): List<Action> {
		if (finished) return emptyList()
		val out = ArrayList<Action>()
		for (s in batch) {
			when (s.state) {
				State.WAITING -> if (now >= s.startAt && (s === batch.first() || !anyResult())) {
					s.state = State.RUNNING; s.startedAt = now; out += Action.Start(s.step.id)
				}
				State.RUNNING -> if (now - s.startedAt >= s.step.limitMs) {
					s.state = State.CANCELLED; s.cancelledAt = now; out += Action.Cancel(s.step.id, "time limit")
				}
				State.CANCELLED -> if (now - s.cancelledAt >= cfg.killGraceMs) { s.state = State.STOPPED; out += Action.Kill(s.step.id) }
				else -> Unit
			}
		}
		// a batch is over when every member ended (or never started because a candidate was proven)
		if (batch.none { it.state == State.WON } && batch.all { it.state == State.DONE || it.state == State.STOPPED || (it.state == State.WAITING && anyResult()) }) {
			if (queue.isEmpty()) {
				if (batch.none { it.state == State.CANCELLED }) { finished = true; out += Action.Exhausted }
			} else {
				formBatch(now)
				out += tick(now)
			}
		}
		return out
	}

	/** [id] handed back a candidate: probe it (the others keep running; the next ones wait) */
	fun onCandidate(id: String, now: Long): List<Action> {
		val s = slot(id) ?: return emptyList()
		if (s.state != State.RUNNING) return emptyList()
		s.state = State.PROBING
		return listOf(Action.Probe(id)) + tick(now)
	}

	/** the probe of [id]'s candidate ended: proven wins and cancels the rest; not proven counts as its failure */
	fun onProof(id: String, proven: Boolean, now: Long): List<Action> {
		val s = slot(id) ?: return emptyList()
		if (s.state != State.PROBING || finished) return emptyList()
		if (!proven) { s.state = State.DONE; return tick(now) }
		s.state = State.WON
		finished = true
		val out = arrayListOf<Action>(Action.Win(id))
		for (o in batch) if (o !== s && (o.state == State.RUNNING || o.state == State.PROBING)) {
			o.state = State.CANCELLED; o.cancelledAt = now; out += Action.Cancel(o.step.id, "another method won")
		}
		for (o in batch) if (o.state == State.WAITING) o.state = State.DONE
		queue.clear()
		return out
	}

	/** [id] ended without a candidate (failed, unsupported, or stopped after a cancel) */
	fun onEnded(id: String, now: Long): List<Action> {
		val s = slot(id) ?: return emptyList()
		when (s.state) {
			State.RUNNING, State.PROBING -> s.state = State.DONE
			State.CANCELLED -> s.state = State.STOPPED
			else -> return emptyList()
		}
		return if (finished) emptyList() else tick(now)
	}

	/** after a win: cancelled losers still to be killed */
	fun reap(now: Long): List<Action> = batch.filter { it.state == State.CANCELLED && now - it.cancelledAt >= cfg.killGraceMs }
		.map { it.state = State.STOPPED; Action.Kill(it.step.id) }

	private fun slot(id: String) = batch.firstOrNull { it.step.id == id }
}
