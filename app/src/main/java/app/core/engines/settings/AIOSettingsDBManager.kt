package app.core.engines.settings

import app.core.engines.objectbox.*
import app.core.engines.settings.AIOSettingsDBManager.APP_SETTINGS_DB_ID
import app.core.engines.settings.AIOSettingsDBManager.createDefaultSettingsObject
import app.core.engines.settings.AIOSettingsDBManager.saveSettingsInDB
import app.core.engines.supabase.*
import io.objectbox.*
import lib.process.*

/**
 * Singleton manager for handling AIOSettings persistence using ObjectBox database.
 *
 * This manager provides a centralized interface for all database operations related to
 * application settings, ensuring thread-safe access and proper lifecycle management.
 *
 * Key Responsibilities:
 * - Initializing and managing the ObjectBox database connection
 * - Loading and saving application settings with automatic default value handling
 * - Providing thread-safe singleton access to database operations
 * - Managing database lifecycle (initialization, closure, cleanup)
 * - Handling error scenarios with appropriate fallbacks and logging
 *
 * Architecture:
 * - Uses double-checked locking for thread-safe singleton initialization
 * - Maintains a single settings record with fixed ID (1) for the entire application
 * - Provides seamless migration from legacy file storage to database
 * - Implements proper error handling with comprehensive logging
 *
 * Usage Pattern:
 * 1. Initialize in Application.onCreate() with AIOSettingsDBManager.init(application)
 * 2. Load settings during app startup with loadSettingsFromDB()
 * 3. Use saveSettingsInDB() to persist changes
 * 4. Close database in Application.onTerminate() with closeDB()
 *
 * @see AIOSettings for the entity definition and property documentation
 * @see BoxStore for ObjectBox database operations
 */
object AIOSettingsDBManager {
	
	/**
	 * Logger instance for tracking operations and debugging issues.
	 * Marked as transient to exclude from any potential serialization.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Fixed identifier for the application's single settings record in the database.
	 *
	 * This constant ID ensures that all operations (load, save, update) consistently
	 * target the same unique settings object. By using a fixed ID, we simplify database
	 * queries and prevent the creation of multiple, conflicting settings entries.
	 */
	const val APP_SETTINGS_DB_ID = -1L
	
	/**
	 * Retrieves the initialized `BoxStore` instance from the central `ObjectBoxManager`.
	 *
	 * This method acts as a proxy to the `ObjectBoxManager`, providing access
	 * to the underlying ObjectBox database store for advanced operations or for
	 * use in other database-related managers.
	 *
	 * @return The initialized `BoxStore` instance.
	 * @throws IllegalStateException if the `ObjectBoxManager` has not been initialized.
	 * @see ObjectBoxManager.getBoxStore
	 */
	@JvmStatic
	fun getGlobalObjectBoxStore(): BoxStore {
		return ObjectBoxManager.getBoxStore()
	}
	
	/**
	 * Provides a type-safe `Box` for the [AIOSettings] entity.
	 *
	 * This function is a direct gateway to performing CRUD (Create, Read, Update, Delete)
	 * operations on the settings data. The returned `Box` instance is specialized for
	 * the [AIOSettings] class, ensuring all operations are type-safe.
	 *
	 * It acts as a convenient shorthand for `ObjectBoxManager.getBoxStore().boxFor(AIOSettings::class.java)`.
	 *
	 * @return A `Box<AIOSettings>` instance for direct database interactions with settings.
	 * @throws IllegalStateException if the underlying `BoxStore` has not been initialized
	 *         via `ObjectBoxManager.init()`.
	 * @see Box
	 * @see ObjectBoxManager.getBoxStore
	 */
	@JvmStatic
	fun getSettingsObjectBox(): Box<AIOSettings> {
		return getGlobalObjectBoxStore().boxFor(AIOSettings::class.java)
	}
	
	/**
	 * Loads application settings from the ObjectBox database, ensuring a valid
	 * settings object is always returned.
	 *
	 * This method implements a multi-layered fallback strategy to guarantee application
	 * stability, even in cases of database corruption or I/O errors. The process is
	 * as follows:
	 *
	 * 1.  **Primary:** Attempts to load the existing settings record from the database
	 *     using the fixed `APP_SETTINGS_DB_ID`.
	 * 2.  **First-time Run:** If no settings are found, it creates a new default
	 *     settings object, migrates legacy data if present, and persists it to the
	 *     database for future use.
	 * 3.  **Recovery:** If the initial load fails due to a database error, it attempts
	 *     to create and save default settings as a recovery mechanism.
	 * 4.  **Final Fallback:** If both loading and saving fail, it returns a transient,
	 *     in-memory default `AIOSettings` object to allow the application to continue
	 *     running in a default state.
	 *
	 * Due to this comprehensive error handling, this function will never return `null`.
	 *
	 * @return An `AIOSettings` instance, either loaded from the database or newly
	 *         created as a fallback.
	 * @see createDefaultSettingsObject For the logic behind default settings creation and legacy migration.
	 * @see saveSettingsInDB For the persistence mechanism.
	 */
	@JvmStatic
	fun loadSettingsFromDB(): AIOSettings {
		return try {
			val settingsBox = getSettingsObjectBox()
			var appSettings = settingsBox.query()
				.equal(AIOSettings_.downloadDataModelDBId, APP_SETTINGS_DB_ID)
				.build()
				.findFirst()
			
			if (appSettings == null) {
				logger.d("No settings found in database, creating default settings")
				appSettings = createDefaultSettingsObject()
				saveSettingsInDB(appSettings)
			} else {
				logger.d("Settings loaded successfully from ObjectBox database, id: ${appSettings.id}")
			}
			
			appSettings
		} catch (error: Exception) {
			logger.e("Error loading settings from ObjectBox: ${error.message}", error)
			try {
				// Attempt to create and save default settings as recovery
				createDefaultSettingsObject().also { savedSettings ->
					saveSettingsInDB(savedSettings)
					logger.d("Recovery: Default settings created and saved after load error")
				}
			} catch (saveError: Exception) {
				logger.e("Failed to save default settings after error: ${saveError.message}", saveError)
				// Final fallback: return basic default settings without persistence
				AIOSettings().also {
					logger.d("Using in-memory default settings as final fallback")
				}
			}
		}
	}
	
