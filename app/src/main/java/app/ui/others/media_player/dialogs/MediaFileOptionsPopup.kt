package app.ui.others.media_player.dialogs

import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.widget.CheckBox
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import app.ui.others.media_player.MediaPlayerActivity
import app.ui.others.media_player.dialogs.Mp4ToAudioConverterDialog.showMp4ToAudioConverterDialog
import com.aio.R
import com.aio.R.layout
import com.aio.R.string
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.setTextColorKT
import lib.ui.builders.PopupBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MediaOptionsPopup is responsible for managing and displaying a popup window
 * that provides additional media-related options during playback (e.g., settings, info, etc.).
 *
 * This class is designed to safely interact with the parent activity through a [WeakReference],
 * preventing memory leaks if the activity is destroyed. It encapsulates popup creation,
 * event setup, and visibility control.
 *
 * Usage:
 * ```
 * val mediaOptionsPopup = MediaOptionsPopup(mediaPlayerActivity)
 * mediaOptionsPopup.show()
 * ```
 *
 * Responsibilities:
 * - Initialize and configure the popup layout via [PopupBuilder].
 * - Safely manage activity context using [WeakReference].
 * - Provide methods to show and close the popup.
 * - Log all major lifecycle and UI interactions for debugging.
 */
@UnstableApi
class MediaFileOptionsPopup(private val mediaPlayerActivity: MediaPlayerActivity?) {

	/** Logger instance scoped to this class for consistent debug and error output. */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the associated [MediaPlayerActivity].
	 * Ensures the activity can be garbage-collected if destroyed,
	 * avoiding potential memory leaks.
	 */
	private val safePlayerActivityRef = WeakReference(mediaPlayerActivity).get()

	/** Builder responsible for constructing and displaying the popup window. */
	private lateinit var popupBuilder: PopupBuilder

	/** Initializes the popup by setting up its layout, click events, and logging. */
	init {
		logger.d("Initializing MediaOptionsPopup...")
		setupPopupBuilder()
		setupClickEvents()
		logger.d("MediaOptionsPopup initialized successfully.")
	}

	/**
	 * Displays the popup window anchored to the player’s options button.
	 *
	 * Before showing, it refreshes the "private session" state
	 * to ensure the displayed options reflect the current playback context.
	 */
	fun show() {
		logger.d("Showing MediaOptionsPopup...")
		refreshPrivateSession()
		popupBuilder.show()
	}

	/**
	 * Closes the popup window if it is currently visible.
	 */
	fun close() {
		logger.d("Closing MediaOptionsPopup...")
		popupBuilder.close()
	}

	/**
	 * Initializes the [PopupBuilder] with the appropriate layout and anchor view.
	 *
	 * Ensures that the popup is tied to the player’s options button within
	 * the provided activity. If the weak reference to the activity is invalid,
	 * logs an error message to aid debugging.
	 */
	private fun setupPopupBuilder() {
		safePlayerActivityRef?.let { safeActivityRef ->
			logger.d("Setting up PopupBuilder for MediaOptionsPopup...")
			popupBuilder = PopupBuilder(
				activityInf = safeActivityRef,
				popupLayoutId = layout.activity_player_5_options_1,
				popupAnchorView = safeActivityRef.optionsButton
			)
		} ?: logger.e("Failed to setup PopupBuilder: Activity reference is null")
	}

	/**
	 * Registers the click listeners for UI elements within the popup layout.
	 * This method connects UI buttons to their respective logic handlers.
	 * Implementation details depend on popup design and button functionality.
	 */
	private fun setupClickEvents() {
		safePlayerActivityRef?.let { playerActivity ->
			with(popupBuilder.getPopupView()) {
				logger.d("Binding click events for MediaOptionsPopup buttons...")
				mapOf(
					R.id.btn_delete_file to { close(); deleteFile() },
					R.id.btn_convert_to_audio to { close(); convertAudio() },
					R.id.btn_open_in_another to { close(); openMediaFile() },
					R.id.btn_media_info to { close(); openMediaFileInfo() },
					R.id.btn_private_videos to { close(); togglePrivateSession() },
					R.id.btn_snapshot to { close(); captureActivityViewSnapshot() },
					R.id.btn_share_media to { close(); playerActivity.shareCurrentMediaFile() },
					R.id.btn_discover_video to { close(); discoverMore() }
				).forEach { (id, action) ->
					setClickListener(id) { action() }
				}
			}
		} ?: logger.e("PopupBuilder view not found for click event setup")
	}

