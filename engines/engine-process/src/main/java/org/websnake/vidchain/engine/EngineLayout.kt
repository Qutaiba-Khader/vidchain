package org.websnake.vidchain.engine

import java.io.File

/**
 * Where youtubedl-android 0.18.1 puts its runtime (verified from the library's YoutubeDL.init bytecode):
 * binaries are lib*.so in nativeLibraryDir; the extracted Python / ffmpeg / aria2c trees live in
 * noBackupFilesDir/youtubedl-android/packages/{python,ffmpeg,aria2c}. The environment below is the one the library
 * itself passes to yt-dlp. The library extracts everything at app start (upstream code); we only reuse it.
 */
class EngineLayout(val nativeLibDir: File, noBackupDir: File, val cacheDir: File) {
	val base = File(noBackupDir, "youtubedl-android")
	private val packages = File(base, "packages")
	val python = File(nativeLibDir, "libpython.so")
	val ffmpeg = File(nativeLibDir, "libffmpeg.so")
	val ffprobe = File(nativeLibDir, "libffprobe.so")
	val aria2c = File(nativeLibDir, "libaria2c.so")
	val quickJs = File(nativeLibDir, "libqjs.so")
	val lux = File(nativeLibDir, "liblux.so")      // VidChain's own (natives.yml), arm64-v8a and x86_64 only
	val pythonHome = File(packages, "python/usr")
	val aria2cHome = File(packages, "aria2c/usr")
	val ytdlp = File(base, "yt-dlp/yt-dlp")
	val certFile = File(pythonHome, "etc/tls/cert.pem")

	/** the library has extracted Python (true after its first successful init) */
	val pythonReady: Boolean get() = python.isFile && File(pythonHome, "lib").isDirectory

	fun env(systemPath: String? = System.getenv("PATH"), extraPythonPath: List<File> = emptyList()): Map<String, String> = buildMap {
		put("LD_LIBRARY_PATH", listOf("python", "ffmpeg", "aria2c").joinToString(":") { File(packages, "$it/usr/lib").absolutePath })
		put("SSL_CERT_FILE", certFile.absolutePath)
		put("PATH", listOfNotNull(systemPath?.takeIf { it.isNotEmpty() }, nativeLibDir.absolutePath).joinToString(":"))
		put("PYTHONHOME", pythonHome.absolutePath)
		put("HOME", pythonHome.absolutePath)
		put("TMPDIR", cacheDir.absolutePath)
		if (extraPythonPath.isNotEmpty()) put("PYTHONPATH", extraPythonPath.joinToString(":") { it.absolutePath })
	}

	/** cheap identity of an engine binary for the probe cache and quarantine: size + mtime (changes with every app update) */
	fun engineHash(binary: File): String = if (binary.exists()) "${binary.length()}-${binary.lastModified()}" else "missing"
}
