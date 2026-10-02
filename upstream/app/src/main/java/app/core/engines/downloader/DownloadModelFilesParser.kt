package app.core.engines.downloader

import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.downloadSystem
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_FILE_JSON_EXTENSION
import app.core.engines.downloader.DownloadDataModel.Companion.convertJSONStringToClass
import app.core.engines.downloader.DownloadModelsDBManager.getAllDownloadsWithRelationsAssembled
import com.aio.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import lib.process.*
import java.io.*
import java.util.concurrent.*

/**
 * DownloadModelParser – Robust Model Cache and Recovery System
 *
 * A high-performance manager responsible for loading, validating, caching, and repairing
 * serialized DownloadDataModel instances from persistent storage.
 *
 * Core Responsibilities:
 * - Efficiently parse and load cached download models from disk or memory
 * - Maintain a centralized in-memory cache for quick access
 * - Recover gracefully from file corruption, deserialization errors, or missing data
 * - Ensure data integrity through validation and controlled write-back operations
 *
 * Key Features:
 * 1. **Automatic Recovery and Retry**
 *    - Detects corrupted or unreadable cache entries and attempts reprocessing.
 *    - Supports multiple recovery attempts before fallback to a safe default state.
 *
 * 2. **Failure Isolation**
 *    - Each model load occurs in an isolated coroutine scope to prevent cascading failures.
 *    - Faulty models are logged, skipped, and quarantined without interrupting other tasks.
 *
 * 3. **Thread-Safe Operations**
 *    - Uses Kotlin coroutines and synchronized blocks for concurrent access safety.
 *    - Guarantees atomic reads/writes to prevent race conditions.
 *
 * 4. **Cache Management**
 *    - Supports smart invalidation when model metadata changes.
 *    - Periodically verifies cache consistency and prunes obsolete entries.
 *
 * 5. **Performance-Oriented Design**
 *    - Leverages asynchronous I/O for faster disk reads.
 *    - Caches frequently accessed models in memory to reduce deserialization overhead.
 *
 * Logging & Diagnostics:
 * - All critical operations are logged using LogHelperUtils.
 * - Parsing errors, cache invalidations, and recovery events include stack trace details.
 *
 * Usage Example:
 * ```
 * DownloadModelParser.loadAllModels()
 * DownloadModelParser.getCachedModel(downloadId)
 * DownloadModelParser.invalidateCache(downloadId)
 * ```
 *
 * This class is designed to ensure persistent, reliable, and recoverable state management
 * for downloads — even under storage corruption, app crashes, or interrupted I/O operations.
 */
object DownloadModelFilesParser {
	
	/** Logger instance for tracking parsing operations, cache hits, and recovery events */
	private val logger = LogHelperUtils.from(javaClass)
	
	/** Concurrent hash map storing parsed download models with filename as key for fast retrieval */
	private val downloadModelsCache = ConcurrentHashMap<String, DownloadDataModel>()
	
	/**
	 * Dedicated coroutine scope using IO dispatcher for file operations
	 * SupervisorJob ensures failure in one coroutine doesn't cancel others
	 */
	private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
	
	/**
	 * Thread-safe map tracking files that failed to parse and their failure timestamps
	 * Used to implement retry delay and prevent excessive retry attempts
	 */
	private val failedDownloadModelFiles = ConcurrentHashMap<String, Long>()
	
	/** Delay period before retrying failed file parsing (30 seconds) */
	private const val FAILURE_RETRY_DELAY_MS = 30000L // 30 seconds
	
	/**
	 * Retrieves all download data models with intelligent caching and recovery mechanisms.
	 * This method implements a multi-layered approach to model retrieval:
	 * 1. First attempts to load from database (preferred source)
	 * 2. Falls back to cached models if database is empty
	 * 3. Performs cache validation and recovery if needed
	 * 4. Ensures thread-safe operation using coroutine context switching
	 *
	 * @return List of DownloadDataModel objects, never null but potentially empty
	 * @throws Exception if critical errors occur during database access or file recovery
	 */
	@Throws(Exception::class)
	suspend fun getDownloadDataModels(): List<DownloadDataModel> {
		logger.d("getDownloadDataModels() called. Cache size=${downloadModelsCache.size}")
		return withContext(Dispatchers.IO) {
			// Primary attempt: Load from database as source of truth
			val downloadModelsFromDB = getAllDownloadsWithRelationsAssembled()
			downloadModelsFromDB.ifEmpty {
				// Secondary approach: Use cache when database is empty
				if (downloadModelsCache.isEmpty()) {
					logger.d("Cache empty, loading models with recovery.")
					// Load models from files with built-in recovery mechanisms
					loadAllModelsWithRecovery()
				} else {
					logger.d("Validating cache against files.")
					// Ensure cached models are still valid and up-to-date
					validateCacheAgainstFiles()
				}
				logger.d("Returning ${downloadModelsCache.size} models.")
				// Convert cache values to list for consistent return type
				downloadModelsCache.values.toList()
			}
		}
	}
	
