package lib.texts

import android.text.*
import android.text.Html.*
import androidx.annotation.*
import app.core.AIOApp.Companion.INSTANCE
import lib.process.*
import lib.texts.CommonTextUtils.cutTo100Chars
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import lib.texts.CommonTextUtils.getHtmlString
import lib.texts.CommonTextUtils.safeCutString
import java.io.*
import java.util.*

/**
 * A comprehensive utility object for common text-processing operations.
 *
 * This object provides a static-like interface for a wide range of functions, including:
 * - String manipulation: trimming, cutting, reversing, joining, and removing duplicate characters.
 * - Text formatting: capitalizing words, formatting numbers with metric prefixes (e.g., "1.2K", "5M").
 * - Random data generation: creating random alphanumeric strings.
 * - Resource handling: fetching localized strings and reading raw HTML files.
 * - HTML processing: converting HTML strings to `Spanned` objects for display in UI components.
 *
 * All methods are annotated with `@JvmStatic` to be easily callable from Java code.
 */
object CommonTextUtils {

	/**
	 * Logger instance for this class to log debug and error messages.
	 * Used internally for tracking operations in text utilities.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Normalizes a string by replacing consecutive slashes (`/`) with a single slash.
	 *
	 * This is useful for cleaning up file paths or URL segments where multiple slashes might occur erroneously.
	 * It handles cases with two or more slashes, consolidating them into one.
	 *
	 * Example:
	 * ```kotlin
	 * removeDuplicateSlashes("path//to///file") // Returns "path/to/file"
	 * removeDuplicateSlashes("/leading//and/trailing//") // Returns "/leading/and/trailing/"
	 * removeDuplicateSlashes("no_slashes") // Returns "no_slashes"
	 * ```
	 *
	 * @param input The string that may contain duplicate slashes. Can be `null`.
	 * @return A new string with duplicate slashes replaced by a single slash, or `null` if the input is `null`.
	 */
	@JvmStatic
	fun removeDuplicateSlashes(input: String?): String? {
		if (input == null) return null
		val result = input.replace("/{2,}".toRegex(), "/") // Replace 2 or more slashes with one
		logger.d("removeDuplicateSlashes: input='$input' result='$result'")
		return result
	}

	/**
	 * Retrieves a localized string for a given resource ID.
	 *
	 * This function uses the app's `LocalizationHelper` to fetch the appropriate string
	 * resource, ensuring it respects the user's currently selected language settings.
	 *
	 * @param resID The resource ID of the string to retrieve (e.g., `R.string.app_name`).
	 * @return The localized string associated with the resource ID. If the resource is not found,
	 *         the behavior depends on the `LocalizationHelper` implementation, which may return
	 *         a default string or the resource ID itself.
	 * @see LocalizationHelper.getLocalizedString
	 */
	@JvmStatic
	fun getText(@StringRes resID: Int): String {
		val result = LocalizationHelper.getLocalizedString(INSTANCE, resID)
		logger.d("getText: resID=$resID result='$result'")
		return result
	}

	/**
	 * Generates a random alphanumeric string of a specified length.
	 *
	 * This function creates a cryptographically insecure random string composed of
	 * uppercase letters (A-Z), lowercase letters (a-z), and digits (0-9).
	 * It is suitable for non-sensitive use cases like generating temporary IDs,
	 * random keys for testing, or placeholder strings.
	 *
	 * **Note:** Do not use this for security-sensitive contexts like passwords,
	 * session tokens, or cryptographic keys, as its randomness is not guaranteed
	 * to be unpredictable.
	 *
	 * Example:
	 * ```kotlin
	 * val randomId = generateRandomString(16) // e.g., "aK3v8sR9pL2jM7bX"
	 * ```
	 *
	 * @param length The desired length of the output string. Must be a non-negative integer.
	 * @return A random alphanumeric string of the specified `length`. Returns an empty
	 *         string if `length` is 0 or negative.
	 */
	@JvmStatic
	fun generateRandomString(length: Int): String {
		val characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
		val sb = StringBuilder(length)
		for (index in 0 until length) {
			val randomIndex = (characters.indices).random()  // Pick random index
			sb.append(characters[randomIndex])                // Append random char
		}
		val result = sb.toString()
		logger.d("generateRandomString: length=$length result='$result'")
		return result
	}

