package lib.process

import app.core.AIOApp.Companion.internalDataFolder
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_FILE_JSON_EXTENSION
import java.util.Random

object UniqueNumberUtils {

	@JvmStatic
	fun generateUniqueNumber(): Long {
		val random = Random()
		val currentTime = System.currentTimeMillis() % 1_000_000L
		val randomComponent = random.nextInt(1000)
		return currentTime * 1000 + randomComponent
	}

	/**
	 * T8.9 (inherited bug fix): every DownloadDataModel() calls this, also each of the N models loaded from the
	 * database, and every call listed the whole files/ folder (3-4 files per download) -> N x 4N work on start.
	 * The answer only changes when the folder's entries change, so the scan is reused while the folder's
	 * modification time is the same (same result as before, see MaxIdCacheTest).
	 */
	@JvmStatic
	fun getUniqueNumberForDownloadModels(): Int =
		org.websnake.vidchain.fallback.fixes.MaxIdCache.next(app.core.AIOApp.INSTANCE.filesDir) { scanMaxId() }

	private fun scanMaxId(): Int {
		val existingFiles = internalDataFolder.listFiles()
			.filter { it.name!!.endsWith(DOWNLOAD_MODEL_FILE_JSON_EXTENSION) }

		val existingNumbers = existingFiles.mapNotNull { file ->
			file.name!!.split("_").firstOrNull()?.toIntOrNull()
		}

		return existingNumbers.maxOrNull() ?: 0
	}
}