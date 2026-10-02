package app.core.engines.browser.history

import app.core.engines.objectbox.StringListConverter
import com.google.gson.annotations.SerializedName
import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import java.io.Serializable
import java.util.Calendar
import java.util.Date

/**
 * HistoryModel represents the metadata and interaction details
 * for a single browser history entry.
 *
 * This model stores information such as URL, title, visit time,
 * user engagement (like rating and priority), device data, and user context.
 *
 * It implements Serializable to allow the object to be saved and restored,
 * for example during session management or persistent storage.
 */
@Entity
class HistoryModel : Serializable {

	/**
	 * Unique identifier for the bookmarks record in ObjectBox database.
	 * Auto-assigned by ObjectBox when entity is first persisted.
	 *
	 * @see io.objectbox.annotation.Id for primary key configuration
	 */
	@Id
	var id: Long = 0

	/** The URL of the visited web page. */
	@SerializedName("historyUrl")
	var historyUrl: String = ""

	/** The title of the visited web page. */
	@SerializedName("historyTitle")
	var historyTitle: String = ""

	/** The date and time when the page was visited. */
	@SerializedName("historyVisitDateTime")
	var historyVisitDateTime: Date = Date()

	/** Path to the favicon image file associated with the visited page. */
	@SerializedName("historyFaviconFilePath")
	var historyFaviconFilePath: String = ""

	/** Description or summary of the web page. */
	@SerializedName("historyDescription")
	var historyDescription: String = ""

	/** Flag indicating whether the history item is marked as important. */
	@SerializedName("historyImportant")
	var historyImportant: Boolean = false

	/** Name of the folder this history entry may be categorized under. */
	@SerializedName("historyFolder")
	var historyFolder: String = ""

	/** Number of times the URL has been accessed. */
	@SerializedName("historyAccessCount")
	var historyAccessCount: Int = 0

	/** The last time this page was accessed. */
	@SerializedName("historyLastAccessed")
	var historyLastAccessed: Date = Date()

	/** Total duration (in milliseconds) the page was viewed. */
	@SerializedName("historyDuration")
	var historyDuration: Long = 0L

	/** User-provided rating of the visited page (0.0 to 5.0). */
	@SerializedName("historyRating")
	var historyRating: Float = 0.0f

	/** Priority level (custom sorting or filtering). */
	@SerializedName("historyPriority")
	var historyPriority: Int = 0

	/** Flag indicating whether this history item has been archived. */
	@SerializedName("historyArchived")
	var historyArchived: Boolean = false

	/** Icon associated with the page or custom category. */
	@SerializedName("historyIcon")
	var historyIcon: String = ""

	/** Any notes or annotations about the visit. */
	@SerializedName("historyNotes")
	var historyNotes: String = ""

	/** The user who owns this history entry. */
	@SerializedName("historyOwner")
	var historyOwner: String = ""

	/** List of other users the history entry is shared with. */
	@SerializedName("historySharedWith")
	@Convert(converter = StringListConverter::class, dbType = String::class)
	var historySharedWith: ArrayList<String> = ArrayList()

	/** Session identifier that this visit is part of. */
	@SerializedName("historySessionId")
	var historySessionId: String = ""

	/** User-Agent string from the browser/device. */
	@SerializedName("historyUserAgent")
	var historyUserAgent: String = ""

	/** The referring URL (if any) that led to this visit. */
	@SerializedName("historyReferrer")
	var historyReferrer: String = ""

	/** Tags associated with this history entry. */
	@SerializedName("historyTags")
	@Convert(converter = StringListConverter::class, dbType = String::class)
	var historyTags: ArrayList<String> = ArrayList()

	/** Search terms used to find or navigate to this page. */
	@SerializedName("historySearchTerms")
	@Convert(converter = StringListConverter::class, dbType = String::class)
	var historySearchTerms: ArrayList<String> = ArrayList()

	/** Content type of the visited page (e.g., "text/html", "application/pdf"). */
	@SerializedName("historyContentType")
	var historyContentType: String = ""

	/** Type of device used (e.g., "mobile", "desktop"). */
	@SerializedName("historyDeviceType")
	var historyDeviceType: String = ""

	/** Location of the user at the time of visit. */
	@SerializedName("historyLocation")
	var historyLocation: String = ""

	/**
	 * Resets all fields in the model to their default values.
	 */
	fun defaultAllFields() {
		historyUrl = ""
		historyTitle = ""
		historyVisitDateTime = Date()
		historyFaviconFilePath = ""
		historyDescription = ""
		historyImportant = false
		historyFolder = ""
		historyAccessCount = 0
		historyLastAccessed = Date()
		historyDuration = 0L
		historyRating = 0.0f
		historyPriority = 0
		historyArchived = false
		historyIcon = ""
		historyNotes = ""
		historyOwner = ""
		historySharedWith = ArrayList()
		historySessionId = ""
		historyUserAgent = ""
		historyReferrer = ""
		historyTags = ArrayList()
		historySearchTerms = ArrayList()
		historyContentType = ""
		historyDeviceType = ""
		historyLocation = ""
	}

	/**
	 * Increments the access count for this history item.
	 */
	fun updateAccessCount() {
		historyAccessCount += 1
	}

	/**
	 * Sets the visit duration based on start and end timestamps.
	 *
	 * @param startTime The start time of the visit
	 * @param endTime The end time of the visit
	 */
	fun setVisitDuration(startTime: Date, endTime: Date) {
		historyDuration = endTime.time - startTime.time
	}

	/**
	 * Checks if this history entry was visited within a given number of days.
	 *
	 * @param thresholdDays Number of past days to consider
	 * @return true if the visit is recent, false otherwise
	 */
	fun isRecent(thresholdDays: Int): Boolean {
		val calendar = Calendar.getInstance()
		calendar.add(Calendar.DAY_OF_YEAR, -thresholdDays)
		val cutoffDate = calendar.time
		return historyVisitDateTime.after(cutoffDate)
	}

	/**
	 * Adds a new tag to the history entry.
	 *
	 * @param tag The tag to add
	 * @param onResult Callback invoked with true if tag was added, false otherwise
	 */
	fun addTag(tag: String, onResult: (Boolean) -> Unit = {}) {
		onResult.invoke(historyTags.add(tag))
	}

	/**
	 * Removes an existing tag from the history entry.
	 *
	 * @param tag The tag to remove
	 * @param onResult Callback invoked with true if tag was removed, false otherwise
	 */
	fun removeTag(tag: String, onResult: (Boolean) -> Unit = {}) {
		onResult.invoke(historyTags.remove(tag))
	}

	/**
	 * Returns a formatted string representation of key history fields.
	 *
	 * @return A human-readable summary string
	 */
	fun toFormattedString(): String {
		return "Title: $historyTitle\n" +
				"URL: $historyUrl\n" +
				"Visited on: ${historyVisitDateTime}\n" +
				"Duration: ${historyDuration / 1000} seconds"
	}

	/**
	 * Clears sensitive information from the history entry.
	 * This might be used before sharing or exporting history data.
	 */
	fun clearSensitiveData() {
		historyReferrer = ""
		historyLocation = ""
	}
}