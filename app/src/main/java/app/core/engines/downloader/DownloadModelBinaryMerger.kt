package app.core.engines.downloader

import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.engines.fst_serializer.FSTBuilder.fstConfig
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_FILE_JSON_EXTENSION
import lib.process.LogHelperUtils
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DownloadModelMerger - Merges and Maintains a Unified Binary of Download Models
 *
 * Responsibilities:
 * 1. Periodically checks individual JSON model files and merges them into a single binary file.
 * 2. Keeps the merged binary up-to-date by comparing modification timestamps.
 * 3. Provides fast retrieval of merged models to reduce I/O overhead.
 * 4. Runs in a background thread to avoid blocking the main thread.
 *
 * Features:
 * - Thread-safe operations using AtomicBoolean and @Synchronized
 * - Single-threaded executor to serialize merge operations
 * - Incremental updates based on file timestamps
 * - Avoids unnecessary writes if the merged file is already up-to-date
 */
class DownloadModelBinaryMerger {

	companion object {
		/** Filename used for storing merged binary of download models */
		const val MERGRED_DATA_MODEL_BINARY_FILENAME = "merged_data_binary.dat"
	}

	/** Logger instance for tracking merge operations and debugging */
	private val logger = LogHelperUtils.from(javaClass)

	/** Single-thread executor for running merge loop in background */
	private val executor = Executors.newSingleThreadExecutor()

	/** Ensures that only one merge operation runs at a time */
	private val isRunning = AtomicBoolean(false)

	/** Interval between merge checks in milliseconds */
	private val loopInterval = 5000L

	/**
	 * Attempts to load the merged binary data if it exists and is valid.
	 * This method serves as the public interface for retrieving pre-merged download models
	 * from the binary cache file, providing faster access than loading individual JSON files.
	 *
	 * @return List of DownloadDataModel objects if a valid merged file exists and is current,
	 *         null if no merged file exists or if it's outdated
	 */
	fun loadMergedDataModelIfPossible(): List<DownloadDataModel>? = loadMergedBinaryData()

	/**
	 * Starts a continuous background loop that periodically merges modified download data models.
	 * This method runs on a dedicated executor thread and performs periodic maintenance by:
	 * - Checking for modified download models that need to be merged
	 * - Using atomic compare-and-set to ensure only one merge operation runs at a time
	 * - Handling errors gracefully without breaking the loop
	 * - Sleeping for a configured interval between merge attempts
	 *
	 * The loop continues indefinitely until the thread is interrupted, providing ongoing
	 * background maintenance for download model data consistency.
	 */
	fun startLoop() {
		executor.execute {
			while (true) {
				// Only proceed if no other merge is currently running
				// Uses atomic compare-and-set for thread-safe operation state management
				if (isRunning.compareAndSet(false, true)) {
					try {
						// Perform the merge operation for modified download data models
						mergeModifiedDownloadDataModels()
					} catch (error: Exception) {
						// Log any errors during merge operation but continue the loop
						logger.e("Error in merging array list of download data models:", error)
					} finally {
						// Always reset the running flag, even if an exception occurs
						isRunning.set(false)
					}
				}
				try {
					// Sleep for the configured interval before next merge check
					Thread.sleep(loopInterval)
				} catch (error: InterruptedException) {
					// Break the loop if thread is interrupted (e.g., during app shutdown)
					logger.e("Error found in sleeping the background thread:", error)
					break
				}
			}
		}
	}

	/**
	 * Merges modified download data models into a single binary file for efficient loading.
	 * This method checks if any individual JSON download model files have been modified
	 * since the last merged binary file was created, and if so, creates an updated merged file.
	 *
	 * The process includes:
	 * 1. Checking timestamps of all JSON files against the merged binary file
	 * 2. Determining if an update is needed (any JSON file is newer than merged file)
	 * 3. Combining active and finished download models while removing duplicates
	 * 4. Saving the combined list to a merged binary file for future fast loading
	 *
	 * This optimization reduces I/O operations when loading multiple download models.
	 */
	private fun mergeModifiedDownloadDataModels() {
		val internalDir = INSTANCE.filesDir
		val mergedBinaryFile = File(internalDir, MERGRED_DATA_MODEL_BINARY_FILENAME)

		// Map of JSON files to their last modified timestamp
		val fileTimestamps = loadFileModifiedTimestamps(internalDir)
		val mergedTimestamp = mergedBinaryFile.lastModified()

		// Determine if any file is newer than the merged file
		val needsUpdate = fileTimestamps.values.any { it > mergedTimestamp }
		if (!needsUpdate) return // No updates needed

		// Combine active and finished download models and remove duplicates
		val downloadSystem = AIOApp.downloadSystem
		val allDownloadModels = (
				downloadSystem.activeDownloadDataModels +
						downloadSystem.finishedDownloadDataModels)
			.distinctBy { it.downloadId }
			.toMutableList()

		if (allDownloadModels.isEmpty()) return // Nothing to save
		saveToBinary(mergedBinaryFile, allDownloadModels)
	}

