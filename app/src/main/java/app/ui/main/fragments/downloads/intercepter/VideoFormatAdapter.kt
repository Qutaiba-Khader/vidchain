package app.ui.main.fragments.downloads.intercepter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.TextView
import app.core.AIOApp.Companion.IS_ULTIMATE_VERSION_UNLOCKED
import app.core.bases.BaseActivity
import app.core.engines.video_parser.parsers.SupportedURLs.isYouTubeUrl
import app.core.engines.video_parser.parsers.VideoFormat
import app.core.engines.video_parser.parsers.VideoFormatsUtils.cleanFileSize
import app.core.engines.video_parser.parsers.VideoInfo
import com.aio.R
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.capitalizeWords
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import lib.texts.CommonTextUtils.getText
import java.lang.ref.WeakReference

/**
 * Adapter responsible for displaying a list of available [VideoFormat] items
 * within a resolution and format picker dialog.
 *
 * Each list item represents a specific video format (resolution, file size, etc.).
 * Selecting an item highlights it and triggers a callback to handle the selection.
 *
 * @param baseActivity Weak reference to the [BaseActivity] context.
 * @param videoInfo Metadata containing details about the video and its source URL.
 * @param videoFormats List of all available [VideoFormat] options.
 * @param onVideoFormatClick Callback executed when a user selects a format.
 */
