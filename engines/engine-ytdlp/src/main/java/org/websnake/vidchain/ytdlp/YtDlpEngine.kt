package org.websnake.vidchain.ytdlp

import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.EngineSpec
import org.websnake.vidchain.engine.ProcessEngineRunner
import java.io.File

/**
 * yt-dlp as a child process on the library's own Python and yt-dlp (the files youtubedl-android extracts at app start).
 * Every URL goes after "--" so a URL can never be read as an option. Cookies arrive as a private Netscape file.
 */
class YtDlpEngine(
	private val runner: ProcessEngineRunner,
	private val layout: EngineLayout,
	private val ytdlp: File = layout.ytdlp,
	private val python: File = layout.python,
) {
	data class Options(
		val userAgent: String? = null,
		val referer: String? = null,
		val cookiesFile: File? = null,
		val extraArgs: List<String> = emptyList(),
	)

	enum class Problem { UNSUPPORTED, UNAVAILABLE, LOGIN, GEO, FORMAT, HTTP, NETWORK, NOT_READY, CRASHED, KILLED, OTHER }

	sealed class Result<out T> {
		data class Ok<T>(val value: T) : Result<T>()
		data class Failed(val problem: Problem, val reason: String, val outcome: EngineOutcome? = null) : Result<Nothing>()
	}

	val ready: Boolean get() = python.isFile && ytdlp.isFile && layout.pythonReady

	suspend fun info(url: String, o: Options = Options(), timeoutMs: Long = 180_000): Result<YtDlpInfo> {
		if (!ready) return Result.Failed(Problem.NOT_READY, "yt-dlp runtime not extracted yet")
		val r = runner.run(EngineSpec(argv(o, listOf("--dump-single-json", "--no-playlist", "--no-progress")) + listOf("--", url), layout.env(), timeoutMs = timeoutMs))
		if (r.outcome != EngineOutcome.Success) return failure(r.outcome, r.stderrTail)
		val json = r.stdoutTail.lastOrNull { it.trimStart().startsWith("{") } ?: return Result.Failed(Problem.OTHER, "no JSON from yt-dlp")
		return runCatching { Result.Ok(YtDlpInfo.parse(json)) }.getOrElse { Result.Failed(Problem.OTHER, "unreadable JSON: ${it.javaClass.simpleName}") }
	}

	/** downloads [url] with format [selector] to [dir]/[base].<ext>; returns the finished file */
	suspend fun download(url: String, selector: String, dir: File, base: String, o: Options = Options(),
						 timeoutMs: Long = 4 * 3_600_000L, onProgress: (Double) -> Unit = {}): Result<File> {
		if (!ready) return Result.Failed(Problem.NOT_READY, "yt-dlp runtime not extracted yet")
		dir.mkdirs()
		val args = listOf(
			"-f", selector, "--no-playlist", "--newline", "--no-mtime", "--ffmpeg-location", layout.ffmpeg.absolutePath,
			"-o", File(dir, "$base.%(ext)s").absolutePath, "--print", "after_move:$PATH_MARK%(filepath)s",
		)
		var finalPath: String? = null
		val r = runner.run(EngineSpec(argv(o, args) + listOf("--", url), layout.env(), timeoutMs = timeoutMs, stallMs = STALL_MS, watchDir = dir), onStdout = { line ->
			if (line.startsWith(PATH_MARK)) finalPath = line.removePrefix(PATH_MARK).trim()
			else progress(line)?.let(onProgress)
		})
		if (r.outcome != EngineOutcome.Success) return failure(r.outcome, r.stderrTail)
		val f = finalPath?.let(::File)?.takeIf { it.isFile }
			?: dir.listFiles { x -> x.name.startsWith("$base.") && !x.name.endsWith(".part") && !x.name.endsWith(".ytdl") }?.maxByOrNull { it.lastModified() }
			?: return Result.Failed(Problem.OTHER, "yt-dlp finished but left no file")
		return Result.Ok(f)
	}

	internal fun argv(o: Options, args: List<String>): List<String> = buildList {
		add(python.absolutePath); add(ytdlp.absolutePath)
		addAll(listOf("--ignore-config", "--no-warnings", "--socket-timeout", "20", "--retries", "3"))
		o.userAgent?.let { add("--user-agent"); add(it) }
		o.referer?.let { add("--referer"); add(it) }
		o.cookiesFile?.let { add("--cookies"); add(it.absolutePath) }
		addAll(o.extraArgs)
		addAll(args)
	}

	companion object {
		const val PATH_MARK = "__VIDCHAIN_FILE__"
		/** no progress line and no byte for this long: the download is stuck (the 4 h cap stays behind it) */
		const val STALL_MS = 180_000L
		private val PROGRESS = Regex("""^\[download]\s+(\d+(?:\.\d+)?)%""")

		fun progress(line: String): Double? = PROGRESS.find(line.trim())?.groupValues?.get(1)?.toDoubleOrNull()

		fun failure(outcome: EngineOutcome, stderr: List<String>): Result.Failed {
			val err = stderr.lastOrNull { it.startsWith("ERROR:") } ?: stderr.lastOrNull { it.isNotBlank() } ?: outcome.toString()
			val e = err.lowercase()
			val problem = when {
				outcome is EngineOutcome.Crashed -> Problem.CRASHED
				outcome is EngineOutcome.OsKilled -> Problem.KILLED
				outcome !is EngineOutcome.Failed && outcome !is EngineOutcome.Unsupported -> Problem.OTHER
				"unsupported url" in e -> Problem.UNSUPPORTED
				"sign in" in e || "login" in e || "log in" in e || "cookies" in e && "authentication" in e || "members-only" in e -> Problem.LOGIN
				"not available in your country" in e || "geo" in e && "restrict" in e -> Problem.GEO
				"requested format is not available" in e || "no video formats" in e -> Problem.FORMAT
				"private video" in e || "video unavailable" in e || "removed" in e || "does not exist" in e || "404" in e -> Problem.UNAVAILABLE
				"http error" in e -> Problem.HTTP
				"timed out" in e || "connection" in e || "network" in e || "resolve" in e -> Problem.NETWORK
				else -> Problem.OTHER
			}
			return Result.Failed(problem, (if (outcome is EngineOutcome.Failed || outcome is EngineOutcome.Unsupported) err else "$outcome: $err").take(300), outcome)
		}
	}
}