	/**
	 * Truncates a string to a maximum of 100 characters.
	 *
	 * This function cuts the string at the 100-character mark. It then attempts to clean up the
	 * end of the string by removing trailing whitespace or invalid characters to prevent
	 * malformed output.
	 *
	 * @param input The string to truncate. Can be null.
	 * @return A truncated string up to 100 characters long, or null if the input is null.
	 *
	 * @deprecated This function is unsafe as it does not correctly handle multi-byte characters
	 * (like emojis) and may split them, causing rendering issues or data corruption.
	 * It is also considered buggy due to its complex and unreliable trailing character logic.
	 * Use [safeCutString] instead for a robust, Unicode-aware alternative.
	 */
	@Deprecated("Unsafe and buggy")
	@JvmStatic
	fun cutTo100Chars(input: String?): String? {
		if (input == null) return null

		if (input.length > 100) {
			var result = input.take(100)  // Take first 100 chars
			val lastChar = result.last()
			if (lastChar.isWhitespace() || !lastChar.isValidCharacter()) {
				result = result.trimEnd()  // Remove trailing whitespace or invalid char
			}

			// Check second last character for safety
			if (!lastChar.isWhitespace() && result.length > 1) {
				val secondLastChar = result[result.length - 2]
				if (!secondLastChar.isValidCharacter()) {
					result = result.dropLast(1)
				}
			}
			logger.d("cutTo100Chars: result='$result'")
			return result
		}
		return input
	}

	/**
	 * Trims the input string to a maximum of 30 characters, ensuring it doesn't end with
	 * broken or invalid characters.
	 *
	 * This function truncates the string to 30 characters and then cleans up the end
	 * to prevent partial or invalid characters from being displayed. It's designed for
	 * creating short, readable previews.
	 *
	 * @param input The string to trim.
	 * @return A string with a maximum length of 30 characters, ending cleanly.
	 *
	 * @see safeCutString for a more robust version that handles multi-byte characters correctly.
	 * @see cutTo100Chars for a similar function with a different length limit.
	 */
	@Deprecated(message = "Unsafe and buggy")
	@JvmStatic
	fun cutTo30Chars(input: String): String {
		if (input.length > 30) {
			var result = input.take(30)
			val lastChar = result.last()
			if (lastChar.isWhitespace() || !lastChar.isValidCharacter()) {
				result = result.trimEnd()
			}

			if (!lastChar.isWhitespace() && result.length > 1) {
				val secondLastChar = result[result.length - 2]
				if (!secondLastChar.isValidCharacter()) {
					result = result.dropLast(1)
				}
			}
			logger.d("cutTo30Chars: result='$result'")
			return result
		}
		return input
	}

	/**
	 * Truncates the input string to a maximum of 60 characters.
	 *
	 * This function ensures the resulting string does not exceed 60 characters. It also attempts to
	 * clean up the end of the string by removing trailing whitespace or invalid characters to avoid
	 * breaking multi-byte sequences or leaving partial words.
	 *
	 * It is marked as deprecated because its character validation logic is simplistic and may not
	 * handle all edge cases correctly, especially with Unicode characters (e.g., emojis).
	 * Use [safeCutString] instead for a more robust implementation.
	 *
	 * @param input The string to truncate. Can be null.
	 * @return A truncated string of up to 60 characters, or `null` if the input is `null`.
	 * @see safeCutString
	 */
	@Deprecated(message = "Unsafe and buggy")
	@JvmStatic
	fun cutTo60Chars(input: String?): String? {
		if (input == null) return null

		if (input.length > 60) {
			var result = input.take(60)
			val lastChar = result.last()
			if (lastChar.isWhitespace() || !lastChar.isValidCharacter()) {
				result = result.trimEnd()
			}

			if (!lastChar.isWhitespace() && result.length > 1) {
				val secondLastChar = result[result.length - 2]
				if (!secondLastChar.isValidCharacter()) {
					result = result.dropLast(1)
				}
			}
			logger.d("cutTo60Chars: result='$result'")
			return result
		}
		return input
	}

