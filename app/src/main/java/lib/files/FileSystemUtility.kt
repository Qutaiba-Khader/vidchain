@file:Suppress("DEPRECATION")

package lib.files

import android.content.*
import android.content.Context.*
import android.content.Intent.*
import android.net.*
import android.os.*
import android.provider.*
import android.provider.OpenableColumns.*
import android.webkit.*
import androidx.core.net.*
import androidx.documentfile.provider.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOApp.Companion.internalDataFolder
import com.aio.*
import com.anggrayudi.storage.file.*
import lib.files.FileExtensions.ARCHIVE_EXTENSIONS
import lib.files.FileExtensions.DOCUMENT_EXTENSIONS
import lib.files.FileExtensions.IMAGE_EXTENSIONS
import lib.files.FileExtensions.MUSIC_EXTENSIONS
import lib.files.FileExtensions.PROGRAM_EXTENSIONS
import lib.files.FileExtensions.VIDEO_EXTENSIONS
import lib.files.FileSystemUtility.addToMediaStore
import lib.files.FileSystemUtility.getFileExtension
import lib.files.FileSystemUtility.getFileType
import lib.files.FileSystemUtility.isAudioByName
import lib.files.FileSystemUtility.isVideoByName
import lib.files.FileSystemUtility.sanitizeFileNameExtreme
import lib.texts.CommonTextUtils.getText
import java.io.*
import java.net.*
import java.nio.charset.StandardCharsets.*
import java.security.MessageDigest
import java.util.*

/**
 * Utility functions for file operations, such as extracting file information, saving data,
 * handling file extensions, and interacting with Android's media store.
 */
object FileSystemUtility {

