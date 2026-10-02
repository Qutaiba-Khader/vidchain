package app.core.engines.youtube

import app.core.engines.objectbox.*
import app.core.engines.youtube.YTVideosModelsDBManager.getYouTubeVideoById
import io.objectbox.*
import lib.process.*
import java.util.concurrent.*

/**
 * YouTube Videos Models Database Manager
 *
 * This class provides Create, Read, Update, and Delete (CRUD) operations for managing
 * [YouTubeVideoDataModel] entities in the local **ObjectBox** database. It acts as the
 * single source of truth for persisting video metadata, including titles, channel information,
 * and view counts.
 *
 * The manager utilizes the **Singleton pattern** for consistent, centralized database access
 * and includes synchronized methods to ensure **thread safety** during concurrent operations.
 * Operations are executed within ObjectBox transactions for atomicity.
 *
 * @see YouTubeVideoDataModel The entity class representing YouTube video data stored locally.
 * @see ObjectBoxManager The base database manager responsible for initializing the BoxStore.
 */
object YTVideosModelsDBManager {
	
	/**
	 * Logger instance for this class, used for detailed operation logging.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * ObjectBox BoxStore instance.
	 * This central access point to the database is initialized lazily upon first use.
	 */
	private val globalObjectBoxStore: BoxStore by lazy {
		logger.d("Retrieving BoxStore instance for YouTube videos database.")
		ObjectBoxManager.getBoxStore()
	}
	
	/**
	 * Lazy-initialized Box (Data Access Object) for [YouTubeVideoDataModel] entities.
	 * This Box provides the direct CRUD methods for the specific video entity type.
	 */
	private val ytVideoModelsBox: Box<YouTubeVideoDataModel> by lazy {
		globalObjectBoxStore.boxFor(YouTubeVideoDataModel::class.java).also {
			logger.d("YTVideos box initialized - Ready for YouTube video data operations.")
		}
	}
	
	@JvmStatic
	@Synchronized
	fun getYouTubeVideosModelBox(): Box<YouTubeVideoDataModel> {
		return ytVideoModelsBox
	}
	
	/**
	 * **Saves or updates** a YouTube video data model to the database.
	 *
	 * This method performs a synchronous database transaction to persist the data.
	 * If the model's ID is set (i.e., non-zero), the existing entity is updated;
	 * otherwise, a new entity is inserted with a newly generated ID.
	 *
	 * **Enhanced Logic:** This version includes a high-level function callback ([OnSaveResultListener])
	 * to communicate the operation result back to the caller without exposing low-level exceptions.
	 *
	 * @param youTubeVideoModel The [YouTubeVideoDataModel] object to save.
	 * The ID field will be updated upon success. If the ID is zero, a new entity will be created.
	 *
	 * @param listener The callback interface to handle the result (success or error).
	 * @threadsafe The method is synchronized to prevent concurrent modification issues.
	 */
	@JvmStatic
	@Synchronized
	fun saveYouTubeVideoModelsInDB(
		youTubeVideoModel: YouTubeVideoDataModel,
		listener: OnSaveResultListener
	) {
		try {
			// Execute within a database transaction for atomicity (all-or-nothing)
			globalObjectBoxStore.runInTx { ytVideoModelsBox.put(youTubeVideoModel) }
			
			logger.d(
				"YouTube video saved successfully - ID: ${youTubeVideoModel.id}, " +
					"Title: ${youTubeVideoModel.videoTitle}"
			)
			listener.onSuccess(youTubeVideoModel.id)
			
		} catch (error: Exception) {
			logger.e("Error saving youtube video data model: ${error.message}", error)
			listener.onError(error)
		}
	}
	
	/**
	 * Retrieves all YouTube video data models from the database.
	 *
	 * This function fetches every `YouTubeVideoDataModel` persisted in the ObjectBox store.
	 * The operation is performed synchronously and is thread-safe.
	 *
	 * **Warning:** Loading all entities into memory can be resource-intensive if the database
	 * contains a very large number of video models. For performance-critical applications or
	 * large datasets, consider using a paginated query or a more specific query function.
	 *
	 * @return A [List] of all [YouTubeVideoDataModel] instances. Returns an empty list
	 *         if no models are found in the database or if an error occurs during retrieval.
	 * @see getYouTubeVideoById
	 * @threadsafe This method is synchronized to ensure safe concurrent read access.
	 */
	@JvmStatic
	@Synchronized
	fun getAllYouTubeVideoDataModels(): List<YouTubeVideoDataModel> {
		return try {
			val ytVidModels = ytVideoModelsBox.all
			
			if (ytVidModels.isEmpty()) {
				logger.d("No youtube videos found in database.")
			} else {
				logger.d("Retrieved ${ytVidModels.size} YouTube video(s) from database.")
			}
			ytVidModels
		} catch (error: Exception) {
			logger.e("Error retrieving YouTube video models: ${error.message}", error)
			emptyList()
		}
	}
	
