package app.core.engines.browser.history

import android.content.Context.MODE_PRIVATE
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioGSONInstance
import app.core.AIOApp.Companion.aioHistory
import app.core.engines.fst_serializer.FSTBuilder.fstConfig
import com.anggrayudi.storage.file.getAbsolutePath
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.Serializable
import java.nio.channels.FileChannel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Comprehensive browser history management system with dual-format persistence.
 *
 * This class serves as the central repository for all browsing history operations,
 * providing efficient storage, retrieval, and management of user browsing sessions.
 * It implements a sophisticated multi-format persistence strategy with intelligent
 * fallback mechanisms and performance-optimized file handling.
 *
 * ## Key Features:
 * - **Dual-Format Persistence**: Binary (performance) + JSON (readability) storage
 * - **Intelligent File Handling**: Size-optimized reading strategies for various file sizes
 * - **Thread-Safe Operations**: Background execution for all storage operations
 * - **Comprehensive History Management**: Full CRUD operations with advanced filtering
 * - **Automatic Duplicate Detection**: Smart identification of redundant history entries
 * - **Archive Functionality**: Long-term storage of important browsing sessions
 * - **ObjectBox Integration**: Seamless database synchronization for reliable persistence
 *
 * @see HistoryModel for individual history entry structure
 * @see AIOHistoryDBManager for ObjectBox database operations
 * @see Serializable for Java serialization support
 */
@Entity
class AIOHistory : Serializable {

	/**
	 * Logger instance for comprehensive operation tracking and debugging.
	 * Provides detailed insights into storage operations, performance metrics,
	 * and error conditions throughout the history management lifecycle.
	 */
	@Transient
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Unique identifier for the history record in ObjectBox database.
	 * Auto-assigned by ObjectBox when the entity is first persisted.
	 *
	 * @see io.objectbox.annotation.Id for primary key configuration
	 */
	@Id
	var id: Long = 0

	/**
	 * Collection containing all recorded browser history entries.
	 *
	 * This field is marked as @Transient because:
	 * - ObjectBox cannot directly store ArrayList<HistoryModel> collections
	 * - HistoryModel entities are stored individually in ObjectBox
	 * - The list is reconstructed from ObjectBox when needed
	 * - Maintains compatibility with legacy serialization system
	 *
	 * @see HistoryModel for the structure of individual history entries
	 */
	@SerializedName("historyModels")
	@Transient
	var historyModels: ArrayList<HistoryModel> = ArrayList()

	/**
	 * Loads history data from persistent storage with intelligent format selection.
	 *
	 * This method implements a sophisticated loading strategy that ensures history data
	 * is always available while optimizing for performance and reliability:
	 *
	 * ## Loading Priority:
	 * 1. **Binary Format** (Primary): Fast, compact, efficient deserialization
	 * 2. **JSON Format** (Fallback): Human-readable, corruption-resistant
	 * 3. **Empty Collection** (Final): Fresh start when no data exists
	 *
	 * ## File Size Optimization:
	 * - **Small Files** (<0.5MB): Direct read operations for immediate loading
	 * - **Medium Files** (0.5-5MB): Buffered reading for balanced performance
	 * - **Large Files** (>5MB): Memory-mapped I/O with OOM protection fallback
	 *
	 * ## Error Resilience:
	 * - Automatic corruption detection and file cleanup
	 * - Comprehensive exception handling with detailed logging
	 * - Graceful degradation to alternative storage formats
	 *
	 * @param bypassBinaryFormat If true, forces JSON loading and deletes binary file
	 *                          (useful for recovery from corrupted binary data)
	 *
	 * @see loadFromBinary for binary format deserialization
	 * @see convertJSONStringToClass for JSON format parsing
	 * @see readSmallFile for optimized small file handling
	 * @see readMediumFile for balanced medium file performance
	 * @see readLargeFile for memory-efficient large file processing
	 */
	fun readObjectFromStorage(bypassBinaryFormat: Boolean = false) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				var isBinaryFileValid = false
				val internalDir = AIOApp.internalDataFolder
				val historyBinaryDataFile = internalDir.findFile(AIO_HISTORY_FILE_NAME_BINARY)

