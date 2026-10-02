package app.core.engines.browser.bookmarks

import android.content.Context.MODE_PRIVATE
import app.core.AIOApp
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioGSONInstance
import app.core.engines.fst_serializer.FSTBuilder.fstConfig
import com.anggrayudi.storage.file.getAbsolutePath
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import lib.files.FileSystemUtility.saveStringToInternalStorage
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileReader
import java.io.Serializable
import java.nio.channels.FileChannel
import java.util.Date
import java.util.Locale

/**
 * Manages browser bookmarks including storage, retrieval, and various operations.
 *
 * Features:
 * - Persistent storage using both JSON and binary serialization
 * - Efficient file reading strategies based on file size
 * - Thread-safe operations through background execution
 * - Advanced bookmark filtering and sorting capabilities
 * - Support for bookmark metadata (ratings, tags, favorites, etc.)
 * - Automatic duplicate detection
 * - Archive functionality
 *
 * Storage Strategy:
 * - Small files (<0.5MB): Simple read operation
 * - Medium files (0.5-5MB): Buffered read
 * - Large files (>5MB): Memory-mapped I/O with fallback to line-by-line reading
 *
 * All file operations are performed asynchronously to avoid blocking the UI thread.
 */
@Entity
class AIOBookmarks : Serializable {

	@Transient
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Unique identifier for the bookmarks record in ObjectBox database.
	 * @see io.objectbox.annotation.Id for primary key configuration
	 */
	@Id
	var id: Long = 0

	/** List containing all recorded bookmark entries. */
	@SerializedName("bookmarkModels")
	@Transient
	var bookmarkModels: ArrayList<BookmarkModel> = ArrayList()

	/**
	 * Reads bookmarks from persistent storage.
	 *
	 * - Attempts to load from binary format first (faster, smaller).
	 * - If binary is missing, invalid, or bypassed → fall back to JSON.
	 * - Chooses optimal reading strategy (small/medium/large) based on file size.
	 * - Ensures aioBookmark is always initialized (either loaded or preloaded).
	 *
	 * @param bypassBinaryFormat If true, binary file is ignored and deleted, forcing JSON load.
	 */
	fun readObjectFromStorage(bypassBinaryFormat: Boolean = false) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				var isBinaryFileValid = false
				val internalDir = AIOApp.internalDataFolder
				val bookmarkBinaryDataFile = internalDir.findFile(AIO_BOOKMARKS_FILE_NAME_BINARY)

				// --- 1. Try to load from binary format ---
				if (!bypassBinaryFormat) {
					if (bookmarkBinaryDataFile != null && bookmarkBinaryDataFile.exists()) {
						logger.d("Found binary bookmarks file, attempting load")

						val absolutePath = bookmarkBinaryDataFile.getAbsolutePath(INSTANCE)
						val objectInMemory = loadFromBinary(File(absolutePath))

						if (objectInMemory != null) {
							// Successfully restored bookmarks from binary
							logger.d("Successfully loaded bookmarks from binary format")
							aioBookmark = objectInMemory
							aioBookmark.updateInStorage()
							isBinaryFileValid = true
						} else {
							// Corrupted or unreadable binary
							logger.d("Failed to load bookmarks from binary format")
						}
					}
				} else {
					// If bypass flag is true → force delete binary file
					if (bookmarkBinaryDataFile != null && bookmarkBinaryDataFile.exists()) {
						bookmarkBinaryDataFile.delete()
						logger.d("Bypassed and deleted binary bookmarks file")
					}
				}

