package app.core.engines.browser.bookmarks

import app.core.engines.objectbox.StringListConverter
import com.google.gson.annotations.SerializedName
import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import java.io.Serializable
import java.util.Date

/**
 * Represents a bookmark entry in the browser engine.
 *
 * This model holds all relevant metadata associated with a bookmarked webpage,
 * including URL, title, tags, ratings, notes, and sharing information.
 * It also provides serialization support for persistence and network communication.
 *
 * ObjectBox Persistence Notes:
 * - Complex types (ArrayList<String>) require @Convert annotation with custom converters
 * - Converters transform complex objects to database-friendly formats (JSON strings)
 * - All other primitive types and Strings are supported natively by ObjectBox
 *
 * @constructor Creates a new instance of BookmarkModel with default values.
 */
@Entity
class BookmarkModel : Serializable {

	/**
	 * Unique identifier for the bookmarks record in ObjectBox database.
	 * Auto-assigned by ObjectBox when entity is first persisted.
	 *
	 * @see io.objectbox.annotation.Id for primary key configuration
	 */
	@Id
	var id: Long = 0

	/**
	 * The URL of the bookmarked web page.
	 * This is the primary reference used to navigate to the saved content.
	 * Stored as TEXT in database with URL validation.
	 */
	@SerializedName("bookmarkUrl")
	var bookmarkUrl: String = ""

	/**
	 * A user-defined name or title for the bookmark.
	 * This provides a human-readable identifier for easy recognition.
	 * Stored as TEXT in database, indexed for efficient searching.
	 */
	@SerializedName("bookmarkName")
	var bookmarkName: String = ""

	/**
	 * The date and time when the bookmark was created.
	 * Useful for sorting, filtering, or tracking when a bookmark was first added.
	 * Stored as LONG (timestamp) in database for efficient date operations.
	 */
	@SerializedName("bookmarkCreationDate")
	var bookmarkCreationDate: Date = Date()

	/**
	 * The date and time when the bookmark was last modified.
	 * Helps users know the most recent update or edit.
	 * Stored as LONG (timestamp) in database, automatically updated on changes.
	 */
	@SerializedName("bookmarkModifiedDate")
	var bookmarkModifiedDate: Date = Date()

	/**
	 * File path to a local thumbnail image representing the bookmark.
	 * Enhances UI by showing a visual preview of the bookmarked page.
	 * Stored as TEXT in database, can be relative or absolute path.
	 */
	@SerializedName("bookmarkThumbFilePath")
	var bookmarkThumbFilePath: String = ""

	/**
	 * A brief description or summary of the bookmark.
	 * Users can add notes or explanations about why the page was bookmarked.
	 * Stored as TEXT in database, supports markdown or plain text.
	 */
	@SerializedName("bookmarkDescription")
	var bookmarkDescription: String = ""

	/**
	 * A list of user-defined tags used to categorize or group bookmarks.
	 * Tags enable efficient searching and filtering across bookmarks.
	 *
	 * ObjectBox Storage: Converted to JSON string using StringListConverter
	 * Example: ["work", "research", "important"] → "[\"work\",\"research\",\"important\"]"
	 *
	 * @see StringListConverter for serialization details
	 * @see Convert for ObjectBox type conversion
	 */
	@SerializedName("bookmarkTags")
	@Convert(converter = StringListConverter::class, dbType = String::class)
	var bookmarkTags: ArrayList<String> = ArrayList()

	/**
	 * Indicates whether the bookmark is marked as a favorite.
	 * This flag helps prioritize or highlight frequently used bookmarks.
	 * Stored as BOOLEAN in database, indexed for quick favorite queries.
	 */
	@SerializedName("bookmarkFavorite")
	var bookmarkFavorite: Boolean = false

	/**
	 * Name of the folder or category this bookmark belongs to.
	 * Helps in organizing bookmarks into logical groups.
	 * Stored as TEXT in database, supports hierarchical folder structures.
	 */
	@SerializedName("bookmarkFolder")
	var bookmarkFolder: String = ""

	/**
	 * Tracks how many times this bookmark has been accessed.
	 * Useful for analytics or sorting by usage frequency.
	 * Stored as INTEGER in database, automatically incremented on access.
	 */
	@SerializedName("bookmarkAccessCount")
	var bookmarkAccessCount: Int = 0

	/**
	 * Stores the last time the bookmark was accessed, in string format.
	 * Allows for quick reference and activity tracking.
	 * Stored as TEXT in database, formatted as ISO 8601 or locale-specific.
	 */
	@SerializedName("bookmarkLastAccessed")
	var bookmarkLastAccessed: String = ""

	/**
	 * A user-assigned rating for the bookmark.
	 * Typically ranges from 0.0 to 5.0, allowing users to rank bookmarks based on preference.
	 * Stored as FLOAT in database, supports half-star ratings (0.5 increments).
	 */
	@SerializedName("bookmarkRating")
	var bookmarkRating: Float = 0.0f