	/**
	 * Loads the last modified timestamps of all download model JSON files in a directory.
	 * This method scans the specified directory for JSON files with the download model extension
	 * and returns a mapping of each file to its last modified timestamp.
	 *
	 * @param directory The directory to scan for JSON download model files
	 * @return Map of File objects to their last modified timestamps, or empty map if directory
	 *         doesn't exist, isn't a directory, or contains no matching files
	 */
	private fun loadFileModifiedTimestamps(directory: File?): Map<File, Long> {
		val suffix = DOWNLOAD_MODEL_FILE_JSON_EXTENSION
		return directory?.takeIf { it.isDirectory }
			?.listFiles { file ->
				// Filter for JSON download model files, excluding temporary files
				file.isFile && file.name.endsWith(suffix) && !file.name.contains("temp")
			}
			?.associateWith { it.lastModified() } ?: emptyMap()
	}

	/**
	 * Saves a list of DownloadDataModel objects to a binary file using FST serialization.
	 * This synchronized method ensures thread-safe serialization and file writing operations
	 * when persisting multiple download models to a single merged binary file.
	 *
	 * The method performs the following operations:
	 * 1. Serializes the list of DownloadDataModel objects to byte array using FST configuration
	 * 2. Writes the serialized bytes to the specified file using output stream
	 * 3. Logs the success with file path and serialized data size
	 * 4. Handles and logs any exceptions during the serialization or file writing process
	 *
	 * @param mergedBinaryFile The target File where the binary data will be saved
	 * @param data The List of DownloadDataModel objects to serialize and save
	 */
	@Synchronized
	private fun saveToBinary(mergedBinaryFile: File, data: List<DownloadDataModel>) {
		try {
			// Serialize the list of DownloadDataModel objects to byte array using FST
			val bytes = fstConfig.asByteArray(data)

			// Write the serialized bytes to the file using try-with-resources pattern
			mergedBinaryFile.outputStream().use { it.write(bytes) }

			// Log successful save operation with file path and data size
			logger.d("Binary save successful: $mergedBinaryFile, size: ${bytes.size} bytes")
		} catch (error: Exception) {
			// Log any errors that occur during serialization or file writing
			logger.e("Binary save error for file: $mergedBinaryFile", error)
		}
	}

	/**
	 * Loads download models from a merged binary file if it exists and is up-to-date.
	 * This method attempts to load all download models from a single merged binary file
	 * for improved performance compared to loading individual JSON files.
	 *
	 * The method performs several checks before loading:
	 * 1. Verifies the merged binary file exists
	 * 2. Checks if the merged file is newer than all individual JSON files
	 * 3. Only proceeds if the merged file contains the most recent data
	 *
	 * Performance metrics are logged to track file reading and deserialization times.
	 *
	 * @return List of DownloadDataModel objects if successfully loaded, null otherwise
	 */
	@Synchronized
	private fun loadMergedBinaryData(): List<DownloadDataModel>? {
		val internalDir = INSTANCE.filesDir
		val mergedBinaryFile = File(internalDir, MERGRED_DATA_MODEL_BINARY_FILENAME)

		// Return early if merged binary file doesn't exist
		if (!mergedBinaryFile.exists()) return null

		// Load timestamps of all individual JSON files for comparison
		val fileTimestamps = loadFileModifiedTimestamps(internalDir)
		val mergedTimestamp = mergedBinaryFile.lastModified()

		// Only load from merged file if it's newer than all individual JSON files
		// This ensures we don't load stale data from the merged file
		val isMergedNewer = fileTimestamps.values.all { it <= mergedTimestamp }
		if (!isMergedNewer) return null

		return try {
			// Measure file reading performance
			val startFile = System.currentTimeMillis()
			val bytes = mergedBinaryFile.readBytes()
			val endFile = System.currentTimeMillis()

			// Measure FST deserialization performance
			val startFST = System.currentTimeMillis()
			@Suppress("UNCHECKED_CAST") val list =
				fstConfig.asObject(bytes) as List<DownloadDataModel>
			val endFST = System.currentTimeMillis()

			// Log performance metrics for optimization purposes
			logger.d("Read file: ${endFile - startFile} ms, Deserialize: ${endFST - startFST} ms")
			return list
		} catch (error: Exception) {
			// Log error and return null if loading fails (file may be corrupted)
			logger.e("Failed to load merged binary file", error)
			null
		}
	}
}