	/**
	 * Persists the provided [AIOSettings] instance to the ObjectBox database.
	 *
	 * This method saves or updates the application's configuration. It uses ObjectBox's `put`
	 * operation, which seamlessly handles both inserting a new record and updating an existing one.
	 * Before saving, it ensures the settings object has the correct fixed ID (`APP_SETTINGS_DB_ID`)
	 * to guarantee that only a single, consistent settings record exists.
	 *
	 * The method is synchronized to prevent race conditions from concurrent writes, ensuring
	 * data integrity and stability.
	 *
	 * Error Handling:
	 * If a database error occurs during the save operation, the exception is caught and logged.
	 * The function does not re-throw the exception, allowing the application to continue
	 * running with its current in-memory settings. This prevents crashes due to
	 * intermittent I/O or database failures.
	 *
	 * @param settings The `AIOSettings` object to be saved. This instance will be modified
	 *                 if its `downloadDataModelDBId` is not already set to the standard ID.
	 * @see Box.put for details on ObjectBox's insert/update operation.
	 * @see APP_SETTINGS_DB_ID for the fixed ID of the settings record.
	 */
	@JvmStatic
	@Synchronized
	fun saveSettingsInDB(settings: AIOSettings) {
		try {
			if (settings.downloadDataModelDBId < 0) {
				settings.downloadDataModelDBId = APP_SETTINGS_DB_ID
				logger.d("Saving setting to cloud: ${settings.convertClassToJSON()}")
				SupabaseCloudServer.updateDataModelToSupabase(
					dataModelName = AIOSettings::class.java.simpleName,
					dataModelJsonString = settings.convertClassToJSON()
				)
			}
			getSettingsObjectBox().put(settings)
			logger.d("Settings saved successfully to ObjectBox database id:${settings.id}")
		} catch (error: Exception) {
			logger.e("Error saving settings to ObjectBox: ${error.message}", error)
		}
	}
	
	/**
	 * Creates an `AIOSettings` instance with default values and migrates legacy data.
	 *
	 * This function initializes a new `AIOSettings` object. It then immediately
	 * calls `AIOSettings.readObjectFromStorage` on the new instance. This secondary
	 * function attempts to find and load settings from older, file-based storage
	 * (like JSON files), effectively migrating them into the new settings object.
	 *
	 * If no legacy files are found, the object retains its default values.
	 *
	 * @return A new `AIOSettings` instance, populated with migrated data if available,
	 *         otherwise containing default values.
	 * @see AIOSettings.readObjectFromStorage
	 */
	@JvmStatic
	private fun createDefaultSettingsObject(): AIOSettings {
		return AIOSettings().apply(AIOSettings::readObjectFromStorage).also {
			logger.d("Default settings created with legacy data migration")
		}
	}
	
	/**
	 * Checks if a settings record exists in the database.
	 *
	 * This method performs a quick query to determine if the main settings object,
	 * identified by [APP_SETTINGS_DB_ID], is present in the database. It is a
	 * lightweight and safe way to check the database state without loading the
	 * entire settings object.
	 *
	 * Use cases include:
	 *  - Verifying if this is the first time the application is launched.
	 *  - Validating the database state during application startup.
	 *  - Implementing conditional data migration logic.
	 *
	 * @return `true` if the settings record exists in the database, `false` otherwise.
	 *         Returns `false` as a safe default in case of any database errors.
	 */
	@JvmStatic
	fun doesSettingsRecordExist(): Boolean {
		return try {
			val appSettings = getSettingsObjectBox().query()
				.equal(AIOSettings_.downloadDataModelDBId, APP_SETTINGS_DB_ID)
				.build()
				.findFirst()
			
			appSettings != null
		} catch (error: Exception) {
			logger.e("Error checking settings existence: ${error.message}", error)
			false
		}
	}
	
	/**
	 * Deletes the application settings record from the ObjectBox database.
	 *
	 * This method locates the single settings object using its fixed ID and removes it.
	 * It provides a safe way to reset the application's configuration, handling cases
	 * where settings might not exist and logging any errors that occur during the
	 * process without crashing the application.
	 *
	 * **Warning:** This is a destructive and irreversible operation. After this method is
	 * called, the application will revert to default settings on the next load,
	 * as if it were a fresh installation.
	 *
	 * Typical use cases include:
	 * - Implementing a "Reset to Defaults" feature for the user.
	 * - Clearing data during automated testing or debugging.
	 * - Handling user data deletion upon account removal.
	 *
	 * @see Box.remove for the underlying ObjectBox deletion operation.
	 */
	@JvmStatic
	fun deleteAppSettingsFromDB() {
		try {
			val appSettings = getSettingsObjectBox().query()
				.equal(AIOSettings_.downloadDataModelDBId, APP_SETTINGS_DB_ID)
				.build()
				.findFirst()
			if (appSettings == null) return
			getSettingsObjectBox().remove(appSettings.id)
			logger.d("Settings cleared from ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error clearing settings: ${error.message}", error)
		}
	}
}