package org.websnake.vidchain.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One engine run. [argv] starts with the binary (absolute path). */
data class EngineSpec(
	val argv: List<String>,
	val env: Map<String, String> = emptyMap(),
	val workDir: File? = null,
	val timeoutMs: Long = 30 * 60_000L,
)

data class EngineResult(
	val outcome: EngineOutcome,
	val exitCode: Int?,
	val stdoutTail: List<String>,
	val stderrTail: List<String>,
	val durationMs: Long,
)

/**
 * How engines are started: `setsid sh -c '<wrapper>' argv...`. setsid gives every engine its own process group, so a
 * cancel kills the engine AND everything it spawned. The wrapper shell prints its pid (= the group id) first and the
 * engine's exit code last (128 + signal when the engine was killed); a missing exit line means the whole group was
 * killed from outside.
 */
class Launcher(val setsid: String?, val sh: String) {
	fun wrap(argv: List<String>): List<String> = listOfNotNull(setsid, sh, "-c", WRAPPER) + argv

	companion object {
		const val PGID_MARK = "__VIDCHAIN_PGID__"
		const val EXIT_MARK = "__VIDCHAIN_EXIT__"
		private const val WRAPPER = "echo \"$PGID_MARK $$\"; \"\$0\" \"\$@\" </dev/null; rc=\$?; echo \"$EXIT_MARK \$rc\" >&2; exit \$rc"

		fun detect(): Launcher = Launcher(
			setsid = listOf("/system/bin/setsid", "/usr/bin/setsid", "/bin/setsid").firstOrNull { File(it).canExecute() },
			sh = listOf("/system/bin/sh", "/bin/sh").first { File(it).canExecute() },
		)
	}
}

/** Global cap on native engine processes, separate from the user's download slots (Android's phantom-process limit is 32). */
class SlotLimiter(val permits: Int = 4) {
	private val sem = Semaphore(permits)
	val available: Int get() = sem.availablePermits
	suspend fun <T> withSlot(block: suspend () -> T): T = sem.withPermit { block() }

	companion object {
		val GLOBAL = SlotLimiter(4)
	}
}

class ProcessEngineRunner(
	private val launcher: Launcher = Launcher.detect(),
	private val slots: SlotLimiter = SlotLimiter.GLOBAL,
	private val log: (String) -> Unit = {},
	private val tailLines: Int = 64,
	private val killGraceMs: Long = 2_000,
) {
	/**
	 * Runs the engine and returns how it ended. Cancelling the calling coroutine kills the whole process group and
	 * rethrows. Lines are delivered to [onStdout] / [onStderr] as they arrive (on an IO thread).
	 */
	suspend fun run(spec: EngineSpec, onStdout: (String) -> Unit = {}, onStderr: (String) -> Unit = {}): EngineResult = slots.withSlot {
		val started = System.currentTimeMillis()
		val pb = ProcessBuilder(launcher.wrap(spec.argv))
		pb.environment().putAll(spec.env)
		spec.workDir?.let { pb.directory(it) }
		pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
		val process = try {
			withContext(Dispatchers.IO) { pb.start() }
		} catch (e: IOException) {
			return@withSlot EngineResult(EngineOutcome.Unsupported("cannot start: ${e.message}"), null, emptyList(), emptyList(), 0)
		}
		val out = ArrayDeque<String>()
		val err = ArrayDeque<String>()
		val pgid = CompletableDeferred<Int?>()
		var exitLine: Int? = null
		var timedOut = false
		coroutineScope {
			val readers = listOf(
				launch(Dispatchers.IO) {
					process.inputStream.bufferedReader().useLines { lines ->
						for (line in lines) {
							if (!pgid.isCompleted && line.startsWith(Launcher.PGID_MARK)) { pgid.complete(line.substringAfter(' ').trim().toIntOrNull()); continue }
							tail(out, line); runCatching { onStdout(line) }
						}
					}
					pgid.complete(null)
				},
				launch(Dispatchers.IO) {
					process.errorStream.bufferedReader().useLines { lines ->
						for (line in lines) {
							if (line.startsWith(Launcher.EXIT_MARK)) { exitLine = line.substringAfter(' ').trim().toIntOrNull(); continue }
							tail(err, line); runCatching { onStderr(line) }
						}
					}
				},
			)
			try {
				withTimeout(spec.timeoutMs) { runInterruptible(Dispatchers.IO) { process.waitFor() } }
			} catch (e: TimeoutCancellationException) {
				timedOut = true
				withContext(NonCancellable) { killGroup(process, pgid) }
			} catch (e: CancellationException) {
				withContext(NonCancellable) {
					killGroup(process, pgid)
					withTimeoutOrNull(killGraceMs) { readers.forEach { it.join() } }
				}
				log("engine cancelled: ${spec.argv.firstOrNull()?.substringAfterLast('/')}")
				throw e
			}
			// whatever the engine left running in its group goes too
			withContext(NonCancellable) { signalGroup(pgid, ExitCodes.SIGKILL) }
			if (withTimeoutOrNull(killGraceMs) { readers.forEach { it.join() } } == null) readers.forEach { it.cancel() }
		}
		val outcome = ExitCodes.classify(exitLine, cancelledByUs = false, timedOut = timedOut, stderrTail = err.joinToString("\n"))
		val result = EngineResult(outcome, exitLine, out.toList(), err.toList(), System.currentTimeMillis() - started)
		log("engine ${spec.argv.firstOrNull()?.substringAfterLast('/')}: $outcome in ${result.durationMs}ms")
		result
	}

	private fun tail(q: ArrayDeque<String>, line: String) = synchronized(q) {
		q.addLast(line)
		while (q.size > tailLines) q.removeFirst()
	}

	/** TERM to the group, KILL after the grace period, then the direct child as a last resort */
	private suspend fun killGroup(process: Process, pgid: CompletableDeferred<Int?>) {
		signalGroup(pgid, ExitCodes.SIGTERM)
		val exited = withContext(Dispatchers.IO) { process.waitFor(killGraceMs, TimeUnit.MILLISECONDS) }
		signalGroup(pgid, ExitCodes.SIGKILL)
		if (!exited) process.destroyForcibly()
	}

	private suspend fun signalGroup(pgid: CompletableDeferred<Int?>, sig: Int) {
		val id = withTimeoutOrNull(500) { pgid.await() } ?: return
		// with setsid the shell's pid is its group id; without it only that pid is signalled
		val target = if (launcher.setsid != null) "-$id" else "$id"
		withContext(Dispatchers.IO) {
			runCatching {
				ProcessBuilder(launcher.sh, "-c", "kill -$sig $target 2>/dev/null").redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS)
			}
		}
	}
}