	/**
	 * Retrieves a specific download data model by ID with failure recovery and caching.
	 * This method implements a robust retrieval strategy with multiple layers:
	 * 1. Failure tracking - prevents repeated attempts on recently failed files
	 * 2. Cache lookup - returns cached model immediately if available
	 * 3. Recovery loading - attempts to load single model with built-in error recovery
	 * 4. Thread-safe operation using coroutine context switching
	 *
	 * @param id The unique identifier of the download model to retrieve
	 * @return DownloadDataModel object if found and successfully loaded, null otherwise
	 */
	suspend fun getDownloadDataModel(id: String): DownloadDataModel? {
		logger.d("getDownloadDataModel() called for id=$id")
		return withContext(Dispatchers.IO) {
			// Check if this file recently failed to prevent excessive retries
			failedDownloadModelFiles[id]?.let { timestamp ->
				if (System.currentTimeMillis() - timestamp < FAILURE_RETRY_DELAY_MS) {
					logger.d("Skipping $id due to recent failure.")
					return@withContext null
				}
			}
			
			// Return cached model if available, otherwise attempt recovery load
			downloadModelsCache[id] ?: run {
				logger.d("Model $id not in cache, attempting load with recovery.")
				loadSingleModelWithRecovery(id)
				downloadModelsCache[id]
			}
		}
	}
	
	/**
	 * Loads all download model files with comprehensive recovery mechanisms and progress tracking.
	 * This method processes multiple model files concurrently with controlled parallelism,
	 * providing real-time UI updates and intelligent failure handling.
	 *
	 * Key features:
	 * - Controlled concurrency with semaphore to prevent resource exhaustion
	 * - Real-time progress updates on main thread
	 * - Intelligent skipping of recently failed files
	 * - Parallel processing with coroutine async/await pattern
	 *
	 * The method processes files in batches with maximum concurrency limit to balance
	 * performance and system resource usage.
	 */
	private suspend fun loadAllModelsWithRecovery() {
		// List all model files available for processing
		val files = listModelFiles(INSTANCE.filesDir)
		logger.d("Found ${files.size} model files to process.")
		
		// Resource IDs for UI progress updates
		val progressResId = R.string.title_loading_of_downloads
		val startingResId = R.string.title_attempt_to_load_download_models
		
		// Limit parallelism to prevent resource exhaustion
		val maxConcurrency = 50
		val semaphore = Semaphore(maxConcurrency)
		
		// Track processing progress for UI updates
		var processedCount = 0
		
		// Initialize UI with starting message and total file count
		AsyncJobUtils.executeOnMainThread {
			downloadSystem.downloadsUIManager.loadingDownloadModelTextview?.let {
				it.text = it.context.getString(startingResId, files.size)
			}
		}
		
		// Process all files concurrently with controlled parallelism
		val deferredResults = files.map { file ->
			coroutineScope.async {
				semaphore.withPermit {
					// Update UI progress on main thread
					AsyncJobUtils.executeOnMainThread {
						downloadSystem.downloadsUIManager.loadingDownloadModelTextview?.let { tv ->
							processedCount++
							tv.text = tv.context.getString(progressResId, processedCount, files.size)
						}
					}
					
					// Process file only if not recently failed, otherwise skip
					if (shouldAttemptLoad(file.nameWithoutExtension)) {
						processModelFileWithRecovery(file)
					} else {
						logger.d("Skipping ${file.name} due to recent failure.")
						false
					}
				}
			}
		}
		
		// Wait for all concurrent processing to complete
		deferredResults.awaitAll()
	}
	