	/**
	 * Safely sets a click listener on a child view within the current [View].
	 *
	 * This helper prevents null pointer exceptions by checking if the view with the given [id]
	 * exists before assigning the listener. It also logs each click event for debugging.
	 *
	 * @param id The resource ID of the view to attach the click listener to.
	 * @param action The lambda function to execute when the view is clicked.
	 */
	private fun View.setClickListener(id: Int, action: () -> Unit) {
		findViewById<View>(id)?.setOnClickListener {
			logger.d("Button with ID $id clicked in MediaOptionsPopup.")
			action()
		}
	}

	/**
	 * Handles the deletion process for the currently playing media file.
	 *
	 * - If the current media is a streaming source, the deletion option is disabled
	 *   and a message dialog informs the user.
	 * - For local media files, a confirmation dialog is displayed.
	 *   Upon confirmation, the file is deleted and the popup is closed.
	 *
	 * Includes robust logging for each step and safeguards against null
	 * activity references.
	 */
	private fun deleteFile() {
		safePlayerActivityRef?.let { safeActivityRef ->
			logger.d("Attempting to delete media file...")
			if (safeActivityRef.isStreamingVideoPlaying()) {
				logger.d("Delete unavailable for streaming media.")
				showMessageDialog(
					baseActivityInf = safeActivityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(R.color.color_error)
					},
					positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(R.drawable.ic_okay_done)
						positiveButton.setText(string.title_okay)
					},
					messageTextViewCustomize = {
						val stringId = string.text_delete_stream_media_unavailable
						it.setText(stringId)
					}
				)
				return
			}

			val dialogBuilder = getMessageDialog(
				baseActivityInf = safeActivityRef,
				titleText = getText(string.title_are_you_sure_about_this),
				isTitleVisible = true,
				isNegativeButtonVisible = false,
				positiveButtonText = getText(string.title_delete_file),
				messageTextViewCustomize = { it.setText(string.text_are_you_sure_about_delete) },
				negativeButtonTextCustomize = { it.setLeftSideDrawable(R.drawable.ic_button_cancel) },
				positiveButtonTextCustomize = { it.setLeftSideDrawable(R.drawable.ic_button_delete) }
			)

			dialogBuilder?.setOnClickForPositiveButton {
				dialogBuilder.close()
				this@MediaFileOptionsPopup.close()
				logger.d("Confirmed deletion of current media file.")
				safeActivityRef.deleteCurrentMediaFile()
			}