	/**
	 * Safely truncates a string to a specified maximum length, preserving Unicode characters.
	 *
	 * This function ensures that multi-byte characters (like emojis or complex scripts) are not
	 * split in the middle. It counts Unicode code points (which correspond to visual characters)
	 * rather than raw `char`s. After truncating, it also trims any trailing whitespace or
	 * invalid characters to produce a clean output.
	 *
	 * This method is a robust replacement for deprecated functions like `cutTo60Chars`, which
	 * could break multi-byte characters.
	 *
	 * Example:
	 * ```kotlin
	 * safeCutString("Hello World! 🌎", 13) // "Hello World! 🌎"
	 * safeCutString("Hello World! 🌎", 12) // "Hello World!" (emoji and space are trimmed)
	 * safeCutString("TestString", 100)      // "TestString"
	 * ```
	 *
	 * @param input The string to truncate. Can be `null`.
	 * @param maxLength The maximum number of Unicode code points (visual characters) to retain.
	 *        Defaults to 60.
	 * @return A safely truncated string, or `null` if the input was `null`.
	 */
	@JvmStatic
	fun safeCutString(input: String?, maxLength: Int = 60): String? {
		if (input == null) return null

		// Count actual code points (visual characters)
		val codePointCount = input.codePointCount(0, input.length)
		if (codePointCount <= maxLength) return input

		// Clamp the length to avoid IndexOutOfBoundsException
		val safeLength = minOf(maxLength, codePointCount)

		// Compute safe end index without splitting surrogate pairs
		val endIndex = input.offsetByCodePoints(0, safeLength)
		var result = input.take(endIndex)

		// Trim trailing whitespace or invalid characters
		while (result.isNotEmpty() && (result.last().isWhitespace() ||
					!result.last().isValidCharacter())
		) result = result.dropLast(1)

		logger.d("safeCutString: maxLength=$maxLength, codePointCount=$codePointCount, result='$result'")
		return result
	}

	/**
	 * Checks if a character is considered valid for use in specific text processing contexts,
	 * such as at the end of a trimmed string.
	 *
	 * A character is considered valid if it is a letter, a digit, or one of the following
	 * special symbols: `_`, `-`, `.`, `@`, ` `, `[`, `]`, `(`, `)`.
	 *
	 * This extension function is primarily used by trimming functions like `safeCutString`
	 * to ensure that truncated strings do not end with broken or undesirable characters.
	 *
	 * @return `true` if the character is a letter, digit, or a permitted symbol; `false` otherwise.
	 */
	@JvmStatic
	fun Char.isValidCharacter(): Boolean {
		return this.isLetterOrDigit() ||
				this in setOf('_', '-', '.', '@', ' ', '[', ']', '(', ')')
	}

	/**
	 * Joins a collection of string elements into a single string, with each element
	 * separated by the specified delimiter.
	 *
	 * This function is a wrapper around the standard library's `joinToString` for convenience
	 * and consistency within this utility class.
	 *
	 * Example:
	 * ```kotlin
	 * join(", ", "apple", "banana", "cherry") // returns "apple, banana, cherry"
	 * join("-", "one")                        // returns "one"
	 * join(":", )                             // returns "" (empty string)
	 * ```
	 *
	 * @param delimiter The string to use as a separator between elements.
	 * @param elements A variable number of string arguments to join.
	 * @return A new string containing all elements joined by the delimiter. If `elements`
	 *         is empty, an empty string is returned.
	 */
	@JvmStatic
	fun join(delimiter: String, vararg elements: String): String {
		if (elements.isEmpty()) return ""
		val result = elements.joinToString(separator = delimiter)
		return result
	}

	/**
	 * Reverses the given string. If the input is null, it returns null.
	 *
	 * This function handles multi-byte characters (like emojis) correctly,
	 * ensuring the reversed string remains valid.
	 *
	 * Example:
	 * ```
	 * reverse("hello")      // Returns "olleh"
	 * reverse("Kotlin ?") // Returns "? niltoK"
	 * reverse(null)         // Returns null
	 * ```
	 *
	 * @param input The string to be reversed. Can be `null`.
	 * @return The reversed string, or `null` if the input was `null`.
	 */
	@JvmStatic
	fun reverse(input: String?): String? {
		if (input == null) return null
		val result = StringBuilder(input).reverse().toString()
		return result
	}

	/**
	 * Capitalizes the first letter of the given string.
	 *
	 * This function handles null or empty strings by returning them as is. If the first
	 * character is already uppercase, the original string is returned without modification.
	 *
	 * @param string The string to capitalize. Can be null or empty.
	 * @return A new string with the first letter capitalized, or null if the input was null or empty.
	 *
	 * @sample
	 * val sample1 = capitalizeFirstLetter("hello") // "Hello"
	 * val sample2 = capitalizeFirstLetter("World") // "World"
	 * val sample3 = capitalizeFirstLetter("")      // null
	 * val sample4 = capitalizeFirstLetter(null)   // null
	 */
	@JvmStatic
	fun capitalizeFirstLetter(string: String?): String? {
		if (string.isNullOrEmpty()) return null
		val first = string[0]
		val capitalized = if (Character.isUpperCase(first)) string
		else first.uppercaseChar().toString() + string.substring(1)
		return capitalized
	}