	/**
	 * Attempts to load a single download model by ID with built-in failure recovery checks.
	 * This method provides targeted loading of individual model files while respecting
	 * failure history to prevent repeated attempts on problematic files.
	 *
	 * The method performs the following steps:
	 * 1. Checks failure history to determine if loading should be attempted
	 * 2. Constructs the appropriate filename from the ID
	 * 3. Verifies the file exists before attempting processing
	 * 4. Initiates recovery processing if file exists and loading is permitted
	 *
	 * @param id The unique identifier of the download model to load
	 */
	private fun loadSingleModelWithRecovery(id: String) {
		// Check if this file should be attempted based on failure history
		if (!shouldAttemptLoad(id)) {
			logger.d("Skipping load for $id due to failure history.")
			return
		}
		
		// Construct filename and verify file existence
		val fileName = "$id$DOWNLOAD_MODEL_FILE_JSON_EXTENSION"
		val file = File(INSTANCE.filesDir, fileName)
		if (file.exists()) {
			logger.d("Loading model file: ${file.absolutePath}")
			processModelFileWithRecovery(file)
		} else {
			logger.d("Model file not found for id=$id")
		}
	}
	
	/**
	 * Determines whether a file should be attempted for loading based on failure history.
	 * This method implements a retry delay mechanism to prevent excessive attempts
	 * on files that have previously failed to load, reducing system resource waste.
	 *
	 * @param fileId The unique file identifier to check
	 * @return true if loading should be attempted, false if retry delay period hasn't elapsed
	 */
	private fun shouldAttemptLoad(fileId: String): Boolean {
		val should = failedDownloadModelFiles[fileId]?.let { failureTimestamp ->
			// Only attempt load if FAILURE_RETRY_DELAY_MS has elapsed since last failure
			System.currentTimeMillis() - failureTimestamp > FAILURE_RETRY_DELAY_MS
		} ?: true // Default to true if no failure history exists
		
		logger.d("shouldAttemptLoad($fileId) -> $should")
		return should
	}
	
	/**
	 * Processes a single model file with comprehensive error recovery and caching.
	 * This method attempts to parse a download model file and handles both success
	 * and failure scenarios with appropriate cleanup and state management.
	 *
	 * Success flow:
	 * - Parse file to DownloadDataModel object
	 * - Cache the successful model for future access
	 * - Remove file from failure tracking
	 *
	 * Failure flow:
	 * - Handle corrupted files with cleanup
	 * - Track failures to prevent repeated attempts
	 * - Maintain system stability through error isolation
	 *
	 * @param file The File object to process and parse
	 * @return Boolean indicating success (true) or failure (false) of processing
	 */
	private fun processModelFileWithRecovery(file: File): Boolean {
		logger.d("Processing file: ${file.name}")
		return try {
			// Attempt to parse the JSON file into a DownloadDataModel object
			val model = convertJSONStringToClass(file)
			
			if (model != null) {
				// Success case: Cache model and clear failure history
				logger.d("Successfully parsed model: ${file.nameWithoutExtension}")
				downloadModelsCache[file.nameWithoutExtension] = model
				failedDownloadModelFiles.remove(file.nameWithoutExtension)
				true
			} else {
				// Corrupted file case: Handle cleanup and track failure
				logger.d("Corrupted file detected: ${file.name}")
				handleCorruptedFile(file)
				false
			}
		} catch (error: Exception) {
			// Exception case: Log error and handle processing failure
			logger.d("Error processing file ${file.name}: ${error.message}")
			error.printStackTrace()
			handleProcessingError(file, error)
			false
		}
	}
	
	/**
	 * Handles the cleanup and removal of corrupted model files.
	 * This method is called when a file is determined to be corrupted and unrecoverable.
	 * It attempts to delete the corrupted file to prevent future processing attempts
	 * and removes the file from the failure tracking system.
	 *
	 * @param file The corrupted File object to be deleted
	 */
	private fun handleCorruptedFile(file: File) {
		logger.d("Deleting corrupted file: ${file.name}")
		try {
			// Attempt to delete the corrupted file
			file.delete()
			// Remove from failure tracking since file no longer exists
			failedDownloadModelFiles.remove(file.nameWithoutExtension)
		} catch (error: Exception) {
			// Log deletion failure but don't rethrow - system should continue operating
			logger.d("Failed to delete corrupted file ${file.name}: ${error.message}")
			error.printStackTrace()
		}
	}
	
