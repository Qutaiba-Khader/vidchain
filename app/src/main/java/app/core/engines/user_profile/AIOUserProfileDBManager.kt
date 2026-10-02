package app.core.engines.user_profile

import app.core.engines.objectbox.*
import app.core.engines.settings.AIOSettingsDBManager.getSettingsObjectBox
import io.objectbox.*
import lib.process.*

/**
 * Manages all database operations for the `AIOUserProfile` object using ObjectBox.
 *
 * This singleton object provides a centralized API to handle the Create, Read, Update, and Delete (CRUD)
 * operations for the user profile. It ensures that there is a single, consistent way to interact with
 * the user profile data stored in the database.
 *
 * Key responsibilities include:
 * - Retrieving the global ObjectBox store and the specific `Box` for `AIOUserProfile`.
 * - Saving or updating the user profile in the database.
 * - Loading the user profile from the database, or creating a default one if none exists or an error occurs.
 * - Checking for the existence of a user profile record.
 * - Deleting the user profile from the database.
 */
object AIOUserProfileDBManager {
	
	/**
	 * Logger for the [AIOUserProfileDBManager].
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Retrieves the global [BoxStore] instance for the application.
	 *
	 * This function serves as a centralized access point to the main ObjectBox database store,
	 * which is managed by [ObjectBoxManager]. It ensures that all parts of the application
	 * use the same database instance.
	 *
	 * @return The singleton [BoxStore] instance.
	 * @see ObjectBoxManager.getBoxStore
	 */
	@JvmStatic
	fun getGlobalObjectBoxStore(): BoxStore {
		return ObjectBoxManager.getBoxStore()
	}
	
	/**
	 * Retrieves the ObjectBox `Box` for managing `AIOUserProfile` entities.
	 *
	 * This function provides a direct interface to the database table (Box)
	 * where user profiles are stored, allowing for CRUD (Create, Read, Update, Delete) operations.
	 *
	 * @return The `Box<AIOUserProfile>` instance for database interactions.
	 */
	@JvmStatic
	fun getUserProfileObjectBox(): Box<AIOUserProfile> {
		return getGlobalObjectBoxStore().boxFor(AIOUserProfile::class.java)
	}
	
	/**
	 * Saves or updates a user profile in the ObjectBox database.
	 *
	 * This function is synchronized to ensure thread-safe database write operations.
	 * It takes an [AIOUserProfile] object and persists it. If the profile object
	 * already has an ID, the existing record in the database will be updated.
	 * If the ID is 0, a new record will be created.
	 *
	 * Any exceptions during the save operation are caught and logged, preventing
	 * the application from crashing.
	 *
	 * @param userProfile The [AIOUserProfile] object to be saved or updated.
	 */
	@JvmStatic
	@Synchronized
	fun saveUserProfileInDB(userProfile: AIOUserProfile) {
		try {
			getUserProfileObjectBox().put(userProfile)
			logger.d("User profile saved successfully to database id:${userProfile.id}")
		} catch (error: Exception) {
			logger.e("Error saving user profile to: ${error.message}", error)
		}
	}
	
	/**
	 * Loads the user profile from the ObjectBox database.
	 *
	 * This function attempts to retrieve the existing user profile. If no profile is found,
	 * it creates a new default profile, saves it to the database, and then returns it.
	 * This ensures that the application always has a valid user profile to work with.
	 *
	 * The function includes a robust error handling mechanism:
	 * 1.  If the initial load from the database fails due to an exception (e.g., database corruption),
	 *     it logs the error.
	 * 2.  As a recovery step, it attempts to create and save a new default user profile.
	 * 3.  If saving the new default profile also fails, it logs this second error.
	 * 4.  As a final fallback, it returns a transient, in-memory default `AIOUserProfile` object
	 *     to prevent the application from crashing, though settings will not persist in this state.
	 *
	 * @return The loaded or newly created [AIOUserProfile]. In case of multiple failures,
	 *         returns a non-persistent, in-memory default instance.
	 */
	@JvmStatic
	fun loadSettingsFromDB(): AIOUserProfile {
		return try {
			val userProfileBox = getUserProfileObjectBox()
			logger.d("Loading user profile from database, size ${userProfileBox.count()}")
			var appUserProfile = userProfileBox.query().build().findFirst()
			
			if (appUserProfile == null) {
				logger.d("No user profile found in database, creating default user profile")
				appUserProfile = createDefaultUserProfileObject()
				saveUserProfileInDB(appUserProfile)
			} else {
				logger.d("User profile loaded successfully from database, id: ${appUserProfile.id}")
			}
			
			appUserProfile
		} catch (error: Exception) {
			logger.e("Error loading user profile from database: ${error.message}", error)
			try {
				createDefaultUserProfileObject().also { savedUserProfile ->
					saveUserProfileInDB(savedUserProfile)
					logger.d("Recovery: Default user profile created and saved after load error")
				}
			} catch (saveError: Exception) {
				logger.e("Failed to save default user profile after error: ${saveError.message}", saveError)
				AIOUserProfile().also { logger.d("Using in-memory default profile as final fallback") }
			}
		}
	}
	
	/**
	 * Creates a default user profile instance.
	 *
	 * This function is used when no existing user profile can be found in the database.
	 * It initializes a new `AIOUserProfile` object and then attempts to populate it
	 * with data from a legacy storage mechanism by calling `readObjectFromStorage`.
	 * This ensures that user data from older versions of the application is migrated
	 * into the new profile object.
	 *
	 * @return A new [AIOUserProfile] instance, potentially populated with migrated legacy data.
	 */
	@JvmStatic
	private fun createDefaultUserProfileObject(): AIOUserProfile {
		return AIOUserProfile().apply(AIOUserProfile::readObjectFromStorage).also {
			logger.d("Default user profile created with legacy data migration")
		}
	}
	
	/**
	 * Checks if a user profile record exists in the ObjectBox database.
	 *
	 * This function queries the user profile box for the first available entry.
	 * It's a quick way to determine if any user profile has been saved without
	 * loading the entire object.
	 *
	 * @return `true` if at least one user profile record is found, `false` otherwise.
	 *         It also returns `false` if an exception occurs during the database query.
	 */
	@JvmStatic
	fun doesUserProfileRecordExist(): Boolean {
		return try {
			val appUserProfile = getUserProfileObjectBox().query().build().findFirst()
			appUserProfile != null
		} catch (error: Exception) {
			logger.e("Error checking user profile existence: ${error.message}", error)
			false
		}
	}
	
	/**
	 * Deletes the user profile from the ObjectBox database.
	 *
	 * This function first queries for the user profile. If found, it removes the profile
	 * using its ID. If no profile is found, the function returns without performing any action.
	 * Errors during the process are caught and logged.
	 *
	 * Note: This function attempts to remove the profile from the 'Settings' box (`getSettingsObjectBox()`),
	 * which might be a bug. It should likely be using `getUserProfileObjectBox().remove()`.
	 */
	@JvmStatic
	fun deleteUserProfileFromDB() {
		try {
			val appUserProfile = getUserProfileObjectBox()
				.query().build().findFirst() ?: return
			getSettingsObjectBox().remove(appUserProfile.id)
			logger.d("User profile cleared from ObjectBox database")
		} catch (error: Exception) {
			logger.e("Error clearing user profile: ${error.message}", error)
		}
	}
}