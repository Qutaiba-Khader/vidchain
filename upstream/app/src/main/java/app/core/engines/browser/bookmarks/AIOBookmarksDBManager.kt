package app.core.engines.browser.bookmarks

import app.core.engines.objectbox.ObjectBoxManager
import io.objectbox.Box
import lib.process.LogHelperUtils

/**
 * Manages persistent storage and retrieval of user bookmarks using ObjectBox.
 *
 * This singleton provides CRUD (Create, Read, Update, Delete) operations
 * for [BookmarkModel] entities, ensuring that all bookmark data is stored efficiently
 * and consistently across the application.
 *
 * ### Design Overview
 * - **Storage Layer:** Each [BookmarkModel] is stored as a distinct ObjectBox entity.
 * - **Service Layer:** [AIOBookmarks] acts as a logical container for bookmarks,
 *   aggregating them for in-memory manipulation or export.
 * - **Data Consistency:** Synchronization between ObjectBox and local JSON storage
 *   (via `readObjectFromStorage()`) ensures bookmarks persist even after DB resets.
 *
 * ### Advantages
 * - Fine-grained database operations (single bookmark or batch)
 * - Efficient querying and indexing
 * - Improved scalability for large bookmark sets
 * - No parent-child entity complexity; all bookmarks are independent
 *
 * This class depends on [ObjectBoxManager] for a shared database connection.
 */
object AIOBookmarksDBManager {

	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Returns a [Box] instance that provides low-level ObjectBox operations
	 * for [BookmarkModel] entities.
	 *
	 * @return A [Box] for managing [BookmarkModel] records
	 * @throws IllegalStateException if ObjectBox is not initialized via [ObjectBoxManager.init]
	 */
	@JvmStatic
	fun getBookmarkBox(): Box<BookmarkModel> {
		return ObjectBoxManager.getBoxStore().boxFor(BookmarkModel::class.java)
	}

	/**
	 * Loads the full [AIOBookmarks] instance from ObjectBox or fallback storage.
	 *
	 * - Attempts to load all bookmarks from the ObjectBox database.
	 * - If the database is empty, falls back to loading bookmarks from
	 *   JSON or serialized storage using `AIOBookmarks.readObjectFromStorage()`.
	 *
	 * @return A fully initialized [AIOBookmarks] object containing all bookmarks.
	 */
	@JvmStatic
	fun loadAIOBookmarksFromDB(): AIOBookmarks {
		val aioBookmarks = AIOBookmarks()
		val bookmarkModels = loadAllBookmarkModelsFromDB()
		if (bookmarkModels.isNotEmpty()) {
			aioBookmarks.bookmarkModels = bookmarkModels
		} else {
			logger.d("No bookmarks found in ObjectBox, loading from backup storage")
			aioBookmarks.readObjectFromStorage()
		}
		return aioBookmarks
	}

	/**
	 * Loads all [BookmarkModel] entries from the ObjectBox database.
	 *
	 * @return An [ArrayList] of all bookmarks stored in the database.
	 * Returns an empty list if an error occurs or no bookmarks are found.
	 */
	@JvmStatic
	private fun loadAllBookmarkModelsFromDB(): ArrayList<BookmarkModel> {
		return try {
			val bookmarkBox = getBookmarkBox()
			val allBookmarks = bookmarkBox.all
			ArrayList(allBookmarks).also {
				logger.d("Loaded ${it.size} bookmarks from ObjectBox database")
			}
		} catch (error: Exception) {
			logger.e("Error loading bookmarks from ObjectBox: ${error.message}", error)
			ArrayList()
		}
	}

	/**
	 * Updates the entire bookmark collection in the ObjectBox database.
	 *
	 * - Clears existing bookmarks.
	 * - Inserts all new [BookmarkModel] instances in a single transaction.
	 *
	 * This operation is atomic — partial updates do not occur.
	 *
	 * @param bookmarks The list of [BookmarkModel] instances to save.
	 */
	@JvmStatic
	fun updateAllBookmarksInDB(bookmarks: List<BookmarkModel>) {
		try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.removeAll()
			bookmarkBox.put(bookmarks)
			logger.d("Saved ${bookmarks.size} bookmarks to ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error saving bookmarks to ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Deletes a single bookmark entry from ObjectBox.
	 *
	 * @param bookmark The [BookmarkModel] to delete.
	 * @return `true` if the operation succeeded, `false` otherwise.
	 */
	@JvmStatic
	fun deleteBookmarkFromDB(bookmark: BookmarkModel): Boolean {
		return try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.remove(bookmark)
			logger.d("Bookmark deleted from ObjectBox: ${bookmark.bookmarkName}")
			true
		} catch (error: Exception) {
			logger.e("Error deleting bookmark from ObjectBox: ${error.message}", error)
			false
		}
	}

	/**
	 * Deletes all bookmarks stored in the ObjectBox database.
	 *
	 * This operation cannot be undone.
	 */
	@JvmStatic
	fun deleteAllBookmarksFromDB() {
		try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.removeAll()
			logger.d("All bookmarks deleted from ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error deleting all bookmarks from ObjectBox: ${error.message}", error)
		}
	}

	/**
	 * Retrieves the total number of bookmarks stored in ObjectBox.
	 *
	 * @return The count of [BookmarkModel] entities in the database.
	 * Returns `0` if an error occurs.
	 */
	@JvmStatic
	fun getBookmarksCountsInDB(): Long {
		return try {
			val bookmarkBox = getBookmarkBox()
			bookmarkBox.count()
		} catch (error: Exception) {
			logger.e("Error counting bookmarks in ObjectBox: ${error.message}", error)
			0
		}
	}
}