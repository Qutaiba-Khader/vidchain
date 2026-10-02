package app.core.engines.browser.history

import app.core.engines.browser.history.AIOHistoryDBManager.loadAllHistoryModelsFromDB
import app.core.engines.objectbox.ObjectBoxManager
import io.objectbox.Box
import lib.process.LogHelperUtils

/**
 * Singleton manager for handling browser history persistence using ObjectBox database.
 *
 * This manager provides a centralized interface for all database operations related to
 * browser history, ensuring efficient storage, retrieval, and management of browsing history
 * entries. It serves as the primary data access layer between the application and the
 * ObjectBox database for history-related operations.
 *
 * @see HistoryModel for the entity definition and structure
 * @see AIOHistory for the main history management class
 * @see ObjectBoxManager for the underlying database connection
 * @see Box for ObjectBox entity operations
 */
object AIOHistoryDBManager {

	/**
	 * Logger instance for tracking database operations and debugging.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Retrieves the ObjectBox Box instance for HistoryModel entities.
	 *
	 * The Box interface provides type-safe access to all CRUD operations for HistoryModel
	 * entities, including querying, inserting, updating, and deleting records. This method
	 * leverages the shared BoxStore instance from ObjectBoxManager for optimal performance
	 * and resource management.
	 *
	 * @return Box<HistoryModel> configured for history entity operations
	 * @throws IllegalStateException if ObjectBox is not initialized or BoxStore is unavailable
	 *
	 * @see ObjectBoxManager.getBoxStore for the shared database instance
	 * @see Box for available entity operations and query capabilities
	 *
	 */
	@JvmStatic
	fun getHistoryBox(): Box<HistoryModel> {
		return ObjectBoxManager.getBoxStore().boxFor(HistoryModel::class.java)
	}

	/**
	 * Loads the complete browsing history from ObjectBox database with automatic fallback.
	 *
	 * This method implements a robust loading strategy that ensures history data is always
	 * available to the application:
	 *
	 * @return AIOHistory instance populated with all available history data
	 *
	 * @see AIOHistory.readObjectFromStorage for legacy file loading implementation
	 * @see loadAllHistoryModelsFromDB for ObjectBox loading implementation
	 */
	@JvmStatic
	fun loadAIOHistoryFromDB(): AIOHistory {
		val aioHistory = AIOHistory()
		val historyModels = loadAllHistoryModelsFromDB()

		if (historyModels.isNotEmpty()) {
			// Successfully loaded from ObjectBox database
			aioHistory.historyModels = historyModels
			logger.d("History loaded from ObjectBox: ${historyModels.size} entries")
		} else {
			// Fallback to legacy file storage
			logger.d("No history found in ObjectBox, loading from backup storage")
			aioHistory.readObjectFromStorage()
			logger.d("History loaded from legacy storage: ${aioHistory.historyModels.size} entries")
		}

		return aioHistory
	}

	/**
	 * Loads all HistoryModel entities from the ObjectBox database.
	 *
	 * This method performs a complete scan of the history database and returns all
	 * available entries. It's optimized for scenarios where the entire history dataset
	 * needs to be available in memory, such as during application startup or when
	 * displaying the full history list.
	 *
	 * @return ArrayList<HistoryModel> containing all history entries from database,
	 *         or empty list if no history exists or an error occurs
	 *
	 * @see Box.all for ObjectBox bulk loading implementation
	 */
	@JvmStatic
	private fun loadAllHistoryModelsFromDB(): ArrayList<HistoryModel> {
		return try {
			val historyBox = getHistoryBox()
			val allHistory = historyBox.all
			ArrayList(allHistory).also {
				logger.d("Loaded ${it.size} history entries from ObjectBox database")
			}
		} catch (error: Exception) {
			logger.e("Error loading history from ObjectBox: ${error.message}", error)
			ArrayList()
		}
	}

	/**
	 * Replaces all history entries in the database with the provided collection.
	 *
	 * This method performs a complete database refresh by:
	 * 1. Removing all existing history entries from the database
	 * 2. Inserting the new collection of history models
	 * 3. Maintaining data consistency through transactional operations
	 *
	 * @param historyModels The complete collection of HistoryModel entities to store
	 *
	 * @see Box.removeAll for database clearance
	 * @see Box.put for bulk insertion
	 *
	 */
	@JvmStatic
	fun updateAllHistoryInDB(historyModels: List<HistoryModel>) {
		try {
			val historyBox = getHistoryBox()

			// Clear existing data and insert new collection
			historyBox.removeAll()
			historyBox.put(historyModels)

			logger.d("Saved ${historyModels.size} history entries to ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error saving history to ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Deletes a specific history entry from the database.
	 *
	 * This method removes an individual history record based on the provided
	 * HistoryModel instance. The deletion is performed using the entity's
	 * internal ID for precise targeting.
	 *
	 * @param historyModel The HistoryModel instance to delete from the database
	 * @return true if deletion was successful, false if an error occurred
	 *
	 * @see Box.remove for entity deletion implementation
	 */
	@JvmStatic
	fun deleteHistoryFromDB(historyModel: HistoryModel): Boolean {
		return try {
			val historyBox = getHistoryBox()
			historyBox.remove(historyModel)
			logger.d("History deleted from ObjectBox: ${historyModel.historyUrl}")
			true
		} catch (error: Exception) {
			logger.e("Error deleting history from ObjectBox: ${error.message}", error)
			false
		}
	}

	/**
	 * Removes all history entries from the database.
	 *
	 * This method performs a complete clearance of the history database, removing
	 * all browsing history records. Use with caution as this operation is irreversible.
	 *
	 * @see Box.removeAll for complete database clearance
	 *
	 */
	@JvmStatic
	fun deleteAllHistoryFromDB() {
		try {
			val historyBox = getHistoryBox()
			historyBox.removeAll()
			logger.d("All history entries deleted from ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error deleting all history from ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Retrieves the total number of history entries in the database.
	 *
	 * This method provides efficient counting without loading the actual data,
	 * making it suitable for statistics display, progress tracking, and UI updates.
	 *
	 * @return The total number of HistoryModel entities in the database,
	 *         or 0 if the database is empty or an error occurs
	 * @see Box.count for efficient entity counting
	 */
	@JvmStatic
	fun getHistoryCountsInDB(): Long {
		return try {
			val historyBox = getHistoryBox()
			historyBox.count()
		} catch (error: Exception) {
			logger.e("Error counting history in ObjectBox: ${error.message}", error)
			0
		}
	}
}