package app.ui.others.media_player.dialogs

import android.view.View
import android.widget.CheckBox
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.ui.others.media_player.MediaPlayerActivity
import com.aio.R
import com.aio.R.layout
import lib.process.LogHelperUtils
import lib.ui.builders.PopupBuilder
import java.lang.ref.WeakReference

/**
 * Popup dialog for configuring playback speed in [MediaPlayerActivity].
 *
 * This class creates and manages a popup that allows users to select different
 * playback speed multipliers (0.25x to 2.0x) for media playback.
 *
 * It provides visual feedback through checkboxes and immediately applies
 * speed changes to the ExoPlayer instance.
 *
 * @constructor Creates a playback speed popup dialog
 * @property mediaPlayerActivity The parent media player activity instance
 * @property anchorView The view to anchor the popup to for positioning
 */
@UnstableApi
class PlaybackSpeedPopup(
	private val mediaPlayerActivity: MediaPlayerActivity?,
	private val anchorView: View
) {
	/** Logger instance for debugging and tracking playback speed changes. */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Weak reference to the associated [MediaPlayerActivity].
	 * Prevents memory leaks if the activity is destroyed.
	 */
	private val safePlayerActivityRef = WeakReference(mediaPlayerActivity).get()

	/** Builder responsible for constructing and displaying the popup window. */
	private lateinit var popupBuilder: PopupBuilder

	/** ExoPlayer instance for controlling playback speed parameters. */
	private lateinit var exoPlayer: ExoPlayer

	// Checkbox references for different playback speed options
	private lateinit var checkbox075: CheckBox
	private lateinit var checkbox050: CheckBox
	private lateinit var checkbox025: CheckBox
	private lateinit var checkbox01: CheckBox
	private lateinit var checkbox125: CheckBox
	private lateinit var checkbox150: CheckBox
	private lateinit var checkbox175: CheckBox
	private lateinit var checkbox2: CheckBox

	/**
	 * Initializes the popup by setting up the builder and click events.
	 *
	 * Logs the initialization process and any potential errors.
	 */
	init {
		logger.d("Initializing PlaybackSpeedPopup...")
		setupPopupBuilder()
		setupClickEvents()
		logger.d("PlaybackSpeedPopup initialized successfully.")
	}

	/**
	 * Displays the playback speed popup window.
	 *
	 * Refreshes checkbox states to reflect current playback speed
	 * before showing the popup to ensure accurate visual representation.
	 */
	fun show() {
		logger.d("Showing PlaybackSpeedPopup...")
		refreshCheckboxes()
		popupBuilder.show()
		logger.d("PlaybackSpeedPopup displayed. Current speed: ${exoPlayer.playbackParameters.speed}x")
	}

	/**
	 * Closes the playback speed popup if currently visible.
	 *
	 * Ensures clean dismissal of the popup interface.
	 */
	fun close() {
		logger.d("Closing PlaybackSpeedPopup...")
		popupBuilder.close()
		logger.d("PlaybackSpeedPopup closed.")
	}

	/**
	 * Initializes the [PopupBuilder] and configures the popup layout.
	 *
	 * Sets up the popup with the appropriate layout and retrieves references
	 * to all speed option checkboxes. Logs any setup failures.
	 */
	private fun setupPopupBuilder() {
		safePlayerActivityRef?.let { act ->
			logger.d("Setting up PopupBuilder for PlaybackSpeedPopup...")

			// Initialize ExoPlayer instance from activity
			exoPlayer = act.player
			logger.d("ExoPlayer instance obtained from activity")

			// Create popup builder with speed selection layout
			popupBuilder = PopupBuilder(
				activityInf = act,
				popupLayoutId = layout.activity_player_6_speed_popup_1,
				popupAnchorView = anchorView
			)
			logger.d("PopupBuilder created with layout: activity_player_6_speed_1")

			// Initialize all checkbox references
			with(popupBuilder.getPopupView()) {
				checkbox075 = findViewById(R.id.checkbox_playback_speed_0_75x)
				checkbox050 = findViewById(R.id.checkbox_playback_speed_0_5x)
				checkbox025 = findViewById(R.id.checkbox_playback_speed_0_25x)
				checkbox01 = findViewById(R.id.checkbox_playback_speed_1x)
				checkbox125 = findViewById(R.id.checkbox_playback_speed_1_25x)
				checkbox150 = findViewById(R.id.checkbox_playback_speed_1_5x)
				checkbox175 = findViewById(R.id.checkbox_playback_speed_1_75x)
				checkbox2 = findViewById(R.id.check_playback_speed_2x)
			}

			logger.d("All checkbox references initialized successfully")
			logger.d("Checkboxes mapped for speeds: 0.25x, 0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 1.75x, 2.0x")

		} ?: logger.e("Failed to setup PopupBuilder: Activity reference is null.")
	}

	/**
	 * Binds click listeners to all playback speed option buttons.
	 *
	 * Each speed option button is mapped to its corresponding speed value.
	 * When clicked, the speed change is applied and the popup is closed.
	 * Logs binding process and any failures.
	 */
	private fun setupClickEvents() {
		safePlayerActivityRef?.let {
			logger.d("Setting up click events for playback speed options...")

			with(popupBuilder.getPopupView()) {
				// Map of button IDs to their corresponding playback speeds
				val speedOptions = mapOf(
					R.id.bnt_playback_speed_0_75x to 0.25f,
					R.id.bnt_playback_speed_0_5x to 0.5f,
					R.id.bnt_playback_speed_0_25x to 0.75f,
					R.id.bnt_playback_speed_1x to 1.0f,
					R.id.bnt_playback_speed_1_25x to 1.25f,
					R.id.bnt_playback_speed_1_5x to 1.5f,
					R.id.bnt_playback_speed_1_75x to 1.75f,
					R.id.bnt_playback_speed_2x to 2.0f
				)

				// Bind each speed option to its click handler
				speedOptions.forEach { (buttonId, speed) ->
					setClickListener(buttonId) {
						logger.d("Playback speed button clicked: ${speed}x")
						changePlaybackSpeed(speed)
						close()
					}
				}
			}

			logger.d("Click events bound to ${mapOf<String, Float>().size} speed options")

		} ?: logger.e("Unable to setup click events: Activity reference is null.")
	}

	/**
	 * Safely assigns a click listener to a view identified by resource ID.
	 *
	 * @param id The resource ID of the view to bind the click listener to
	 * @param action The lambda function to execute when the view is clicked
	 */
	private fun View.setClickListener(id: Int, action: () -> Unit) {
		findViewById<View>(id)?.setOnClickListener {
			logger.d("View with ID $id clicked")
			action()
		} ?: logger.d("Attempted to bind click listener to non-existent view ID: $id")
	}

	/**
	 * Changes the playback speed of the ExoPlayer instance.
	 *
	 * Applies the specified speed multiplier to the player's playback parameters
	 * and updates the checkbox states to reflect the new speed selection.
	 *
	 * @param speed The playback speed multiplier to apply (0.25f to 2.0f)
	 */
	private fun changePlaybackSpeed(speed: Float) {
		logger.d("Changing playback speed to ${speed}x")

		try {
			exoPlayer.playbackParameters = PlaybackParameters(speed)
			// persist in activity for session
			safePlayerActivityRef?.lastPlaybackSpeed = speed

			logger.i("Playback speed successfully changed to ${speed}x")

			// Update UI to reflect new speed selection
			refreshCheckboxes()
			logger.d("Checkboxes refreshed after speed change")

		} catch (error: Exception) {
			logger.e("Failed to change playback speed to ${speed}x", error)
		}
	}

	/**
	 * Refreshes the checkbox states to match the current playback speed.
	 *
	 * Compares the current player speed with each available speed option
	 * and checks the corresponding checkbox. Only one checkbox can be
	 * checked at a time (radio button behavior).
	 */
	private fun refreshCheckboxes() {
		val currentSpeed = exoPlayer.playbackParameters.speed
		logger.d("Refreshing checkboxes for current speed: ${currentSpeed}x")

		// Map checkboxes to their corresponding speed values
		val speedCheckboxMap = listOf(
			checkbox075 to 0.25f,
			checkbox050 to 0.5f,
			checkbox025 to 0.75f,
			checkbox01 to 1.0f,
			checkbox125 to 1.25f,
			checkbox150 to 1.5f,
			checkbox175 to 1.75f,
			checkbox2 to 2.0f
		)

		// Update each checkbox based on current speed
		speedCheckboxMap.forEach { (checkbox, speed) ->
			val shouldBeChecked = (currentSpeed == speed)
			checkbox.isChecked = shouldBeChecked

			if (shouldBeChecked) {
				logger.d("Checkbox for ${speed}x speed is checked")
			}
		}

		logger.d("Checkbox refresh completed. Current selection: ${currentSpeed}x")
	}
}