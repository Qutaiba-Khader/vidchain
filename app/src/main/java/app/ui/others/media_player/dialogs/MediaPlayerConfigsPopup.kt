package app.ui.others.media_player.dialogs

import android.view.View
import androidx.media3.common.util.UnstableApi
import app.ui.others.media_player.MediaPlayerActivity
import com.aio.R
import com.aio.R.layout
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.getCurrentOrientation
import lib.ui.builders.PopupBuilder
import java.lang.ref.WeakReference

/**
 * Popup dialog for configuring media playback settings in [MediaPlayerActivity].
 *
 * This class creates and manages a popup containing various player configuration options
 * such as playback speed, repeat mode, subtitle tracks, and background playback.
 *
 * It uses a [WeakReference] to prevent memory leaks and a [PopupBuilder]
 * to construct and display the dialog dynamically.
 *
 * @constructor Accepts a nullable [MediaPlayerActivity] reference.
 * @property mediaPlayerActivity Optional instance of the parent [MediaPlayerActivity].
 */
@UnstableApi
class MediaPlayerConfigsPopup(private val mediaPlayerActivity: MediaPlayerActivity?) {

	/** Logger instance scoped to this class for consistent debug and error output. */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the associated [MediaPlayerActivity].
	 * Prevents memory leaks if the activity is destroyed.
	 */
	private val safePlayerActivityRef = WeakReference(mediaPlayerActivity).get()

	/** Builder responsible for constructing and displaying the popup window. */
	private lateinit var popupBuilder: PopupBuilder

	/** Initializes the popup and binds UI elements with event handlers. */
	init {
		logger.d("Initializing MediaConfigsPopup...")
		setupPopupBuilder()
		setupClickEvents()
		logger.d("MediaConfigsPopup initialized successfully.")
	}

	/**
	 * Displays the popup window anchored to the player's options button.
	 *
	 * Logs the show operation for debugging and confirms successful rendering.
	 */
	fun show() {
		logger.d("Attempting to display MediaConfigsPopup...")
		popupBuilder.show()
		logger.d("MediaConfigsPopup displayed successfully.")
	}

	/**
	 * Closes the popup if currently visible.
	 *
	 * Ensures no lingering popups remain on screen during playback transitions.
	 */
	fun close() {
		logger.d("Closing MediaConfigsPopup...")
		popupBuilder.close()
		logger.d("MediaConfigsPopup closed.")
	}

	/**
	 * Initializes [PopupBuilder] with layout and anchor view from [MediaPlayerActivity].
	 *
	 * Logs setup progress and error conditions (e.g., null activity reference).
	 */
	private fun setupPopupBuilder() {
		safePlayerActivityRef?.let { safeActivityRef ->
			logger.d("Setting up PopupBuilder for MediaConfigsPopup...")
			popupBuilder = PopupBuilder(
				activityInf = safeActivityRef,
				popupLayoutId = layout.activity_player_5_options_2,
				popupAnchorView = getPopupAnchorView(safeActivityRef)
			)
			logger.d("PopupBuilder setup completed successfully.")
		} ?: logger.e("Failed to setup PopupBuilder: Activity reference is null.")
	}

	/**
	 * Binds click listeners to popup options (buttons).
	 *
	 * Each button is associated with a specific media configuration logic.
	 * Logs every binding and warns if the popup view is unavailable.
	 */
	private fun setupClickEvents() {
		safePlayerActivityRef?.let { playerActivity ->
			val popupView = popupBuilder.getPopupView()
			logger.d("Binding click events for MediaConfigsPopup options...")

			with(popupView) {
				mapOf(
					R.id.btn_playback_speed to { openPlaybackSpeedSettings() },
					R.id.btn_video_screen_mode to { openVideoScaleTypeSettings() },
					R.id.btn_playback_repeat_mode to { openPlaybackModeSettings() },
					R.id.btn_player_rotation to { openOrientationSettings() },
					R.id.btn_subtitle_tracks to { openSubtitleTracksSettings() },
					R.id.btn_audio_tracks to { openAudioTracksSettings() },
					R.id.btn_play_in_background to { toggleBackgroundPlayback() }
				).forEach { (id, action) -> setClickListener(id) { action() } }
			}

			logger.d("Click events successfully bound for MediaConfigsPopup.")
		} ?: logger.e("Unable to bind click events: MediaPlayerActivity reference is null.")
	}