open class VideoFormatAdapter(
	private val baseActivity: BaseActivity?,
	private val videoInfo: VideoInfo,
	private val videoFormats: List<VideoFormat>,
	private val onVideoFormatClick: () -> Unit
) : BaseAdapter() {

	/** Logger instance for debugging and event tracking within the adapter */
	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to the activity to prevent memory leaks */
	private val safeBaseActivityRef = WeakReference(baseActivity).get()

	/** Stores the index of the currently selected video format in the list */
	open var selectedPosition: Int = -1

	/** @return The total number of available video formats */
	override fun getCount(): Int = videoFormats.size

	/** @return The [VideoFormat] object at the specified list position */
	override fun getItem(position: Int): VideoFormat = videoFormats[position]

	/** @return The stable ID for the item at the specified position */
	override fun getItemId(position: Int): Long = position.toLong()

	/**
	 * Retrieves the currently selected video format from the adapter.
	 *
	 * @return The selected [VideoFormat], or null if no format is currently selected.
	 */
	fun getSelectedFormat(): VideoFormat? {
		logger.d("Retrieving selected video format at position: $selectedPosition")
		if (selectedPosition == -1) return null
		return getItem(selectedPosition)
	}

	/**
	 * Inflates and binds the view for each video format item in the resolution picker list.
	 *
	 * This method handles view recycling, populates resolution and file size info,
	 * updates selection state, and applies dynamic UI changes based on the selected item.
	 *
	 * @param position Index of the current item in the list.
	 * @param convertView The old view to reuse, if possible.
	 * @param parent The parent view that this view will be attached to.
	 * @return The populated view for the current list item.
	 */
	override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
		logger.d("Binding view for video format at position: $position")

		// Inflate layout if not reused
		val view: View = convertView ?: LayoutInflater.from(safeBaseActivityRef)
			.inflate(R.layout.dialog_video_res_picker_1_item, parent, false)

		safeBaseActivityRef?.let { safeBaseActivityRef ->
			val mainLayout = view.findViewById<View>(R.id.main_layout)
			val resolutionTextView: TextView = view.findViewById(R.id.txt_resolution)
			val fileSizeTextView: TextView = view.findViewById(R.id.txt_file_size)
			val imgSelectionView: ImageView = view.findViewById(R.id.img_checkbox_selection)

			val videoFormat = getItem(position)
			logger.d("Processing format: ${videoFormat.formatResolution} (${videoFormat.formatFileSize})")

			// Format file size for better readability
			val cleanedFileSize = cleanFileSize(videoFormat.formatFileSize)
			val fileSizeSpanned = cleanedFileSize.ifEmpty { videoFormat.formatFileSize }

			// Adjust resolution text (capitalize audio formats)
			var resolution = videoFormat.formatResolution
			if (resolution.lowercase().contains("audio", ignoreCase = true)) {
				resolution = capitalizeWords(resolution) ?: resolution
			}

			// Bind formatted data to views
			resolutionTextView.text = extractHeightFromResolution(resolution)
			fileSizeTextView.text = fromHtmlStringToSpanned(fileSizeSpanned)

			// Hide "Unknown" file size for YouTube videos if ultimate version unlocked
			removeFileInfoOnYT(fileSizeTextView)

			// Store tag for potential reuse
			if (view.tag == null) view.tag = videoFormat

			// Highlight selected item visually
			if (position == selectedPosition) {
				logger.d("Item selected at position $position")
				mainLayout.setBackgroundResource(R.drawable.rounded_secondary_color)
				resolutionTextView.setTextColor(safeBaseActivityRef.getColor(R.color.color_on_secondary))
				fileSizeTextView.setTextColor(safeBaseActivityRef.getColor(R.color.color_on_secondary))
				imgSelectionView.visibility = View.VISIBLE
			} else {
				mainLayout.setBackgroundResource(R.drawable.rounded_secondary_color_border)
				resolutionTextView.setTextColor(safeBaseActivityRef.getColor(R.color.color_text_primary))
				fileSizeTextView.setTextColor(safeBaseActivityRef.getColor(R.color.color_text_primary))
				imgSelectionView.visibility = View.GONE
			}

			// Handle click to select format
			view.setOnClickListener {
				logger.d("User clicked format at position: $position")
				selectedPosition = position
				notifyDataSetChanged()
				onVideoFormatClick()
			}
		}

		return view
	}

	/**
	 * Hides "Unknown" file size for YouTube videos if the Ultimate version is unlocked.
	 *
	 * This ensures a cleaner and more user-friendly display by replacing the generic
	 * "Unknown" text with "N/A" when users have access to advanced features.
	 *
	 * @param fileSizeTextView The [TextView] displaying the file size information.
	 */
	private fun removeFileInfoOnYT(fileSizeTextView: TextView) {
		logger.d("Checking if YouTube file size needs to be replaced with 'N/A'")
		if (fileSizeTextView.text.toString() == getText(R.string.title_unknown)
			&& IS_ULTIMATE_VERSION_UNLOCKED && isYouTubeUrl(videoInfo.videoUrl)
		) {
			logger.d("Replacing 'Unknown' with 'N/A' for YouTube video (Ultimate version unlocked)")
			fileSizeTextView.text = "N/A"
		}
	}

	/**
	 * Extracts the vertical resolution (height) from a given resolution string.
	 *
	 * Supports a wide range of formats such as:
	 * - "1280x720" → "720p"
	 * - "720p" → "720p"
	 * - "4UHD" → "4p"
	 *
	 * @param resolution Raw resolution string.
	 * @return Cleaned resolution string like "720p", or the original value if parsing fails.
	 */
	private fun extractHeightFromResolution(resolution: String): String {
		logger.d("Extracting height value from resolution string: $resolution")
		val patterns = listOf(
			// Common patterns
			Regex("(\\d+)p"),                         // 720p
			Regex("(\\d+)\\s*[xX×]\\s*(\\d+)p?"),    // 1280x720, 1280×720p
			Regex("[^\\d](\\d+)\\s*[pP]"),            // ...720p
			Regex("(\\d+)\\s*[pP][^\\d]"),           // 720p...
			Regex("(\\d+)\\s*[pP]\\s*[^\\d]"),        // 720p ...
			Regex("[^\\d](\\d+)\\s*[iI]"),            // ...720i
			Regex("(\\d+)\\s*[iI][^\\d]"),            // 720i...

			// Patterns with px
			Regex("(\\d+)px(\\d+)p?"),                // 1280px720, 1280px720p
			Regex("(\\d+)\\s*px\\s*(\\d+)\\s*[pP]"),  // 1280 px 720 p

			// Patterns with other separators
			Regex("(\\d+)\\s*[*]\\s*(\\d+)p?"),       // 1280*720, 1280*720p
			Regex("(\\d+)\\s*[|]\\s*(\\d+)p?"),       // 1280|720, 1280|720p

			// UHD/HD patterns
			Regex("(\\d+)\\s*[uU][hH][dD]"),         // 4UHD
			Regex("(\\d+)\\s*[hH][dD]")               // 1080HD
		)

		for (pattern in patterns) {
			val match = pattern.find(resolution)
			if (match != null) {
				// Try to get height (usually the last number)
				val groups = match.groupValues
				for (i in groups.size downTo 1) {
					val group = groups[i - 1]
					if (group.isNotEmpty() && group.all { it.isDigit() }) {
						val extracted = "${group}p"
						logger.d("Extracted resolution height: $extracted")
						return extracted
					}
				}
			}
		}

		logger.d("No match found for resolution extraction, returning original: $resolution")
		return resolution
	}
}
