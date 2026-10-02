package app.core.engines.objectbox

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.objectbox.converter.PropertyConverter

/**
 * ObjectBox property converter for ArrayList<String> to JSON string storage.
 *
 * This converter handles the serialization and deserialization of string lists
 * to and from JSON format for ObjectBox database persistence.
 *
 * ObjectBox does not natively support complex types like ArrayList<String>,
 * so this converter transforms the list into a JSON string for storage and
 * back to ArrayList<String> when reading from the database.
 *
 * @see PropertyConverter for the base converter interface
 * @see Gson for JSON serialization
 */
class StringListConverter : PropertyConverter<ArrayList<String>, String> {

	private val gson = Gson()
	private val typeToken = object : TypeToken<ArrayList<String>>() {}.type

	/**
	 * Converts ArrayList<String> to JSON string for database storage.
	 *
	 * This method serializes the string list into a compact JSON array format
	 * that can be efficiently stored in the ObjectBox database.
	 *
	 * @param entityProperty The ArrayList<String> to convert
	 * @return JSON string representation of the list, or empty array JSON if null
	 *
	 * @example
	 * Input: ["tag1", "tag2", "tag3"]
	 * Output: "[\"tag1\",\"tag2\",\"tag3\"]"
	 */
	override fun convertToDatabaseValue(entityProperty: ArrayList<String>?): String {
		return entityProperty?.let { list ->
			if (list.isEmpty()) {
				"[]" // Return empty array for empty lists
			} else {
				gson.toJson(list)
			}
		} ?: "[]" // Return empty array for null values
	}

	/**
	 * Converts JSON string back to ArrayList<String> when reading from database.
	 *
	 * This method deserializes the JSON string back into a mutable ArrayList<String>
	 * that can be used in the application logic.
	 *
	 * @param databaseValue The JSON string from database
	 * @return ArrayList<String> containing the deserialized strings, or empty list if invalid
	 *
	 * @example
	 * Input: "[\"tag1\",\"tag2\",\"tag3\"]"
	 * Output: ["tag1", "tag2", "tag3"]
	 */
	override fun convertToEntityProperty(databaseValue: String?): ArrayList<String> {
		return try {
			databaseValue?.let { json ->
				if (json.isBlank() || json == "[]") {
					ArrayList()
				} else {
					gson.fromJson(json, typeToken) ?: ArrayList()
				}
			} ?: ArrayList()
		} catch (e: Exception) {
			// Return empty list on any parsing errors
			ArrayList()
		}
	}
}