	/**
	 * Capitalizes the first letter of each word in a string, trimming leading/trailing whitespace.
	 *
	 * This function handles various edge cases:
	 * - If the input is `null`, `empty`, or consists only of whitespace, it is returned as is.
	 * - Multiple whitespace characters between words are collapsed into a single space.
	 *
	 * @param input The string to capitalize. Can be null.
	 * @return A new string with each word capitalized, or the original input if it's null or blank.
	 *
	 * @sample
	 * val capitalized = capitalizeWords("hello world") // "Hello World"
	 * val trimmed = capitalizeWords("  leading and trailing spaces  ") // "Leading And Trailing Spaces"
	 * val nullInput = capitalizeWords(null) // null
	 * val blankInput = capitalizeWords("   ") // "   "
	 */
	@JvmStatic
	fun capitalizeWords(input: String?): String? {
		if (input.isNullOrBlank()) return input // handles null, empty, and whitespace-only
		return input
			.trim()
			.split("\\s+".toRegex())
			.joinToString(" ") { word ->
				word.replaceFirstChar {
					if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
				}
			}
	}

	/**
	 * Converts an HTML-formatted string into a `Spanned` object that can display styled text
	 * in Android UI components like `TextView`.
	 *
	 * This function uses `Html.fromHtml` with `FROM_HTML_MODE_COMPACT`, which means it can handle
	 * a common subset of HTML tags (e.g., `<b>`, `<i>`, `<u>`, `<font>`, `<a>`). Paragraphs
	 * are separated by a single newline character.
	 *
	 * For a comprehensive list of supported tags, refer to the official Android documentation
	 * for `Html.fromHtml`.
	 *
	 * @param htmlString The HTML string to parse.
	 * @return A `Spanned` object containing the styled text from the HTML input.
	 *
	 * @see android.text.Html.fromHtml
	 * @sample
	 * val html = "<b>Hello</b>, <i>World</i>!"
	 * val spannedText = fromHtmlStringToSpanned(html)
	 * myTextView.text = spannedText
	 */
	@JvmStatic
	fun fromHtmlStringToSpanned(htmlString: String): Spanned {
		val result = fromHtml(htmlString, FROM_HTML_MODE_COMPACT)
		return result
	}

	/**
	 * Reads an HTML string from a raw resource file.
	 *
	 * This function opens and reads the entire content of a file located in the `res/raw`
	 * directory, identified by its resource ID. It is designed for fetching HTML content
	 * that will be parsed or displayed elsewhere.
	 *
	 * Error handling is performed internally; if an `IOException` occurs during file reading,
	 * an error is logged, and the function may return an empty or partial string.
	 *
	 * @param resId The raw resource ID of the HTML file (e.g., `R.raw.my_html_file`).
	 * @return A string containing the full content of the raw resource file. Returns an
	 *         empty string if the file is empty or an error occurs during reading.
	 * @see fromHtmlStringToSpanned To convert the resulting HTML string into styled text.
	 */
	@JvmStatic
	fun getHtmlString(resId: Int): String {
		val result = convertRawHtmlFileToString(resId)
		return result
	}

	/**
	 * Reads a raw resource file line by line and converts its content into a single string.
	 *
	 * This function is primarily designed for reading text-based files, such as HTML, stored in
	 * the `res/raw` directory. It opens the file specified by `resourceId`, reads all its lines,
	 * and concatenates them into one continuous string. Line breaks from the original file
	 * are not preserved.
	 *
	 * Resource streams are properly closed in a `finally` block to prevent leaks, even if
	 * an error occurs during reading. Errors encountered during file I/O are logged.
	 *
	 * @param resourceId The resource ID of the raw file to read (e.g., `R.raw.my_html_file`).
	 * @return A string containing the entire content of the file. Returns an empty string if
	 *         the file is empty or if an error occurs during the reading process.
	 * @see getHtmlString which uses this function specifically for HTML files.
	 */
	@JvmStatic
	fun convertRawHtmlFileToString(resourceId: Int): String {
		val inputStream = INSTANCE.resources.openRawResource(resourceId)
		val reader = BufferedReader(InputStreamReader(inputStream))
		val stringBuilder = StringBuilder()
		var line: String?
		try {
			while (reader.readLine()
					.also { line = it } != null
			) stringBuilder.append(line)
		} catch (error: Throwable) {
			logger.e("Error while converting raw html file to string:", error)
		} finally {
			try {
				inputStream.close()
				reader.close()
			} catch (error: Exception) {
				logger.e("Error while converting raw html file to string (in finally{}):", error)
			}
		}; return stringBuilder.toString()
	}

