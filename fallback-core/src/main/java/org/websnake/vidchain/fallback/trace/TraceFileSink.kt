package org.websnake.vidchain.fallback.trace

import java.io.File

/**
 * Size-capped, rotating trace file: [dir]/trace.log, rotated to trace.1.log ... trace.<maxFiles-1>.log.
 * The owner opens or shares it from the app (T1.10); it never leaves the phone otherwise.
 */
class TraceFileSink(
	private val dir: File,
	private val maxBytes: Long = TraceConfig.MAX_FILE_BYTES,
	private val maxFiles: Int = TraceConfig.MAX_FILES,
) : TraceSink {

	val current: File get() = File(dir, "trace.log")

	@Synchronized
	override fun write(line: String) {
		if (!dir.exists() && !dir.mkdirs()) return
		val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
		if (current.length() + bytes.size > maxBytes) rotate()
		current.appendBytes(bytes)
	}

	/** all trace files, newest first */
	fun files(): List<File> = (listOf(current) + (1 until maxFiles).map { File(dir, "trace.$it.log") }).filter { it.exists() }

	private fun rotate() {
		File(dir, "trace.${maxFiles - 1}.log").delete()
		for (i in maxFiles - 2 downTo 1) {
			val f = File(dir, "trace.$i.log")
			if (f.exists()) f.renameTo(File(dir, "trace.${i + 1}.log"))
		}
		if (current.exists()) current.renameTo(File(dir, "trace.1.log"))
	}
}