				// --- PHASE 1: Binary Format Loading (Performance-Optimized) ---
				if (!bypassBinaryFormat) {
					if (historyBinaryDataFile != null && historyBinaryDataFile.exists()) {
						logger.d("Found binary history file, attempting high-performance load")

						val absolutePath = historyBinaryDataFile.getAbsolutePath(INSTANCE)
						val objectInMemory = loadFromBinary(File(absolutePath))

						if (objectInMemory != null) {
							// Successful binary load → update memory and synchronize storage
							logger.d("Successfully loaded history from binary format")
							aioHistory = objectInMemory
							aioHistory.updateInStorage()
							isBinaryFileValid = true
						} else {
							logger.d("Binary history file exists but loading failed (possibly corrupted)")
						}
					}
				} else {
					// Force JSON loading by removing binary file (recovery scenario)
					if (historyBinaryDataFile != null && historyBinaryDataFile.exists()) {
						historyBinaryDataFile.delete()
						logger.d("Binary history bypass requested - file deleted for clean JSON load")
					}
				}

				// --- PHASE 2: JSON Format Fallback (Reliability-Focused) ---
				if (!isBinaryFileValid) {
					logger.d("Initiating JSON format fallback loading")
					val configFile = File(INSTANCE.filesDir, AIO_HISTORY_FILE_NAME_JSON)

					if (!configFile.exists()) {
						// No history data available → initialize with empty collection
						aioHistory.historyModels = ArrayList()
						logger.d("No history files found - initialized with empty history library")
						return@executeInBackground
					}

					// Intelligent file size analysis for optimal reading strategy
					val fileSizeMb = configFile.length().toDouble() / (1024 * 1024)
					logger.d("History file size analysis: ${"%.2f".format(fileSizeMb)} MB")

					val json = when {
						fileSizeMb <= 0.5 -> {
							logger.d("Small file detected - using direct read optimization")
							readSmallFile(configFile)
						}
						fileSizeMb <= 5.0 -> {
							logger.d("Medium file detected - using buffered read optimization")
							readMediumFile(configFile)
						}
						else -> {
							logger.d("Large file detected - using memory-mapped read optimization")
							readLargeFile(configFile)
						}
					}

					if (json.isNotEmpty()) {
						val historyClass = convertJSONStringToClass(json)

						// Update application state and ensure storage consistency
						logger.d("Successfully loaded ${historyClass.historyModels.size} history entries from JSON")
						aioHistory = historyClass
						aioHistory.updateInStorage()
					} else {
						logger.d("History JSON file exists but contains no data")
					}
				}
			} catch (error: Exception) {
				logger.e("Critical error during history loading: ${error.message}", error)
			}
		})
	}

	/**
	 * Saves the current history state to binary format for optimal performance.
	 *
	 * This method uses Fast Serialization (FST) for efficient object-to-binary conversion,
	 * providing significant performance advantages over JSON serialization for large datasets.
	 *
	 * ## Performance Characteristics:
	 * - **FST Serialization**: 5-10x faster than standard Java serialization
	 * - **Compact Storage**: Typically 50-80% smaller than equivalent JSON
	 * - **Thread Safety**: Synchronized to prevent concurrent write conflicts
	 *
	 * @param fileName The target filename within the application's internal storage
	 *
	 * @see fstConfig.asByteArray for FST serialization implementation
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Initiating binary history save operation: $fileName")
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				val bytes = fstConfig.asByteArray(this)
				fos.write(bytes)
				logger.d("History successfully saved in binary format (${bytes.size} bytes)")
			}
		} catch (error: Exception) {
			logger.e("Error during binary history save operation: ${error.message}", error)
		}
	}

	/**
	 * Loads history data from binary format using FST deserialization.
	 *
	 * This method provides high-performance loading of history data with automatic
	 * corruption detection and cleanup mechanisms.
	 *
	 * @param historyBinaryFile The binary file containing serialized history data
	 * @return AIOHistory instance if successful, null if file is missing or corrupted
	 *
	 * @see fstConfig.asObject for FST deserialization implementation
	 */
	private fun loadFromBinary(historyBinaryFile: File): AIOHistory? {
		if (!historyBinaryFile.exists()) {
			logger.d("Binary history file not found at path: ${historyBinaryFile.absolutePath}")
			return null
		}

		return try {
			logger.d("Starting FST deserialization of binary history file")
			val bytes = historyBinaryFile.readBytes()
			fstConfig.asObject(bytes).apply {
				logger.d("FST deserialization completed successfully")
			} as AIOHistory
		} catch (error: Exception) {
			logger.e("FST deserialization failed: ${error.message}", error)
			// Auto-cleanup of corrupted binary file to prevent repeated failures
			historyBinaryFile.delete()
			logger.d("Corrupted binary history file deleted to prevent future loading issues")
			null
		}
	}

	/**
	 * Optimized file reading for small history datasets (<0.5MB).
	 *
	 * Uses direct file read operations for maximum performance with minimal memory overhead.
	 *
	 * @param file The source file to read
	 * @return File contents as UTF-8 string
	 */
	private fun readSmallFile(file: File): String {
		logger.d("Executing direct file read for small history dataset")
		return file.readText(Charsets.UTF_8)
	}

	/**
	 * Balanced file reading for medium history datasets (0.5-5MB).
	 *
	 * Uses buffered reading to optimize I/O performance while maintaining reasonable memory usage.
	 *
	 * @param file The source file to read
	 * @return File contents as UTF-8 string
	 */
	private fun readMediumFile(file: File): String {
		logger.d("Executing buffered file read for medium history dataset")
		return BufferedReader(FileReader(file)).use { it.readText() }
	}

	/**
	 * Memory-optimized file reading for large history datasets (>5MB).
	 *
	 * Uses memory-mapped I/O for efficient large file handling with automatic
	 * fallback to line-by-line reading if memory constraints are encountered.
	 *
	 * @param file The source file to read
	 * @return File contents as UTF-8 string
	 */
	private fun readLargeFile(file: File): String {
		logger.d("Attempting memory-mapped I/O for large history dataset")
		return try {
			FileInputStream(file).channel.use { channel ->
				val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
				Charsets.UTF_8.decode(buffer).toString()
			}
		} catch (error: OutOfMemoryError) {
			logger.e("Memory constraints detected - falling back to line-by-line reading", error)
			buildString {
				BufferedReader(FileReader(file)).forEachLine { line -> append(line) }
			}
		}
	}

	/**
	 * Serializes the current history state to JSON format for human readability.
	 *
	 * @return JSON string representation of the complete history collection
	 */
	private fun convertClassToJSON(): String {
		logger.d("Serializing history collection to JSON format")
		return aioGSONInstance.toJson(this)
	}

	/**
	 * Deserializes JSON string back into AIOHistory instance with proper type handling.
	 *
	 * This method handles the complex type information for HistoryModel collections
	 * that standard GSON deserialization might not preserve correctly.
	 *
	 * @param data JSON string containing serialized history data
	 * @return Fully reconstructed AIOHistory instance
	 */
	private fun convertJSONStringToClass(data: String): AIOHistory {
		logger.d("Deserializing JSON to AIOHistory instance")

		// Initial deserialization of the wrapper object
		val history = aioGSONInstance.fromJson(data, AIOHistory::class.java)

		// Specialized handling for HistoryModel collection with proper type information
		val type = object : TypeToken<ArrayList<HistoryModel>>() {}.type
		val historyLibraryJSON = JSONObject(data).getString("historyLibrary")
		val parsedList: ArrayList<HistoryModel> = aioGSONInstance.fromJson(historyLibraryJSON, type)

		history.historyModels = parsedList
		return history
	}

	/**
	 * Persists the current history state to all available storage systems.
	 *
	 * This method ensures data consistency across multiple storage formats:
	 * 1. Binary format for performance (primary)
	 * 2. JSON format for readability and external access (secondary)
	 * 3. ObjectBox database for reliable persistence (tertiary)
	 *
	 * All operations are performed asynchronously to avoid blocking the UI thread.
	 *
	 * @see saveToBinary for binary storage implementation
	 * @see saveStringToInternalStorage for JSON storage
	 * @see AIOHistoryDBManager.updateAllHistoryInDB for database synchronization
	 */
	fun updateInStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Initiating comprehensive history storage update")

				// Triple-redundancy storage strategy
				saveToBinary(AIO_HISTORY_FILE_NAME_BINARY)
				saveStringToInternalStorage(
					fileName = AIO_HISTORY_FILE_NAME_JSON,
					data = convertClassToJSON()
				)
				AIOHistoryDBManager.updateAllHistoryInDB(historyModels = getHistoryLibrary())

				logger.d("History successfully persisted across all storage systems")
			} catch (error: Exception) {
				logger.e("Error during history storage update: ${error.message}", error)
			}
		})
	}

	/**
	 * Retrieves the complete collection of history entries.
	 *
	 * @return ArrayList<HistoryModel> containing all browsing history records
	 */
	fun getHistoryLibrary(): ArrayList<HistoryModel> {
		logger.d("Retrieving complete history library (${historyModels.size} entries)")
		return historyModels
	}

	/**
	 * Adds a new history entry to the collection.
	 *
	 * @param historyModel The history entry to add
	 */
	fun insertNewHistory(historyModel: HistoryModel) {
		logger.d("Adding new history entry: ${historyModel.historyTitle}")
		historyModels.add(historyModel)
	}

	/**
	 * Updates an existing history entry with new data.
	 *
	 * @param oldHistory The existing history entry to replace
	 * @param newHistory The updated history entry data
	 */
	fun updateHistory(oldHistory: HistoryModel, newHistory: HistoryModel) {
		logger.d("Updating history entry: ${oldHistory.historyTitle}")
		val index = historyModels.indexOf(oldHistory)
		if (index != -1) {
			historyModels[index] = newHistory
		}
	}

	/**
	 * Searches history entries by title or URL content.
	 *
	 * @param query The search term to match against history titles and URLs
	 * @return List<HistoryModel> containing all matching history entries
	 */
	fun searchHistory(query: String): List<HistoryModel> {
		logger.d("Executing history search for query: '$query'")
		return historyModels.filter {
			it.historyTitle.contains(query, ignoreCase = true) ||
					it.historyUrl.contains(query, ignoreCase = true)
		}
	}

	/**
	 * Identifies duplicate history entries based on URL matching.
	 *
	 * @return List<HistoryModel> containing all entries with duplicate URLs
	 */
	fun findDuplicateHistory(): List<HistoryModel> {
		logger.d("Scanning for duplicate history entries")
		return historyModels
			.groupBy { it.historyUrl }
			.filter { it.value.size > 1 }
			.flatMap { it.value }
	}

	/**
	 * Removes a specific history entry from the collection.
	 *
	 * @param historyModel The history entry to remove
	 */
	fun removeHistory(historyModel: HistoryModel) {
		logger.d("Removing history entry: ${historyModel.historyTitle}")
		historyModels.remove(historyModel)
	}

	/**
	 * Returns history entries sorted by the specified attribute.
	 *
	 * @param attribute The sorting criteria ("title" or "date")
	 * @return List<HistoryModel> sorted according to the specified attribute
	 */
	fun getHistorySortedBy(attribute: String): List<HistoryModel> {
		logger.d("Sorting history by attribute: $attribute")
		return when (attribute.lowercase(Locale.ROOT)) {
			"title" -> historyModels.sortedBy { it.historyTitle }
			"date" -> historyModels.sortedBy { it.historyVisitDateTime }
			else -> historyModels
		}
	}

	/**
	 * Clears all history entries from the collection.
	 */
	fun clearAllHistory() {
		logger.d("Clearing entire history library (${historyModels.size} entries)")
		historyModels.clear()
	}

	/**
	 * Returns the total number of history entries in the collection.
	 *
	 * @return Int representing the count of history entries
	 */
	fun countHistory(): Int {
		logger.d("Counting history entries: ${historyModels.size} total")
		return historyModels.size
	}

	/**
	 * Retrieves recent history entries within the specified time period.
	 *
	 * @param days The number of days to look back for recent history
	 * @return List<HistoryModel> containing entries from the specified period
	 */
	fun getRecentHistory(days: Int): List<HistoryModel> {
		logger.d("Retrieving recent history for last $days days")
		val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
		val calendar = Calendar.getInstance()
		calendar.add(Calendar.DAY_OF_YEAR, -days)
		val cutoffDate = calendar.time

		val cutoffDateString = dateFormat.format(cutoffDate)
		val cutoffDateParsed = dateFormat.parse(cutoffDateString) ?: return emptyList()

		return historyModels.filter {
			val visitDate = dateFormat.parse(it.historyVisitDateTime.toString())
			val lastAccessedDate = dateFormat.parse(it.historyLastAccessed.toString())
			visitDate != null && visitDate.after(cutoffDateParsed) ||
					lastAccessedDate != null && lastAccessedDate.after(cutoffDateParsed)
		}
	}

	/**
	 * Filters history entries by minimum visit duration.
	 *
	 * @param minDuration The minimum visit duration in milliseconds
	 * @return List<HistoryModel> containing entries meeting the duration criteria
	 */
	fun filterHistoryByDuration(minDuration: Long): List<HistoryModel> {
		logger.d("Filtering history by minimum duration: $minDuration ms")
		return historyModels.filter { it.historyDuration >= minDuration }
	}

	/**
	 * Retrieves history entries marked as important.
	 *
	 * @return List<HistoryModel> containing important history entries
	 */
	fun getImportantHistory(): List<HistoryModel> {
		logger.d("Retrieving important history entries")
		return historyModels.filter { it.historyImportant }
	}

	/**
	 * Filters history entries by specific tag.
	 *
	 * @param tag The tag to filter history entries by
	 * @return List<HistoryModel> containing entries with the specified tag
	 */
	fun filterHistoryByTag(tag: String): List<HistoryModel> {
		logger.d("Filtering history by tag: '$tag'")
		return historyModels.filter { it.historyTags.contains(tag) }
	}

	/**
	 * Archives a specific history entry for long-term preservation.
	 *
	 * @param historyModel The history entry to archive
	 */
	fun archiveHistory(historyModel: HistoryModel) {
		logger.d("Archiving history entry: ${historyModel.historyTitle}")
		val index = historyModels.indexOf(historyModel)
		if (index != -1) {
			historyModels[index].historyArchived = true
		}
	}

	/**
	 * Retrieves all archived history entries.
	 *
	 * @return List<HistoryModel> containing archived history entries
	 */
	fun getArchivedHistory(): List<HistoryModel> {
		logger.d("Retrieving archived history entries")
		return historyModels.filter { it.historyArchived }
	}

	companion object {

		/**
		 * Default filename for JSON-formatted history storage.
		 * Provides human-readable format for debugging and external tool compatibility.
		 */
		const val AIO_HISTORY_FILE_NAME_JSON: String = "browsing_history.json"

		/**
		 * Default filename for binary-formatted history storage.
		 * Optimized for performance with compact storage and fast serialization.
		 */
		const val AIO_HISTORY_FILE_NAME_BINARY: String = "browsing_history.dat"
	}
}