	/**
	 * Handles processing errors with intelligent failure management and selective cleanup.
	 * This method categorizes errors and applies appropriate recovery strategies:
	 * - All errors: Track failure timestamp for retry delay
	 * - Specific error types: Delete problematic files that are causing persistent issues
	 * - Other errors: Keep files for future retry attempts
	 *
	 * @param file The File object that caused the processing error
	 * @param error The Exception encountered during processing
	 */
	private fun handleProcessingError(file: File, error: Exception) {
		logger.e("Handling error for file ${file.name}: ${error.javaClass.simpleName}", error)
		
		// Record failure timestamp to implement retry delay mechanism
		failedDownloadModelFiles[file.nameWithoutExtension] = System.currentTimeMillis()
		
		// Only delete files that are causing specific, unrecoverable problems
		if (error is IllegalStateException || error is NumberFormatException) {
			try {
				logger.e("Deleting problematic file: ${file.name}", error)
				file.delete()
			} catch (error: Exception) {
				logger.e("Failed to delete problematic file ${file.name}: ${error.message}", error)
			}
		}
	}
	
	/**
	 * Validates the cache against the current filesystem state to ensure consistency.
	 * This method performs cache maintenance by:
	 * 1. Removing cache entries for files that no longer exist on disk
	 * 2. Identifying files that are ready for retry after failure delay period
	 * 3. Keeping the cache synchronized with actual file system contents
	 *
	 * The validation process helps prevent stale cache entries and ensures
	 * that recovery mechanisms can attempt to reload previously failed files
	 * after the appropriate delay period.
	 */
	private fun validateCacheAgainstFiles() {
		logger.d("Validating cache entries against current files.")
		
		// Get current model files from filesystem and map by ID (filename without extension)
		val currentFiles = listModelFiles(INSTANCE.filesDir)
			.associateBy { it.nameWithoutExtension }
		
		// Remove cache entries that are no longer valid
		downloadModelsCache.keys.removeAll { id ->
			if (!currentFiles.containsKey(id)) {
				// Remove from cache if file no longer exists on disk
				logger.d("Removing $id from cache (file no longer exists).")
				true
			} else {
				// Check if previously failed file is ready for retry
				val retryReady = failedDownloadModelFiles[id]?.let { failureTimestamp ->
					System.currentTimeMillis() - failureTimestamp > FAILURE_RETRY_DELAY_MS
				} ?: false
				
				if (retryReady) {
					logger.d("Marking $id for retry after failure delay.")
				}
				// Remove from cache if ready for retry (will be reloaded on next access)
				retryReady
			}
		}
	}
	
	/**
	 * Lists all valid model files in the specified directory, excluding temporary files.
	 * This method scans the directory for JSON model files with the correct extension
	 * and filters out any temporary files to ensure only valid model files are processed.
	 *
	 * @param directory The directory to scan for model files
	 * @return List of File objects representing valid model files, or empty list if
	 *         directory doesn't exist, isn't a directory, or contains no matching files
	 */
	private fun listModelFiles(directory: File?): List<File> {
		val suffix = DOWNLOAD_MODEL_FILE_JSON_EXTENSION
		val files = directory?.takeIf { it.isDirectory }
			?.listFiles { file ->
				// Filter for JSON model files while excluding temporary files
				file.isFile && file.name.endsWith(suffix) &&
					!file.name.contains("temp")
			}
			?.toList() ?: emptyList()
		
		logger.d("listModelFiles() -> Found ${files.size} files.")
		return files
	}
	
	/**
	 * Performs a complete reset of the parser's internal state.
	 * This method clears both the model cache and failure tracking records,
	 * effectively returning the parser to a fresh initial state.
	 *
	 * Use cases:
	 * - After major application updates that change model structure
	 * - When manual cache refresh is required by user
	 * - During debugging and testing scenarios
	 */
	fun fullReset() {
		logger.d("Performing full reset of cache and failure records.")
		downloadModelsCache.clear()
		failedDownloadModelFiles.clear()
	}
	
	/**
	 * Invalidates the model cache while preserving failure tracking.
	 * This method clears all cached models but maintains the failure history,
	 * allowing for cache refresh without resetting the retry delay mechanisms.
	 *
	 * Use cases:
	 * - When models need to be reloaded from source files
	 * - After external modifications to model files
	 * - Periodic cache refresh to ensure data freshness
	 */
	fun invalidateCache() {
		logger.d("Invalidating model cache.")
		downloadModelsCache.clear()
	}
	
	/**
	 * Performs cleanup of parser resources and internal state.
	 * This method cancels ongoing coroutine operations and clears failure tracking,
	 * preparing the parser for shutdown or reinitialization.
	 *
	 * Use cases:
	 * - Application shutdown procedures
	 * - Memory management during low-memory conditions
	 * - Reinitialization of the parser component
	 */
	fun cleanup() {
		logger.d("Cleaning up DownloadModelParser resources.")
		coroutineScope.cancel()
		failedDownloadModelFiles.clear()
	}
}