	/**
	 * Opens the settings screen where the user can grant "All Files Access" permission.
	 *
	 * On Android 11 (API 30) and above, this opens the special "All files access" permission screen.
	 * On lower versions, it falls back to the standard app settings screen.
	 *
	 * @param context The context used to start the settings activity.
	 */
	@JvmStatic
	fun openAllFilesAccessSettings(context: Context) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
				val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
					data = "package:${context.packageName}".toUri()
				}
				context.startActivity(intent)
			} else {
				// For Android 10 and below, open the app's settings page
				val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
					data = "package:${context.packageName}".toUri()
				}
				context.startActivity(intent)
			}
		} catch (error: Exception) {
			error.printStackTrace()
			// Fallback: open general app settings
			val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
				data = "package:${context.packageName}".toUri()
			}
			context.startActivity(intent)
		}
	}

	/**
	 * Checks whether the application has full access to the device's file system.
	 *
	 * Behavior differs based on the Android version:
	 *
	 * - **Android 11 (API 30) and above**:
	 *   Uses [Environment.isExternalStorageManager] to determine if the app has been
	 *   granted the special `MANAGE_EXTERNAL_STORAGE` ("All files access") permission.
	 *   This permission allows unrestricted access to external storage, but is tightly
	 *   restricted by Google Play policy and must be requested via a system settings page.
	 *
	 * - **Android 10 (API 29) and below**:
	 *   Falls back to checking whether both `READ_EXTERNAL_STORAGE` and
	 *   `WRITE_EXTERNAL_STORAGE` runtime permissions have been granted.
	 *
	 * @param context The [Context] used to check permission status.
	 * @return `true` if the app currently has full access to external storage,
	 *         `false` otherwise.
	 *
	 * @see Environment.isExternalStorageManager
	 * @see android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
	 * @see android.Manifest.permission.READ_EXTERNAL_STORAGE
	 * @see android.Manifest.permission.WRITE_EXTERNAL_STORAGE
	 */
	@JvmStatic
	fun hasFullFileSystemAccess(context: Context): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			// For Android 11 (API 30) and above
			Environment.isExternalStorageManager()
		} else {
			// For older versions, check READ/WRITE external storage
			val readPermission = android.Manifest.permission.READ_EXTERNAL_STORAGE
			val writePermission = android.Manifest.permission.WRITE_EXTERNAL_STORAGE
			val readGranted = context.checkSelfPermission(readPermission) ==
					android.content.pm.PackageManager.PERMISSION_GRANTED
			val writeGranted = context.checkSelfPermission(writePermission) ==
					android.content.pm.PackageManager.PERMISSION_GRANTED
			readGranted && writeGranted
		}
	}

	/**
	 * Updates the Android MediaStore with files from the list of finished downloads.
	 *
	 * This function processes all entries in [downloadSystem.finishedDownloadDataModels],
	 * retrieves the associated downloaded file for each entry, and calls [addToMediaStore]
	 * to insert it into the system's MediaStore. This ensures that downloaded files
	 * (e.g., images, videos, audio, documents) become visible to other apps such as
	 * Gallery, Music, or File Manager without requiring a device reboot or manual refresh.
	 *
	 * Notes:
	 * - If the [downloadSystem] is still initializing, the function will return immediately.
	 * - Exceptions during MediaStore insertion are caught and logged with stack trace,
	 *   preventing crashes but allowing debugging visibility.
	 *
	 * Typical usage:
	 * This function should be called once a download has completed and the app wants
	 * the system to recognize the new file.
	 *
	 * @throws Exception if an unexpected error occurs during MediaStore updates,
	 *         though such errors are caught internally and logged.
	 *
	 * @see android.provider.MediaStore
	 * @see addToMediaStore
	 */
	@JvmStatic
	fun updateMediaStore() {
		try {
			if (downloadSystem.isInitializing) return
			var index = 0
			while (index < downloadSystem.finishedDownloadDataModels.size) {
				val model = downloadSystem.finishedDownloadDataModels[index]
				val downloadedFile = model.getDestinationFile()
				addToMediaStore(downloadedFile)
				index++
			}
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/**
	 * Extracts a file name from the `Content-Disposition` HTTP header of a response.
	 *
	 * Many HTTP responses (especially file downloads) include a header like:
	 * ```
	 * Content-Disposition: attachment; filename="example.pdf"
	 * ```
	 * This function parses that header and returns the file name value (`example.pdf` in this case).
	 *
	 * Behavior:
	 * - Uses a case-insensitive regex to locate the `filename` parameter within the header.
	 * - Accepts optional quotes around the filename.
	 * - Returns the extracted file name if found, otherwise returns `null`.
	 *
	 * Example:
	 * ```
	 * val header = "attachment; filename=\"report.csv\""
	 * val fileName = extractFileNameFromContentDisposition(header)
	 * // fileName == "report.csv"
	 * ```
	 *
	 * @param contentDisposition The raw `Content-Disposition` header string from an HTTP response,
	 *                           or `null` if not provided.
	 * @return The extracted file name string if present, or `null` if no valid filename was found.
	 *
	 * @see java.net.HttpURLConnection
	 */
	@JvmStatic
	fun extractFileNameFromContentDisposition(contentDisposition: String?): String? {
		if (!contentDisposition.isNullOrEmpty()) {
			val filenameRegex = """(?i)filename=["']?([^";]+)""".toRegex()
			val filenameMatch = filenameRegex.find(contentDisposition)
			if (filenameMatch != null) {
				val filename = filenameMatch.groupValues[1]
				return filename
			}
		}
		return null
	}

	/**
	 * Decodes a URL-encoded file name into a human-readable string.
	 *
	 * Many servers encode special characters in file names when sending them
	 * via HTTP headers or URLs. For example:
	 *
	 * ```
	 * "report%20final%28v2%29.pdf"  ->  "report final(v2).pdf"
	 * ```
	 *
	 * This function attempts to decode such strings using UTF-8 encoding.
	 *
	 * Behavior:
	 * - Uses [java.net.URLDecoder.decode] with UTF-8.
	 * - If decoding fails due to an unexpected exception, it logs the error
	 *   and safely returns the original (encoded) string instead of crashing.
	 *
	 * Example:
	 * ```
	 * val encoded = "photo%20album%202025.jpg"
	 * val decoded = decodeURLFileName(encoded)
	 * // decoded == "photo album 2025.jpg"
	 * ```
	 *
	 * @param encodedString The URL-encoded file name (e.g., `"file%20name.txt"`).
	 * @return The decoded file name if decoding succeeds,
	 *         or the original `encodedString` if an error occurs.
	 *
	 * @see java.net.URLDecoder
	 */
	@JvmStatic
	fun decodeURLFileName(encodedString: String): String {
		return try {
			val decodedFileName = URLDecoder.decode(encodedString, UTF_8.name())
			decodedFileName
		} catch (error: Exception) {
			error.printStackTrace()
			encodedString
		}
	}

	/**
	 * Retrieves the file name associated with a given [Uri].
	 *
	 * Behavior:
	 * - If the URI has a `"content"` scheme (commonly returned by the Storage Access Framework,
	 *   file pickers, or other content providers), it queries the [ContentResolver] for the
	 *   [android.provider.OpenableColumns.DISPLAY_NAME] column to obtain the file name.
	 * - If the URI has a `"file"` scheme (direct file path), it extracts the file name
	 *   using [java.io.File.name].
	 * - If neither approach works, the function returns `null`.
	 *
	 * Example:
	 * ```
	 * val uri: Uri = ... // e.g. from an Intent or SAF picker
	 * val name = getFileNameFromUri(context, uri)
	 * // name might be "document.pdf" or null if unavailable
	 * ```
	 *
	 * Notes:
	 * - Always returns only the file **name**, not the full path.
	 * - Returns `null` if the query fails, the URI is invalid, or no display name is available.
	 * - Catches and logs exceptions to prevent crashes.
	 *
	 * @param context The [Context] used to access the [ContentResolver].
	 * @param uri The [Uri] pointing to a file, either with `content://` or `file://` scheme.
	 * @return The file name as a [String], or `null` if it cannot be retrieved.
	 *
	 * @see android.provider.OpenableColumns.DISPLAY_NAME
	 * @see android.content.ContentResolver
	 * @see java.io.File
	 */
	@JvmStatic
	fun getFileNameFromUri(context: Context, uri: Uri): String? {
		try {
			var fileName: String? = null
			if ("content" == uri.scheme) {
				val cursor = context.contentResolver.query(uri, null, null, null, null)
				if (cursor != null) {
					if (cursor.moveToFirst()) {
						val nameIndex = cursor.getColumnIndex(DISPLAY_NAME)
						if (nameIndex != -1) {
							fileName = cursor.getString(nameIndex)
						}
					}
					cursor.close()
				}
			} else if ("file" == uri.scheme) {
				fileName = File(uri.path!!).name
			}
			return fileName
		} catch (error: Exception) {
			error.printStackTrace()
			return null
		}
	}

	/**
	 * Converts a given [Uri] into a [File] object.
	 *
	 * Behavior:
	 * - Extracts the raw file system path from [Uri.path].
	 * - If the path is not `null`, wraps it in a [File] object.
	 * - If the URI does not contain a valid path, returns `null`.
	 *
	 * Notes:
	 * - This only works reliably for URIs with a `"file://"` scheme.
	 * - For `"content://"` URIs (e.g., from Storage Access Framework or media providers),
	 *   this may not return a valid file path. In such cases, use
	 *   [ContentResolver.openInputStream] instead.
	 *
	 * @param uri The [Uri] of the file.
	 * @return A [File] object pointing to the URI’s path, or `null` if unavailable.
	 *
	 * @see java.io.File
	 * @see android.net.Uri
	 */
	@JvmStatic
	fun getFileFromUri(uri: Uri): File? {
		return try {
			val filePath = uri.path
			val file = if (filePath != null) File(filePath) else null
			file
		} catch (error: Exception) {
			error.printStackTrace()
			null
		}
	}

	/**
	 * Saves a string of data to the app’s internal storage.
	 *
	 * Behavior:
	 * - Creates (or overwrites) a private file in the app’s internal storage directory.
	 * - Writes the provided string data in UTF-8 encoding.
	 *
	 * Example:
	 * ```
	 * saveStringToInternalStorage("settings.json", "{ \"theme\": \"dark\" }")
	 * ```
	 *
	 * Notes:
	 * - Files saved here are private to the app and cannot be accessed by other apps.
	 * - Data persists across app restarts but is removed when the app is uninstalled.
	 *
	 * @param fileName The name of the file (within internal storage).
	 * @param data The string data to write into the file.
	 * @return `true` if the file was saved successfully, `false` otherwise.
	 *
	 * @see android.content.Context.openFileOutput
	 */
	@JvmStatic
	fun saveStringToInternalStorage(fileName: String, data: String): Boolean {
		return try {
			val context = INSTANCE
			val fileOutputStream = context.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.write(data.toByteArray())
			fileOutputStream.close()
			true
		} catch (error: Exception) {
			error.printStackTrace()
			false
		}
	}

	/**
	 * Reads a string of data from a file in the app’s internal storage.
	 *
	 * Behavior:
	 * - Opens a file from the app’s internal storage directory.
	 * - Reads its contents fully as a UTF-8 encoded string.
	 * - Returns the string to the caller.
	 *
	 * Example:
	 * ```
	 * val json = readStringFromInternalStorage("settings.json")
	 * // json == "{ \"theme\": \"dark\" }"
	 * ```
	 *
	 * Notes:
	 * - Throws an exception if the file does not exist or cannot be read.
	 * - Use with try/catch if the file may not always exist.
	 *
	 * @param fileName The name of the file to read from internal storage.
	 * @return The contents of the file as a UTF-8 [String].
	 * @throws Exception If the file cannot be opened or read.
	 *
	 * @see android.content.Context.openFileInput
	 */
	@JvmStatic
	fun readStringFromInternalStorage(fileName: String): String {
		val context = INSTANCE
		return try {
			val fileInputStream: FileInputStream = context.openFileInput(fileName)
			val content = fileInputStream.readBytes().toString(Charsets.UTF_8)
			fileInputStream.close()
			content
		} catch (error: Exception) {
			throw error
		}
	}

	/**
	 * Sanitizes a file name by aggressively replacing invalid characters with underscores.
	 *
	 * Behavior:
	 * - Replaces all characters not in `[a-zA-Z0-9()@[]_.-]` with `_`.
	 * - Also replaces spaces with underscores.
	 * - Collapses multiple consecutive underscores into a single underscore.
	 *
	 * Use this version when you need a "strict" sanitization that strips almost everything
	 * except alphanumeric characters and a handful of safe symbols.
	 *
	 * Example:
	 * ```
	 * val name = "my*illegal:file?.txt"
	 * val safe = sanitizeFileNameExtreme(name)
	 * // safe == "my_illegal_file.txt"
	 * ```
	 *
	 * @param fileName The original file name to sanitize.
	 * @return A sanitized, file-system-safe file name.
	 */
	@JvmStatic
	fun sanitizeFileNameExtreme(fileName: String): String {
		val sanitizedFileName = fileName.replace(Regex("[^a-zA-Z0-9()@\\[\\]_.-]"), "_")
			.replace(" ", "_")
			.replace("___", "_")
			.replace("__", "_")
		return sanitizedFileName
	}

	/**
	 * Sanitizes a file name by removing or replacing invalid characters, while being
	 * less strict than [sanitizeFileNameExtreme].
	 *
	 * Behavior:
	 * - Replaces disallowed characters (`\ / : * ? " < > |`), control characters,
	 *   and non-printable ASCII characters with `_`.
	 * - Trims trailing dots and whitespace.
	 * - Replaces spaces with underscores.
	 * - Collapses multiple consecutive underscores into one.
	 *
	 * This produces a more natural file name while still being safe for most file systems.
	 *
	 * Example:
	 * ```
	 * val name = "Report 2025: Final*.pdf"
	 * val safe = sanitizeFileNameNormal(name)
	 * // safe == "Report_2025_Final.pdf"
	 * ```
	 *
	 * @param fileName The original file name to sanitize.
	 * @return A cleaned and file-system-safe file name.
	 */
	@JvmStatic
	fun sanitizeFileNameNormal(fileName: String): String {
		val sanitizedFileName = fileName
			.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}\u0000-\u001F\u007F]"), "_")
			.trimEnd('.')
			.trim()
			.replace(Regex("_+"), "_")
			.replace(" ", "_")
			.replace("___", "_")
			.replace("__", "_")
		return sanitizedFileName
	}

	/**
	 * Checks whether a given file name is valid and writable in the app’s internal storage.
	 *
	 * Behavior:
	 * - Attempts to create a temporary file with the given name inside the app’s internal
	 *   storage directory.
	 * - If creation and deletion succeed, the file name is considered valid.
	 * - If an [IOException] occurs, the file name is considered invalid.
	 *
	 * Example:
	 * ```
	 * val valid = isFileNameValid("report.txt")
	 * // returns true if the file can be created in internal storage
	 * ```
	 *
	 * Notes:
	 * - This check does not guarantee cross-platform safety (e.g., Windows vs Linux),
	 *   only that it works on the current Android environment.
	 * - Any file created during the check is immediately deleted.
	 *
	 * @param fileName The file name to validate.
	 * @return `true` if the file name is valid and usable in internal storage,
	 *         `false` otherwise.
	 *
	 * @see java.io.File.createNewFile
	 */
	@JvmStatic
	fun isFileNameValid(fileName: String): Boolean {
		return try {
			val directory = File(internalDataFolder.getAbsolutePath(INSTANCE))
			val tempFile = File(directory, fileName)
			tempFile.createNewFile()
			tempFile.delete()
			true
		} catch (error: IOException) {
			error.printStackTrace()
			false
		}
	}

	/**
	 * Checks if a given [DocumentFile] is writable.
	 *
	 * Behavior:
	 * - Returns `true` if the [DocumentFile] is non-null and [DocumentFile.canWrite] reports true.
	 * - Returns `false` if the file is `null` or not writable.
	 *
	 * Example:
	 * ```
	 * val file: DocumentFile = ...
	 * val canWrite = isWritableFile(file)
	 * // true if the app can modify the file
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return `true` if the file is writable, `false` otherwise.
	 *
	 * @see androidx.documentfile.provider.DocumentFile
	 */
	@JvmStatic
	fun isWritableFile(file: DocumentFile?): Boolean {
		val isWritable = file?.canWrite() ?: false
		return isWritable
	}

	/**
	 * Checks if a given folder has write access by attempting to create and delete a temporary file.
	 *
	 * Behavior:
	 * - Creates a small temporary file (`temp_check_file.txt`) inside the folder.
	 * - Writes a short test string to the file, then deletes it.
	 * - If all operations succeed, the folder is considered writable.
	 *
	 * Example:
	 * ```
	 * val folder: DocumentFile = ...
	 * val hasAccess = hasWriteAccess(folder)
	 * // true if the app can create and modify files in the folder
	 * ```
	 *
	 * Notes:
	 * - The temporary file is always cleaned up after the test.
	 * - If folder is `null`, the function immediately returns `false`.
	 * - Exceptions are caught and logged, and result in `false`.
	 *
	 * @param folder The [DocumentFile] representing a directory.
	 * @return `true` if the folder allows writing, `false` otherwise.
	 *
	 * @see androidx.documentfile.provider.DocumentFile
	 */
	@JvmStatic
	fun hasWriteAccess(folder: DocumentFile?): Boolean {
		if (folder == null) {
			return false
		}

		return try {
			val tempFile = folder.createFile("text/plain", "temp_check_file.txt")
			if (tempFile != null) {
				INSTANCE.contentResolver.openOutputStream(tempFile.uri)?.use { stream ->
					stream.write("test".toByteArray())
					stream.flush()
				}
				tempFile.delete()
				true
			} else false
		} catch (error: IOException) {
			error.printStackTrace()
			false
		}
	}

	/**
	 * Creates an empty placeholder file of a specified size in the given [DocumentFile].
	 *
	 * Behavior:
	 * - Opens an [OutputStream] to the target [DocumentFile].
	 * - Writes a zero-filled byte array of length [fileSize].
	 * - Flushes and closes the stream.
	 *
	 * Example:
	 * ```
	 * val file: DocumentFile = ...
	 * val created = writeEmptyFile(context, file, 1024L)
	 * // Creates a 1 KB empty file at the target location
	 * ```
	 *
	 * Notes:
	 * - The file content will be a block of zero bytes.
	 * - If writing fails, the function logs the exception and returns `false`.
	 *
	 * @param context The [Context] used to access the [ContentResolver].
	 * @param file The target [DocumentFile] to write into.
	 * @param fileSize The size in bytes of the empty file to create.
	 * @return `true` if the file was created successfully, `false` otherwise.
	 *
	 * @see android.content.ContentResolver.openOutputStream
	 * @see androidx.documentfile.provider.DocumentFile
	 */
	@JvmStatic
	fun writeEmptyFile(context: Context, file: DocumentFile, fileSize: Long): Boolean {
		return try {
			val contentResolver: ContentResolver = context.contentResolver
			val outputStream: OutputStream? = contentResolver.openOutputStream(file.uri)

			outputStream?.use { stream ->
				val placeholder = ByteArray(fileSize.toInt())
				stream.write(placeholder)
				stream.flush()
			}
			true
		} catch (error: IOException) {
			error.printStackTrace()
			false
		}
	}

	/**
	 * Generates a unique file name by appending or incrementing a number prefix
	 * if the file already exists in the given directory.
	 *
	 * Behavior:
	 * - Sanitizes the original file name using [sanitizeFileNameExtreme].
	 * - Checks if a file with the same name exists in [fileDirectory].
	 * - If so, it prefixes the name with an incrementing number (`1_`, `2_`, etc.).
	 * - Continues until a non-conflicting name is found.
	 *
	 * Example:
	 * ```
	 * val uniqueName = generateUniqueFileName(directory, "myfile.txt")
	 * // If "myfile.txt" exists, result could be "1_myfile.txt", "2_myfile.txt", etc.
	 * ```
	 *
	 * Notes:
	 * - If the file name already has a numeric prefix, the function increments it.
	 *
	 * @param fileDirectory The [DocumentFile] directory where the file will be stored.
	 * @param originalFileName The original file name to sanitize and make unique.
	 * @return A unique file name string guaranteed not to conflict in [fileDirectory].
	 */
	@JvmStatic
	fun generateUniqueFileName(fileDirectory: DocumentFile, originalFileName: String): String {
		var sanitizedFileName = sanitizeFileNameExtreme(originalFileName)
		var index = 1
		val regex = Regex("^(\\d+)_")

		while (fileDirectory.findFile(sanitizedFileName) != null) {
			val matchResult = regex.find(sanitizedFileName)
			if (matchResult != null) {
				val currentIndex = matchResult.groupValues[1].toInt()
				sanitizedFileName = sanitizedFileName.replaceFirst(regex, "")
				index = currentIndex + 1
			}
			sanitizedFileName = "${index}_$sanitizedFileName"
			index++
		}

		return sanitizedFileName
	}

	/**
	 * Finds the first file in the given [File] directory whose name starts with [namePrefix].
	 *
	 * Behavior:
	 * - Iterates over files in [internalDir].
	 * - Returns the first file that is a regular file (not a directory) and has a name starting with [namePrefix].
	 * - Returns `null` if no matching file is found or if [internalDir] is empty.
	 *
	 * Example:
	 * ```
	 * val file = findFileStartingWith(File("/data/user/0/app/files"), "log_")
	 * // Finds "log_2023.txt" if it exists
	 * ```
	 *
	 * @param internalDir The [File] directory to search within.
	 * @param namePrefix The prefix string to match at the start of file names.
	 * @return The first matching [File], or `null` if no match is found.
	 */
	@JvmStatic
	fun findFileStartingWith(internalDir: File, namePrefix: String): File? {
		val result = internalDir.listFiles()?.find {
			it.isFile && it.name.startsWith(namePrefix)
		}
		return result
	}

	/**
	 * Creates a new directory inside the given [parentFolder].
	 *
	 * Behavior:
	 * - Calls [DocumentFile.createDirectory] on [parentFolder].
	 * - Returns the newly created directory if successful.
	 * - Returns `null` if [parentFolder] is null or if creation fails.
	 *
	 * Example:
	 * ```
	 * val newDir = makeDirectory(parent, "images")
	 * // Creates a subfolder "images" under parent
	 * ```
	 *
	 * @param parentFolder The parent [DocumentFile] where the new directory should be created.
	 * @param folderName The desired name of the new directory.
	 * @return The new [DocumentFile] directory, or `null` if creation fails.
	 */
	@JvmStatic
	fun makeDirectory(parentFolder: DocumentFile?, folderName: String): DocumentFile? {
		val newDirectory = parentFolder?.createDirectory(folderName)
		return newDirectory
	}

	/**
	 * Returns the MIME type of a file based on its extension or, if unknown, by querying the [ContentResolver].
	 *
	 * Behavior:
	 * - Extracts the file extension using [getFileExtension].
	 * - Uses [MimeTypeMap.getMimeTypeFromExtension] for known extensions.
	 * - Falls back to [ContentResolver.getType] if extension is null or unrecognized.
	 *
	 * Example:
	 * ```
	 * val mimeType = getMimeType("song.mp3")
	 * // Returns "audio/mpeg"
	 * ```
	 *
	 * @param fileName The name of the file.
	 * @return The MIME type string (e.g., `"image/png"`) or `null` if it cannot be determined.
	 */
	@JvmStatic
	fun getMimeType(fileName: String): String? {
		val extension = getFileExtension(fileName)?.lowercase(Locale.getDefault())
		val mimeType = extension?.let {
			MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
		} ?: run {
			val uri = "content://$extension".toUri()
			INSTANCE.contentResolver.getType(uri)
		}
		return mimeType
	}

	/**
	 * Extracts the file extension from the given file name.
	 *
	 * Example:
	 * ```
	 * val ext = getFileExtension("document.pdf")
	 * // Returns "pdf"
	 * ```
	 *
	 * @param fileName The name of the file.
	 * @return The file extension (without the dot), or `null` if no extension is present.
	 */
	@JvmStatic
	fun getFileExtension(fileName: String): String? {
		return fileName.substringAfterLast('.', "").takeIf { it.isNotEmpty() }
	}

	/**
	 * Extension function for [DocumentFile] that checks whether its name ends
	 * with one of the provided extensions.
	 *
	 * Example:
	 * ```
	 * val isMusic = file.isFileType(arrayOf("mp3", "wav"))
	 * ```
	 *
	 * @param extensions Array of valid file extensions to check against.
	 * @return `true` if the file ends with one of the extensions, `false` otherwise.
	 */
	@JvmStatic
	fun DocumentFile.isFileType(extensions: Array<String>): Boolean {
		return endsWithExtension(name, extensions)
	}

	/**
	 * Checks if a [DocumentFile] is an audio file based on its extension.
	 *
	 * Example:
	 * ```
	 * val isSong = isAudio(file)
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return `true` if the file matches known audio extensions, `false` otherwise.
	 */
	@JvmStatic
	fun isAudio(file: DocumentFile): Boolean {
		return file.isFileType(MUSIC_EXTENSIONS)
	}

	/**
	 * Checks if a [DocumentFile] is an archive file based on its extension.
	 *
	 * Example:
	 * ```
	 * val isZip = isArchive(file)
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return `true` if the file matches known archive extensions, `false` otherwise.
	 */
	@JvmStatic
	fun isArchive(file: DocumentFile): Boolean {
		return file.isFileType(ARCHIVE_EXTENSIONS)
	}

	/**
	 * Checks if a [DocumentFile] is a program file based on its extension.
	 *
	 * Example:
	 * ```
	 * val isApk = isProgram(file)
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return `true` if the file matches known program extensions, `false` otherwise.
	 */
	@JvmStatic
	fun isProgram(file: DocumentFile): Boolean {
		return file.isFileType(PROGRAM_EXTENSIONS)
	}

	/**
	 * Checks if a [DocumentFile] is a video file based on its extension.
	 *
	 * Example:
	 * ```
	 * val isMovie = isVideo(file)
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return `true` if the file matches known video extensions, `false` otherwise.
	 */
	@JvmStatic
	fun isVideo(file: DocumentFile): Boolean {
		return file.isFileType(VIDEO_EXTENSIONS)
	}

	/**
	 * Checks if a [DocumentFile] is a document file based on its extension.
	 *
	 * Example:
	 * ```
	 * val isDoc = isDocument(file)
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return `true` if the file matches known document extensions, `false` otherwise.
	 */
	@JvmStatic
	fun isDocument(file: DocumentFile): Boolean {
		return file.isFileType(DOCUMENT_EXTENSIONS)
	}

	/**
	 * Checks if a [DocumentFile] is an image file based on its extension.
	 *
	 * Example:
	 * ```
	 * val isPhoto = isImage(file)
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return `true` if the file matches known image extensions, `false` otherwise.
	 */
	@JvmStatic
	fun isImage(file: DocumentFile): Boolean {
		return file.isFileType(IMAGE_EXTENSIONS)
	}

	/**
	 * Returns the type of a [DocumentFile] as a human-readable string based on its extension.
	 *
	 * Behavior:
	 * - Uses [getFileType] with the file’s name to determine type.
	 * - Typical values: `"Audio"`, `"Video"`, `"Image"`, `"Document"`, `"Archive"`, `"Program"`.
	 *
	 * Example:
	 * ```
	 * val type = getFileType(file)
	 * // Returns "Audio" if it's an mp3
	 * ```
	 *
	 * @param file The [DocumentFile] to check.
	 * @return A string representing the file's type.
	 */
	@JvmStatic
	fun getFileType(file: DocumentFile): String {
		return getFileType(file.name)
	}

	/**
	 * Determines the type of a file from its name by checking known extensions.
	 *
	 * Behavior:
	 * - Calls helper functions like [isAudioByName], [isVideoByName], etc.
	 * - Returns a localized string (e.g., "Audio", "Video", "Image") using [getText].
	 * - Falls back to `"Others"` if no known type matches.
	 *
	 * Example:
	 * ```
	 * val type = getFileType("song.mp3")
	 * // Returns "Sounds"
	 * ```
	 *
	 * @param fileName The name of the file (nullable).
	 * @return A localized string representing the file type.
	 */
	@JvmStatic
	fun getFileType(fileName: String?): String {
		return when {
			isAudioByName(fileName) -> getText(R.string.title_sounds)
			isArchiveByName(fileName) -> getText(R.string.title_archives)
			isProgramByName(fileName) -> getText(R.string.title_programs)
			isVideoByName(fileName) -> getText(R.string.title_videos)
			isDocumentByName(fileName) -> getText(R.string.title_documents)
			isImageByName(fileName) -> getText(R.string.title_images)
			else -> getText(R.string.title_others)
		}
	}

	/**
	 * Requests the Android media scanner to index the given file
	 * so that it appears in the system’s media store.
	 *
	 * Behavior:
	 * - Converts the file to a [Uri].
	 * - Sends a broadcast with [Intent.ACTION_MEDIA_SCANNER_SCAN_FILE].
	 * - The system updates media databases (Gallery, Music apps, etc.).
	 *
	 * Example:
	 * ```
	 * addToMediaStore(File("/sdcard/Music/song.mp3"))
	 * ```
	 *
	 * @param file The file to be scanned and added to the media store.
	 */
	@JvmStatic
	fun addToMediaStore(file: File) {
		try {
			val fileUri = Uri.fromFile(file)
			val mediaScanIntent = Intent(ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
				data = fileUri
			}
			INSTANCE.sendBroadcast(mediaScanIntent)
		} catch (error: Exception) {
			error.printStackTrace()
		}
	}

	/**
	 * Checks whether a file name ends with one of the provided extensions.
	 *
	 * Example:
	 * ```
	 * val isMp3 = endsWithExtension("track.mp3", arrayOf("mp3", "wav"))
	 * // Returns true
	 * ```
	 *
	 * @param fileName The name of the file (nullable).
	 * @param extensions The array of valid extensions (without the dot).
	 * @return `true` if the file name ends with one of the extensions, otherwise `false`.
	 */
	@JvmStatic
	fun endsWithExtension(fileName: String?, extensions: Array<String>): Boolean {
		return extensions.any {
			fileName?.lowercase(Locale.getDefault())?.endsWith(".$it") == true
		}
	}

	/**
	 * Checks if a file name belongs to the "Audio" category by extension.
	 *
	 * @param name The file name to check (nullable).
	 * @return `true` if the file name ends with a known audio extension, otherwise `false`.
	 */
	@JvmStatic
	fun isAudioByName(name: String?): Boolean {
		return endsWithExtension(name, MUSIC_EXTENSIONS)
	}

	/**
	 * Checks if a file name belongs to the "Archive" category by extension.
	 *
	 * @param name The file name to check (nullable).
	 * @return `true` if the file name ends with a known archive extension, otherwise `false`.
	 */
	@JvmStatic
	fun isArchiveByName(name: String?): Boolean {
		return endsWithExtension(name, ARCHIVE_EXTENSIONS)
	}

	/**
	 * Checks if a file name belongs to the "Program" category by extension.
	 *
	 * @param name The file name to check (nullable).
	 * @return `true` if the file name ends with a known program extension, otherwise `false`.
	 */
	@JvmStatic
	fun isProgramByName(name: String?): Boolean {
		return endsWithExtension(name, PROGRAM_EXTENSIONS)
	}

	/**
	 * Checks if a file name belongs to the "Video" category by extension.
	 *
	 * @param name The file name to check (nullable).
	 * @return `true` if the file name ends with a known video extension, otherwise `false`.
	 */
	@JvmStatic
	fun isVideoByName(name: String?): Boolean {
		return endsWithExtension(name, VIDEO_EXTENSIONS)
	}

	/**
	 * Checks if a file name belongs to the "Document" category by extension.
	 *
	 * @param name The file name to check (nullable).
	 * @return `true` if the file name ends with a known document extension, otherwise `false`.
	 */
	@JvmStatic
	fun isDocumentByName(name: String?): Boolean {
		return endsWithExtension(name, DOCUMENT_EXTENSIONS)
	}

	/**
	 * Checks if a file name belongs to the "Image" category by extension.
	 *
	 * @param name The file name to check (nullable).
	 * @return `true` if the file name ends with a known image extension, otherwise `false`.
	 */
	@JvmStatic
	fun isImageByName(name: String?): Boolean {
		return endsWithExtension(name, IMAGE_EXTENSIONS)
	}

	/**
	 * Removes the extension part from a file name, if present.
	 *
	 * Behavior:
	 * - Finds the last occurrence of `.` in the file name.
	 * - Returns the substring before the dot.
	 * - If no dot is found, returns the original file name.
	 * - If an exception occurs, returns the original file name as fallback.
	 *
	 * Example:
	 * ```
	 * val name = getFileNameWithoutExtension("document.pdf")
	 * // Returns "document"
	 *
	 * val name2 = getFileNameWithoutExtension("README")
	 * // Returns "README"
	 * ```
	 *
	 * @param fileName The full file name, possibly with extension.
	 * @return The file name without its extension.
	 */
	@JvmStatic
	fun getFileNameWithoutExtension(fileName: String): String {
		return try {
			val dotIndex = fileName.lastIndexOf('.')
			if (dotIndex > 0) fileName.substring(0, dotIndex) else fileName
		} catch (error: Exception) {
			error.printStackTrace()
			fileName
		}
	}

	/**
	 * Computes the SHA-256 cryptographic hash of a given file.
	 *
	 * Behavior:
	 * - Reads the file in chunks of 8192 bytes.
	 * - Updates the [MessageDigest] incrementally.
	 * - Returns the final hash encoded as a lowercase hexadecimal string.
	 *
	 * Example:
	 * ```
	 * val sha256 = getFileSha256(File("/sdcard/Download/sample.zip"))
	 * // Returns a 64-character hex string
	 * ```
	 *
	 * @param file The file to hash.
	 * @return A lowercase hexadecimal string of the file’s SHA-256 hash.
	 * @throws NoSuchAlgorithmException If SHA-256 algorithm is not available.
	 * @throws IOException If the file cannot be read.
	 */
	@JvmStatic
	fun getFileSha256(file: File): String {
		val digest = MessageDigest.getInstance("SHA-256")
		FileInputStream(file).use { input ->
			val buffer = ByteArray(8192)
			var bytesRead: Int
			while (input.read(buffer).also { bytesRead = it } != -1) {
				digest.update(buffer, 0, bytesRead)
			}
		}
		return digest.digest().joinToString("") { "%02x".format(it) }
	}
}
