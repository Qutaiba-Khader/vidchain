package org.websnake.vidchain.engine.python

import org.websnake.vidchain.engine.EngineLayout
import org.websnake.vidchain.engine.EngineOutcome
import org.websnake.vidchain.engine.EngineSpec
import org.websnake.vidchain.engine.ProcessEngineRunner
import java.io.File
import java.util.Collections

/**
 * The aio_engine launcher (T5.3) on the library's bundled Python: VidChain's Python engines (gallery-dl now, you-get
 * and Streamlink later) come as one zip of pure wheels on PYTHONPATH. requests/urllib3 use the library's CA bundle.
 */
class PythonEngine(
	private val runner: ProcessEngineRunner,
	private val layout: EngineLayout,
	private val python: File = layout.python,
	private val requireLibraryRuntime: Boolean = true,
) {
	data class Run(val outcome: EngineOutcome, val exitCode: Int?, val lines: List<String>, val stderrTail: List<String>)

	val ready: Boolean get() = python.isFile && (!requireLibraryRuntime || layout.pythonReady)

	suspend fun aio(args: List<String>, zip: File, timeoutMs: Long = 2 * 3_600_000L, onLine: (String) -> Unit = {}): Run {
		val lines = Collections.synchronizedList(ArrayList<String>())
		val env = layout.env(extraPythonPath = listOf(zip)).toMutableMap()
		if (!requireLibraryRuntime) { env.remove("PYTHONHOME"); env.remove("LD_LIBRARY_PATH") }   // a host Python in tests
		env["REQUESTS_CA_BUNDLE"] = env["SSL_CERT_FILE"] ?: layout.certFile.absolutePath
		env["PYTHONDONTWRITEBYTECODE"] = "1"
		env["PYTHONIOENCODING"] = "utf-8"
		if (!requireLibraryRuntime) env.remove("SSL_CERT_FILE").also { env.remove("REQUESTS_CA_BUNDLE") }
		val r = runner.run(EngineSpec(listOf(python.absolutePath, "-m", "aio_engine") + args, env, timeoutMs = timeoutMs), onStdout = { lines += it; onLine(it) })
		return Run(r.outcome, r.exitCode, lines.toList(), r.stderrTail)
	}
}
