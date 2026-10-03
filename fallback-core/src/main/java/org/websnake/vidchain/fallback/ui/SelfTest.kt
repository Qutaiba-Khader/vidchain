package org.websnake.vidchain.fallback.ui

import android.content.Context
import android.os.Build
import org.websnake.vidchain.engine.CapabilityProbe
import org.websnake.vidchain.engine.Launcher
import org.websnake.vidchain.fallback.core.EngineKit
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.core.FallbackSettings
import org.websnake.vidchain.fallback.trace.TraceConfig

/** Engine self-test: what this phone can run. Plain text, exportable, no personal data. */
object SelfTest {
	suspend fun run(context: Context): String {
		val kit = FallbackRuntime.engines ?: EngineKit(context)
		val l = kit.layout
		val launcher = Launcher.detect()
		val lines = ArrayList<String>()
		val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
		lines += "VidChain ${version ?: "?"} self-test"
		lines += "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABI ${kit.abi}"
		lines += "Fallbacks: ${if (FallbackSettings.enabled(context)) "on" else "off"}; trace log: ${if (TraceConfig.enabled) "on" else "off"}"
		lines += "Methods built: ${FallbackRuntime.methods.joinToString(", ") { TrailText.name(it.id) }}"
		lines += "Process groups (setsid): ${launcher.setsid ?: "not available - cancel kills only the engine itself"}"
		lines += "Library runtime extracted: ${if (l.pythonReady) "yes" else "no (open a video once so the downloader sets it up)"}"
		if (l.pythonReady) {
			val py = kit.probe.check("python", kit.layout.engineHash(l.python), CapabilityProbe.pythonSpec(l)) { r ->
				org.websnake.vidchain.engine.Capability(r.outcome == org.websnake.vidchain.engine.EngineOutcome.Success, r.stdoutTail.joinToString(" / ").ifEmpty { r.outcome.toString() })
			}
			lines += "Python: ${if (py.ok) "OK " else "FAILED "}${py.detail}"
		}
		lines += if (l.ffmpeg.isFile) {
			val ff = kit.probe.check("ffmpeg", kit.layout.engineHash(l.ffmpeg), CapabilityProbe.ffmpegSpec(l))
			"ffmpeg: ${if (ff.ok) "OK " else "FAILED "}${ff.detail}"
		} else "ffmpeg: not found"
		lines += "aria2c: ${if (l.aria2c.isFile) "present" else "not in this build yet"}"
		val zip = org.websnake.vidchain.fallback.methods.PyZip.installed(context)
		lines += if (l.pythonReady && zip != null) {
			val r = org.websnake.vidchain.engine.python.PythonEngine(kit.runner, l).aio(listOf("probe"), zip, timeoutMs = 60_000)
			"aio_engine (gallery-dl): " + (if (r.outcome == org.websnake.vidchain.engine.EngineOutcome.Success) "OK " else "FAILED ") + (r.lines.lastOrNull() ?: r.outcome.toString()).take(300)
		} else "aio_engine (gallery-dl): " + (if (zip == null) "engines zip missing" else "library runtime not extracted yet")
		return lines.joinToString("\n")
	}
}