				// --- 2. Fall back to JSON format if binary was invalid or bypassed ---
				if (!isBinaryFileValid) {
					logger.d("Attempting to load bookmarks from JSON format")
					val configFile = File(INSTANCE.filesDir, AIO_BOOKMARKS_FILE_NAME_JSON)

					if (!configFile.exists()) {
						// No JSON file available → start with preloaded defaults
						aioBookmark.bookmarkModels = getPreloadedBookmarks()
						logger.d("No bookmarks file found, starting with preloaded library")
						return@executeInBackground
					}

					// Log file size and decide which reader to use
					val fileSizeMb = configFile.length().toDouble() / (1024 * 1024)
					logger.d("Bookmarks file size: ${"%.2f".format(fileSizeMb)} MB")

					val json = when {
						fileSizeMb <= 0.5 -> readSmallFile(configFile)   // Simple load
						fileSizeMb <= 5.0 -> readMediumFile(configFile) // Buffered load
						else -> readLargeFile(configFile)               // Streamed load
					}

					// Deserialize JSON → Kotlin class
					if (json.isNotEmpty()) {
						convertJSONStringToClass(json).let { bookmarkClass ->
							logger.d("Successfully loaded ${bookmarkClass.bookmarkModels.size} bookmarks")
							aioBookmark = bookmarkClass
							aioBookmark.updateInStorage()
						}
					} else {
						logger.d("Bookmarks JSON file was empty")
					}
				}
			} catch (error: Exception) {
				// Catch-all safeguard to prevent crashes
				logger.e("Error reading bookmarks: ${error.message}", error)
			}
		})
	}

	/**
	 * Creates and returns a list of preloaded bookmarks.
	 *
	 * @return ArrayList of BookmarkModel with 50+ well-known sites.
	 */
	fun getPreloadedBookmarks(): ArrayList<BookmarkModel> {
		val bookmarks = ArrayList<BookmarkModel>()

		val sites = listOf(
			// 🌍 General / Search
			"Google" to "https://google.com/",
			"Bing" to "https://bing.com/",
			"Yahoo" to "https://yahoo.com/",
			"DuckDuckGo" to "https://duckduckgo.com/",
			"Wikipedia" to "https://wikipedia.org/",

			// 🎵 Music
			"PagalNew" to "https://pagalnew.com/",
			"Mr-Jatt" to "https://mr-jatt.im/",
			"DJMaza" to "https://djmaza.my/",
			"DJPunjab" to "https://djpunjab.fm/",
			"Raag.fm" to "https://raag.fm/",
			"JioSaavn" to "https://jiosaavn.com/",
			"Wynk Music" to "https://wynk.in/music",
			"Gaana" to "https://gaana.com/",

			// 🎬 Movie Download Sites
			"MoviesWap" to "https://movieswap.cam/",
			"TamilYogi" to "https://tamilyogi.cool/",
			"9xMovies" to "https://9xmovies.team/",
			"7StarHD" to "https://7starhd.run/",
			"Khatrimaza" to "https://khatrimaza.uno/",
			"WorldFree4U" to "https://worldfree4u.lol/",
			"MoviesFlix" to "https://themoviesflix.org/",
			"HDMoviesHub" to "https://hdmovieshub.ltd/",
			"BollywoodHungama" to "https://bollywoodhungama.com/movies/",
			"SSRMovies" to "https://ssrmovies.rest/",
			"SkyMoviesHD" to "https://skymovieshd.work/",
			"KatMovieHD" to "https://katmoviehd.run/",
			"HDHub4U" to "https://hdhub4u.day/",
			"RdxHD" to "https://rdxhd.com/",
			"Okjatt" to "https://okjatt.team/",
			"VegaMovies" to "https://vegamovies.boo/",
			"KuttyMovies" to "https://kuttymovies.day/",
			"MLWBD" to "https://mlwbd.world/",
			"JioRockers" to "https://jiorockers.link/",
			"Extramovies" to "https://extramovies.wiki/",
			"MoviesBaba" to "https://moviesbaba.run/",
			"DesiFilmy" to "https://desifilmy.me/",
			"DownloadHub" to "https://downloadhub.baby/",
			"MovieMad" to "https://moviemad.run/",
			"KatMovies" to "https://katmovieshd.surf/",

			// 🔞 Adult (18+)
			"Pornhub" to "https://pornhub.com/",
			"TubeSafari" to "https://tubesafari.com/",
			"UncutClips" to "https://uncutclips.org/",
			"XVideos" to "https://xvideos.com/",
			"XNXX" to "https://xnxx.com/",
			"RedTube" to "https://redtube.com/",
			"YouPorn" to "https://youporn.com/",
			"Eporner" to "https://eporner.com/",
			"SpankBang" to "https://spankbang.com/",
			"xHamster" to "https://xhamster.com/",
			"Tube8" to "https://tube8.com/",
			"Porn.com" to "https://porn.com/",
			"Thumbzilla" to "https://thumbzilla.com/",
			"Tnaflix" to "https://tnaflix.com/",
			"SexTB" to "https://sextb.net/",
			"Beeg" to "https://beeg.com/",
			"DrTuber" to "https://drtuber.com/",
			"SunPorno" to "https://sunporno.com/",
			"PornTrex" to "https://porntrex.com/",
			"XTube" to "https://xtube.com/",
			"Slutload" to "https://slutload.com/",
			"PornHD" to "https://pornhd.com/",
			"BravoPorn" to "https://bravoporn.com/",
			"Fantasti.cc" to "https://fantasti.cc/",
			"XTubePorn" to "https://xtubeporn.com/",
			"HotMovies" to "https://hotmovies.com/",
			"XMovie8" to "https://xmovie8.com/",
			"PornOne" to "https://pornone.com/",
			"NuBay" to "https://nubay.com/",
			"Motherless" to "https://motherless.com/",
			"RedGifs" to "https://redgifs.com/",
			"CamSoda" to "https://camsoda.com/",
			"Chaturbate" to "https://chaturbate.com/",
			"MyFreeCams" to "https://myfreecams.com/",
			"LiveJasmin" to "https://livejasmin.com/",
			"Stripchat" to "https://stripchat.com/",
			"BongaCams" to "https://bongacams.com/",
			"Cam4" to "https://cam4.com/",
			"XConfessions" to "https://xconfessions.com/",
			"Sex.com" to "https://sex.com/",
			"HClips" to "https://hclips.com/",
			"HQPorner" to "https://hqporner.com/",
			"XXNX" to "https://xxnx.com/",
			"4Tube" to "https://4tube.com/",
			"Fapster" to "https://fapster.xxx/",
			"FapVid" to "https://fapvid.com/",
			"FapNation" to "https://fapnxx.com/",
			"YesPornPlease" to "https://yespornplease.to/",
			"DaftSex" to "https://daftsex.com/",
			"Xozilla" to "https://xozilla.com/",
			"XFreeHD" to "https://xfreehd.com/",
			"Porndig" to "https://porndig.com/",
			"WatchMyGF" to "https://watchmygf.xxx/",
			"PornMegaLoad" to "https://pornmegaload.com/",
			"Analdin" to "https://analdin.com/",
			"Fux" to "https://fux.com/",
			"XXXBunker" to "https://xxxbunker.com/",
			"BravoTube" to "https://bravotube.net/",
			"PornDoe" to "https://porndoe.com/",
			"PornHat" to "https://pornhat.com/",
			"JizzBunker" to "https://jizzbunker.com/",
			"ExtremeTube" to "https://extremetube.com/",
			"Spankwire" to "https://spankwire.com/",
			"YourPorn" to "https://youporn.xxx/",
			"PornXO" to "https://pornxo.com/",
			"SexVid" to "https://sexvid.xxx/",
			"FapTube" to "https://faptube.com/"
		)

		for ((name, url) in sites) {
			val bookmark = BookmarkModel().apply {
				bookmarkName = name
				bookmarkUrl = url
				bookmarkCreationDate = Date()
			}
			bookmarks.add(bookmark)
		}

		return bookmarks
	}

	/**
	 * Saves bookmarks to binary format.
	 * @param fileName Name of the binary file to save to
	 */
	@Synchronized
	private fun saveToBinary(fileName: String) {
		try {
			logger.d("Saving bookmarks to binary file: $fileName")
			val fileOutputStream = INSTANCE.openFileOutput(fileName, MODE_PRIVATE)
			fileOutputStream.use { fos ->
				val bytes = fstConfig.asByteArray(this)
				fos.write(bytes)
				logger.d("Bookmarks saved successfully to binary format")
			}
		} catch (error: Exception) {
			logger.e("Error saving binary bookmarks: ${error.message}", error)
		}
	}

	/**
	 * Loads bookmarks from binary format.
	 * @param bookmarksBinaryFile File containing binary bookmarks data
	 * @return AIOBookmarks instance or null if loading fails
	 */
	private fun loadFromBinary(bookmarksBinaryFile: File): AIOBookmarks? {
		if (!bookmarksBinaryFile.exists()) {
			logger.d("Binary bookmarks file does not exist")
			return null
		}

		return try {
			logger.d("Loading bookmarks from binary file")
			val bytes = bookmarksBinaryFile.readBytes()
			fstConfig.asObject(bytes).apply {
				logger.d("Successfully loaded bookmarks from binary format")
			} as AIOBookmarks
		} catch (error: Exception) {
			logger.e("Error loading binary bookmarks: ${error.message}", error)
			bookmarksBinaryFile.delete()
			null
		}
	}

	/**
	 * Reads small files using simple file operation.
	 * @param file File to read
	 * @return File contents as String
	 */
	private fun readSmallFile(file: File): String {
		logger.d("Using simple read for small file")
		return file.readText(Charsets.UTF_8)
	}

	/**
	 * Reads medium files using buffered reader.
	 * @param file File to read
	 * @return File contents as String
	 */
	private fun readMediumFile(file: File): String {
		logger.d("Using buffered read for medium file")
		return BufferedReader(FileReader(file)).use { it.readText() }
	}

	/**
	 * Reads large files using memory-mapped I/O with fallback.
	 * @param file File to read
	 * @return File contents as String
	 */
	private fun readLargeFile(file: File): String {
		return try {
			logger.d("Using memory-mapped I/O for large file")
			FileInputStream(file).channel.use { channel ->
				val buffer = channel.map(
					FileChannel.MapMode.READ_ONLY,
					0, channel.size()
				)
				Charsets.UTF_8.decode(buffer).toString()
			}
		} catch (error: OutOfMemoryError) {
			logger.e("Falling back to line-by-line reading: ${error.message}", error)
			buildString {
				BufferedReader(FileReader(file)).forEachLine { line ->
					append(line)
				}
			}
		}
	}

	/**
	 * Serializes bookmarks to JSON.
	 * @return JSON string representation
	 */
	private fun convertClassToJSON(): String {
		logger.d("Converting bookmarks to JSON")
		return aioGSONInstance.toJson(this)
	}

	/**
	 * Deserializes JSON to AIOBookmarks instance.
	 * @param data JSON string to convert
	 * @return AIOBookmarks instance
	 */
	private fun convertJSONStringToClass(data: String): AIOBookmarks {
		logger.d("Converting JSON to bookmarks object")
		// Parse the wrapper first
		val bookmarks = aioGSONInstance.fromJson(data, AIOBookmarks::class.java)

		// Parse the list properly with type info
		val type = object : TypeToken<ArrayList<BookmarkModel>>() {}.type
		val bookmarksLibraryJSON = JSONObject(data).getString("bookmarkLibrary")
		val parsedList: ArrayList<BookmarkModel> =
			aioGSONInstance.fromJson(bookmarksLibraryJSON, type)

		bookmarks.bookmarkModels = parsedList
		return bookmarks
	}

	/**
	 * Persists current bookmarks to storage.
	 * Saves both JSON and binary formats asynchronously.
	 */
	fun updateInStorage() {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				logger.d("Updating bookmarks in storage")
				saveToBinary(AIO_BOOKMARKS_FILE_NAME_BINARY)
				saveStringToInternalStorage(
					fileName = AIO_BOOKMARKS_FILE_NAME_JSON,
					data = convertClassToJSON()
				)
				AIOBookmarksDBManager.updateAllBookmarksInDB(bookmarks = getBookmarkLibrary())
				logger.d("Bookmarks successfully updated in storage")
			} catch (error: Exception) {
				logger.e("Error updating bookmarks: ${error.message}", error)
			}
		})
	}

	/**
	 * Gets all bookmarks in the library.
	 * @return List of all bookmarks
	 */
	fun getBookmarkLibrary(): ArrayList<BookmarkModel> {
		return bookmarkModels
	}

	/**
	 * Adds a new bookmark to the library.
	 * @param bookmarkModel Bookmark to add
	 */
	fun insertNewBookmark(bookmarkModel: BookmarkModel) {
		getBookmarkLibrary().add(bookmarkModel)
	}

	/**
	 * Updates an existing bookmark.
	 * @param oldBookmark Bookmark to replace
	 * @param newBookmark New bookmark data
	 */
	fun updateBookmark(oldBookmark: BookmarkModel, newBookmark: BookmarkModel) {
		val index = bookmarkModels.indexOf(oldBookmark)
		if (index != -1) {
			bookmarkModels[index] = newBookmark
		}
	}

	/**
	 * Removes a bookmark from the library.
	 * @param bookmarkModel Bookmark to remove
	 */
	fun removeBookmark(bookmarkModel: BookmarkModel) {
		bookmarkModels.remove(bookmarkModel)
	}

	/**
	 * Searches bookmarks by name or URL.
	 * @param query Search term
	 * @return List of matching bookmarks (case-insensitive)
	 */
	fun searchBookmarksNormal(query: String): List<BookmarkModel> {
		return bookmarkModels.filter {
			it.bookmarkName.contains(query, ignoreCase = true) ||
					it.bookmarkUrl.contains(query, ignoreCase = true)
		}
	}

	/**
	 * Fuzzy search bookmarks by name or URL.
	 *
	 * Behavior:
	 * - Case-insensitive.
	 * - Supports multi-term queries (all terms must appear somewhere).
	 * - Uses a mix of exact matching, substring matching, and Jaro-Winkler similarity for typo tolerance.
	 * - Results are ranked by relevance: exact matches first, then higher similarity scores.
	 *
	 * @param query The search input string.
	 * @return A ranked list of matching bookmarks.
	 */
	fun searchBookmarksFuzzy(query: String): List<BookmarkModel> {
		// Normalize search string (trim + lowercase) for case-insensitive comparison
		val normalizedQuery = query.trim().lowercase()
		if (normalizedQuery.isEmpty()) return emptyList()

		// Split query into words for multi-term support
		val queryTerms = normalizedQuery.split("\\s+".toRegex()).filter { it.isNotEmpty() }

		return bookmarkModels
			.map { bookmark ->
				val name = bookmark.bookmarkName.lowercase()
				val url = bookmark.bookmarkUrl.lowercase()

				// Strongest match: name or URL exactly equals query
				val exactMatch = (name == normalizedQuery || url == normalizedQuery)

				// Fuzzy similarity using Jaro-Winkler (tolerates typos)
				val jwScore = maxOf(
					jaroWinklerSimilarity(normalizedQuery, name),
					jaroWinklerSimilarity(normalizedQuery, url)
				)

				// Medium strength: query appears as substring in name or URL
				val contains = name.contains(normalizedQuery) || url.contains(normalizedQuery)

				// Multi-term matching: all terms must appear in either name or URL
				val allTermsMatch = queryTerms.all { term ->
					name.contains(term) || url.contains(term)
				}

				// Assign a relevance score
				val score = when {
					exactMatch -> 1.0
					contains -> 0.9
					allTermsMatch -> 0.85
					else -> jwScore
				}

				bookmark to (score to exactMatch)
			}
			// Drop weak matches (below threshold)
			.filter { (_, scoreInfo) -> scoreInfo.first >= 0.75 }
			// Sort: exact matches first, then by score
			.sortedWith(
				compareByDescending<Pair<BookmarkModel, Pair<Double, Boolean>>> { it.second.second }
					.thenByDescending { it.second.first }
			)
			.map { it.first }
	}

	/**
	 * Computes Jaro-Winkler similarity between two strings.
	 * - Returns 1.0 for identical strings, 0.0 for no similarity.
	 * - Prioritizes common prefixes and transpositions.
	 */
	private fun jaroWinklerSimilarity(s1: String, s2: String): Double {
		if (s1 == s2) return 1.0

		val matchDistance = maxOf(s1.length, s2.length) / 2 - 1
		var matches = 0
		var transpositions = 0

		val s1Matches = BooleanArray(s1.length)
		val s2Matches = BooleanArray(s2.length)

		// Identify matching characters within the allowable distance
		for (i in s1.indices) {
			val start = maxOf(0, i - matchDistance)
			val end = minOf(i + matchDistance + 1, s2.length)

			for (j in start until end) {
				if (!s2Matches[j] && s1[i] == s2[j]) {
					s1Matches[i] = true
					s2Matches[j] = true
					matches++
					break
				}
			}
		}

		if (matches == 0) return 0.0

		// Count transpositions (characters matched out of order)
		var k = 0
		for (i in s1.indices) {
			if (s1Matches[i]) {
				while (!s2Matches[k]) k++
				if (s1[i] != s2[k]) transpositions++
				k++
			}
		}

		val jaro = (matches / s1.length.toDouble() +
				matches / s2.length.toDouble() +
				(matches - transpositions / 2.0) / matches) / 3.0

		// Winkler bonus for common prefix (up to 4 chars)
		val prefixLimit = minOf(4, minOf(s1.length, s2.length))
		var prefix = 0
		while (prefix < prefixLimit && s1[prefix] == s2[prefix]) {
			prefix++
		}

		return jaro + (prefix * 0.1 * (1 - jaro))
	}

	/**
	 * Finds duplicate bookmarks (same URL).
	 * @return List of duplicate bookmarks
	 */
	fun findDuplicateBookmarks(): List<BookmarkModel> {
		return bookmarkModels
			.groupBy { it.bookmarkUrl }
			.filter { it.value.size > 1 }
			.flatMap { it.value }
	}

	/**
	 * Sorts bookmarks by specified attribute.
	 * @param attribute Attribute to sort by ("name" or "date")
	 * @return Sorted list of bookmarks
	 */
	fun getBookmarksSortedBy(attribute: String): List<BookmarkModel> {
		return when (attribute.lowercase(Locale.ROOT)) {
			"name" -> bookmarkModels.sortedBy { it.bookmarkName }
			"date" -> bookmarkModels.sortedBy { it.bookmarkCreationDate }
			else -> bookmarkModels
		}
	}

	/**
	 * Filters bookmarks by minimum rating.
	 * @param minRating Minimum rating threshold
	 * @return List of bookmarks meeting the rating criteria
	 */
	fun filterBookmarksByRating(minRating: Float): List<BookmarkModel> {
		return bookmarkModels.filter { it.bookmarkRating >= minRating }
	}

	/**
	 * Gets all favorite bookmarks.
	 * @return List of favorite bookmarks
	 */
	fun getFavoriteBookmarks(): List<BookmarkModel> {
		return bookmarkModels.filter { it.bookmarkFavorite }
	}

	/**
	 * Filters bookmarks by tag.
	 * @param tag Tag to filter by
	 * @return List of bookmarks containing the specified tag
	 */
	fun filterBookmarksByTag(tag: String): List<BookmarkModel> {
		return bookmarkModels.filter { it.bookmarkTags.contains(tag) }
	}

	/**
	 * Filters bookmarks by minimum priority.
	 * @param minPriority Minimum priority threshold
	 * @return List of bookmarks meeting the priority criteria
	 */
	fun filterBookmarksByPriority(minPriority: Int): List<BookmarkModel> {
		return bookmarkModels.filter { it.bookmarkPriority >= minPriority }
	}

	/**
	 * Gets all archived bookmarks.
	 * @return List of archived bookmarks
	 */
	fun getArchivedBookmarks(): List<BookmarkModel> {
		return bookmarkModels.filter { it.bookmarkArchived }
	}

	/**
	 * Archives a bookmark.
	 * @param bookmarkModel Bookmark to archive
	 */
	fun archiveBookmark(bookmarkModel: BookmarkModel) {
		val index = bookmarkModels.indexOf(bookmarkModel)
		if (index != -1) {
			bookmarkModels[index].bookmarkArchived = true
		}
	}

	/**
	 * Gets bookmarks shared with specific user.
	 * @param user User identifier
	 * @return List of shared bookmarks
	 */
	fun getBookmarksSharedWithUser(user: String): List<BookmarkModel> {
		return bookmarkModels.filter { it.bookmarkSharedWith.contains(user) }
	}

	/**
	 * Clears all bookmarks from the library.
	 */
	fun clearAllBookmarks() {
		bookmarkModels.clear()
	}

	/**
	 * Gets total number of bookmarks.
	 * @return Count of bookmarks
	 */
	fun countBookmarks(): Int {
		return bookmarkModels.size
	}

	companion object {

		/**
		 * Default filename for JSON formatted bookmark storage
		 */
		const val AIO_BOOKMARKS_FILE_NAME_JSON: String = "aio_bookmarks.json"

		/**
		 * Default filename for binary formatted bookmark storage
		 */
		const val AIO_BOOKMARKS_FILE_NAME_BINARY: String = "aio_bookmarks.dat"
	}
}