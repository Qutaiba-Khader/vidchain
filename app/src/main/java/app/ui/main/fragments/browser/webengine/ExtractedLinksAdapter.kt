package app.ui.main.fragments.browser.webengine

import android.view.View
import android.view.View.GONE
import android.view.View.inflate
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import app.core.engines.video_parser.parsers.SupportedURLs.isM3U8Url
import app.core.engines.video_parser.parsers.VideoInfo
import app.core.engines.video_parser.parsers.VideoThumbGrabber.getCurrentOgImage
import app.ui.main.MotherActivity
import app.ui.main.fragments.browser.webengine.M3U8InfoExtractor.InfoCallback
import app.ui.main.fragments.downloads.intercepter.SharedVideoURLIntercept
import com.aio.R
import lib.device.DateTimeUtils.formatVideoDuration
import lib.networks.DownloaderUtils.getVideoDurationFromUrl
import lib.networks.DownloaderUtils.getVideoResolutionFromUrl
import lib.process.AsyncJobUtils.executeInBackground
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.ClipboardUtils.copyTextToClipboard
import lib.texts.CommonTextUtils.getText
import lib.ui.ViewUtility.animateFadInOutAnim
import lib.ui.ViewUtility.closeAnyAnimation
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.WeakReference

/**
 * Adapter class to show a list of extracted video links in a dialog.
 * Each item represents a video URL with resolution and metadata information.
 *
 * @param extractedLinksDialog Reference to the dialog showing this adapter.
 * @param webviewEngine WebView engine instance to extract cookies, titles, and context.
 * @param listOfVideoUrlInfos List of extracted video URL information to display.
 */
