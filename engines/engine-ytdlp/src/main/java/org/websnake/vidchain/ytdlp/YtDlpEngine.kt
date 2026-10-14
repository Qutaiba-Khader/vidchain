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

	suspend fun info(url: String, o: Options = Options(), timeoutMs: Long = 180_000): Result<YtDlpInfo> =
		when (val r = infoWithJson(url, o, timeoutMs)) {
			is Result.Ok -> Result.Ok(r.value.first)
			is Result.Failed -> r
		}

	/**
	 * T9 decision 2: the parsed info and yt-dlp's whole JSON (for `--load-info-json` at download time). The JSON can
	 * carry cookies and request headers: the caller strips them before keeping it ([YtDlpInfo.stripSecrets]) and
	 * never logs it.
	 */
	suspend fun infoWithJson(url: String, o: Options = Options(), timeoutMs: Long = 180_000): Result<Pair<YtDlpInfo, String>> {
		if (!ready) return Result.Failed(Problem.NOT_READY, "yt-dlp runtime not extracted yet")
		val r = runner.run(EngineSpec(argv(o.forUrl(url), listOf("--dump-single-json", "--no-playlist", "--no-progress")) + listOf("--", url), layout.env(), timeoutMs = timeoutMs))
		if (r.outcome != EngineOutcome.Success) return failure(r.outcome, r.stderrTail)
		val json = r.stdoutTail.lastOrNull { it.trimStart().startsWith("{") } ?: return Result.Failed(Problem.OTHER, "no JSON from yt-dlp")
		return runCatching { Result.Ok(YtDlpInfo.parse(json) to json) }.getOrElse { Result.Failed(Problem.OTHER, "unreadable JSON: ${it.javaClass.simpleName}") }
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
		val r = runner.run(EngineSpec(argv(o.forUrl(url), args) + listOf("--", url), layout.env(), timeoutMs = timeoutMs, stallMs = STALL_MS, watchDir = dir), onStdout = { line ->
			if (line.startsWith(PATH_MARK)) finalPath = line.removePrefix(PATH_MARK).trim()
			else progress(line)?.let(onProgress)
		})
		if (r.outcome != EngineOutcome.Success) return failure(r.outcome, r.stderrTail)
		val f = finalPath?.let(::File)?.takeIf { it.isFile }
			?: dir.listFiles { x -> x.name.startsWith("$base.") && !x.name.endsWith(".part") && !x.name.endsWith(".ytdl") }?.maxByOrNull { it.lastModified() }
			?: return Result.Failed(Problem.OTHER, "yt-dlp finished but left no file")
		return Result.Ok(f)
	}

	/**
	 * T8.11: current yt-dlp needs a JavaScript runtime for YouTube; the library's QuickJS is offered when this yt-dlp
	 * build knows the option (the bundled one may be older). Asked once per yt-dlp file; [] when not possible.
	 */
	suspend fun jsRuntimeArgs(): List<String> {
		val qjs = layout.quickJs.takeIf { it.isFile } ?: return emptyList()
		if (!ready) return emptyList()
		val key = "${ytdlp.absolutePath}:${ytdlp.length()}:${ytdlp.lastModified()}"
		val known = JS_RUNTIME_SUPPORT[key] ?: run {
			val probe = listOf(python.absolutePath, ytdlp.absolutePath, "--ignore-config", "--js-runtimes", "quickjs:${qjs.absolutePath}", "--version")
			val r = runner.run(EngineSpec(probe, layout.env(), timeoutMs = 60_000))
			// only a clear answer is remembered: yes, or yt-dlp's own "no such option" exit; a kill or time-out is asked again
			when (r.outcome) {
				EngineOutcome.Success -> true.also { JS_RUNTIME_SUPPORT[key] = it }
				is EngineOutcome.Failed -> false.also { JS_RUNTIME_SUPPORT[key] = it }
				else -> false
			}
		}
		return if (known) listOf("--js-runtimes", "quickjs:${qjs.absolutePath}") else emptyList()
	}

	/**
	 * T9 (owner's Facebook reel, 2026-10-14): with a phone browser's User-Agent Facebook serves its mobile page, and
	 * yt-dlp's Facebook extractor fails on it ("Cannot parse data"; a share link falls back to "generic" with no format).
	 * yt-dlp's own User-Agent works: Facebook addresses get it.
	 */
	internal fun Options.forUrl(url: String): Options =
		if (userAgent != null && MOBILE.containsMatchIn(userAgent) && FACEBOOK.containsMatchIn(url)) copy(userAgent = null) else this

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
		private val JS_RUNTIME_SUPPORT = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
		private val MOBILE = Regex("""\b(Mobile|Android|iPhone|iPad)\b""")
		private val FACEBOOK = Regex("""^https?://([a-z0-9-]+\.)*(facebook\.com|fb\.watch|fb\.com|fb\.gg)(/|:|$)""", RegexOption.IGNORE_CASE)
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
