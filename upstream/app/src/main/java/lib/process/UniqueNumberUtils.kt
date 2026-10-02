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

	@JvmStatic
	fun getUniqueNumberForDownloadModels(): Int {
		val existingFiles = internalDataFolder.listFiles()
			.filter { it.name!!.endsWith(DOWNLOAD_MODEL_FILE_JSON_EXTENSION) }

		val existingNumbers = existingFiles.mapNotNull { file ->
			file.name!!.split("_").firstOrNull()?.toIntOrNull()
		}

		val maxNumber = existingNumbers.maxOrNull() ?: 0
		return maxNumber + 1
	}
}