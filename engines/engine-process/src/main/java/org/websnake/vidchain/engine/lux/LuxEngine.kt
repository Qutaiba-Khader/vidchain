package org.websnake.vidchain.engine.lux

import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.EngineSpec
import org.websnake.vidchain.engine.ProcessEngineRunner
import java.io.File
import java.nio.file.Files

/**
 * Method X's engine (T5.6): lux (github.com/iawia002/lux, MIT), built by natives.yml for arm64-v8a and x86_64 and
 * shipped as liblux.so. Go with the system resolver (cgo) and Android's CA store. Merging needs ffmpeg, which lux
 * looks for as ./ffmpeg in its working directory: that is a private folder holding a link to the library's ffmpeg.
 * Cookies are never passed: lux sends its cookie to every host it talks to.
 */
class LuxEngine(
	private val runner: ProcessEngineRunner,
	private val layout: EngineLayout,
	private val lux: File = layout.lux,
) {
	data class Job(
		val url: String,
		val dir: File,
		val outName: String,
		val userAgent: String? = null,
		val referer: String? = null,
		val audioOnly: Boolean = false,
	)

	sealed class Result {
		data class Ok(val files: List<File>) : Result()
		data class Failed(val reason: String, val outcome: EngineOutcome?) : Result()
	}

	val ready: Boolean get() = lux.isFile

	suspend fun run(job: Job, timeoutMs: Long = 6 * 3_600_000L): Result {
		if (!ready) return Result.Failed("lux not on this phone", null)
		job.dir.mkdirs()
		val work = workDir()
		val out = ArrayList<String>()
		val r = runner.run(EngineSpec(argv(job), env(), workDir = work, timeoutMs = timeoutMs, stallMs = STALL_MS, watchDir = job.dir), onStdout = { l -> synchronized(out) { out += l; if (out.size > 60) out.removeAt(0) } })
		if (r.outcome != EngineOutcome.Success) {
			val reason = synchronized(out) { error(out) } ?: r.stderrTail.lastOrNull { it.isNotBlank() }?.take(200) ?: r.outcome.toString()
			return Result.Failed(reason, r.outcome)
		}
		val files = job.dir.walkTopDown().filter { it.isFile && !it.name.endsWith(".download") && it.name != ".nomedia" }.sortedByDescending { it.length() }.toList()
		return if (files.isEmpty()) Result.Failed("lux finished but wrote no file", r.outcome) else Result.Ok(files)
	}

	fun argv(job: Job): List<String> = buildList {
		add(lux.absolutePath)
		addAll(listOf("-s", "-o", job.dir.absolutePath, "-O", job.outName, "--retry", "3"))
		job.userAgent?.let { add("-u"); add(it) }
		job.referer?.let { add("-r"); add(it) }
		if (job.audioOnly) add("-ao")
		add("--"); add(job.url)
	}

	/** the library's environment, minus its Python CA file: Go reads Android's own CA store */
	fun env(): Map<String, String> = layout.env().toMutableMap().apply {
		remove("SSL_CERT_FILE"); put("HOME", layout.cacheDir.absolutePath)
	}

	/** private working directory with ./ffmpeg -> the library's ffmpeg (lux's merge step looks there first) */
	fun workDir(): File {
		val dir = File(layout.cacheDir, "vidchain-lux").apply { mkdirs() }
		val link = File(dir, "ffmpeg").toPath()
		val target = layout.ffmpeg.toPath()
		runCatching { if (!Files.isSymbolicLink(link) || Files.readSymbolicLink(link) != target) { Files.deleteIfExists(link); Files.createSymbolicLink(link, target) } }
		return dir
	}

	companion object {
		/** no line and no byte for this long: lux is stuck (the 6 h cap stays behind it) */
		const val STALL_MS = 180_000L

		/** lux prints "Downloading <url> error:" on stdout, then the error and its stack */
		fun error(stdout: List<String>): String? {
			val i = stdout.indexOfLast { it.startsWith("Downloading ") && it.trimEnd().endsWith("error:") }
			return stdout.getOrNull(i + 1)?.takeIf { i >= 0 && it.isNotBlank() }?.trim()?.take(200)
		}

		/** extractors registered at the pinned commit (natives.lock); anything else goes to lux's generic file fetcher */
		val EXTRACTORS = setOf("163", "acfun", "b23", "bcy", "bilibili", "bitchute", "douyin", "douyu", "eporner", "facebook", "geekbang",
			"haokan", "hupu", "huya", "iesdouyin", "instagram", "iq", "iqiyi", "ixigua", "kuaishou", "mgtv", "miaopai", "odysee",
			"pinterest", "pixivision", "pornhub", "qq", "reddit", "rumble", "streamta", "streamtape", "tangdou", "threads", "tiktok",
			"toutiao", "tumblr", "twitter", "udn", "vimeo", "vk", "weibo", "xiaohongshu", "ximalaya", "xinpianchang", "xvideos",
			"yinyuetai", "youku", "youtu", "youtube", "zhihu", "zing", "zingmp3")
		private val AUTHORITY = Regex("""^[A-Za-z][A-Za-z0-9+.-]*://([^/?#]*)""")
		private val DOMAIN = Regex("""([a-z0-9][-a-z0-9]{0,62})\.(com\.cn|com\.hk|cn|com|net|edu|gov|biz|org|info|pro|name|xxx|xyz|be|me|top|cc|tv|tt|vn)""")

		/** lux's own choice of extractor for a URL (extractors.Extract + utils.Domain), or null for its generic fetcher */
		fun extractorFor(url: String): String? {
			// exactly what lux matches: Go's url.Host (case kept, port kept)
			val host = AUTHORITY.find(url.trim())?.groupValues?.get(1)?.substringAfterLast('@')?.takeIf { it.isNotEmpty() } ?: return null
			val name = when (host) { "haokan.baidu.com" -> "haokan"; "xhslink.com" -> "xiaohongshu"; else -> DOMAIN.find(host)?.groupValues?.get(1) }
			return name?.takeIf { it in EXTRACTORS }
		}
	}
}