	/**
	 * Determines the appropriate anchor view for the popup based on screen orientation.
	 *
	 * In landscape mode, uses the landscape configuration button; otherwise uses the
	 * portrait configuration button.
	 *
	 * @param playerActivity The media player activity instance
	 * @return The appropriate View to anchor the popup to
	 */
	private fun getPopupAnchorView(playerActivity: MediaPlayerActivity): View {
		val currentOrientation = getCurrentOrientation(playerActivity)
		return if (currentOrientation.contains("landscape", true))
			playerActivity.configButtonLand else playerActivity.configButton
	}

	/**
	 * Safely assigns a click listener to a child view.
	 *
	 * Includes click logging for debugging and avoids null pointer exceptions.
	 *
	 * @param id Resource ID of the target view.
	 * @param action Lambda to execute when clicked.
	 */
	private fun View.setClickListener(id: Int, action: () -> Unit) {
		findViewById<View>(id)?.setOnClickListener {
			logger.d("Button clicked → View ID: $id")
			action()
		} ?: logger.d("Attempted to assign click listener to missing view ID: $id")
	}

	/**
	 * Opens playback speed configuration dialog for speed multiplier selection.
	 *
	 * Displays a popup allowing users to adjust playback speed (0.5x, 1x, 1.5x, 2x, etc.).
	 * The current popup is closed before showing the playback speed dialog.
	 */
	private fun openPlaybackSpeedSettings() {
		safePlayerActivityRef?.let { playerActivity ->
			logger.d("Opening Playback Speed settings...")
			close()
			val playbackSpeedPopup = PlaybackSpeedPopup(
				mediaPlayerActivity = mediaPlayerActivity,
				anchorView = getPopupAnchorView(playerActivity)
			)
			playbackSpeedPopup.show()
		}
	}

	/**
	 * Opens video scale type configuration for aspect ratio and display mode selection.
	 *
	 * Allows users to choose how video content is scaled within the player view
	 * (fit, fill, stretch, zoom, etc.).
	 */
	private fun openVideoScaleTypeSettings() {
		logger.d("Opening Video Scale Type settings...")
		close()
		// TODO: Implement video scale type dialog logic
	}

	/**
	 * Opens playback mode configuration for repeat and shuffle settings.
	 *
	 * Configures repeat modes (none, one, all) and shuffle functionality for playlists.
	 */
	private fun openPlaybackModeSettings() {
		logger.d("Opening Playback Mode settings...")
		close()
		// TODO: Implement playback mode dialog logic
	}

	/**
	 * Opens screen orientation settings for rotation lock and auto-rotation configuration.
	 *
	 * Allows users to control whether the screen rotates with device orientation
	 * or remains locked in current orientation.
	 */
	private fun openOrientationSettings() {
		logger.d("Opening Orientation settings...")
		close()
		// TODO: Implement orientation dialog logic
	}

	/**
	 * Opens subtitle track selection and styling configuration.
	 *
	 * Provides interface for selecting available subtitle tracks and customizing
	 * subtitle appearance (font, size, color, background).
	 */
	private fun openSubtitleTracksSettings() {
		logger.d("Opening Subtitle Tracks settings...")
		close()
		// TODO: Implement subtitle tracks dialog logic
	}

	/**
	 * Opens audio track selection for language and audio stream configuration.
	 *
	 * Allows users to switch between available audio tracks/languages and configure
	 * audio output settings.
	 */
	private fun openAudioTracksSettings() {
		logger.d("Opening Audio Tracks settings...")
		close()
		// TODO: Implement audio tracks dialog logic
	}

	/**
	 * Toggles background playback capability for audio continuation when minimized.
	 *
	 * Enables/disables the ability for media to continue playing when the app is
	 * in the background or the screen is turned off.
	 */
	private fun toggleBackgroundPlayback() {
		logger.d("Toggling Background Playback setting...")
		close()
		// TODO: Implement background playback toggle logic
	}
}