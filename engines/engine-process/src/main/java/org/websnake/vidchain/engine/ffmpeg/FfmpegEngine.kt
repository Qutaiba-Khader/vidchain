package org.websnake.vidchain.engine.ffmpeg

import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.EngineSpec
import org.websnake.vidchain.engine.ProcessEngineRunner
import java.io.File

/**
 * Method F's engine (T3.4): the libffmpeg.so the library ships, run through the engine runner. Reads HLS (also
 * AES-128), DASH or plain media over HTTP with the page's headers, joins separate video and audio, and writes an MP4
 * by stream copy (no re-encoding). Progress comes from `-progress pipe:1`.
 */
class FfmpegEngine(
	private val runner: ProcessEngineRunner,
	private val layout: EngineLayout,
	private val ffmpeg: File = layout.ffmpeg,
) {
	data class Input(val url: String, val userAgent: String? = null, val referer: String? = null, val headers: Map<String, String> = emptyMap())
	data class Caps(val protocols: Set<String>, val demuxers: Set<String>) {
		fun can(needs: Collection<String>) = needs.all { it in protocols || it in demuxers }
	}
	sealed class Result {
		data class Ok(val file: File) : Result()
		data class Failed(val reason: String, val outcome: EngineOutcome?) : Result()
	}

	val ready: Boolean get() = ffmpeg.isFile

	/** what this build can read (dumped once per build by the caller's probe cache) */
	suspend fun capabilities(): Caps? {
		// the full listing: the runner only keeps the last lines of a run, and -demuxers prints ~350
		val protocols = java.util.Collections.synchronizedList(ArrayList<String>())
		val demuxers = java.util.Collections.synchronizedList(ArrayList<String>())
		val p = runner.run(EngineSpec(listOf(ffmpeg.absolutePath, "-hide_banner", "-protocols"), layout.env(), timeoutMs = 20_000), onStdout = { protocols += it })
		val d = runner.run(EngineSpec(listOf(ffmpeg.absolutePath, "-hide_banner", "-demuxers"), layout.env(), timeoutMs = 20_000), onStdout = { demuxers += it })
		if (p.outcome != EngineOutcome.Success || d.outcome != EngineOutcome.Success) return null
		return Caps(parseProtocols(protocols.toList()), parseDemuxers(demuxers.toList()))
	}

	/** downloads/remuxes [inputs] (one stream, or video + audio) into [out] (MP4, stream copy) */
	suspend fun save(inputs: List<Input>, out: File, durationMs: Long? = null, timeoutMs: Long = 4 * 3_600_000L,
					 format: String = "mp4", onProgress: (Double) -> Unit = {}): Result {
		require(inputs.size in 1..2) { "one input, or video + audio" }
		if (!ready) return Result.Failed("ffmpeg not found", null)
		out.parentFile?.mkdirs(); out.delete()
		val r = runner.run(EngineSpec(argv(inputs, out, format), layout.env(), timeoutMs = timeoutMs), onStdout = { line ->
			progressMs(line)?.let { ms -> durationMs?.takeIf { it > 0 }?.let { onProgress((ms * 100.0 / it).coerceIn(0.0, 100.0)) } }
		})
		if (r.outcome != EngineOutcome.Success) {
			out.delete()
			val err = r.stderrTail.lastOrNull { it.isNotBlank() } ?: r.outcome.toString()
			return Result.Failed("ffmpeg ${r.outcome}: ${err.take(240)}", r.outcome)
		}
		return if (out.isFile && out.length() > 0) Result.Ok(out) else Result.Failed("ffmpeg wrote no file", r.outcome)
	}

	internal fun argv(inputs: List<Input>, out: File, format: String = "mp4"): List<String> = buildList {
		add(ffmpeg.absolutePath)
		addAll(listOf("-hide_banner", "-nostdin", "-y", "-loglevel", "error"))
		for (i in inputs) {
			val http = i.url.startsWith("http", ignoreCase = true)
			if (http) {
				i.userAgent?.let { add("-user_agent"); add(it) }
				i.referer?.let { add("-referer"); add(it) }
				val extra = i.headers.filterKeys { k -> !k.equals("User-Agent", true) && !k.equals("Referer", true) && !k.equals("Cookie", true) }
				if (extra.isNotEmpty()) { add("-headers"); add(extra.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }) }
				addAll(listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5"))
			}
			add("-i"); add(i.url)
		}
		if (inputs.size == 2) addAll(listOf("-map", "0:v:0", "-map", "1:a:0"))
		else addAll(listOf("-map", "0:v:0?", "-map", "0:a:0?"))
		addAll(listOf("-c", "copy"))
		if (format == "mp4") addAll(listOf("-movflags", "+faststart"))
		addAll(listOf("-progress", "pipe:1", "-f", format, out.absolutePath))
	}

	companion object {
		/** the MP4 muxer refused the streams as they are (e.g. MPEG-4 Part 2 in TS has no global header): MPEG-TS takes them */
		fun needsOtherContainer(reason: String) = "could not write header" in reason.lowercase() || "codec not currently supported in container" in reason.lowercase()

		fun parseProtocols(lines: List<String>): Set<String> = lines.map { it.trim() }
			.filter { it.isNotEmpty() && !it.endsWith(":") && !it.startsWith("Supported") }.toSet()

		/** "-protocols" lists names one per line; "-demuxers" lines look like " D  hls   Apple HTTP Live Streaming" */
		fun parseDemuxers(lines: List<String>): Set<String> = lines.mapNotNull { l ->
			Regex("""^\s*D[ .A-Za-z]?\s+([A-Za-z0-9_][A-Za-z0-9_,]*)\s""").find(l)?.groupValues?.get(1)
		}.flatMap { it.split(',') }.toSet()

		fun progressMs(line: String): Long? = when {
			line.startsWith("out_time_us=") -> line.substringAfter('=').toLongOrNull()?.div(1000)
			line.startsWith("out_time_ms=") -> line.substringAfter('=').toLongOrNull()?.div(1000)   // also microseconds, despite the name
			else -> null
		}

		/** what reading [url] needs from this build */
		fun needs(url: String): List<String> {
			val l = url.lowercase().substringBefore('?')
			val proto = if (url.startsWith("https", true)) "https" else if (url.startsWith("http", true)) "http" else "file"
			return when {
				l.endsWith(".m3u8") -> listOf(proto, "hls", "crypto")
				l.endsWith(".mpd") -> listOf(proto, "dash")
				else -> listOf(proto)
			}
		}
	}
}