	/**
	 * Counts the number of occurrences of a specific character within a string.
	 *
	 * This function is case-sensitive. If either the input string or the character
	 * is `null`, it returns 0.
	 *
	 * @param input The string to search within. Can be `null`.
	 * @param char The character to count. Can be `null`.
	 * @return The total number of times `char` appears in `input`, or 0 if `input` or `char` is `null`.
	 *
	 * @sample
	 * val count = countOccurrences("hello world", 'l') // Returns 3
	 * val noMatch = countOccurrences("hello world", 'z') // Returns 0
	 * val nullInput = countOccurrences(null, 'a') // Returns 0
	 */
	@JvmStatic
	fun countOccurrences(input: String?, char: Char?): Int {
		if (input == null || char == null) return 0
		val count = input.count { it == char }
		return count
	}

	/**
	 * Removes all empty or whitespace-only lines from a string.
	 *
	 * This function splits the input string by newline characters (`\n`), filters out any lines
	 * that are blank (empty or contain only whitespace), and then rejoins the remaining lines
	 * with newlines. It effectively compacts a multiline string by removing all vertical spacing.
	 *
	 * Example:
	 * ```kotlin
	 * val text = """
	 * First line
	 *
	 *
	 * Third line
	 *
	 * Last line
	 * """
	 * val compacted = removeEmptyLines(text)
	 * // compacted will be:
	 * // "First line
	 * // Third line
	 * // Last line"
	 * ```
	 *
	 * @param input The multiline string to process. Can be `null` or empty.
	 * @return A new string with all blank lines removed, or `null` if the input is `null` or empty.
	 */
	@JvmStatic
	fun removeEmptyLines(input: String?): String? {
		if (input.isNullOrEmpty()) return null
		return input.split("\n")
			.filter { it.isNotBlank() }
			.joinToString("\n")
	}
	
	/**
	 * Formats a large number into a compact, human-readable string using metric prefixes
	 * (K for thousand, M for million, B for billion, T for trillion).
	 *
	 * This is commonly used for displaying view counts, subscriber counts, or other large metrics
	 * in a limited space. The function provides the following formatting rules:
	 * - Numbers less than 1,000 are returned as a standard string (e.g., `999` becomes `"999"`).
	 * - Numbers 1,000 or greater are scaled down and appended with the appropriate suffix.
	 * - If the scaled number is a whole number, no decimal part is shown (e.g., `10000` becomes `"10K"`).
	 * - If the scaled number has a decimal part, it is formatted to one decimal place (e.g., `1234` becomes `"1.2K"`).
	 *
	 * The function supports numbers up to the trillions.
	 *
	 * @param count The non-negative numeric count to format. Although it accepts any `Long`,
	 *              negative values are not handled gracefully and may produce unexpected results.
	 * @return A formatted string representing the count with a metric prefix if applicable.
	 *
	 * @sample
	 * formatViewCounts(999)      // "999"
	 * formatViewCounts(1000)     // "1K"
	 * formatViewCounts(1234)     // "1.2K"
	 * formatViewCounts(10000)    // "10K"
	 * formatViewCounts(12345)    // "12.3K"
	 * formatViewCounts(1234567)  // "1.2M"
	 * formatViewCounts(1000000000) // "1B"
	 */
	@JvmStatic
	fun formatViewCounts(count: Long): String {
		if (count < 1000) return count.toString()
		
		val units = arrayOf("K", "M", "B", "T")
		var value = count.toDouble()
		var unitIndex = -1
		
		while (value >= 1000 && unitIndex < units.lastIndex) {
			value /= 1000
			unitIndex++
		}
		
		return if (value % 1.0 == 0.0) {
			"${value.toInt()}${units[unitIndex]}"
		} else {
			String.format("%.1f%s", value, units[unitIndex])
		}
	}
	
}