class ExtractedLinksAdapter(
	private val extractedLinksDialog: ExtractedLinksDialog,
	private val webviewEngine: WebViewEngine,
	private val listOfVideoUrlInfos: ArrayList<VideoUrlInfo>
) : BaseAdapter() {

	/** Logger instance for debug messages and error tracking */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the parent [MotherActivity] from the [WebViewEngine].
	 * Helps prevent memory leaks by avoiding direct strong references to the activity.
	 */
	private val safeMotherActivityRef = WeakReference(webviewEngine.safeMotherActivityRef).get()

	/**
	 * Returns the total number of video URLs available.
	 */
	override fun getCount(): Int = listOfVideoUrlInfos.size

	/**
	 * Returns the [VideoUrlInfo] object at the specified position.
	 *
	 * @param position Index of the video URL in the list
	 * @return VideoUrlInfo at the given position
	 */
	override fun getItem(position: Int): VideoUrlInfo = listOfVideoUrlInfos[position]

	/**
	 * Returns the stable ID for the item at the given position.
	 *
	 * @param position Index of the video URL in the list
	 * @return ID corresponding to the item (here simply the position as Long)
	 */
	override fun getItemId(position: Int): Long = position.toLong()

	/**
	 * Provides a view for each video item in the list.
	 * Handles view recycling and binds video data using a ViewHolder.
	 *
	 * @param position Index of the video item in the list
	 * @param convertView Reusable view to recycle if available
	 * @param parent Parent [ViewGroup] that will contain this item
	 * @return Configured [View] for the video item
	 */
	override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
		val extractedVideoLink = listOfVideoUrlInfos[position]
		var itemLayout = convertView

		if (itemLayout == null) {
			val layoutResId = R.layout.dialog_extracted_links_item
			itemLayout = inflate(safeMotherActivityRef, layoutResId, null)
			logger.d("Inflated new view for position $position")
		}

		if (itemLayout!!.tag == null) {
			// Initialize ViewHolder and bind data
			ViewHolder(
				extractedLinksDialog = extractedLinksDialog,
				webviewEngine = webviewEngine,
				position = position,
				layoutView = itemLayout,
				safeMotherActivity = safeMotherActivityRef
			).apply {
				updateView(extractedVideoLink)
				itemLayout.tag = this
				logger.d("Created ViewHolder and bound data for position $position")
			}
		} else {
			logger.d("Reusing existing ViewHolder for position $position")
			itemLayout.tag as ViewHolder
		}

		return itemLayout
	}

	/**
	 * ViewHolder for caching views and handling logic for each extracted video item in the list.
	 *
	 * Responsibilities:
	 * - Binds video URL and metadata to the layout.
	 * - Handles async resolution extraction for normal and HLS/M3U8 videos.
	 * - Sets up click and long-click listeners for user interactions.
	 * - Caches video info to avoid repeated network requests.
	 */
	class ViewHolder(
		private val extractedLinksDialog: ExtractedLinksDialog,
		private val webviewEngine: WebViewEngine,
		private val position: Int,
		private val layoutView: View,
		private val safeMotherActivity: MotherActivity?
	) {
		private val logger = LogHelperUtils.from(javaClass)
		private val m3U8InfoExtractor = M3U8InfoExtractor()

		// Main clickable container for the video item
		private var itemClickableContainer: View = layoutView.findViewById(R.id.main_container)

		// TextView displaying the video URL
		private var linkItemUrl: TextView = layoutView.findViewById(R.id.txt_video_url)

		// TextView displaying the video info (resolution, duration)
		private var linkItemInfo: TextView = layoutView.findViewById(R.id.txt_video_info)

		// Cached info string to prevent repeated extraction
		private var textLinkItemInfo: String = ""

		// Current video thumbnail from the webpage (if available)
		private var currentWebpageVideoThumb = ""

		/**
		 * Updates the item layout with video URL, metadata, and click listeners.
		 *
		 * - Sets the video URL in the TextView.
		 * - Fetches OG image from current webpage if thumbnail not cached.
		 * - Displays metadata for normal or HLS/M3U8 videos asynchronously.
		 * - Sets up click listener to prompt download or show options.
		 * - Sets up long-click listener to copy URL to clipboard.
		 *
		 * @param videoUrlInfo The [VideoUrlInfo] containing metadata for this video item.
		 */
		fun updateView(videoUrlInfo: VideoUrlInfo) {
			logger.d("Updating view for video URL: ${videoUrlInfo.fileUrl}")
			linkItemUrl.text = videoUrlInfo.fileUrl

			if (currentWebpageVideoThumb.isEmpty()) {
				webviewEngine.currentWebView?.getCurrentOgImage {
					currentWebpageVideoThumb = it ?: ""
					logger.d("Fetched webpage thumbnail: $currentWebpageVideoThumb")
				}
			}

			// If cached info exists, reuse it
			if (textLinkItemInfo.isNotEmpty()) {
				linkItemInfo.text = textLinkItemInfo
				return
			}

			// Display metadata based on video type
			if (isM3U8Url(videoUrlInfo.fileUrl)) {
				showHSLVideoLinkInfo(videoUrlInfo)
			} else {
				showNormalVideoLinkInfo(videoUrlInfo)
			}

			// Setup user interactions
			setupLongClickItemListener(videoUrlInfo.fileUrl)
			setupOnClickItemListener(videoUrlInfo)
		}

		/**
		 * Displays metadata for HLS/M3U8 video streams.
		 *
		 * - Extracts available resolutions and duration asynchronously using [m3U8InfoExtractor].
		 * - Updates the UI with resolution count and duration once available.
		 * - Caches the extracted info in [VideoUrlInfo.infoCached] to avoid repeated network requests.
		 * - Handles single or multiple resolutions differently for display purposes.
		 * - Marks the video as HLS/M3U8 via [VideoUrlInfo.isM3U8].
		 *
		 * @param videoUrlInfo Information about the M3U8 video including cached data.
		 */
		private fun showHSLVideoLinkInfo(videoUrlInfo: VideoUrlInfo) {
			safeMotherActivity?.let { motherActivity ->
				ThreadsUtility.executeInBackground(codeBlock = {
					// Animate the link info TextView while fetching
					ThreadsUtility.executeOnMain { animateFadInOutAnim(linkItemInfo) }

					// Use cached info if available
					if (videoUrlInfo.infoCached.isNotEmpty()) {
						linkItemInfo.text = videoUrlInfo.infoCached
						ThreadsUtility.executeOnMain { closeAnyAnimation(linkItemInfo) }
						return@executeInBackground
					}

					// Extract resolutions and duration from M3U8 stream
					m3U8InfoExtractor.getDurationAndResolutionFrom(
						m3u8Url = videoUrlInfo.fileUrl,
						callback = object : InfoCallback {
							override fun onResolutionsAndDuration(resolutions: List<String>, durationMs: Long) {
								ThreadsUtility.executeInBackground(codeBlock = {
									ThreadsUtility.executeOnMain {
										closeAnyAnimation(linkItemInfo)

										// Format info text based on number of resolutions
										val infoText = if (resolutions.size > 1) {
											val stringResId = R.string.title_video_available_resolutions
											val durationText = if (durationMs > 0) "  ‣ ${formatVideoDuration(durationMs)}" else ""
											motherActivity.getString(stringResId, "${resolutions.size}$durationText")
										} else {
											val stringResId = R.string.title_video_type_m3u8_resolution
											val durationText = if (durationMs > 0) "  ‣ ${formatVideoDuration(durationMs)}" else ""
											motherActivity.getString(stringResId, "${resolutions[0]}$durationText")
										}

										linkItemInfo.text = infoText
										textLinkItemInfo = infoText
										videoUrlInfo.infoCached = infoText
										videoUrlInfo.fileResolution = resolutions[0]
										videoUrlInfo.fileDuration = durationMs
										videoUrlInfo.totalResolutions = resolutions.size
										videoUrlInfo.isM3U8 = true

										logger.d("HLS video info updated: $infoText")
									}
								})
							}

							override fun onError(errorMessage: String) {
								logger.e("Failed to fetch HLS video info: $errorMessage")
								linkItemInfo.text = getText(R.string.title_failed_extract_video_info)
								closeAnyAnimation(linkItemInfo)
							}
						})
				})
			}
		}

		/**
		 * Displays metadata for a regular (non-HLS) video URL.
		 *
		 * - Fetches video duration and resolution asynchronously.
		 * - Updates the UI with resolution and duration once available.
		 * - Caches the result in [VideoUrlInfo.infoCached] for future reference.
		 * - Provides fallback if resolution cannot be determined.
		 *
		 * @param videoUrlInfo Information about the video URL including cached info.
		 */
		private fun showNormalVideoLinkInfo(videoUrlInfo: VideoUrlInfo) {
			safeMotherActivity?.let { safeMotherActivity ->
				executeInBackground {
					// Show temporary "fetching" info on main thread
					executeOnMainThread {
						linkItemInfo.text = getText(R.string.title_fetching_file_info)
						animateFadInOutAnim(linkItemInfo)
					}

					// If info already cached, use it
					if (videoUrlInfo.infoCached.isNotEmpty()) {
						executeOnMainThread {
							linkItemInfo.text = videoUrlInfo.infoCached
							closeAnyAnimation(linkItemInfo)
						}
						return@executeInBackground
					}

					try {
						val durationMs = getVideoDurationFromUrl(videoUrlInfo.fileUrl)
						val resolution = getVideoResolutionFromUrl(videoUrlInfo.fileUrl)

						resolution?.let { (_, height) ->
							executeOnMainThread {
								closeAnyAnimation(linkItemInfo)
								videoUrlInfo.totalResolutions = 1
								videoUrlInfo.fileResolution = "${height}p"
								videoUrlInfo.fileDuration = durationMs
								videoUrlInfo.isM3U8 = false

								val infoText = if (durationMs > 0L) {
									safeMotherActivity.getString(
										R.string.title_video_type_mp4_resolution_duration,
										videoUrlInfo.fileResolution,
										formatVideoDuration(durationMs)
									)
								} else {
									safeMotherActivity.getString(
										R.string.title_video_type_mp4_resolution,
										videoUrlInfo.fileResolution
									)
								}

								linkItemInfo.text = infoText
								videoUrlInfo.infoCached = infoText
							}
						} ?: run {
							// Fallback if resolution unavailable
							executeOnMainThread {
								closeAnyAnimation(linkItemInfo)
								videoUrlInfo.totalResolutions = 1
								videoUrlInfo.fileResolution = getText(R.string.title_unknown)
								videoUrlInfo.isM3U8 = false
								val infoText = safeMotherActivity.getString(
									R.string.title_video_type_mp4_resolution,
									videoUrlInfo.fileResolution
								)
								linkItemInfo.text = infoText
								videoUrlInfo.infoCached = infoText
							}
						}

						textLinkItemInfo = linkItemInfo.text.toString()
						logger.d("Normal video info updated: $textLinkItemInfo")

					} catch (error: Exception) {
						logger.e("Error while parsing resolution from video link:", error)
						executeOnMainThread {
							closeAnyAnimation(linkItemInfo)
							val fallbackText = safeMotherActivity.getString(
								R.string.title_video_type_mp4,
								safeMotherActivity.getText(R.string.title_click_to_get_info)
							)
							linkItemInfo.text = fallbackText
							textLinkItemInfo = linkItemInfo.text.toString()
							layoutView.visibility = GONE
						}
					}
				}
			}
		}

		/**
		 * Sets up a click listener for a video item to handle download actions.
		 *
		 * - If the video info is not yet cached, notifies the user to wait.
		 * - For M3U8 (multi-resolution) videos, opens appropriate resolution selection dialogs.
		 * - For regular videos, prompts for download using a single-resolution dialog.
		 *
		 * @param videoUrlInfo The video URL information including resolutions, duration, and cookies.
		 */
		private fun setupOnClickItemListener(videoUrlInfo: VideoUrlInfo) {
			itemClickableContainer.setOnClickListener {
				if (videoUrlInfo.infoCached.isEmpty()) {
					logger.d("Video info not cached yet, prompting user to wait")
					safeMotherActivity?.doSomeVibration(50)
					showToast(
						activityInf = safeMotherActivity,
						msgId = R.string.title_video_info_unavailable
					)
					return@setOnClickListener
				}

				val videoTitle = webviewEngine.currentWebView?.title
				val currentWebUrl = webviewEngine.currentWebView?.url
				val videoCookie = webviewEngine.getCurrentWebViewCookies()

				if (isM3U8Url(videoUrlInfo.fileUrl)) {
					logger.d("Detected M3U8 video URL: ${videoUrlInfo.fileUrl}")

					if (videoUrlInfo.totalResolutions > 1) {
						// Multi-resolution: show shared intercept dialog
						executeOnMainThread {
							SharedVideoURLIntercept(
								baseActivity = safeMotherActivity,
								userGivenVideoInfo = VideoInfo(
									videoTitle = videoTitle,
									videoUrlReferer = currentWebUrl,
									videoThumbnailUrl = currentWebpageVideoThumb.ifEmpty { null },
									videoThumbnailByReferer = true,
									videoCookie = videoCookie,
									videoDuration = videoUrlInfo.fileDuration
								)
							).interceptIntentURI(videoUrlInfo.fileUrl, false)
							extractedLinksDialog.close()
						}
					} else {
						// Single-resolution M3U8: show single resolution prompt
						safeMotherActivity?.let { safeMotherActivity ->
							logger.d("Showing single-resolution M3U8 download prompt")
							extractedLinksDialog.close()
							SingleResolutionPrompter(
								baseActivity = safeMotherActivity,
								singleResolutionName = videoUrlInfo.fileResolution,
								extractedVideoLink = videoUrlInfo.fileUrl,
								thumbnailUrlProvided = currentWebpageVideoThumb.ifEmpty { null },
								currentWebUrl = currentWebUrl,
								videoCookie = videoCookie,
								videoTitle = videoTitle,
								videoUrlReferer = currentWebUrl,
								isSocialMediaUrl = false,
								isDownloadFromBrowser = true,
								videoFileDuration = videoUrlInfo.fileDuration
							).show()
						}
					}
				} else {
					// Regular video URL
					try {
						logger.d("Detected regular video URL: ${videoUrlInfo.fileUrl}")
						safeMotherActivity?.let { safeMotherActivity ->
							extractedLinksDialog.close()
							RegularDownloadPrompter(
								motherActivity = safeMotherActivity,
								singleResolutionName = videoUrlInfo.fileResolution,
								extractedVideoLink = videoUrlInfo.fileUrl,
								thumbnailUrlProvided = currentWebpageVideoThumb.ifEmpty { null },
								currentWebUrl = currentWebUrl,
								videoCookie = videoCookie,
								videoTitle = videoTitle,
								videoUrlReferer = currentWebUrl,
								isFromSocialMedia = false,
								videoFileDuration = videoUrlInfo.fileDuration
							).show()
						}
					} catch (error: Exception) {
						extractedLinksDialog.close()
						logger.e("Error showing regular download prompt", error)
					}
				}
			}
		}

		/**
		 * Sets up a long-click listener for the video item to copy its URL to clipboard.
		 * Can be extended in the future for additional actions on long-press.
		 *
		 * @param extractedVideoLink The URL of the video to copy
		 */
		private fun setupLongClickItemListener(extractedVideoLink: String) {
			itemClickableContainer.setOnLongClickListener {
				logger.d("Long-click detected, copying URL: $extractedVideoLink")
				copyTextToClipboard(safeMotherActivity, extractedVideoLink)
				showToast(
					activityInf = safeMotherActivity,
					msgId = R.string.title_copied_url_to_clipboard
				)
				true
			}
		}
	}
}