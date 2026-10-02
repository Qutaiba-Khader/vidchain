package app.core

import app.core.AIOApp.Companion.aioBackend
import app.core.AIOApp.Companion.aioSettings
import com.aio.*
import lib.process.*
import lib.texts.CommonTextUtils.getText
import java.io.*
import java.text.*
import java.util.*

/**
 * A global [Thread.UncaughtExceptionHandler] that captures and logs uncaught exceptions.
 *
 * This handler is responsible for intercepting unexpected app terminations,
 * capturing detailed debugging information, and persisting it for analysis.
 *
 * Its core duties include:
 * - Acting as the default handler for all uncaught exceptions application-wide.
 * - Capturing the full stack trace of the exception.
 * - Writing the stack trace to a local log file (`.aio_crash_log.txt`) for offline inspection.
 * - Storing the crash information in the backend.
 * - Setting a flag in [aioSettings] to indicate a recent crash, which can be
 *   checked on the next app launch to inform the user or perform recovery actions.
 *
 * By centralizing crash reporting, it improves the app's robustness and simplifies
 * post-mortem diagnostics.
 */
class AIOCrashHandler : Thread.UncaughtExceptionHandler {

	/** Logger for this class, used for structured logging. */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Overrides the default uncaught exception handling mechanism for any application thread.
	 *
	 * This method is invoked when a thread terminates due to an uncaught exception. It logs
	 * the exception, captures the full stack trace, and saves it to a persistent crash log file
	 * (`.aio_crash_log.txt`). It also updates application settings to indicate that a crash has
	 * occurred, allowing for post-crash recovery or reporting logic on the next app launch.
	 *
	 * The entire process is wrapped in a try-catch block to prevent the crash handler itself
	 * from causing a secondary failure.
	 *
	 * @param thread The thread that is about to terminate due to the uncaught exception.
	 * @param exception The uncaught exception that caused the thread to terminate.
	 */
	override fun uncaughtException(thread: Thread, exception: Throwable) {
		try {
			logger.d("Uncaught exception in thread: ${thread.name}")

			// Extract full stack trace into a string
			val stackTrace = StringWriter().use { sw ->
				PrintWriter(sw).use { pw ->
					exception.printStackTrace(pw)
					sw.toString()
				}
			}
			logger.d("Stack trace successfully captured")

			// Attempt to persist crash log
			try {
				val externalDataFolderPath = getText(R.string.text_default_aio_download_folder_path)
				val directoryPath = "$externalDataFolderPath/${getText(R.string.title_aio_others)}/.configs"
				val dir = File(directoryPath)

				// Ensure directory exists
				if (!dir.exists()) {
					dir.mkdirs()
					logger.d("Crash log directory created at: ${dir.absolutePath}")
				}

				val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
					.format(System.currentTimeMillis())
				val fileName = ".aio_crash_log_$timeStamp.txt"
				// Write stack trace to log file
				val crashLogFile = File(dir, fileName)
				crashLogFile.writeText(stackTrace)

				logger.d("Crash log written at: ${crashLogFile.absolutePath}")
			} catch (error: Exception) {
				logger.e("Failed to save crash log: ${error.message}", error)
			}

			// Save crash information for later inspection
			aioBackend.saveAppCrashedInfo(stackTrace)

			// Mark crash state in settings
			aioSettings.hasAppCrashedRecently = true
			aioSettings.updateInStorage()
			logger.d("Crash flag persisted in aioSettings")

		} catch (error: Exception) {
			// Catch secondary failures in the crash handling flow
			logger.d("Secondary error during crash handling: ${error.message}")
		}
	}
}
