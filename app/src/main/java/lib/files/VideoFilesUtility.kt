package lib.files

import com.googlecode.mp4parser.FileDataSourceImpl
import com.googlecode.mp4parser.authoring.Movie
import com.googlecode.mp4parser.authoring.builder.DefaultMp4Builder
import com.googlecode.mp4parser.authoring.container.mp4.MovieCreator
import lib.process.LogHelperUtils
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object VideoFilesUtility {
	private val logger = LogHelperUtils.from(javaClass)

	const val TMP_MOOV_OPTIMIZED_PREFIX = "moov_optimize_"
	const val TMP_MOOV_OPTIMIZED_SUFFIX = ".mp4"

	/**
	 * Performs an advanced validation of an MP4 file using the MP4Parser library.
	 * Ensures that the file structure is intact by checking that it contains at least one track
	 * and that each track has non-empty samples. This helps detect corrupted or incomplete files.
	 *
	 * @param file The MP4 file to validate
	 * @return true if the file structure is valid and contains playable content, false otherwise
	 */
	@JvmStatic
	fun isValidMp4FileAdvanced(file: File): Boolean {
		// Quick check: file must exist and have a reasonable size
		if (!file.exists() || file.length() < 100) return false

		return try {
			val dataSource = FileDataSourceImpl(file.absolutePath)
			try {
				// Parse the MP4 file into a Movie object
				val movie = MovieCreator.build(dataSource)

				// Validate that the movie has tracks and each track has non-empty samples
				val isValid = movie.tracks.isNotEmpty() && movie.tracks.all { it.samples.isNotEmpty() }
				if (!isValid) logger.d("Advanced validation failed: empty tracks or samples")

				isValid
			} catch (error: Exception) {
				logger.e("Advanced MP4 validation failed:", error)
				false

			} finally {
				// Ensure the data source is closed
				dataSource.close()
			}
		} catch (error: Exception) {
			logger.e("Error during advanced validation:", error)
			false
		}
	}

	/**
	 * Performs a basic validation to check if a file appears to be a valid MP4 file.
	 * This is done by verifying the presence of the 'ftyp' atom in the first 12 bytes of the file.
	 *
	 * @param file The MP4 file to validate
	 * @return true if the file contains the MP4 signature and is likely valid, false otherwise
	 */
	@JvmStatic
	fun isValidMp4File(file: File): Boolean {
		// Ensure the file exists and has at least 12 bytes to read the 'ftyp' signature
		if (!file.exists() || file.length() < 12) {
			logger.d("File does not exist or is too small to be a valid MP4: ${file.absolutePath}")
			return false
		}

		return try {
			// Open the file input stream and read the first 12 bytes
			FileInputStream(file).use { fis ->
				val buffer = ByteArray(12)
				val bytesRead = fis.read(buffer)

				// Ensure we successfully read 12 bytes
				if (bytesRead < 12) {
					logger.d("Failed to read first 12 bytes of file: ${file.absolutePath}")
					return false
				}

				// Extract the 4-byte signature at offset 4 and compare with "ftyp"
				val signature = String(buffer, 4, 4, Charsets.US_ASCII)
				val isValid = (signature == "ftyp")
				if (!isValid) logger.d(
					"File signature mismatch. " +
							"Expected 'ftyp', found '$signature' for ${file.name}"
				)

				isValid
			}
		} catch (error: Exception) {
			logger.e("Error validating MP4 file:", error)
			false
		}
	}

	/**
	 * Moves the 'moov' atom to the beginning of an MP4 file for optimized streaming
	 * using the MP4Parser library. Includes comprehensive validation and atomic operations
	 * to prevent corrupt output files.
	 *
	 * @param inputFile The source MP4 file to be processed
	 * @param outputFile The destination file where the optimized MP4 will be written
	 * @return true if the operation was successful and the output file is valid, false otherwise
	 */
	@JvmStatic
	fun moveMoovAtomToStart(inputFile: File, outputFile: File): Boolean {
		// Pre-validation checks
		if (!inputFile.exists()) {
			logger.e("Input file does not exist: ${inputFile.absolutePath}")
			return false
		}

		if (inputFile.length() == 0L) {
			logger.e("Input file is empty: ${inputFile.absolutePath}")
			return false
		}

		if (!inputFile.canRead()) {
			logger.e("Cannot read input file: ${inputFile.absolutePath}")
			return false
		}

		val outputDir = outputFile.parentFile
		if (outputDir != null && !outputDir.canWrite()) {
			logger.e("Cannot write to output directory: ${outputDir.absolutePath}")
			return false
		}

		val requiredSpace = inputFile.length() * 3 // More conservative estimate
		val availableSpace = outputDir?.freeSpace ?: 0L
		if (availableSpace < requiredSpace) {
			logger.e("Insufficient storage space. Required: $requiredSpace, Available: $availableSpace")
			return false
		}

		// Validate input file is actually an MP4
		if (!isValidMp4File(inputFile)) {
			logger.e("Input file is not a valid MP4 file: ${inputFile.absolutePath}")
			return false
		}

		// Create temporary file in the same directory for atomic operation
		val tempFile = File.createTempFile(
			"$TMP_MOOV_OPTIMIZED_PREFIX${inputFile.name}",
			TMP_MOOV_OPTIMIZED_SUFFIX, outputDir
		)

		return try {
			logger.d("Starting moov atom optimization for: ${inputFile.name}")
			logger.d("Input file size: ${inputFile.length()} bytes")
			logger.d("Using temp file: ${tempFile.absolutePath}")

			// Load the MP4 file into a Movie object with proper resource management
			val dataSource = FileDataSourceImpl(inputFile.absolutePath)
			val movie: Movie = try {
				MovieCreator.build(dataSource)
			} catch (error: Exception) {
				logger.e("Failed to parse MP4 file: ${error.message}")
				dataSource.close()
				return false
			}

			logger.d("MP4 file parsed successfully. Track count: ${movie.tracks.size}")

			if (movie.tracks.isEmpty()) {
				logger.e("No tracks found in movie - cannot process")
				return false
			}

			// Build a new container with moov atom at the beginning
			val mp4Builder = DefaultMp4Builder()
			val container = mp4Builder.build(movie)
			logger.d("New MP4 container built with optimized structure")

			// Write to temporary file first
			FileOutputStream(tempFile).use { fos ->
				val channel = fos.channel
				container.writeContainer(channel)
				channel.force(true) // Force write to disk
			}

			dataSource.close()

			// Validate the temporary file
			if (!isValidMp4File(tempFile)) {
				logger.e(
					"Temporary file validation failed - " +
							"optimization may have corrupted the file"
				)
				tempFile.delete()
				return false
			}

			// Check if output file size is reasonable (within 10% of input)
			val inputSize = inputFile.length()
			val tempSize = tempFile.length()
			val sizeRatio = tempSize.toDouble() / inputSize.toDouble()

			if (sizeRatio < 0.5 || sizeRatio > 1.5) {
				logger.e(
					"Suspicious file size ratio" +
							": $sizeRatio (input: $inputSize, output: $tempSize)"
				)
				tempFile.delete()
				return false
			}

			// Atomic move from temp to final location
			if (tempFile.renameTo(outputFile)) {
				logger.d(
					"Optimization completed successfully. " +
							"Output file size: ${outputFile.length()} bytes"
				)

				logger.d("Output file created at: ${outputFile.absolutePath}")

				// Final validation of the output file
				if (!isValidMp4FileAdvanced(outputFile)) {
					logger.e("Final output file validation failed - file may be corrupt")
					outputFile.delete()
					return false
				}

				true
			} else {
				logger.e("Failed to move temp file to final location")
				false
			}

		} catch (error: Exception) {
			logger.e("Failed to move moov atom to start: ${error.message}", error)

			// Clean up temporary file on failure
			if (tempFile.exists()) {
				try {
					tempFile.delete()
					logger.d("Cleaned up temporary file after failure")
				} catch (cleanupError: Exception) {
					logger.e(
						"Failed to clean up temporary file" +
								": ${cleanupError.message}", cleanupError
					)
				}
			}

			// Clean up output file if it exists (shouldn't with atomic move)
			if (outputFile.exists()) {
				try {
					outputFile.delete()
					logger.d("Cleaned up output file after failure")
				} catch (cleanupError: Exception) {
					logger.e(
						"Failed to clean up output file" +
								": ${cleanupError.message}", cleanupError
					)
				}
			}

			false
		} finally {
			// Ensure temp file is always cleaned up
			if (tempFile.exists()) {
				tempFile.delete()
			}
		}
	}

	/**
	 * Checks if an MP4 file is truly seekable.
	 * Performs two levels of validation:
	 * 1. Checks if the 'moov' atom is near the beginning.
	 * 2. Validates that the movie has tracks and each track contains samples.
	 *
	 * @param file The MP4 file to check
	 * @return true if the file is likely seekable, false otherwise
	 */
	@JvmStatic
	fun isMp4Seekable(file: File): Boolean {
		if (!file.exists() || file.length() < 100) {
			logger.d("File does not exist or is too small: ${file.absolutePath}")
			return false
		}

		// Check if 'moov' atom is near the start
		try {
			FileInputStream(file).use { fis ->
				val buffer = ByteArray(1024 * 1024) // 1MB
				val bytesRead = fis.read(buffer)
				if (bytesRead <= 0) {
					logger.d("Failed to read file: ${file.absolutePath}")
					return false
				}
				val content = buffer.copyOf(bytesRead)
				if (!content.containsMoovAtomAtStart()) {
					logger.d("'moov' atom not found near start: ${file.name}")
					return false
				}
			}
		} catch (error: Exception) {
			logger.e("Error reading file for moov check: ${error.message}", error)
			return false
		}

		// Validate tracks and samples using MP4Parser
		try {
			val dataSource = FileDataSourceImpl(file.absolutePath)
			val movie = MovieCreator.build(dataSource)
			val isValid = movie.tracks.isNotEmpty() &&
					movie.tracks.all { it.samples.isNotEmpty() }
			dataSource.close()

			if (!isValid) {
				logger.d("Movie tracks or samples invalid: ${file.name}")
			}

			return isValid
		} catch (error: Exception) {
			logger.e("MP4Parser validation failed: ${error.message}", error)
			return false
		}
	}

	/**
	 * Checks if this byte array contains a 'moov' atom at the beginning of an MP4 file.
	 * Scans the first part of the file to locate the 'moov' atom and returns true if found
	 * within the first 1KB, indicating the file is already optimized for streaming.
	 */
	fun ByteArray.containsMoovAtomAtStart(): Boolean {
		var i = 0
		while (i + 8 <= this.size) {
			// Read the size of the current atom (4 bytes)
			val size = ((this[i].toInt() and 0xFF) shl 24) or
					((this[i + 1].toInt() and 0xFF) shl 16) or
					((this[i + 2].toInt() and 0xFF) shl 8) or
					(this[i + 3].toInt() and 0xFF)
			if (i + size > this.size || size < 8) break

			// Read the atom type (next 4 bytes)
			val type = String(this, i + 4, 4, Charsets.US_ASCII)

			// Return true if 'moov' is within the first 1KB
			if (type == "moov") return i <= 1024
			i += size
		}
		return false
	}
}