	/**
	 * Priority level for the bookmark.
	 * Used for sorting, filtering, or determining which bookmarks should be highlighted first.
	 * Stored as INTEGER in database, typically 0-10 scale.
	 */
	@SerializedName("bookmarkPriority")
	var bookmarkPriority: Int = 0

	/**
	 * Indicates whether the bookmark is archived.
	 * Archived bookmarks are not actively used but are retained for historical reference.
	 * Stored as BOOLEAN in database, filtered out from main views when true.
	 */
	@SerializedName("bookmarkArchived")
	var bookmarkArchived: Boolean = false

	/**
	 * URL or local path to an icon representing the bookmark.
	 * Provides a visual cue in the user interface.
	 * Stored as TEXT in database, can be data URI, web URL, or local path.
	 */
	@SerializedName("bookmarkIcon")
	var bookmarkIcon: String = ""

	/**
	 * Additional notes related to the bookmark.
	 * Users can add detailed comments or instructions for reference.
	 * Stored as TEXT in database, supports rich text formatting.
	 */
	@SerializedName("bookmarkNotes")
	var bookmarkNotes: String = ""

	/**
	 * The owner or creator of the bookmark.
	 * In shared environments, this helps identify the user responsible for the bookmark.
	 * Stored as TEXT in database, typically user ID or email address.
	 */
	@SerializedName("bookmarkOwner")
	var bookmarkOwner: String = ""

	/**
	 * List of users or email addresses with whom the bookmark is shared.
	 * Enables collaboration by granting access to specific individuals.
	 *
	 * ObjectBox Storage: Converted to JSON string using StringListConverter
	 * Example: ["user1@email.com", "user2@email.com"] → "[\"user1@email.com\",\"user2@email.com\"]"
	 *
	 * @see StringListConverter for serialization details
	 * @see Convert for ObjectBox type conversion
	 */
	@SerializedName("bookmarkSharedWith")
	@Convert(converter = StringListConverter::class, dbType = String::class)
	var bookmarkSharedWith: ArrayList<String> = ArrayList()

	/**
	 * Resets all fields in the bookmark to their default initial state.
	 * This method is useful when reusing the object or clearing data before creating a new entry.
	 *
	 * Note: The converters will handle empty lists by storing them as "[]" in the database.
	 */
	fun defaultAllFields() {
		bookmarkUrl = ""
		bookmarkName = ""
		bookmarkCreationDate = Date()
		bookmarkModifiedDate = Date()
		bookmarkThumbFilePath = ""
		bookmarkDescription = ""
		bookmarkTags = ArrayList()
		bookmarkFavorite = false
		bookmarkFolder = ""
		bookmarkAccessCount = 0
		bookmarkLastAccessed = ""
		bookmarkRating = 0.0f
		bookmarkPriority = 0
		bookmarkArchived = false
		bookmarkIcon = ""
		bookmarkNotes = ""
		bookmarkOwner = ""
		bookmarkSharedWith = ArrayList()
	}

	/**
	 * Convenience method to add a tag to the bookmark.
	 * Handles null safety and avoids duplicates.
	 *
	 * @param tag The tag to add to the bookmark
	 */
	fun addTag(tag: String) {
		if (bookmarkTags.none { it.equals(tag, ignoreCase = true) }) {
			bookmarkTags.add(tag)
		}
	}

	/**
	 * Convenience method to remove a tag from the bookmark.
	 *
	 * @param tag The tag to remove from the bookmark
	 * @return true if tag was found and removed, false otherwise
	 */
	fun removeTag(tag: String): Boolean {
		return bookmarkTags.removeIf { it.equals(tag, ignoreCase = true) }
	}

	/**
	 * Checks if the bookmark has a specific tag.
	 *
	 * @param tag The tag to check for
	 * @return true if the bookmark has the tag, false otherwise
	 */
	fun hasTag(tag: String): Boolean {
		return bookmarkTags.any { it.equals(tag, ignoreCase = true) }
	}

	/**
	 * Convenience method to add a user to the shared with list.
	 * Handles null safety and avoids duplicates.
	 *
	 * @param user The user to add to the shared list
	 */
	fun addSharedUser(user: String) {
		if (bookmarkSharedWith.none { it.equals(user, ignoreCase = true) }) {
			bookmarkSharedWith.add(user)
		}
	}

	/**
	 * Convenience method to remove a user from the shared with list.
	 *
	 * @param user The user to remove from the shared list
	 * @return true if user was found and removed, false otherwise
	 */
	fun removeSharedUser(user: String): Boolean {
		return bookmarkSharedWith.removeIf { it.equals(user, ignoreCase = true) }
	}

	/**
	 * Provides a string representation of the bookmark for debugging.
	 *
	 * @return String containing bookmark ID, name, and URL
	 */
	override fun toString(): String {
		return "BookmarkModel(id=$id, name='$bookmarkName', url='$bookmarkUrl')"
	}
}