			dialogBuilder?.show()
		} ?: logger.e("Activity reference lost during file deletion")
	}

	/**
	 * Toggles the private session mode for the media player.
	 *
	 * - If private mode is currently active, it disables it and resets related flags.
	 * - Otherwise, it enables private session mode by calling
	 *   [MediaPlayerActivity.enablePrivateSession].
	 *
	 * After toggling, it calls [refreshPrivateSession] to update UI indicators
	 * (like checkboxes or labels) to reflect the current state.
	 *
	 * Includes debug logs for each state transition and safely handles
	 * invalid activity references.
	 */
	private fun togglePrivateSession() {
		safePlayerActivityRef?.let { playerActivity ->
			logger.d("Toggling private session mode...")
			if (playerActivity.isPrivateSessionAllowed) {
				playerActivity.isPrivateSessionAllowed = false
				playerActivity.hasMadeDecisionOverPrivateAccess = false
				logger.d("Private session disabled.")
			} else {
				playerActivity.enablePrivateSession()
				logger.d("Private session enabled.")
			}
			refreshPrivateSession()
		} ?: logger.e("Activity reference lost while toggling private session")
	}

	/**
	 * Captures a single bitmap snapshot of the currently rendered video frame from ExoPlayer.
	 *
	 * Implementation details:
	 * - Uses [VideoFrameMetadataListener] to intercept the next frame rendered to the display.
	 * - Grabs the frame pixels using [PixelCopy.request] from the [SurfaceView] used by ExoPlayer.
	 * - Saves the resulting bitmap via [saveCurrentVideoFrameBitmap].
	 *
	 * After successfully capturing one frame, the listener is cleared using
	 * [ExoPlayer.clearVideoFrameMetadataListener] to prevent continuous capturing.
	 *
	 * Logs every major step for debugging and gracefully handles all exceptions
	 * or null activity references.
	 */
	private fun captureActivityViewSnapshot() {
		safePlayerActivityRef?.let { playerActivityRef ->
			try {
				logger.d("Initiating video frame snapshot capture...")
				var isVideoFrameCaptured = false
				val playerView = playerActivityRef.playerView
				val exoPlayer = playerActivityRef.player
				val isPlayerRunning = exoPlayer.isPlaying
				playerActivityRef.nightModeOverlay.visibility = VISIBLE

				exoPlayer.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
					override fun onVideoFrameAboutToBeRendered(
						presentationTimeUs: Long,
						releaseTimeNs: Long,
						format: Format,
						mediaFormat: MediaFormat?
					) {
						if (isVideoFrameCaptured) return
						isVideoFrameCaptured = true
						val srcView = playerView.videoSurfaceView as? SurfaceView
						val surfaceView = srcView ?: return
						val bitmap = createBitmap(surfaceView.width, surfaceView.height)

						PixelCopy.request(
							surfaceView,
							bitmap,
							{ result ->
								if (result == PixelCopy.SUCCESS) {
									logger.d("Snapshot successfully copied from surface.")
									saveCurrentVideoFrameBitmap(
										bitmap = bitmap,
										activity = playerActivityRef,
										shouldResumePlayback = isPlayerRunning
									)
								} else logger.e("PixelCopy failed with result code $result")
								exoPlayer.clearVideoFrameMetadataListener(this)
							},
							Handler(Looper.getMainLooper())
						)
					}
				})
			} catch (error: Exception) {
				logger.e("Exception during snapshot capture", error)
			} finally {
				playerActivityRef.nightModeOverlay.visibility = GONE
			}
		} ?: logger.e("Activity reference lost while capturing snapshot")
	}

	/**
	 * Saves the provided [bitmap] image to external storage asynchronously.
	 *
	 * The image is stored inside the default AIO image directory, under a filename
	 * generated with a timestamp for uniqueness.
	 *
	 * Behavior:
	 * - Executes the file I/O operation on a background thread using [ThreadsUtility].
	 * - Notifies the user via toast message upon successful completion.
	 * - Optionally resumes media playback if [shouldResumePlayback] is `true`.
	 * - Logs success or failure for debugging.
	 *
	 * @param bitmap The [Bitmap] to be saved.
	 * @param activity The [MediaPlayerActivity] instance, used for context and UI updates.
	 * @param shouldResumePlayback If true, resumes playback after saving the image.
	 */
	private fun saveCurrentVideoFrameBitmap(bitmap: Bitmap, activity: MediaPlayerActivity,
		shouldResumePlayback: Boolean = false) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				val parentDir = getText(string.text_default_aio_download_folder_path)
				val childDir = getText(string.title_aio_images)
				val folder = File(parentDir, childDir)
				if (!folder.exists()) folder.mkdirs()

				val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
				val file = File(folder, "Video_Snapshot_$timestamp.png")

				FileOutputStream(file).use { outputStream ->
					bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
					outputStream.flush()
				}

				ThreadsUtility.executeOnMain {
					val toastMsgId = string.title_snapshot_saved_open_gallery
					showToast(activity, msgId = toastMsgId)
					if (shouldResumePlayback) activity.resumePlayback()
					logger.d("Activity snapshot successfully saved to ${file.absolutePath}")
				}
			} catch (error: Exception) {
				logger.e("Error saving snapshot bitmap", error)
			}
		})
	}

	/**
	 * Updates the state of the private session checkbox in the popup UI.
	 *
	 * This ensures that the visual checkbox accurately reflects
	 * the current private session setting of the associated [MediaPlayerActivity].
	 *
	 * Logs the updated checkbox state for debugging.
	 */
	private fun refreshPrivateSession() {
		safePlayerActivityRef?.let { playerActivity ->
			val popupView = popupBuilder.getPopupView()
			val checkBox = popupView.findViewById<CheckBox>(R.id.checkbox_private_videos)
			checkBox.isChecked = playerActivity.isPrivateSessionAllowed
			logger.d("Private session checkbox refreshed: ${checkBox.isChecked}")
		}
	}

	/**
	 * Opens and displays detailed information about the currently playing media file.
	 *
	 * Delegates the actual UI handling to [MediaPlayerActivity.openCurrentMediaFileInfo],
	 * which typically displays metadata such as title, duration, resolution, and format.
	 *
	 * Includes logging for traceability.
	 */
	private fun openMediaFileInfo() {
		logger.d("Opening media file info...")
		safePlayerActivityRef?.openCurrentMediaFileInfo()
	}

	/**
	 * Attempts to open the referrer URL for the current video in a web browser.
	 *
	 * - If the video is streaming, this feature is disabled and a message dialog is shown.
	 * - For local media, retrieves the referrer URL from the current download metadata
	 *   and launches an `ACTION_VIEW` intent to open it in a compatible browser.
	 *
	 * Logs both success and failure cases, and provides user feedback if
	 * no suitable app can handle the intent.
	 */
	private fun discoverMore() {
		safePlayerActivityRef?.let { playerActivity ->
			logger.d("Attempting to discover more content related to this video...")
			if (playerActivity.isStreamingVideoPlaying()) {
				logger.d("Discovery unavailable while streaming.")
				showMessageDialog(
					baseActivityInf = playerActivity,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(R.color.color_error)
					}, messageTextViewCustomize = { msgTextView ->
						msgTextView.setText(string.text_no_discovery_during_streaming)
					}, positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(R.drawable.ic_okay_done)
						positiveButton.setText(string.title_okay)
					}
				)
				return
			}

			val candidate = playerActivity.getCurrentPlayingDownload()
			candidate?.siteReferrer?.let { referrer ->
				try {
					val intent = Intent(Intent.ACTION_VIEW, referrer.toUri())
					playerActivity.startActivity(intent)
					logger.d("Discovery intent started for referrer: $referrer")
				} catch (error: Exception) {
					logger.e("No app can handle discovery intent", error)
					showToast(playerActivity, msgId = string.title_no_app_can_handle_this_request)
				}
			} ?: logger.d("No referrer found for discovery.")
		} ?: logger.e("Activity reference lost during discoverMore()")
	}

	/**
	 * Launches the MP4-to-audio conversion dialog for the currently playing media.
	 *
	 * This feature allows users to extract the audio track from a video file.
	 * The conversion is handled by [showMp4ToAudioConverterDialog].
	 *
	 * Logs invocation for debugging and gracefully handles missing activity references.
	 */
	private fun convertAudio() {
		logger.d("Opening MP4 to Audio Converter dialog...")
		showMp4ToAudioConverterDialog(
			safePlayerActivityRef,
			safePlayerActivityRef?.getCurrentPlayingDownload()
		)
	}

	/**
	 * Opens the currently playing media file using an external application.
	 *
	 * Delegates the action to [MediaPlayerActivity.openCurrentMediaFile], which
	 * creates and launches an appropriate intent to view or play the file externally.
	 *
	 * Logs the operation for tracking user actions.
	 */
	private fun openMediaFile() {
		logger.d("Opening current media file externally...")
		safePlayerActivityRef?.openCurrentMediaFile()
	}
}