	/**
	 * Retrieves a specific YouTube video from the database by its unique ObjectBox ID.
	 *
	 * This method performs a direct lookup using the primary key, making it a highly
	 * efficient way to fetch a single, known entity. If the ID does not correspond to
	 * any existing record, it gracefully returns `null`.
	 *
	 * @param ytVideoModelID The unique `Long` identifier of the `YouTubeVideoDataModel` to retrieve.
	 * @return The matching [YouTubeVideoDataModel] if found, otherwise `null`.
	 */
	@JvmStatic
	fun getYouTubeVideoById(ytVideoModelID: Long): YouTubeVideoDataModel? {
		return try {
			ytVideoModelsBox.get(ytVideoModelID)?.also {
				logger.d("Retrieved YouTube video by ID: $ytVideoModelID, Title: ${it.videoTitle}")
			}
		} catch (error: Exception) {
			logger.e("Error retrieving YouTube video by ID $ytVideoModelID: ${error.message}", error)
			null
		}
	}
	
	/**
	 * Deletes a YouTube video record from the database using its unique ObjectBox ID.
	 *
	 * This operation is executed within a database transaction for atomicity and reliability.
	 * The function will log the outcome of the deletion attempt.
	 *
	 * Note: ObjectBox's `remove(id)` returns `true` if an entity with the given ID was found
	 * and removed, and `false` if no entity with that ID existed. This implementation
	 * returns `true` in both of these cases, as the desired state (the record not being
	 * in the database) is achieved. It only returns `false` if a database exception occurs.
	 *
	 * @param ytVideoModelID The unique identifier ([Long]) of the YouTube video to delete.
	 * @return Returns `true` if the deletion was successful or if the entity did not exist.
	 *         Returns `false` only if a critical database error occurred during the transaction.
	 * @threadsafe This method is synchronized to ensure thread-safe removal operations.
	 */
	@JvmStatic
	@Synchronized
	fun deleteYouTubeVideoById(ytVideoModelID: Long): Boolean {
		return try {
			globalObjectBoxStore.runInTx { ytVideoModelsBox.remove(ytVideoModelID) }
			logger.d("Deleted YouTube video with ID: $ytVideoModelID")
			true
		} catch (error: Exception) {
			logger.e("Error deleting YouTube video with ID $ytVideoModelID: ${error.message}", error)
			false
		}
	}
	
	/**
	 * **CAUTION: This is a destructive operation.**
	 *
	 * Removes **all** YouTube video records from the database. This action is irreversible.
	 * The operation is executed within a database transaction to ensure atomicity, meaning
	 * either all records are removed successfully or none are.
	 *
	 * @return `true` if all records were successfully cleared, or `false` if a database error occurred.
	 * @threadsafe The method is synchronized to prevent concurrent access and ensure a complete, atomic clear.
	 */
	@JvmStatic
	@Synchronized
	fun clearAllYouTubeVideos(onResult: (Boolean) -> Unit = {}) {
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 500,
			codeBlock = {
				val result = try {
					globalObjectBoxStore.runInTx { ytVideoModelsBox.removeAll() }
					logger.d("Cleared all YouTube videos from database.")
					true
				} catch (error: Exception) {
					logger.e("Error clearing YouTube videos: ${error.message}", error)
					false
				}
				onResult.invoke(result)
			}
		)
	}
	
	/**
	 * Asynchronously removes YouTube video models older than a specified number of days from the database.
	 *
	 * This function performs a cleanup operation to manage storage by deleting outdated video records.
	 * It calculates a cutoff timestamp based on the `daysToKeep` parameter and removes all video models
	 * whose `lastModifiedTimeDate` is older than this cutoff. The entire operation is executed
	 * on a background thread to prevent blocking the main thread.
	 *
	 * @param daysToKeep The number of days to retain video models. Records older than this
	 *                   will be deleted. Defaults to 7 days.
	 *
	 * @threadsafe The method is synchronized to ensure that only one cleanup operation can be
	 *             initiated at a time. The database transaction itself is atomic.
	 */
	@JvmStatic
	@Synchronized
	fun clearOldYouTubeVideosModelsFromDB(daysToKeep: Int = 7) {
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 500,
			codeBlock = {
				try {
					val timeToKeepMillis = TimeUnit.DAYS.toMillis(daysToKeep.toLong())
					val cutoffTime = System.currentTimeMillis() - timeToKeepMillis
					
					val listOfModels = getAllYouTubeVideoDataModels()
					val modelsToDelete = listOfModels.filter { model ->
						model.lastModifiedTimeDate < cutoffTime
					}
					
					val idsToDelete = modelsToDelete.map { it.id }
					globalObjectBoxStore.runInTx { ytVideoModelsBox.removeByIds(idsToDelete) }
					
					logger.i(
						"Successfully cleared ${idsToDelete.size} " +
							"old YouTube video models (older than $daysToKeep days)."
					)
				} catch (error: Exception) {
					logger.e("Error clearing old YouTube video models: ${error.message}", error)
				}
			}
		)
	}
	
	/**
	 * Defines a callback to be invoked when a database save operation completes.
	 *
	 * This listener decouples the database layer from the UI or business logic,
	 * providing a standardized way to handle both successful outcomes and failures
	 * without exposing low-level database exceptions to the caller.
	 */
	interface OnSaveResultListener {
		
		/**
		 * Called when the database save operation is successful.
		 * @param savedId The unique ID of the entity that was saved (newly generated or existing).
		 */
		fun onSuccess(savedId: Long)
		
		/**
		 * Called when the database save operation fails due to an exception.
		 * @param error The Exception that occurred during the operation.
		 */
		fun onError(error: Exception)
	}
}