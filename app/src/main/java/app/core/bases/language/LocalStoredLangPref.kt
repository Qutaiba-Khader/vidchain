package app.core.bases.language

import android.content.*
import app.core.bases.language.LocalStoredLangPref.contains
import app.core.bases.language.LocalStoredLangPref.init
import app.core.bases.language.LocalStoredLangPref.languageCode
import java.io.*

/**
 * Manages the persistence of the application's selected language preference using a local file.
 *
 * This singleton object provides a simple mechanism to save and retrieve the user's
 * chosen language code, ensuring the setting persists across application sessions. It uses a
 * plain text file (`language_config/code.txt`) in the app's private file directory.
 *
 * **Important:** The [init] method must be called once during application startup
 * before any other properties or methods are accessed.
 *
 * @see languageCode to get or set the current language.
 * @see init to initialize the storage.
 */
object LocalStoredLangPref {
	
	/**
	 * Directory where language configuration files are stored (`<app_files>/language_config`).
	 *
	 * This property is initialized by the [init] function and should not be accessed before
	 * [init] is called. It represents the private directory within the app's internal storage
	 * dedicated to managing language preference files.
	 */
	private lateinit var languageConfigDir: File
	
	/**
	 * Initializes the language preference storage.
	 *
	 * This method creates the necessary directory (`language_config`) for storing the language
	 * preference file within the application's private files directory. It must be called once,
	 * typically during application startup (e.g., in an `Application` class), before any other
	 * methods of this object are used.
	 *
	 * Calling this method ensures that the storage mechanism is ready for read and write operations.
	 *
	 * @param context The application context, used to access the app's file system.
	 */
	fun init(context: Context) {
		languageConfigDir = File(context.filesDir, "language_config")
		languageConfigDir.mkdirs()
	}
	
	/**
	 * Represents the current language code, persisted by the file's name.
	 *
	 * This property reads and writes the language code by managing files within the
	 * `language_config` directory. The language code is determined by the name of the file present.
	 *
	 * **Get**: Scans the directory for a file with a `.langcode` extension. It returns the
	 * file's name without the extension (e.g., "en" from "en.langcode"). If no such file is
	 * found, or if an error occurs, it defaults to "en".
	 *
	 * **Set**: Deletes any existing `.langcode` file in the directory and then creates a new,
	 * empty file named `{value}.langcode` (e.g., setting the value to "es" creates "es.langcode").
	 *
	 * @return The stored language code as a [String], defaulting to "en" if not set or an error occurs.
	 */
	var languageCode: String
		get() = runCatching {
			if (::languageConfigDir.isInitialized && languageConfigDir.exists()) {
				// Find the .langcode file in the directory
				val langFile = languageConfigDir.listFiles { file ->
					file.isFile && file.extension == "langcode"
				}?.firstOrNull()
				
				langFile?.nameWithoutExtension ?: "en"
			} else {
				"en"
			}
		}.getOrDefault("en")
		set(value) {
			runCatching {
				// Clear existing language files
				clear()
				
				// Create new language file
				val langFile = File(languageConfigDir, "$value.langcode")
				langFile.createNewFile()
			}
		}
	
	/**
	 * Deletes all stored language preference files.
	 *
	 * This action clears any previously saved language setting by removing all `.langcode`
	 * files from the configuration directory. Consequently, the next time the language is
	 * accessed via [languageCode], it will fall back to the default ("en").
	 *
	 * This function is safe to call even if the storage has not been initialized or if no
	 * preference is currently set.
	 */
	fun clear() {
		runCatching {
			if (::languageConfigDir.isInitialized && languageConfigDir.exists()) {
				languageConfigDir.listFiles { file ->
					file.isFile && file.extension == "langcode"
				}?.forEach { it.delete() }
			}
		}
	}
	
	/**
	 * Checks if a custom language preference has been explicitly stored.
	 *
	 * This function determines whether a language preference file (with a `.langcode` extension)
	 * exists in the configuration directory. It is useful for distinguishing between a
	 * user-selected language and the application's default or fallback language.
	 *
	 * It is safe to call this function even if [init] has not been called.
	 *
	 * @return `true` if a language configuration file exists, `false` otherwise.
	 */
	fun contains(): Boolean {
		return runCatching {
			::languageConfigDir.isInitialized &&
				languageConfigDir.exists() &&
				languageConfigDir.listFiles { file ->
					file.isFile && file.extension == "langcode"
				}?.isNotEmpty() == true
		}.getOrDefault(false)
	}
	
	/**
	 * Retrieves the raw language configuration file, if one exists.
	 *
	 * This function provides direct access to the underlying file used to store the
	 * language preference. It scans the `language_config` directory for a file with
	 * the `.langcode` extension. The presence of such a file indicates that a language
	 * preference has been explicitly set.
	 *
	 * This can be useful for diagnostics or for migration logic where direct file
	 * access is needed. For general-purpose language code access, prefer using
	 * the [languageCode] property.
	 *
	 * @return The [File] object representing the language configuration file (e.g., `en.langcode`),
	 *         or `null` if no such file is found or if the storage has not been initialized.
	 * @see languageCode For retrieving the language code as a string.
	 * @see contains To check for the existence of a preference without needing the file itself.
	 */
	fun getLanguageFile(): File? {
		return runCatching {
			if (::languageConfigDir.isInitialized && languageConfigDir.exists()) {
				languageConfigDir.listFiles { file ->
					file.isFile && file.extension == "langcode"
				}?.firstOrNull()
			} else {
				null
			}
		}.getOrNull()
	}
}