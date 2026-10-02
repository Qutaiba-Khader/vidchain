package app.core.engines.objectbox

import io.objectbox.converter.PropertyConverter

/**
 * ObjectBox property converter for mapping [Map] of [String] key-value pairs to/from database [String] storage.
 *
 * This converter serializes a Map<String, String> into a compact string format for efficient database storage
 * and deserializes it back to the original map structure when reading from the database.
 *
 * ## Serialization Format:
 * - Key-value pairs are separated by semicolons (`;`)
 * - Within each pair, key and value are separated by colons (`:`)
 * - Example: `"Content-Type:application/json;User-Agent:MyApp;Authorization:Bearer token"`
 *
 * ## Use Cases:
 * - Storing HTTP headers for download requests
 * - Persisting key-value configuration settings
 * - Saving string-based metadata properties
 *
 * ## Error Handling:
 * - Returns empty map for null, empty, or malformed database values
 * - Trims whitespace from keys and values during deserialization
 * - Handles missing values gracefully (defaults to empty string)
 *
 * ## Limitations:
 * - Keys and values should not contain semicolons (`;`) or colons (`:`)
 * - For complex nested structures, consider using JSON-based converter instead
 *
 * @see PropertyConverter
 * @see io.objectbox.annotation.Convert
 */
class StringMapConverter : PropertyConverter<Map<String, String>, String> {

	/**
	 * Converts a database string value back to a [Map] of key-value pairs.
	 *
	 * Parses the serialized string format where:
	 * - Key-value pairs are separated by `;`
	 * - Keys and values are separated by `:`
	 * - Whitespace is trimmed from both keys and values
	 *
	 * @param databaseValue The serialized string from database, or null if no value exists
	 * @return A [Map] containing the deserialized key-value pairs, or empty map if:
	 *         - [databaseValue] is null or empty
	 *         - Parsing fails due to malformed format
	 *         - Exception occurs during processing
	 *
	 * @sample
	 * Input: "Content-Type:application/json;User-Agent:MyApp"
	 * Output: mapOf("Content-Type" to "application/json", "User-Agent" to "MyApp")
	 */
	override fun convertToEntityProperty(databaseValue: String?): Map<String, String> {
		return if (databaseValue.isNullOrEmpty()) {
			emptyMap()
		} else {
			try {
				databaseValue.split(";").associate { header ->
					val parts = header.split(":", limit = 2)
					if (parts.size == 2) {
						parts[0].trim() to parts[1].trim()
					} else {
						parts[0].trim() to ""
					}
				}
			} catch (e: Exception) {
				emptyMap()
			}
		}
	}

	/**
	 * Converts a [Map] of key-value pairs to a database-friendly string format.
	 *
	 * Serializes the map into a compact string where:
	 * - Each key-value pair is formatted as "key:value"
	 * - Pairs are concatenated with `;` separators
	 * - Null or empty maps return empty string
	 *
	 * @param entityProperty The [Map] of key-value pairs to serialize, or null
	 * @return A serialized string representation of the map, or empty string if:
	 *         - [entityProperty] is null
	 *         - [entityProperty] is empty
	 *
	 * @sample
	 * Input: mapOf("Content-Type" to "application/json", "User-Agent" to "MyApp")
	 * Output: "Content-Type:application/json;User-Agent:MyApp"
	 */
	override fun convertToDatabaseValue(entityProperty: Map<String, String>?): String {
		return entityProperty?.entries?.joinToString(";") { (key, value) ->
			"$key:$value"
		} ?: ""
	}
}