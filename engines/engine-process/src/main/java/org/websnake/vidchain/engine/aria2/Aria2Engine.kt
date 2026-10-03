package org.websnake.vidchain.engine.aria2

import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.EngineSpec
import org.websnake.vidchain.engine.ProcessEngineRunner
import java.io.File

/**
 * Method A's engine (T5.2): the aria2c of youtubedl-android's aria2c module (BitTorrent and Metalink built in),
 * unpacked by Aria2c.init() next to the library's other runtimes. Plain files with several connections, magnet
 * links, .torrent and Metalink files; pieces are checked by their hashes; no seeding after the download completes.
 */
class Aria2Engine(
	private val runner: ProcessEngineRunner,
	private val layout: EngineLayout,
	private val aria2c: File = layout.aria2c,
) {
	data class Job(
		val uri: String,
		val dir: File,
		val outName: String? = null,          // plain files only; torrents keep their own names
		val userAgent: String? = null,
		val headers: Map<String, String> = emptyMap(),
		val dhtFile: File? = null,
		val stallSeconds: Int = 600,
	)

	sealed class Result {
		data class Ok(val files: List<File>) : Result()
		data class Failed(val code: Int?, val reason: String, val outcome: EngineOutcome?) : Result()
	}

	val ready: Boolean get() = aria2c.isFile && layout.aria2cHome.isDirectory

	suspend fun run(job: Job, timeoutMs: Long = 24 * 3_600_000L, onProgress: (Double) -> Unit = {}): Result {
		if (!ready) return Result.Failed(null, "aria2c not unpacked yet", null)
		job.dir.mkdirs()
		val r = runner.run(EngineSpec(argv(job), layout.env(), workDir = job.dir, timeoutMs = timeoutMs), onStdout = { l -> progress(l)?.let(onProgress) })
		if (r.outcome != EngineOutcome.Success) {
			val code = r.exitCode
			return Result.Failed(code, "${meaning(code)}: ${r.stderrTail.lastOrNull { it.isNotBlank() }?.take(200) ?: r.outcome}", r.outcome)
		}
		val files = job.dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".aria2") && it.name != ".nomedia" }.sortedByDescending { it.length() }.toList()
		return if (files.isEmpty()) Result.Failed(0, "aria2c finished but wrote no file", r.outcome) else Result.Ok(files)
	}

	fun argv(job: Job): List<String> = buildList {
		add(aria2c.absolutePath)
		addAll(listOf(
			"--ca-certificate=${layout.certFile.absolutePath}", "--check-certificate=true", "--async-dns=false",
			"--dir=${job.dir.absolutePath}", "--console-log-level=warn", "--summary-interval=1", "--show-console-readout=true",
			"--file-allocation=none", "--allow-overwrite=true", "--auto-file-renaming=false", "--check-integrity=true",
			"--max-connection-per-server=4", "--split=4", "--min-split-size=1M", "--max-tries=5", "--retry-wait=3",
			"--follow-torrent=mem", "--follow-metalink=mem", "--seed-time=0", "--seed-ratio=0.0", "--bt-stop-timeout=${job.stallSeconds}",
			"--enable-dht=true", "--bt-save-metadata=false", "--rpc-save-upload-metadata=false",
		))
		job.dhtFile?.let { add("--dht-file-path=${it.absolutePath}") }
		job.userAgent?.let { add("--user-agent=$it") }
		job.headers.filterKeys { !it.equals("Cookie", true) && !it.equals("User-Agent", true) }.forEach { (k, v) -> add("--header=$k: $v") }
		job.outName?.let { add("--out=$it") }
		add("--"); add(job.uri)
	}

	companion object {
		private val PROGRESS = Regex("""\[#\w+\s+[\d.]+\w*/[\d.]+\w*\((\d+)%\)""")
		fun progress(line: String): Double? = PROGRESS.find(line)?.groupValues?.get(1)?.toDoubleOrNull()

		/** aria2c's documented exit codes */
		fun meaning(code: Int?): String = when (code) {
			null -> "stopped"
			0 -> "ok"; 1 -> "unknown error"; 2 -> "timed out"; 3 -> "resource not found"; 4 -> "too many 'not found'"
			5 -> "too slow (lowest speed limit)"; 6 -> "network problem"; 7 -> "unfinished downloads (stopped)"
			8 -> "server cannot resume"; 9 -> "not enough disk space"; 13 -> "file already exists"
			16 -> "could not create file"; 19 -> "name resolution failed"; 22 -> "bad HTTP response header"
			23 -> "too many redirects"; 24 -> "HTTP authorization failed"; 26 -> "torrent/metalink parse error"
			27 -> "magnet link parse error"; 28 -> "bad option"; 30 -> "invalid metalink/torrent"
			else -> "aria2c exit $code"
		}
	}
}
