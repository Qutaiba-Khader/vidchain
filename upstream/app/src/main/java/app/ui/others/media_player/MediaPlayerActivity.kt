package app.ui.others.media_player

import android.app.Activity
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
import android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.content.res.Configuration.ORIENTATION_PORTRAIT
import android.graphics.Bitmap
import android.graphics.BitmapFactory.decodeByteArray
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
import android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod.getInstance
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.View.GONE
import android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_IMMERSIVE
import android.view.View.VISIBLE
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getColor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.documentfile.provider.DocumentFile.fromFile
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaItem.Builder
import androidx.media3.common.MediaItem.SubtitleConfiguration
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Player.Listener
import androidx.media3.common.Player.STATE_BUFFERING
import androidx.media3.common.Player.STATE_READY
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TimeBar
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioTimer
import app.core.AIOApp.Companion.downloadSystem
import app.core.AIOTimer.AIOTimerListener
import app.core.bases.BaseActivity
import app.core.engines.downloader.DownloadDataModel
import app.core.engines.downloader.DownloadDataModel.Companion.DOWNLOAD_MODEL_ID_KEY
import app.core.engines.downloader.DownloadURLHelper.getFileInfoFromSever
import app.core.engines.settings.AIOSettings
import app.ui.others.media_player.dialogs.MediaFileOptionsPopup
import app.ui.others.media_player.dialogs.MediaMetadataHtmlBuilder.buildMediaInfoHtmlString
import app.ui.others.media_player.dialogs.MediaPlayerConfigsPopup
import com.aio.R
import com.aio.R.color
import com.aio.R.drawable
import com.aio.R.id
import com.aio.R.layout
import com.aio.R.string
import com.airbnb.lottie.LottieAnimationView
import com.airbnb.lottie.LottieCompositionFactory.fromRawRes
import com.anggrayudi.storage.file.FileFullPath
import com.anggrayudi.storage.file.getAbsolutePath
import com.google.common.io.Files.getFileExtension
import lib.device.SecureFileUtil.authenticate
import lib.device.ShareUtility
import lib.device.ShareUtility.shareMediaFile
import lib.files.FileSystemUtility.getFileFromUri
import lib.files.FileSystemUtility.isAudio
import lib.files.FileSystemUtility.isVideo
import lib.networks.URLUtility.isValidURL
import lib.process.AsyncJobUtils.executeOnMainThread
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.MsgDialogUtils.showMessageDialog
import lib.ui.RoundedTimeBar
import lib.ui.VerticalProgressBar
import lib.ui.ViewUtility
import lib.ui.ViewUtility.blurBitmap
import lib.ui.ViewUtility.hideView
import lib.ui.ViewUtility.matchHeightToTopCutout
import lib.ui.ViewUtility.rotateBitmap
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.setTextColorKT
import lib.ui.ViewUtility.showView
import lib.ui.ViewUtility.toggleViewVisibility
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * # Media Player Activity
 *
 * A full-featured media player activity that provides comprehensive video and audio playback
 * capabilities with advanced user interactions and system integrations.
 *
 * ## Overview
 *
 * This activity serves as the main media playback interface, supporting both local media files
 * and streaming content with sophisticated gesture controls, playback management, and user
 * interface optimizations for an immersive media experience.
 *
 * ## Key Features
 *
 * ### Playback Support
 * - **Local Media**: Playback of downloaded video and audio files from local storage
 * - **Streaming Media**: Support for streaming video/audio from remote URLs
 * - **Format Support**: Broad codec support through ExoPlayer with FFmpeg extensions
 * - **Subtitle Integration**: External subtitle file support (SRT, VTT formats)
 *
 * ### User Interface
 * - **Adaptive Layouts**: Optimized interfaces for both portrait and landscape orientations
 * - **Immersive Mode**: Fullscreen playback with hidden system UI elements
 * - **Gesture Controls**: Comprehensive touch gestures for seeking, volume, and brightness
 * - **Auto-hide Controls**: Intelligent UI element management for clean viewing experience
 *
 * ### Playback Controls
 * - **Standard Controls**: Play, pause, seek, previous/next track navigation
 * - **Advanced Seeking**: 10-second skip intervals with visual feedback
 * - **Playback Speed**: Adjustable playback speed (0.5x to 2.0x)
 * - **Audio Management**: Volume control with system audio integration
 *
 * ### System Integration
 * - **Screen Management**: Automatic screen wake lock during playback
 * - **Orientation Handling**: Smart orientation changes with layout adaptation
 * - **Cutout Support**: Proper display around device notches and camera cutouts
 * - **Brightness Control**: System-wide brightness adjustment
 *
 * ## Architecture
 *
 * ### Core Components
 * - **ExoPlayer**: Primary media playback engine with extended codec support
 * - **PlayerView**: Video rendering surface with integrated controls
 * - **TrackSelector**: Advanced audio/video/text track management
 * - **GestureDetector**: Comprehensive touch gesture recognition system
 *
 * ### State Management
 * - **Playback State**: Tracks playing, paused, buffering, and completed states
 * - **UI State**: Manages control visibility, orientation, and immersive mode
 * - **Session State**: Maintains playback position and user preferences
 * - **Security State**: Handles private media access authentication
 *
 * ### Lifecycle Management
 * - **Resource Handling**: Proper initialization and cleanup of media resources
 * - **State Preservation**: Maintains playback position across configuration changes
 * - **Memory Management**: Weak references and proper resource disposal
 * - **Error Recovery**: Graceful handling of playback failures and media errors
 *
 * ## Intent Handling
 *
 * The activity accepts several intent extras for different playback scenarios:
 *
 * ### Local Media Playback
 * ```kotlin
 * Intent(context, MediaPlayerActivity::class.java).apply {
 *     putExtra(INTENT_EXTRA_DOWNLOAD_ID, downloadId)
 *     putExtra(INTENT_EXTRA_MEDIA_FILE_PATH, filePath)
 * }
 * ```
 *
 * ### Streaming Media Playback
 * ```kotlin
 * Intent(context, MediaPlayerActivity::class.java).apply {
 *     putExtra(INTENT_EXTRA_STREAM_URL, streamUrl)
 *     putExtra(INTENT_EXTRA_STREAM_TITLE, "Video Title")
 * }
 * ```
 *
 * ## Gesture System
 *
 * ### Single Finger Gestures
 * - **Horizontal Swipe**: Seek forward/backward (10-second intervals)
 * - **Vertical Swipe (Left)**: Adjust screen brightness
 * - **Vertical Swipe (Right)**: Adjust audio volume
 * - **Single Tap**: Toggle control visibility
 * - **Double Tap**: Toggle play/pause state
 * - **Long Press**: Temporary pause with auto-resume
 *
 * ### Multi-touch Gestures
 * - **Pinch Zoom**: Zoom video content (1.0x to 3.0x scale)
 * - **Two-finger Touch**: Priority handling for zoom operations
 *
 * ## Security Features
 *
 * ### Private Media Access
 * - **Authentication**: Biometric/password authentication for protected content
 * - **Session Management**: Temporary access grants during playback session
 * - **Auto-navigation**: Intelligent skipping of private media when access denied
 *
 * ## Error Handling
 *
 * ### Playback Errors
 * - **Media Unplayable**: User-friendly dialogs with file deletion options
 * - **Network Issues**: Streaming failure detection and user guidance
 * - **Format Unsupported**: Clear error messages with format requirements
 *
 * ### System Errors
 * - **Resource Unavailable**: Graceful degradation when system services fail
 * - **Permission Issues**: Guidance for resolving storage/audio permissions
 * - **Memory Pressure**: Automatic resource cleanup on low memory conditions
 *
 * ## Performance Optimizations
 *
 * ### Resource Management
 * - **Lazy Initialization**: UI components initialized only when needed
 * - **Background Processing**: Heavy operations on background threads
 * - **Memory Efficient**: Weak references and timely resource disposal
 *
 * ### Playback Optimizations
 * - **Buffering Strategy**: Adaptive buffering based on network conditions
 * - **Seek Optimization**: Fast and accurate seeking with frame synchronization
 * - **Track Selection**: Intelligent track selection based on device capabilities
 *
 * ## UI/UX Considerations
 *
 * ### User Experience
 * - **Immersive Design**: Clean interface that focuses on content
 * - **Haptic Feedback**: Tactile responses for key interactions
 * - **Visual Feedback**: Clear status indicators and progress feedback
 * - **Accessibility**: Support for screen readers and accessibility services
 *
 * ### Orientation Handling
 * - **Portrait Mode**: Cutout-aware layout with bottom controls
 * - **Landscape Mode**: Edge-aligned controls for widescreen viewing
 * - **Smooth Transitions**: Animated transitions between orientations
 *
 * ## Integration Points
 *
 * ### External Systems
 * - **Download System**: Integration with app download management
 * - **File System**: DocumentFile-based storage access
 * - **Audio System**: AudioManager integration for volume control
 * - **Display System**: WindowManager for brightness and screen management
 *
 * ### Internal Dependencies
 * - **Popup Systems**: MediaFileOptionsPopup, MediaPlayerConfigsPopup
 * - **Utility Classes**: ShareUtility, ThreadsUtility, ViewUtility
 * - **Logging System**: Structured logging with LogHelperUtils
 *
 * ## Usage Examples
 *
 * ### Basic Playback
 * ```kotlin
 * // Play a downloaded video
 * startActivity(Intent(this, MediaPlayerActivity::class.java).apply {
 *     putExtra(INTENT_EXTRA_DOWNLOAD_ID, downloadModel.downloadId)
 * })
 * ```
 *
 * ### Streaming Playback
 * ```kotlin
 * // Stream from URL
 * startActivity(Intent(this, MediaPlayerActivity::class.java).apply {
 *     putExtra(INTENT_EXTRA_STREAM_URL, "https://example.com/video.mp4")
 *     putExtra(INTENT_EXTRA_STREAM_TITLE, "Streaming Video")
 * })
 * ```
 *
 * ## Threading Model
 *
 * - **Main Thread**: UI updates, user interactions, and player callbacks
 * - **Background Threads**: File operations, media analysis, and heavy computations
 * - **Player Threads**: ExoPlayer internal threading for decoding and rendering
 *
 * ## Memory Considerations
 *
 * - **Large Media Files**: Efficient handling of high-resolution video content
 * - **Bitmap Management**: Careful bitmap loading and caching for album art
 * - **Player Resources**: Proper disposal of ExoPlayer instances and renderers
 * - **Leak Prevention**: Weak references and lifecycle-aware component usage
 *
 * @see ExoPlayer
 * @see PlayerView
 * @see DownloadDataModel
 * @see MediaItem
 */
@UnstableApi
class MediaPlayerActivity : BaseActivity(), AIOTimerListener, Listener {

	/**
	 * Logger instance for tracking events and errors in the MediaPlayerActivity.
	 *
	 * Provides structured logging throughout the player lifecycle for debugging
	 * and monitoring playback issues and user interactions.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Lazy activity reference for safe access to activity context.
	 *
	 * This lazy delegate ensures the activity reference is only obtained when needed
	 * and provides a safe way to access activity context without early initialization.
	 */
	private val activity by lazy { this@MediaPlayerActivity }

	/**
	 * Weak reference to the activity for preventing memory leaks.
	 *
	 * Uses WeakReference to allow garbage collection of the activity when destroyed
	 * while still providing access when the activity is alive. Essential for
	 * preventing memory leaks in media-heavy applications.
	 */
	private val selfActivityRef = WeakReference(activity).get()

	/**
	 * Companion object containing intent extra constants and static definitions.
	 *
	 * These constants define the keys used for intent data passing between activities
	 * and ensure type-safe communication throughout the application.
	 */
	companion object {
		/**
		 * Intent extra key for download ID to identify specific media downloads.
		 * Used to correlate playback with download management system.
		 */
		const val INTENT_EXTRA_DOWNLOAD_ID = "EXTRA_DOWNLOAD_ID"

		/**
		 * Intent extra key for streaming media URL.
		 * Contains the remote URL for streaming video/audio content.
		 */
		const val INTENT_EXTRA_STREAM_URL = "INTENT_EXTRA_STREAM_URL"

		/**
		 * Intent extra key for streaming media title.
		 * Provides the display title for streaming content when available.
		 */
		const val INTENT_EXTRA_STREAM_TITLE = "INTENT_EXTRA_STREAM_TITLE"

		/**
		 * Intent extra key for local media file path.
		 * Contains the file system path for locally stored media files.
		 */
		const val INTENT_EXTRA_MEDIA_FILE_PATH = "INTENT_EXTRA_MEDIA_FILE_PATH"

		/**
		 * Intent extra key for media source origin information.
		 * Tracks where the media originated from (download, share, etc.).
		 */
		const val INTENT_EXTRA_SOURCE_ORIGIN = "INTENT_EXTRA_SOURCE_ORIGIN"
	}

	/**
	 * Abstract base class for video scrubber/progress bar interaction listeners.
	 *
	 * Provides default empty implementation for scrub move events while requiring
	 * concrete implementations for scrub start/stop events. Used for progress bar
	 * seeking functionality.
	 */
	abstract class VideoScrubberListener : TimeBar.OnScrubListener {
		/**
		 * Called during scrub movement - provides default empty implementation.
		 *
		 * @param timeBar The time bar being scrubbed
		 * @param position The current scrub position in milliseconds
		 */
		override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit
	}

	/**
	 * ExoPlayer instance for media playback functionality.
	 *
	 * Handles all audio/video decoding, rendering, and playback control.
	 * Configured with advanced features like FFmpeg extensions and optimized seeking.
	 */
	lateinit var player: ExoPlayer

	/**
	 * PlayerView for rendering video content and displaying player controls.
	 *
	 * The main video surface that displays visual content and provides the
	 * user interface for media interaction and control.
	 */
	lateinit var playerView: PlayerView

	/**
	 * Track selector for managing audio, video, and text track selection.
	 *
	 * Handles track switching, language preferences, and quality selection
	 * for adaptive streaming and multi-track media files.
	 */
	lateinit var trackSelector: DefaultTrackSelector

	/**
	 * Popup menu for media file operations (delete, share, info, etc.).
	 *
	 * Contextual menu that appears when user taps options button, providing
	 * various actions available for the current media file.
	 */
	lateinit var mediaFileOptionsPopup: MediaFileOptionsPopup

	/**
	 * Popup for player configuration and settings adjustment.
	 *
	 * Allows users to modify playback speed, audio tracks, subtitles,
	 * and other player-specific settings during playback.
	 */
	lateinit var playerConfigPopup: MediaPlayerConfigsPopup

	/**
	 * Quick information text overlay for temporary status messages.
	 *
	 * Displays brief messages like "Player locked", volume changes, or
	 * other transient information that auto-hides after short delay.
	 */
	lateinit var quickInfoText: TextView

	/**
	 * Invisible touch area for capturing gesture inputs.
	 *
	 * Overlay view that detects touch gestures for seeking, volume control,
	 * brightness adjustment, and other gesture-based interactions.
	 */
	lateinit var overlayTouchArea: View

	/**
	 * Night mode overlay for reducing blue light emission.
	 *
	 * Semi-transparent overlay that applies warm filter for comfortable
	 * nighttime viewing and reduced eye strain.
	 */
	lateinit var nightModeOverlay: View

	/**
	 * View for handling device cutout (notch) padding in portrait mode.
	 *
	 * Ensures content doesn't overlap with camera cutouts or status bar
	 * areas on modern display designs.
	 */
	lateinit var cutoutPaddingView: View

	/**
	 * ImageView for displaying album artwork during audio playback.
	 *
	 * Shows embedded album art or default artwork when playing audio files
	 * to provide visual context during music playback.
	 */
	lateinit var albumArtView: ImageView

	/**
	 * Lottie animation view for audio visualization during music playback.
	 *
	 * Displays animated audio waveforms that react to music playback,
	 * providing visual feedback for audio-only content.
	 */
	lateinit var audioVisualizerView: LottieAnimationView

	/**
	 * Main container for playback controls (play, pause, seek, etc.).
	 *
	 * Houses all playback control elements and manages their visibility
	 * state for immersive viewing experience.
	 */
	lateinit var playbackController: View

	/**
	 * Portrait orientation-specific playback controls layout.
	 *
	 * Optimized control layout for vertical screen orientation with
	 * bottom-aligned controls and appropriate spacing.
	 */
	lateinit var playbackControllerBottomPortrait: View

	/**
	 * Landscape orientation-specific playback controls layout.
	 *
	 * Optimized control layout for horizontal screen orientation with
	 * edge-aligned controls and widescreen-appropriate design.
	 */
	lateinit var playbackControllerBottomLandscape: View

	/**
	 * Back button for navigation to previous screen.
	 *
	 * Provides consistent navigation pattern and handles custom back
	 * behavior with control lock state consideration.
	 */
	lateinit var backButton: View

	/**
	 * Text view for displaying current media file name or title.
	 *
	 * Shows the currently playing media name with marquee scrolling
	 * for long file names and dynamic title updates.
	 */
	lateinit var videoTitleText: TextView

	/**
	 * Options button for accessing media file operations menu.
	 *
	 * Triggers the media options popup with actions like share, delete,
	 * and file information.
	 */
	lateinit var optionsButton: View

	/**
	 * Text view for displaying current playback time in portrait mode.
	 *
	 * Shows elapsed time in MM:SS format and updates in real-time
	 * during playback.
	 */
	lateinit var currentTimeText: TextView

	/**
	 * Text view for displaying current playback time in landscape mode.
	 *
	 * Landscape-oriented version of current time display with appropriate
	 * layout positioning.
	 */
	lateinit var currentTimeTextLand: TextView

	/**
	 * Text view for displaying total media duration in portrait mode.
	 *
	 * Shows total media length in MM:SS format with validation for
	 * invalid duration values.
	 */
	lateinit var durationText: TextView

	/**
	 * Text view for displaying total media duration in landscape mode.
	 *
	 * Landscape-oriented version of duration display with appropriate
	 * layout positioning.
	 */
	lateinit var durationTextLand: TextView

	/**
	 * Rounded progress bar for media playback tracking in portrait mode.
	 *
	 * Custom styled progress bar that shows current position, buffered
	 * range, and supports scrubbing for manual seeking.
	 */
	lateinit var mediaProgressBar: RoundedTimeBar

	/**
	 * Rounded progress bar for media playback tracking in landscape mode.
	 *
	 * Landscape-oriented version of the progress bar with appropriate
	 * sizing and positioning.
	 */
	lateinit var mediaProgressBarLand: RoundedTimeBar

	/**
	 * Vertical progress bar for brightness control adjustment.
	 *
	 * Custom slider for controlling device screen brightness with
	 * visual feedback and auto-hide behavior.
	 */
	lateinit var brightnessSlider: VerticalProgressBar

	/**
	 * Vertical progress bar for volume control adjustment.
	 *
	 * Custom slider for controlling audio volume with visual feedback
	 * and auto-hide behavior.
	 */
	lateinit var volumeSlider: VerticalProgressBar

	/**
	 * Container view for volume slider with background and positioning.
	 *
	 * Wrapper that provides proper layout and background for the
	 * volume control slider.
	 */
	lateinit var volumeSliderContainer: View

	/**
	 * Container view for brightness slider with background and positioning.
	 *
	 * Wrapper that provides proper layout and background for the
	 * brightness control slider.
	 */
	lateinit var brightnessSliderContainer: View

	/**
	 * Lock button for entering immersive mode in portrait orientation.
	 *
	 * Hides all controls and locks screen orientation for distraction-free
	 * viewing experience.
	 */
	lateinit var lockButton: View

	/**
	 * Lock button for entering immersive mode in landscape orientation.
	 *
	 * Landscape-oriented version of the lock control button.
	 */
	lateinit var lockButtonLand: View

	/**
	 * Previous track button for portrait orientation.
	 *
	 * Navigates to the previous media file in the playback queue with
	 * private media access consideration.
	 */
	lateinit var prevButton: View

	/**
	 * Previous track button for landscape orientation.
	 *
	 * Landscape-oriented version of the previous track button.
	 */
	lateinit var prevButtonLand: View

	/**
	 * Play/pause toggle button for portrait orientation.
	 *
	 * Primary playback control that toggles between play and pause states
	 * with dynamic icon updates.
	 */
	lateinit var playPauseButton: View

	/**
	 * Play/pause toggle button for landscape orientation.
	 *
	 * Landscape-oriented version of the play/pause button.
	 */
	lateinit var playPauseButtonLand: View

	/**
	 * Next track button for portrait orientation.
	 *
	 * Navigates to the next media file in the playback queue with
	 * private media access consideration.
	 */
	lateinit var nextButton: View

	/**
	 * Next track button for landscape orientation.
	 *
	 * Landscape-oriented version of the next track button.
	 */
	lateinit var nextButtonLand: View

	/**
	 * Player configuration button for portrait orientation.
	 *
	 * Opens the player settings popup for playback speed, audio tracks,
	 * and other configuration options.
	 */
	lateinit var configButton: View

	/**
	 * Player configuration button for landscape orientation.
	 *
	 * Landscape-oriented version of the configuration button.
	 */
	lateinit var configButtonLand: View

	/**
	 * Unlock button for exiting immersive mode.
	 *
	 * Appears when controls are locked to allow users to exit immersive
	 * mode and restore full control access.
	 */
	lateinit var unlockButton: View

	/**
	 * Last known playback speed setting for resumption consistency.
	 *
	 * Stores the playback speed (0.5x, 1.0x, 1.5x, 2.0x) to maintain
	 * user preference across playback sessions.
	 */
	var lastPlaybackSpeed: Float = 1.0f

	/**
	 * Flag indicating whether playback controls are currently locked.
	 *
	 * When true, controls are hidden and screen orientation is locked
	 * for immersive viewing mode.
	 */
	var areControllersLocked = false

	/**
	 * Current playback position in milliseconds for resumption.
	 *
	 * Tracks the last known playback position to allow seamless resumption
	 * after activity recreation or app backgrounding.
	 */
	var currentPlaybackPosition: Long = 0L

	/**
	 * Flag indicating whether night mode is currently enabled.
	 *
	 * When true, applies blue light reduction filter for comfortable
	 * nighttime viewing.
	 */
	var isNightModeEnabled = false

	/**
	 * Counter for tracking invisible area tap sequences.
	 *
	 * Used to distinguish between single taps (show/hide controls)
	 * and double taps (play/pause toggle) on the video surface.
	 */
	var invisibleAreaClickCount = 0

	/**
	 * Seek interval in milliseconds for fast-forward/rewind operations.
	 *
	 * Defines how much time to skip (10 seconds) when using seek gestures
	 * or buttons.
	 */
	val seekIntervalMs = 10000L

	/**
	 * Handler for managing quick info text auto-hide timing.
	 *
	 * Coordinates the delayed hiding of transient status messages
	 * on the main looper thread.
	 */
	val quickInfoHandler = Handler(Looper.getMainLooper())

	/**
	 * Handler for managing brightness slider auto-hide timing.
	 *
	 * Coordinates the delayed hiding of brightness control slider
	 * on the main looper thread.
	 */
	val brightnessSliderHandler = Handler(Looper.getMainLooper())

	/**
	 * Handler for managing volume slider auto-hide timing.
	 *
	 * Coordinates the delayed hiding of volume control slider
	 * on the main looper thread.
	 */
	val volumeSliderHandler = Handler(Looper.getMainLooper())

	/**
	 * Runnable for brightness slider auto-hide functionality.
	 *
	 * Contains the logic to hide the brightness slider after
	 * a delay of user inactivity.
	 */
	var brightnessSliderRunnable: Runnable? = null

	/**
	 * Runnable for volume slider auto-hide functionality.
	 *
	 * Contains the logic to hide the volume slider after
	 * a delay of user inactivity.
	 */
	var volumeSliderRunnable: Runnable? = null

	/**
	 * Runnable for quick info text auto-hide functionality.
	 *
	 * Contains the logic to hide transient status messages
	 * after a brief display period.
	 */
	var quickInfoRunnable: Runnable? = null

	/**
	 * Flag indicating whether private media access is currently allowed.
	 *
	 * When true, user has authenticated and can access media files
	 * stored in private/protected directories.
	 */
	var isPrivateSessionAllowed: Boolean = false

	/**
	 * Flag indicating whether user has made a decision about private access.
	 *
	 * When true, the private media access prompt won't be shown again
	 * during the current session.
	 */
	var hasMadeDecisionOverPrivateAccess: Boolean = false

	/**
	 * Defines the layout resource for the player activity during rendering phase.
	 *
	 * This method is called during the activity layout inflation process and:
	 * - Configures system UI for immersive media experience
	 * - Enables edge-to-edge fullscreen display
	 * - Sets up auto-rotation behavior
	 * - Returns the appropriate layout resource ID
	 *
	 * @return The layout resource ID for the player activity
	 */
	override fun onRenderingLayout(): Int {
		setDarkSystemStatusBar()        // Use dark theme for system bars
		setEdgeToEdgeFullscreen()       // Extend content behind system bars
		setAutoRotationEnabled(isEnabled = true)  // Allow orientation changes
		return layout.activity_player_1 // Return the main player layout
	}

	/**
	 * Performs initialization tasks after the activity layout has been rendered.
	 *
	 * This method executes the complete player setup sequence:
	 * 1. Initializes all activity views and UI components
	 * 2. Sets up the ExoPlayer instance with configuration
	 * 3. Configures gesture controls for touch interactions
	 * 4. Starts video playback from intent data
	 * 5. Hides night mode overlay after delay
	 *
	 * Called automatically after layout inflation completes.
	 */
	override fun onAfterLayoutRender() {
		initializeActivityViews().let {
			initializePlayer().let {
				setupPlayerGestures(targetView = overlayTouchArea)
				playVideoFromIntent().let {
					// Hide night mode overlay with smooth animation
					hideView(nightModeOverlay, true, 1000)
				}
			}
		}
	}

	/**
	 * Handles the back button press with special behavior for player controls.
	 *
	 * This method provides custom back navigation logic:
	 * - Prevents exit when controls are locked (shows warning)
	 * - Stops and disposes player resources properly
	 * - Animates player view hide before activity closure
	 * - Ensures smooth transition with delayed activity finish
	 *
	 * Overrides default back button behavior for better user experience.
	 */
	override fun onBackPressActivity() {
		// Prevent exit when controls are locked (immersive mode)
		if (areControllersLocked) {
			doSomeVibration(timeInMillis = 20)  // Haptic feedback
			val quickInfoText = getText(string.title_player_is_locked_unlock_first)
			showQuickPlayerInfo(quickInfoText.toString())
			return
		}

		// Graceful shutdown sequence
		stopAndDisposePlayer()  // Clean up player resources

		// Animate player view hide before closing activity
		hideView(playerView, true, 300).let {
			delay(200, object : OnTaskFinishListener {
				override fun afterDelay() = closeActivityWithFadeAnimation(true)
			})
		}
	}

	/**
	 * Handles activity resume by resuming media playback.
	 *
	 * Automatically resumes video/audio playback when the activity
	 * returns to the foreground from paused state.
	 */
	override fun onResumeActivity() {
		resumePlayback()
	}

	/**
	 * Handles activity pause by pausing media playback.
	 *
	 * Automatically pauses video/audio playback when the activity
	 * is moved to the background to conserve resources.
	 */
	override fun onPauseActivity() {
		pausePlayback()
		releaseScreenOn() // Ensure screen lock is released
	}

	/**
	 * Handles activity destruction with proper resource cleanup.
	 *
	 * Ensures all player resources are released when the activity
	 * is destroyed to prevent memory leaks and resource retention.
	 */
	override fun onDestroy() {
		super.onDestroy()
		stopAndDisposePlayer()  // Final cleanup
		releaseScreenOn() // Clean up screen lock
	}

	/**
	 * Handles timer ticks for UI updates during playback.
	 *
	 * This method is called at regular intervals by the AIO timer
	 * to refresh the playback progress UI elements like:
	 * - Current time display
	 * - Progress bar position
	 * - Buffered position indicator
	 *
	 * @param loopCount The current iteration count of the timer
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		refreshPlaybackProgressUI()
	}

	/**
	 * Handles changes in player playback state.
	 *
	 * Responds to important player state transitions:
	 * - Playback completion (end of media)
	 * - Buffering and ready states for UI updates
	 *
	 * @param playbackState The new playback state from ExoPlayer
	 */
	override fun onPlaybackStateChanged(playbackState: Int) {
		if (playbackState == Player.STATE_ENDED) {
			handlePlaybackCompletion()  // Auto-play next or close
		} else takeIf { isPlayerBufferingOrGettingReady(playbackState) }
			?.let { refreshPlaybackProgressUI() }  // Update UI during buffering
	}

	/**
	 * Checks if the player is in a state that requires UI updates.
	 *
	 * @param playbackState The current player state to check
	 * @return True if player is ready or buffering, false otherwise
	 */
	private fun isPlayerBufferingOrGettingReady(playbackState: Int): Boolean {
		return playbackState == STATE_READY || playbackState == STATE_BUFFERING
	}

	/**
	 * Handles device configuration changes (orientation changes).
	 *
	 * Adapts the UI layout when the device orientation changes:
	 * - Portrait mode: Shows cutout padding and portrait controls
	 * - Landscape mode: Hides cutout and shows landscape controls
	 * - Auto-hides controls after orientation change
	 *
	 * @param newConfig The new configuration data
	 */
	override fun onConfigurationChanged(newConfiguration: Configuration) {
		super.onConfigurationChanged(newConfiguration)
		if (newConfiguration.orientation == ORIENTATION_PORTRAIT) handlePortraitMode()
		else if (newConfiguration.orientation == ORIENTATION_LANDSCAPE) handleLandscapeMode()
	}

	/**
	 * Handles player errors with user-friendly error dialog.
	 *
	 * Displays an error dialog when media playback fails, offering:
	 * - Option to delete the problematic media file
	 * - Option to exit the player
	 * - Detailed error information in logs
	 *
	 * @param error The playback exception that occurred
	 */
	override fun onPlayerError(error: PlaybackException) {
		logger.e("onPlayerError:", error)  // Log detailed error

		showMessageDialog(
			baseActivityInf = selfActivityRef,
			titleText = getString(string.title_couldnt_play_media),
			isTitleVisible = true,
			isCancelable = true,
			isNegativeButtonVisible = false,
			messageTextViewCustomize = { it.setText(string.text_error_media_cant_be_played) },
			titleTextViewCustomize = { it.setTextColor(resources.getColor(color.color_error, theme)) },
			positiveButtonTextCustomize = {
				it.text = getString(string.title_exit_the_player)
				it.setLeftSideDrawable(drawable.ic_button_exit)
			}, negativeButtonTextCustomize = {
				it.setText(string.title_delete_file)
				it.setLeftSideDrawable(drawable.ic_button_delete)
			}
		)?.let { dialogBuilder ->
			dialogBuilder.setOnClickForNegativeButton {
				dialogBuilder.close()
				deleteCurrentMediaFile()  // Remove problematic file
			}

			dialogBuilder.setOnClickForPositiveButton {
				dialogBuilder.close()
				closeActivityWithFadeAnimation()  // Exit player
			}
		}
	}

	/**
	 * Pauses media playback with safety checks.
	 *
	 * Safely pauses the player only if the activity is still active
	 * to prevent operations on destroyed activities.
	 */
	fun pausePlayback() {
		if (!isFinishing && !isDestroyed) {
			player.playWhenReady = false
			player.pause()
		}
		releaseScreenOn() // Allow screen to timeout when paused
	}

	/**
	 * Resumes media playback from current position.
	 *
	 * Resumes playback only if not already playing and seeks to
	 * the last known playback position for continuity.
	 */
	fun resumePlayback() {
		if (player.isPlaying) return
		player.playWhenReady = true
		player.play()
		player.seekTo(currentPlaybackPosition)  // Resume from last position
		keepScreenOn() // Keep screen awake during playback
	}

	/**
	 * Enables private session access through authentication.
	 *
	 * Prompts user for authentication to access private media files.
	 * Sets appropriate flags on successful authentication and provides
	 * feedback on failure.
	 */
	fun enablePrivateSession() {
		selfActivityRef?.let { playerActivity ->
			if (playerActivity.isPrivateSessionAllowed) return

			authenticate(activity = playerActivity, onResult = { isSuccess ->
				if (isSuccess) {
					playerActivity.isPrivateSessionAllowed = true
					playerActivity.hasMadeDecisionOverPrivateAccess = true
				} else {
					playerActivity.doSomeVibration(50)
					showToast(playerActivity, msgId = string.title_authentication_failed)
				}
			})
		}
	}

	/**
	 * Checks if currently playing media is a streaming video.
	 *
	 * Determines playback type by checking intent extras for streaming URLs
	 * with version-safe parcelable handling.
	 *
	 * @return True if streaming media is playing, false for local files
	 */
	fun isStreamingVideoPlaying(): Boolean {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
			intent.getParcelableExtra(INTENT_EXTRA_STREAM_URL, Uri::class.java)?.let { return true }
		else intent.getStringExtra(INTENT_EXTRA_STREAM_URL)?.let { if (isValidURL(it)) return true }
		return false
	}

	/**
	 * Handles changes in playing state for UI and timer management.
	 *
	 * Manages timer registration and UI updates when playback state changes:
	 * - Registers timer for UI updates during playback
	 * - Unregisters timer when playback stops
	 * - Updates video title and play/pause icons
	 *
	 * @param isPlaying True if media is currently playing, false if paused
	 */
	override fun onIsPlayingChanged(isPlaying: Boolean) {
		selfActivityRef?.let { safeActivityRef ->
			let {
				if (isPlaying) aioTimer.register(safeActivityRef)    // Start UI updates
				else aioTimer.unregister(safeActivityRef)           // Stop UI updates
			}.apply {
				val incomingVideoTitle = intent.getStringExtra(INTENT_EXTRA_STREAM_TITLE)
				if (incomingVideoTitle.isNullOrEmpty()) refreshCurrentVideoTitle()
				updatePlayPauseIcon(isPlaying)  // Update button states
			}
		}
	}

	/**
	 * Configures system bars for dark theme immersive experience.
	 *
	 * Sets system status and navigation bars to black with dark icons
	 * for optimal media viewing in low-light conditions.
	 */
	fun setDarkSystemStatusBar() {
		setSystemBarsColors(
			statusBarColorResId = color.color_pure_black,
			navigationBarColorResId = color.color_pure_black,
			isLightStatusBar = false,      // Dark icons on dark background
			isLightNavigationBar = false   // Dark icons on dark background
		)
	}

	/**
	 * Configures UI for landscape orientation.
	 *
	 * Hides cutout padding and switches to landscape control layout
	 * for optimal widescreen media viewing experience.
	 */
	fun handleLandscapeMode() {
		cutoutPaddingView.visibility = GONE                    // No cutouts in landscape
		playbackControllerBottomPortrait.visibility = GONE     // Hide portrait controls
		playbackControllerBottomLandscape.visibility = VISIBLE // Show landscape controls

		// Auto-hide controls after orientation change
		delay(timeInMile = 400, listener = object : OnTaskFinishListener {
			override fun afterDelay() = hidePlaybackControls()
		})
	}

	/**
	 * Configures UI for portrait orientation.
	 *
	 * Shows cutout padding and switches to portrait control layout
	 * for optimal vertical media viewing experience.
	 */
	fun handlePortraitMode() {
		cutoutPaddingView.visibility = VISIBLE                 // Handle notch/cutout
		playbackControllerBottomLandscape.visibility = GONE    // Hide landscape controls
		playbackControllerBottomPortrait.visibility = VISIBLE  // Show portrait controls

		// Auto-hide controls after orientation change
		delay(timeInMile = 400, listener = object : OnTaskFinishListener {
			override fun afterDelay() = hidePlaybackControls()
		})
	}

	/**
	 * Enables or disables auto-rotation for the activity.
	 *
	 * @param isEnabled True to allow auto-rotation, false to lock current orientation
	 */
	fun setAutoRotationEnabled(isEnabled: Boolean = true) {
		requestedOrientation = if (isEnabled) {
			SCREEN_ORIENTATION_UNSPECIFIED  // Allow auto-rotation
		} else {
			SCREEN_ORIENTATION_LOCKED       // Lock current orientation
		}
	}

	/**
	 * Displays temporary quick information overlay.
	 *
	 * Shows brief messages to users (e.g., "Player locked", volume changes)
	 * that automatically disappear after a short delay.
	 *
	 * @param msgText The message text to display
	 */
	fun showQuickPlayerInfo(msgText: String) {
		quickInfoText.apply { visibility = VISIBLE; text = msgText }

		// Remove any existing auto-hide callbacks
		quickInfoRunnable?.let { quickInfoHandler.removeCallbacks(it) }

		// Create new auto-hide runnable
		quickInfoRunnable = Runnable {
			quickInfoText.apply { visibility = GONE; text = "" }
		}

		// Schedule auto-hide after 1.5 seconds
		quickInfoHandler.postDelayed(quickInfoRunnable!!, 1500)
	}

	/**
	 * Updates brightness slider display with auto-hide behavior.
	 *
	 * Shows brightness control slider and automatically hides it
	 * after a short delay to maintain clean UI.
	 *
	 * @param process The brightness percentage (0-100) to display
	 */
	fun setBrightness(process: Int) {
		showView(brightnessSliderContainer, true)  // Show slider
		brightnessSlider.setProgress(value = process)  // Update progress

		// Auto-hide after delay
		brightnessSliderRunnable?.let { brightnessSliderHandler.removeCallbacks(it) }
		brightnessSliderRunnable = Runnable {
			hideView(brightnessSliderContainer, true, 100)
		}
		brightnessSliderHandler.postDelayed(brightnessSliderRunnable!!, 500)
	}

	/**
	 * Updates volume slider display with auto-hide behavior.
	 *
	 * Shows volume control slider and automatically hides it
	 * after a short delay to maintain clean UI.
	 *
	 * @param process The volume percentage (0-100) to display
	 */
	fun setVolume(process: Int) {
		showView(volumeSliderContainer, true)  // Show slider
		volumeSlider.setProgress(value = process)  // Update progress

		// Auto-hide after delay
		volumeSliderRunnable?.let { volumeSliderHandler.removeCallbacks(it) }
		volumeSliderRunnable = Runnable {
			hideView(volumeSliderContainer, true, 100)
		}
		volumeSliderHandler.postDelayed(volumeSliderRunnable!!, 500)
	}

	/**
	 * Formats playback time in milliseconds to MM:SS string format.
	 *
	 * Converts milliseconds to human-readable minutes:seconds format
	 * for display in the player UI.
	 *
	 * @param milliseconds The playback position in milliseconds
	 * @return Formatted time string in MM:SS format
	 */
	fun formatPlaybackTime(milliseconds: Long): String {
		val minutes = TimeUnit.MILLISECONDS.toMinutes(milliseconds)
		val seconds = TimeUnit.MILLISECONDS.toSeconds(milliseconds) % 60
		return String.format(Locale.US, "%02d:%02d", minutes, seconds)
	}

	/**
	 * Deletes the currently playing media file with cleanup.
	 *
	 * Safely deletes the current media file and performs:
	 * - Automatic navigation to next/previous media file
	 * - Database and storage cleanup
	 * - Background execution to prevent UI blocking
	 */
	fun deleteCurrentMediaFile() {
		if (!::player.isInitialized) return

		val currentMediaUri = player.currentMediaItem?.localConfiguration?.uri.toString()
		if (currentMediaUri.isEmpty()) return

		val mediaFiles = fetchAllValidMediaDownloads()
		val matchedIndex = findMatchingModelIndex(currentMediaUri, mediaFiles)

		// Perform deletion and navigation in background
		ThreadsUtility.executeInBackground(codeBlock = {
			playNextOrPreviousMediaFromDownloads(matchedIndex, mediaFiles)  // Auto-navigate
			deleteMediaFileAndCleanDatabase(mediaFiles, matchedIndex)       // Cleanup
		})
	}

	/**
	 * Shares the currently playing media file through system sharing options.
	 *
	 * This function handles the complete sharing workflow:
	 * - Prevents sharing of streaming media (shows error dialog)
	 * - Validates current media file existence and accessibility
	 * - Locates the corresponding download model for the playing media
	 * - Initiates system sharing intent for the media file
	 * - Provides appropriate user feedback for invalid or unavailable media
	 *
	 * Shows error dialogs for streaming media and invalid files instead of crashing.
	 */
	fun shareCurrentMediaFile() {
		selfActivityRef?.let { activityRef ->
			// Prevent sharing of streaming media files
			if (isStreamingVideoPlaying()) {
				showMessageDialog(
					baseActivityInf = activityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					messageTextViewCustomize = {
						it.setText(string.text_share_stream_media_unavailable)
					},
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(color.color_error)  // Error color for emphasis
					},
					positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(drawable.ic_button_checked_circle)
						positiveButton.setText(string.title_okay)
					}
				)
				return
			} else {
				// Extract current media URI for local file sharing
				val currentMediaItem = player.currentMediaItem
				val currentMediaUri = currentMediaItem?.localConfiguration?.uri.toString()
				if (currentMediaUri.isEmpty()) {
					showInvalidMediaToast()
					return
				}

				// Find matching download model in finished downloads
				downloadSystem.finishedDownloadDataModels.find {
					it.getDestinationFile().path == currentMediaUri.toUri().path
				}?.let { downloadDataModel ->
					// Found matching model - share the physical file
					val destinationFile = downloadDataModel.getDestinationFile()
					shareMediaFile(activityRef, destinationFile)
					return
				}

				// No matching download model found
				showInvalidMediaToast()
			}

			// Final fallback for any unhandled cases
			showInvalidMediaToast()
		}
	}

	/**
	 * Opens the player configuration popup for playback settings adjustment.
	 *
	 * This function displays a popup interface allowing users to modify:
	 * - Playback speed
	 * - Audio track selection
	 * - Subtitle preferences
	 * - Video quality settings
	 * - Other player-specific configurations
	 *
	 * Uses lazy initialization to create the popup only when first needed.
	 */
	fun openPlayerConfiguration() {
		// Initialize the popup only once (lazy initialization pattern)
		if (!::playerConfigPopup.isInitialized) {
			playerConfigPopup = MediaPlayerConfigsPopup(selfActivityRef)
			playerConfigPopup.show()
		} else {
			// Reuse existing popup instance
			playerConfigPopup.show()
		}
	}

	/**
	 * Prompts the user to select and sync an external subtitle file.
	 *
	 * This function guides users through the subtitle attachment process:
	 * - Shows explanatory dialog about subtitle selection
	 * - Provides clear call-to-action for file selection
	 * - Delegates to the actual subtitle attachment workflow
	 * - Ensures user understands the process before proceeding
	 *
	 * Used when users want to add external subtitles to the current video.
	 */
	fun promptAndSyncSubtitle() {
		selfActivityRef?.let { safeActivityRef ->
			getMessageDialog(
				baseActivityInf = safeActivityRef,
				messageTxt = getString(string.text_select_subtitle_from_file_manager),
				positiveButtonText = getString(string.title_select_file)
			)?.let { dialogBuilder ->
				// Set up subtitle selection when user confirms
				dialogBuilder.positiveButtonView.setOnClickListener {
					dialogBuilder.close()
					attachExternalSubtitle()  // Launch file picker for subtitle selection
				}.apply {
					dialogBuilder.show()  // Display the prompt dialog
				}
			}
		}
	}

	/**
	 * Opens the current media file using an external application.
	 *
	 * This function allows users to open the playing media file in other apps:
	 * - Prevents opening of streaming media files (shows error dialog)
	 * - Validates player initialization and current media availability
	 * - Finds the corresponding local file for the playing media
	 * - Launches system intent to open the file with external applications
	 *
	 * Useful for when users want to edit or view media in other applications.
	 */
	fun openCurrentMediaFile() {
		selfActivityRef?.let { activityRef ->
			// Streaming media cannot be opened externally
			if (isStreamingVideoPlaying()) {
				val message = getString(string.text_open_stream_media_unavailable)
				showMessageDialog(
					baseActivityInf = activityRef,
					messageTxt = message,
					isNegativeButtonVisible = false
				)
				return
			}

			// Ensure player is properly initialized
			if (!::player.isInitialized) return

			// Extract current media URI for external opening
			val currentMediaItem = player.currentMediaItem
			val currentMediaUri = currentMediaItem?.localConfiguration?.uri.toString()
			if (currentMediaUri.isEmpty()) return

			// Find and open the corresponding local media file
			val mediaFiles = fetchAllValidMediaDownloads()
			val matchedIndex = findMatchingModelIndex(currentMediaUri, mediaFiles)
			val mediaFile = mediaFiles[matchedIndex].getDestinationFile()
			ShareUtility.openFile(mediaFile, activityRef)  // Launch external app intent
		}
	}

	/**
	 * Displays detailed information about the currently playing media file.
	 *
	 * This function shows a comprehensive information dialog containing:
	 * - File name and format details
	 * - File size and duration information
	 * - Download source and date
	 * - Video/audio codec information (if available)
	 * - File path and storage location
	 *
	 * Formats the information as styled HTML for better readability.
	 * Prevents viewing of streaming media information (shows error dialog).
	 */
	fun openCurrentMediaFileInfo() {
		selfActivityRef?.let { activityRef ->
			// Streaming media doesn't have local file information
			if (isStreamingVideoPlaying()) {
				showMessageDialog(
					baseActivityInf = activityRef,
					isTitleVisible = true,
					isNegativeButtonVisible = false,
					messageTextViewCustomize = {
						it.setText(string.text_video_stream_info_unavailable)
					},
					titleTextViewCustomize = { titleView ->
						titleView.setText(string.title_unavailable_for_streaming)
						titleView.setTextColorKT(color.color_error)  // Visual error indication
					},
					positiveButtonTextCustomize = { positiveButton ->
						positiveButton.setLeftSideDrawable(drawable.ic_button_checked_circle)
						positiveButton.setText(string.title_okay)
					}
				)
				return
			}

			// Ensure player is initialized before accessing media information
			if (!::player.isInitialized) return

			// Extract current media URI for information lookup
			val currentItem = player.currentMediaItem
			val currentMediaUri = currentItem?.localConfiguration?.uri.toString()
			if (currentMediaUri.isEmpty()) return

			// Find matching download model and display its information
			val mediaFiles = fetchAllValidMediaDownloads()
			val matchedIndex = findMatchingModelIndex(currentMediaUri, mediaFiles)
			val downloadModel = mediaFiles[matchedIndex]

			// Create and show detailed media information dialog
			getMessageDialog(
				baseActivityInf = activityRef,
				isCancelable = true,  // Allow dismissal by tapping outside
				isTitleVisible = true,
				titleText = getText(string.title_media_file_info),
				messageTextViewCustomize = {
					it.gravity = Gravity.START  // Left-align text for better readability
					it.linksClickable = true   // Enable clickable links in the content

					// Generate formatted HTML string with media information
					val htmlString = buildMediaInfoHtmlString(downloadModel)
					it.text = fromHtmlStringToSpanned(htmlString)
					it.movementMethod = getInstance()  // Enable link clicking
				},
				positiveButtonText = getString(string.title_okay),
				isNegativeButtonVisible = false,  // Simple acknowledgment dialog
				positiveButtonTextCustomize = { positiveButton: TextView ->
					// Add checkmark icon to positive button
					val drawable = ContextCompat.getDrawable(
						applicationContext,
						drawable.ic_button_checked_circle
					)
					drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
					positiveButton.setCompoundDrawables(drawable, null, null, null)
				}
			)?.show()
		}
	}

	/**
	 * Retrieves the DownloadDataModel for the currently playing media file.
	 *
	 * This function correlates the currently playing media item with the corresponding
	 * download model by matching URIs. Essential for operations that require access
	 * to download metadata during playback.
	 *
	 * @return The DownloadDataModel for the current media, or null if not found or on error
	 */
	fun getCurrentPlayingDownload(): DownloadDataModel? {
		try {
			// Ensure player is initialized before attempting to get current media
			if (!::player.isInitialized) return null

			// Extract URI from current media item for matching
			val currentMediaItem = player.currentMediaItem
			val currentMediaUri = currentMediaItem?.localConfiguration?.uri.toString()
			if (currentMediaUri.isEmpty()) return null

			// Find matching download model in valid media downloads
			val downloadDataModelList = fetchAllValidMediaDownloads()
			val matchedIndex = findMatchingModelIndex(currentMediaUri, downloadDataModelList)
			val currentPlayingDownloadModel = downloadDataModelList[matchedIndex]
			return currentPlayingDownloadModel
		} catch (error: Exception) {
			// Log error and return null to prevent crashes during playback
			logger.e("Error in getting current playing download data model:", error)
			return null
		}
	}

	/**
	 * Initializes all activity views and sets up their event listeners.
	 *
	 * This comprehensive function performs the complete UI setup including:
	 * - Player view and overlay components
	 * - Playback control buttons and their click handlers
	 * - Progress bars and sliders for media and system controls
	 * - Text displays for timing and metadata
	 * - Audio visualization components
	 *
	 * Must be called after activity creation to establish the complete user interface.
	 */
	private fun initializeActivityViews() {
		selfActivityRef?.let { _ ->
			// region Core Player Views
			// Initialize ExoPlayer view for video rendering
			playerView = findViewById(id.video_player_view)

			// Quick info text overlay (e.g., zoom, volume, brightness indicators)
			quickInfoText = findViewById(id.txt_video_quick_info)

			// Night mode overlay view for reduced blue light emission
			nightModeOverlay = findViewById(id.night_mode_invisible_area)
			nightModeOverlay.visibility = GONE

			// Invisible touch area for gesture detection (seeking, volume, brightness)
			overlayTouchArea = findViewById(id.invisible_touch_area)

			// Device cutout (notch) padding handling for modern display designs
			cutoutPaddingView = findViewById(id.device_cutout_padding_view)
			cutoutPaddingView.matchHeightToTopCutout()

			// region Playback Controls
			// Playback controls container (hidden initially for immersive experience)
			playbackController = findViewById(id.container_player_controller)
			playbackController.visibility = GONE

			// Orientation-specific control layouts
			playbackControllerBottomPortrait = findViewById(id.controller_portrait_v2)
			playbackControllerBottomLandscape = findViewById(id.controller_landscape_v2)

			// region Audio Visualization
			// Audio album art & visualizer for audio-only content
			albumArtView = findViewById(id.img_audio_album_art)
			audioVisualizerView = findViewById(id.anim_audio_visualizing)
			fromRawRes(INSTANCE, R.raw.animation_audio_visualizing_v1)
				.addListener { audioVisualizerView.setComposition(it) }

			// region Action Bar
			// Action bar buttons for navigation and options
			backButton = findViewById(id.btn_actionbar_back)
			backButton.setOnClickListener { onBackPressActivity() }

			// Video title text with marquee behavior for long file names
			videoTitleText = findViewById(id.txt_video_file_name)
			videoTitleText.apply {
				isSelected = true  // Enable marquee scrolling
				text = extractDownloadModelFromIntent()?.fileName ?: ""
			}

			// Options button for media file operations menu
			optionsButton = findViewById(id.btn_actionbar_option)
			optionsButton.setOnClickListener { showMediaOptionsMenu() }

			// region Progress Display
			// Media progress time displays for both orientations
			currentTimeText = findViewById(id.txt_video_progress_timer)
			currentTimeTextLand = findViewById(id.txt_video_progress_timer_land)

			// Media progress bars with scrubber seek capability
			mediaProgressBar = findViewById(id.video_progress_bar)
			mediaProgressBar.addListener(createScrubberSeekListener())

			mediaProgressBarLand = findViewById(id.video_progress_bar_land)
			mediaProgressBarLand.addListener(createScrubberSeekListener())

			// region System Controls
			// Brightness slider & container for manual brightness adjustment
			brightnessSlider = findViewById(id.progress_brightness)
			brightnessSlider.setMax(100)  // Percentage scale
			brightnessSliderContainer = findViewById(id.container_brightness_slider)

			// Volume slider & container for manual volume adjustment
			volumeSlider = findViewById(id.progress_volume)
			volumeSlider.setMax(100)  // Percentage scale
			volumeSliderContainer = findViewById(id.container_volume_slider)

			// region Duration Display
			// Total duration displays for both orientations
			durationText = findViewById(id.txt_video_duration)
			durationTextLand = findViewById(id.txt_video_duration_land)

			// region Playback Control Buttons
			// Lock buttons for immersive mode control
			lockButton = findViewById(id.btn_video_controllers_lock)
			lockButtonLand = findViewById(id.btn_video_controllers_lock_land)

			lockButton.setOnClickListener { lockPlaybackControls() }
			lockButtonLand.setOnClickListener { lockPlaybackControls() }

			// Previous track navigation buttons
			prevButton = findViewById(id.btn_video_previous)
			prevButtonLand = findViewById(id.btn_video_previous_land)

			prevButton.setOnClickListener { playPreviousVideoItem() }
			prevButtonLand.setOnClickListener { playPreviousVideoItem() }

			// Play/pause toggle buttons
			playPauseButton = findViewById(id.btn_video_play_pause_toggle)
			playPauseButtonLand = findViewById(id.btn_video_play_pause_toggle_land)

			playPauseButton.setOnClickListener { togglePlaybackState() }
			playPauseButtonLand.setOnClickListener { togglePlaybackState() }

			// Next track navigation buttons
			nextButton = findViewById(id.btn_video_next)
			nextButtonLand = findViewById(id.btn_video_next_land)

			nextButton.setOnClickListener { playNextVideoItem() }
			nextButtonLand.setOnClickListener { playNextVideoItem() }

			// Player configuration buttons
			configButton = findViewById(id.btn_player_configs)
			configButtonLand = findViewById(id.btn_player_configs_land)

			configButton.setOnClickListener { openPlayerConfiguration() }
			configButtonLand.setOnClickListener { openPlayerConfiguration() }

			// Unlock button for exiting immersive mode
			unlockButton = findViewById(id.btn_video_unlock_overlay)
			unlockButton.setOnClickListener { unlockPlaybackControls() }
		}
	}

	/**
	 * Initializes and configures the ExoPlayer instance with optimal settings.
	 *
	 * This function sets up the media player with:
	 * - Advanced renderers factory with FFmpeg extension support
	 * - Intelligent track selection with language preferences
	 * - Optimized seek parameters and audio handling
	 * - Custom subtitle styling and rendering
	 *
	 * Must be called before any media playback can occur.
	 */
	private fun initializePlayer() {
		selfActivityRef?.let { activityRef ->
			// region Renderers Configuration
			// Renderers factory with FFmpeg extension preference for broad codec support
			val renderersFactory = DefaultRenderersFactory(activityRef)
				.setExtensionRendererMode(EXTENSION_RENDERER_MODE_PREFER)  // Prefer FFmpeg extensions
				.forceEnableMediaCodecAsynchronousQueueing()  // Improved performance
				.setEnableDecoderFallback(true)  // Fallback to software decoders if needed
				.setEnableAudioTrackPlaybackParams(true)  // Support playback speed changes

			// region Track Selection
			// Track selector with English audio preference
			trackSelector = DefaultTrackSelector(activityRef).apply {
				setParameters(buildUponParameters().setPreferredAudioLanguage("eng"))
			}

			// region Player Construction
			// Build ExoPlayer with optimized configuration
			player = ExoPlayer.Builder(activityRef, renderersFactory)
				.setTrackSelector(trackSelector)  // Apply track selection preferences
				.setUseLazyPreparation(true)  // Delay preparation until needed
				.setHandleAudioBecomingNoisy(true)  // Pause when headphones disconnected
				.setSeekForwardIncrementMs(seekIntervalMs)  // Fast-forward interval
				.setSeekBackIncrementMs(seekIntervalMs)  // Rewind interval
				.build().apply {
					addListener(activityRef)  // Register activity as player event listener
					setForegroundMode(false)  // Standard playback mode
					setSeekParameters(CLOSEST_SYNC)  // Precise seeking behavior
				}

			// region Player View Setup
			// Attach player to PlayerView for video rendering
			playerView.player = player
			player.playbackParameters = PlaybackParameters(lastPlaybackSpeed)

			// region Subtitle Configuration
			// Configure subtitle appearance and rendering
			playerView.subtitleView?.apply {
				setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)  // Readable text size
				setApplyEmbeddedStyles(true)  // Honor embedded subtitle styles
				setApplyEmbeddedFontSizes(false)  // Use our fixed size instead
				setCues(emptyList())  // Clear any existing cues
				setStyle(
					CaptionStyleCompat(
						getColor(activityRef, color.transparent_white),  // Text color
						getColor(activityRef, color.transparent),  // Background color
						getColor(activityRef, color.transparent),  // Window color
						EDGE_TYPE_NONE,  // No text outline
						getColor(activityRef, color.color_primary_variant),  // Edge color
						Typeface.DEFAULT_BOLD  // Bold font for better readability
					)
				)
			}
		}
	}

	/**
	 * Creates a seek listener for the video scrubber/progress bar.
	 *
	 * This listener handles user interactions with the progress bar:
	 * - Scrubbing start (no immediate action needed)
	 * - Scrubbing stop with actual seek to the selected position
	 *
	 * @return A configured VideoScrubberListener instance
	 */
	private fun createScrubberSeekListener(): VideoScrubberListener {
		return object : VideoScrubberListener() {
			/**
			 * Called when user starts scrubbing the progress bar.
			 * No immediate action needed as we wait for final position.
			 */
			override fun onScrubStart(timeBar: TimeBar, position: Long) = Unit

			/**
			 * Called when user stops scrubbing the progress bar.
			 * Performs the actual seek to the selected position if not canceled.
			 *
			 * @param timeBar The time bar that was scrubbed
			 * @param position The selected playback position in milliseconds
			 * @param canceled True if the scrub was canceled, false if committed
			 */
			override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) {
				if (!canceled) player.seekTo(position)
			}
		}
	}

	/**
	 * Refreshes the displayed video title based on the currently playing media.
	 *
	 * This function extracts the file name from the current media URI and updates
	 * the UI to display it as the video title. If the media URI is empty or invalid,
	 * the function returns early without making changes.
	 *
	 * Flow:
	 * 1. Get current media item from player
	 * 2. Extract URI string and validate
	 * 3. Extract file name from URI path
	 * 4. Apply the extracted file name as video title
	 */
	private fun refreshCurrentVideoTitle() {
		// Retrieve the currently playing media item
		val mediaItem = player.currentMediaItem

		// Extract URI string from media item configuration
		val mediaUriString = mediaItem?.localConfiguration?.uri.toString()

		// Early return if no valid media URI is available
		if (mediaUriString.isEmpty()) return

		// Extract file name from URI path segment (last part of the path)
		val videoFileName = mediaUriString.toUri().lastPathSegment ?: return

		// Update UI with the extracted video file name
		applyVideoTitle(videoFileName)
	}

	/**
	 * Applies the video title to the UI text element if it has changed.
	 *
	 * This function safely updates the video title text view only when:
	 * - The text view is properly initialized
	 * - The new title is different from the current displayed title
	 *
	 * This prevents unnecessary UI updates and potential crashes from accessing
	 * uninitialized views.
	 *
	 * @param videoName The new video title to display
	 */
	private fun applyVideoTitle(videoName: CharSequence) {
		// Safely check if the videoTitleText view is initialized before accessing it
		::videoTitleText.isInitialized.takeIf { it }.let {
			// Only update the text if it's different from current value
			// This avoids unnecessary UI redraws and state changes
			if (videoTitleText.text != videoName) videoTitleText.text = videoName
		}
	}

	/**
	 * Keeps the screen awake during media playback.
	 * Uses window flags to prevent the screen from timing out.
	 */
	private fun keepScreenOn() {
		window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
	}

	/**
	 * Releases the screen wake lock when playback stops or activity pauses.
	 * Removes the keep screen on flag to allow normal screen timeout behavior.
	 */
	private fun releaseScreenOn() {
		window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
	}

	/**
	 * Retrieves the current playback position of the video player.
	 *
	 * This function provides the current position in milliseconds within the
	 * currently playing media. Useful for displaying progress, saving playback
	 * state, or implementing seek functionality.
	 *
	 * @return The current playback position in milliseconds
	 */
	private fun getPlaybackPosition(): Long = player.currentPosition

	/**
	 * Retrieves the total duration of the currently loaded video.
	 *
	 * This function returns the duration of the current media in milliseconds.
	 * Returns [C.TIME_UNSET] if the duration is not available yet.
	 * Useful for progress calculations and duration displays.
	 *
	 * @return The total video duration in milliseconds, or [C.TIME_UNSET] if unknown
	 */
	private fun getVideoDuration(): Long = player.duration

	/**
	 * Sets up comprehensive gesture controls for the video player view.
	 *
	 * This function handles multiple gesture types with proper isolation to prevent conflicts:
	 * - Pinch to zoom (two-finger gesture)
	 * - Horizontal swipe for seeking
	 * - Vertical swipe on left side for brightness control
	 * - Vertical swipe on right side for volume control
	 * - Single tap to toggle UI controls
	 * - Double tap to play/pause
	 * - Long press to temporarily pause
	 *
	 * @param targetView The view that will receive and process all gesture inputs
	 */
	private fun setupPlayerGestures(targetView: View) {
		// Flags for tracking gesture and playback states
		var isUserSeeking = false
		var startSeekPosition = 0L
		var wasPlayingBeforeSeek = false
		var isFingerScrolling = false
		var isLongPressTriggered = false
		val longPressDelay = 200L
		val longPressHandler = Handler(Looper.getMainLooper())

		// Zoom-related variables
		var scaleGestureDetector: ScaleGestureDetector?
		var currentScaleFactor = 1.0f
		var isZooming = false

		// System services for volume and screen brightness
		val audioManager = targetView.context.getSystemService(AUDIO_SERVICE) as AudioManager
		val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
		var initialVolume = 0

		val window = (targetView.context as Activity).window
		var initialBrightness = window.attributes.screenBrightness

		// Track gesture state
		var gestureDirection: String? = null
		var activeGesture: String? = null // Tracks which gesture is currently active
		var initialTouchX = 0f
		var initialTouchY = 0f
		var isTwoFingerTouch = false

		// Initialize scale gesture detector
		scaleGestureDetector = ScaleGestureDetector(targetView.context,
			object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
				override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
					if (areControllersLocked) return false

					isZooming = true
					activeGesture = "zoom"
					gestureDirection = null // Reset direction when zoom starts
					return true
				}

				override fun onScale(detector: ScaleGestureDetector): Boolean {
					if (areControllersLocked || !isZooming) return false

					currentScaleFactor *= detector.scaleFactor
					// Limit the scale factor between 1.0f (no zoom) and 3.0f (3x zoom)
					currentScaleFactor = currentScaleFactor.coerceIn(1.0f, 3.0f)

					applyZoomToPlayerView(currentScaleFactor)
					showZoomLevel(currentScaleFactor)
					return true
				}

				override fun onScaleEnd(detector: ScaleGestureDetector) {
					isZooming = false
					if (activeGesture == "zoom") {
						activeGesture = null
					}
				}
			})

		val gestureDetector = GestureDetector(
			targetView.context,
			object : GestureDetector.SimpleOnGestureListener() {

				// Called when user first touches the screen
				override fun onDown(e: MotionEvent): Boolean {
					if (areControllersLocked) return true

					// Don't start new gesture if zooming or two-finger touch
					if (isZooming || isTwoFingerTouch) return false

					// If a gesture is already active, don't start new one
					if (activeGesture != null && activeGesture != "scroll") return false

					isFingerScrolling = false
					isLongPressTriggered = false
					gestureDirection = null
					initialTouchX = e.x
					initialTouchY = e.y

					// Store initial values for volume and brightness
					initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
					initialBrightness = window.attributes.screenBrightness.takeIf { it >= 0 }
						?: 0.5f // fallback if screenBrightness is set to auto

					// Detect long press: pause video if holding without moving
					longPressHandler.postDelayed({
						if (activeGesture == null && !isFingerScrolling &&
							!isUserSeeking && !isZooming && !isTwoFingerTouch) {
							wasPlayingBeforeSeek = player.isPlaying
							if (wasPlayingBeforeSeek) {
								player.pause()
								isLongPressTriggered = true
								activeGesture = "longpress"
							}
						}
					}, longPressDelay)
					return true
				}

				// Called continuously while the user is moving their finger
				override fun onScroll(
					e1: MotionEvent?, e2: MotionEvent,
					distanceX: Float, distanceY: Float
				): Boolean {
					if (areControllersLocked || e1 == null) return true

					// Don't process scroll if zooming or two-finger touch
					if (isZooming || isTwoFingerTouch) return false

					// If another gesture is active and it's not scroll, don't process
					if (activeGesture != null && activeGesture != "scroll") return false

					longPressHandler.removeCallbacksAndMessages(null)

					// Compute movement delta from initial touch
					val totalDeltaX = e2.x - initialTouchX
					val totalDeltaY = e2.y - initialTouchY
					val screenWidth = targetView.width
					val screenHeight = targetView.height

					val movementThreshold = 15 // Reduced threshold for better responsiveness
					val directionConfidenceThreshold = 1.8f // Higher = more confident direction detection

					// Determine gesture direction only once with better logic
					if (gestureDirection == null) {
						val absDeltaX = abs(totalDeltaX)
						val absDeltaY = abs(totalDeltaY)

						// Require minimum movement before deciding direction
						if (absDeltaX < movementThreshold && absDeltaY < movementThreshold) {
							return false
						}

						// Improved direction detection with confidence
						val isClearlyVertical = absDeltaY > absDeltaX * directionConfidenceThreshold
						val isClearlyHorizontal = absDeltaX > absDeltaY * directionConfidenceThreshold

						gestureDirection = when {
							isClearlyVertical -> "vertical"
							isClearlyHorizontal -> "horizontal"
							else -> {
								// If ambiguous, prefer horizontal for video content
								if (absDeltaX > absDeltaY) "horizontal" else "vertical"
							}
						}
						activeGesture = "scroll"
					}

					when (gestureDirection) {
						"vertical" -> {
							// Use total movement for consistent control
							// Swipe from top to bottom decreases volume/brightness (negative deltaY)
							// Swipe from bottom to top increases volume/brightness (positive deltaY)
							val adjustmentFactor = 2f // Adjust this for sensitivity
							val totalDeltaPercent = (totalDeltaY / screenHeight) * adjustmentFactor

							if (e1.x < screenWidth / 2) {
								// Left side → brightness control
								val newBrightness = (initialBrightness - totalDeltaPercent).coerceIn(0.0f, 1.0f)
								val params = window.attributes
								params.screenBrightness = newBrightness
								window.attributes = params
								val progress = (newBrightness * 100).toInt()
								setBrightness(process = progress)
							} else {
								// Right side → volume control
								val volumeChange = (totalDeltaPercent * maxVolume).toInt()
								val newVolume = (initialVolume - volumeChange).coerceIn(0, maxVolume)
								audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, 0)
								val progress = (newVolume * 100) / maxVolume
								setVolume(process = progress)
							}
							return true
						}

						"horizontal" -> {
							// Handle video seeking (scrubbing)
							if (!isFingerScrolling) {
								isFingerScrolling = true
								wasPlayingBeforeSeek = player.isPlaying
								if (wasPlayingBeforeSeek) player.pause()
								isUserSeeking = true
								startSeekPosition = getPlaybackPosition()
							}

							val duration = getVideoDuration()
							if (isUserSeeking && duration > 0) {
								// Use total movement for more precise seeking
								val seekSensitivity = 60000f // Adjust this value to change seek speed
								val seekOffset = ((totalDeltaX / screenWidth) * seekSensitivity).toLong()
								val newSeekPosition =
									(startSeekPosition + seekOffset).coerceIn(0, getVideoDuration())
								mediaProgressBar.setPosition(newSeekPosition)
								mediaProgressBarLand.setPosition(newSeekPosition)
								player.seekTo(newSeekPosition)
								showQuickPlayerInfo(formatPlaybackTime(newSeekPosition))
								return true
							}
						}
					}
					return false
				}

				// Single tap toggles controller visibility
				override fun onSingleTapUp(e: MotionEvent): Boolean {
					// Only process if no other gesture is active and not from scroll
					if (activeGesture == null && !isFingerScrolling
						&& !isZooming && !isTwoFingerTouch) {
						handlePlaybackControllerVisibility(shouldTogglePlayback = false)
						return true
					}
					return false
				}

				// Double tap toggles play/pause
				override fun onDoubleTap(e: MotionEvent): Boolean {
					// Only process if no other gesture is active and not from scroll
					if (activeGesture == null && !isFingerScrolling
						&& !isZooming && !isTwoFingerTouch) {
						togglePlaybackState()
						return true
					}
					return false
				}
			})

		// Attach gesture listener to the target view
		targetView.setOnTouchListener { touchedView, event ->
			when (event.action and MotionEvent.ACTION_MASK) {
				MotionEvent.ACTION_DOWN -> {
					// Store initial touch position
					initialTouchX = event.x
					initialTouchY = event.y
					isTwoFingerTouch = false
				}
				MotionEvent.ACTION_POINTER_DOWN -> {
					// Second finger detected - this is likely a zoom gesture
					isTwoFingerTouch = true
					longPressHandler.removeCallbacksAndMessages(null)
					// Cancel any active scroll gesture
					if (activeGesture == "scroll") {
						activeGesture = null
						gestureDirection = null
					}
				}
				MotionEvent.ACTION_MOVE -> {
					// Check if we should cancel long press when movement is detected
					if (activeGesture == null &&
						(abs(event.x - initialTouchX) > 10 ||
								abs(event.y - initialTouchY) > 10)) {
						longPressHandler.removeCallbacksAndMessages(null)
					}
				}
				MotionEvent.ACTION_POINTER_UP -> {
					// When one finger is lifted but others remain
					if (event.pointerCount == 2) { // Still two fingers (3rd finger case)
						isTwoFingerTouch = true
					}
				}
			}

			// Always pass events to scale gesture detector first
			scaleGestureDetector.onTouchEvent(event)

			// Only handle other gestures if not zooming and not two-finger touch
			if (!isZooming && !isTwoFingerTouch) {
				gestureDetector.onTouchEvent(event)
			}

			when (event.action and MotionEvent.ACTION_MASK) {
				MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
					// Reset states when user lifts finger
					longPressHandler.removeCallbacksAndMessages(null)

					// Resume playback if user was seeking or long-press paused
					if (isUserSeeking) {
						isUserSeeking = false
						if (wasPlayingBeforeSeek) player.play()
					} else if (isLongPressTriggered) {
						isLongPressTriggered = false
						player.play()
					}

					// Reset gesture states
					gestureDirection = null
					activeGesture = null
					isFingerScrolling = false
					isTwoFingerTouch = false

					touchedView.performClick()
				}
			}
			true
		}
	}

	/**
	 * Applies zoom transformation to the player view with the specified scale factor.
	 *
	 * This function scales the player view uniformly on both X and Y axes and sets the pivot point
	 * to the center of the view for more natural zooming behavior around the center point.
	 *
	 * @param scaleFactor The scaling factor to apply (1.0f = normal size, 3.0f = 3x zoom)
	 */
	private fun applyZoomToPlayerView(scaleFactor: Float) {
		// Apply uniform scaling to both X and Y axes
		playerView.scaleX = scaleFactor
		playerView.scaleY = scaleFactor

		// Set pivot point to center for natural zooming behavior
		// Zooming will expand/contract from the center of the view
		playerView.pivotX = playerView.width / 2f
		playerView.pivotY = playerView.height / 2f
	}

	/**
	 * Displays the current zoom level as a percentage to the user.
	 *
	 * Shows a quick player info overlay with the current zoom percentage calculated
	 * from the scale factor (e.g., 1.5f scale = "Zoom: 150%").
	 *
	 * @param scaleFactor The current scale factor to convert to percentage
	 */
	private fun showZoomLevel(scaleFactor: Float) {
		// Convert scale factor to percentage (1.0 = 100%, 2.0 = 200%, etc.)
		val zoomPercent = (scaleFactor * 100).toInt().toString()

		// Display zoom level in the quick player info overlay
		showQuickPlayerInfo("Zoom: $zoomPercent%")
	}

	/**
	 * Retrieves the dimensions of the currently playing video.
	 *
	 * Extracts width and height information from the player's video format to determine
	 * the native resolution of the current video content.
	 *
	 * @return A Pair containing (width, height) in pixels, or null if video format is unavailable
	 */
	private fun getVideoSize(): Pair<Int, Int>? {
		// Get the video format from the player's current media
		val videoFormat = player.videoFormat

		// Return video dimensions if format is available, otherwise return null
		return if (videoFormat != null)
			Pair(videoFormat.width, videoFormat.height) else null
	}

	/**
	 * Handles playback completion by transitioning to the next downloaded video.
	 *
	 * This function is called when the current video finishes playing. It checks if the current
	 * media is part of downloaded content and automatically plays the next video in the downloaded
	 * media list. Skips this behavior for streaming videos.
	 *
	 * Flow:
	 * 1. Skip if currently playing streaming video
	 * 2. Extract URI of current media item
	 * 3. Find matching media in downloaded media list
	 * 4. Reset playback position and play the matched downloaded video
	 */
	private fun handlePlaybackCompletion() {
		// Skip playback completion handling for streaming videos
		if (isStreamingVideoPlaying()) {
			releaseScreenOn() // Release screen lock for streaming completion
			return
		}

		// Extract URI from current media item for matching
		val currentMediaItem = player.currentMediaItem
		val mediaUri = currentMediaItem?.localConfiguration?.uri.toString()

		// Return if no valid media URI is available
		if (mediaUri.isEmpty()) {
			releaseScreenOn()
			return
		}

		// Retrieve all valid downloaded media for matching
		val downloadedMediaList = fetchAllValidMediaDownloads()

		// Find index of current media in downloaded media list
		val matchedMediaIndex = findMatchingModelIndex(mediaUri, downloadedMediaList)

		// Return if no matching media found in downloads
		if (matchedMediaIndex == -1) {
			releaseScreenOn()
			return
		}

		// Reset playback position and play the next downloaded video
		currentPlaybackPosition = 0
		playDownloadedVideo(downloadedMediaList[matchedMediaIndex])
	}

	/**
	 * Initiates video playback based on the incoming intent data.
	 *
	 * This function serves as the entry point for video playback, handling two main scenarios:
	 * 1. Playing downloaded videos from local storage
	 * 2. Streaming videos from remote URLs
	 *
	 * Priority is given to downloaded videos if both types of data are present in the intent.
	 *
	 * Flow:
	 * 1. Attempt to extract and play downloaded video model from intent
	 * 2. If no downloaded video, check for streaming URL (with SDK version compatibility)
	 * 3. Start streaming playback if URL is available
	 */
	private fun playVideoFromIntent() {
		// First priority: Try to play downloaded video from intent data
		extractDownloadModelFromIntent()?.let { playDownloadedVideo(it) } ?: run {
			// Fallback: Check for streaming URL in intent
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
				// Use type-safe parcelable extra for Android 13+ (Tiramisu)
				intent.getParcelableExtra(INTENT_EXTRA_STREAM_URL, Uri::class.java)
					?.let { startStreamingPlayback(it); return }
			} else {
				// Use legacy string extra for older Android versions
				intent.getStringExtra(INTENT_EXTRA_STREAM_URL)?.let {
					streamVideoFromUrl(it)
				}
			}
		}
	}

	/**
	 * Starts streaming video playback from a remote URI.
	 *
	 * This function sets up the player for streaming content, applies the appropriate
	 * video title, and prepares the media item for playback. Also handles album art
	 * display for audio files if applicable.
	 *
	 * @param fileUri The remote URI of the streaming video/audio content
	 */
	private fun startStreamingPlayback(fileUri: Uri) {
		// Set video title from intent extra or extract from URI
		val videoTitle = intent.getStringExtra(INTENT_EXTRA_STREAM_TITLE)
		if (!videoTitle.isNullOrEmpty()) applyVideoTitle(videoName = videoTitle)
		else getFileFromUri(fileUri)?.let { applyVideoTitle(videoName = it.name) }

		// Create and set up media item for streaming
		val mediaItem = MediaItem.fromUri(fileUri)
		player.setMediaItem(mediaItem)
		player.prepare()
		resumePlayback()

		// Handle album art display for audio files
		val downloadModel = extractDownloadModelFromIntent()
		val audioFile = downloadModel?.getDestinationDocumentFile()
		showAlbumArtIfAudio(audioFile)
	}

	/**
	 * Extracts the download model from the incoming intent for local video playback.
	 *
	 * This function retrieves the download ID from intent extras and searches for
	 * the corresponding download model in the system's finished downloads list.
	 *
	 * @return The DownloadDataModel if found, null otherwise
	 */
	private fun extractDownloadModelFromIntent(): DownloadDataModel? {
		intent?.let { incomingIntent ->
			// Retrieve download ID from intent extras (-1 indicates not found)
			val downloadModelID = incomingIntent.getIntExtra(DOWNLOAD_MODEL_ID_KEY, -1)

			// Search for matching download model in finished downloads
			if (downloadModelID > -1) downloadSystem.finishedDownloadDataModels
				.find { it.downloadId == downloadModelID }?.let { return it }
		}; return null
	}

	/**
	 * Plays a downloaded video from the local file system.
	 *
	 * This function verifies the existence of the downloaded video file and
	 * initiates playback. Shows an error message if the file doesn't exist.
	 *
	 * @param downloadModel The download data model containing file location information
	 */
	private fun playDownloadedVideo(downloadModel: DownloadDataModel) {
		// Construct file path from download model data
		val videoFile = fromFile(File("${downloadModel.fileDirectory}/${downloadModel.fileName}"))

		// Verify file exists before attempting playback
		if (videoFile.exists()) {
			playVideoFile(mediaFile = videoFile, subtitleFile = null)
		} else {
			// Show error message if downloaded file is missing
			showQuickPlayerInfo(msgText = getString(string.title_media_file_not_existed))
		}
	}

	/**
	 * Plays a video file with optional subtitle support.
	 *
	 * This function handles the complete playback setup for local video files,
	 * including subtitle integration if available. It performs proper player
	 * lifecycle management by stopping and reinitializing the player.
	 *
	 * @param mediaFile The video file to play
	 * @param subtitleFile Optional subtitle file to display with the video
	 */
	private fun playVideoFile(mediaFile: DocumentFile, subtitleFile: DocumentFile?) {
		// Check if media file is writable (indicates valid access)
		mediaFile.canWrite().let {
			// Clean up existing player instance and initialize fresh one
			stopAndDisposePlayer()
			initializePlayer()

			// Handle playback with or without subtitles
			subtitleFile?.let {
				// Build media item with integrated subtitles
				val mediaWithSubtitles = buildMediaItemWithSubtitles(
					mediaDocument = mediaFile, subtitleDocument = subtitleFile)
				player.setMediaItem(mediaWithSubtitles).apply {
					prepareAndStartPlayback(mediaFile)
				}
			} ?: run {
				// Build media item without subtitles
				val mediaItem = buildMediaItemFromUri(mediaFile.uri)
				player.setMediaItem(mediaItem)
					.apply { prepareAndStartPlayback(mediaFile) }
			}
		}
	}

	/**
	 * Initiates video streaming from a remote URL with comprehensive validation and error handling.
	 *
	 * This function handles the complete streaming workflow including:
	 * - URL validation to ensure the stream link is properly formatted
	 * - User-friendly error dialogs for invalid URLs
	 * - Video title extraction and application
	 * - Media player setup and playback initiation
	 * - Album art display for audio streams
	 *
	 * If the URL is invalid, shows a dialog that allows the user to exit the player safely.
	 *
	 * @param fileUrl The remote URL string of the video/audio stream to play
	 */
	private fun streamVideoFromUrl(fileUrl: String) {
		// Ensure activity reference is available before proceeding
		selfActivityRef?.let { activityRef ->
			// Validate URL format before attempting playback
			if (!isValidURL(fileUrl)) {
				// Show error dialog for invalid streaming URL
				getMessageDialog(
					baseActivityInf = activityRef,
					isCancelable = false, // Force user to take action
					titleText = getString(string.title_invalid_streaming_link),
					isNegativeButtonVisible = false, // Only show exit option
					isTitleVisible = true,
					positiveButtonTextCustomize = {
						// Add exit icon to the button for clear user intent
						it.setLeftSideDrawable(drawable.ic_button_exit)
					},
					positiveButtonText = getString(string.title_exit_the_player),
					titleTextViewCustomize = {
						// Use error color for the title to indicate problem
						val color = resources.getColor(color.color_error, theme)
						it.setTextColor(color)
					},
					messageTextViewCustomize = {
						// Set descriptive error message
						val messageString = getString(string.text_streaming_link_invalid)
						it.text = messageString
					},
					dialogBuilderCustomize = { dialogBuilder ->
						// Handle exit button click - close dialog and activity
						dialogBuilder.setOnClickForPositiveButton {
							dialogBuilder.close()
							closeActivityWithFadeAnimation() // Smooth exit with animation
						}
					}
				)?.show()
				return // Stop execution for invalid URL
			}

			// Set video title from intent extra or extract from URL
			val streamTitle = intent.getStringExtra(INTENT_EXTRA_STREAM_TITLE)
			if (!streamTitle.isNullOrEmpty()) {
				applyVideoTitle(videoName = streamTitle)
			} else {
				// Extract title from URL if not provided in intent
				updateVideoTitleFromUrl(fileUrl)
			}

			// Set up and start media playback
			val mediaItem = MediaItem.fromUri(fileUrl)
			player.setMediaItem(mediaItem)
			player.prepare()
			resumePlayback()

			// Handle album art display for audio content
			val downloadModel = extractDownloadModelFromIntent()
			val audioFile = downloadModel?.getDestinationDocumentFile()
			showAlbumArtIfAudio(audioFile)
		}
	}

	/**
	 * Updates the video title by extracting information from the streaming URL.
	 *
	 * This function attempts to determine the appropriate title through multiple methods:
	 * 1. First checks active download models for a matching download ID
	 * 2. Falls back to querying server for file information
	 * 3. Updates UI on main thread once title is determined
	 *
	 * The operation runs on a background thread to avoid blocking the UI during
	 * network operations or complex processing.
	 *
	 * @param fileUrl The streaming URL to extract title information from
	 */
	private fun updateVideoTitleFromUrl(fileUrl: String) {
		ThreadsUtility.executeInBackground(codeBlock = {
			// Method 1: Check active download models for title
			val downloadId = intent.getIntExtra(INTENT_EXTRA_DOWNLOAD_ID, -1)
			downloadSystem.activeDownloadDataModels.firstOrNull {
				it.downloadId == downloadId
			}?.let { downloadModel ->
				// Use filename from download model if available
				executeOnMainThread { videoTitleText.text = downloadModel.fileName }
			} ?: run {
				// Method 2: Query server for file information
				getFileInfoFromSever(URL(fileUrl)).let { fileInfo ->
					if (fileInfo.fileName.isNotEmpty()) {
						// Use server-provided filename
						executeOnMainThread {
							videoTitleText.text = fileInfo.fileName
						}
					}
					// If both methods fail, title remains unchanged
				}
			}
		})
	}

	/**
	 * Builds a basic MediaItem from a URI for standard video/audio playback.
	 *
	 * This is a convenience method that creates a MediaItem with minimal configuration,
	 * suitable for most basic playback scenarios without subtitles or complex metadata.
	 *
	 * @param mediaUri The URI of the media file (local or remote)
	 * @return A configured MediaItem ready for ExoPlayer playback
	 */
	private fun buildMediaItemFromUri(mediaUri: Uri): MediaItem =
		Builder().setUri(mediaUri).build()

	/**
	 * Prepares the player and initiates playback with associated UI updates.
	 *
	 * This function handles the complete playback startup sequence including:
	 * - Preparing the player for media playback
	 * - Resuming playback from current position
	 * - Displaying album art for audio files
	 *
	 * @param mediaFile The media file being played (used for album art detection)
	 */
	private fun prepareAndStartPlayback(mediaFile: DocumentFile) {
		player.prepare()      // Prepare the player for media playback
		resumePlayback()      // Start or resume playback
		showAlbumArtIfAudio(mediaFile)  // Handle album art display for audio files
	}

	/**
	 * Builds a MediaItem with integrated subtitle tracks for video playback.
	 *
	 * This method creates a media item that includes external subtitle file support,
	 * allowing users to see subtitles during video playback. The subtitles are
	 * configured with standard SRT (SubRip) format support.
	 *
	 * @param mediaDocument The main video media file
	 * @param subtitleDocument The external subtitle file (typically .srt format)
	 * @return A MediaItem configured with both video and subtitle tracks
	 */
	private fun buildMediaItemWithSubtitles(
		mediaDocument: DocumentFile,
		subtitleDocument: DocumentFile
	): MediaItem {
		// Configure subtitle track with SRT mime type and default selection
		val subtitleConfigs = listOf(
			SubtitleConfiguration.Builder(subtitleDocument.uri)
				.setMimeType(MimeTypes.APPLICATION_SUBRIP)  // Standard SRT subtitle format
				.setSelectionFlags(C.SELECTION_FLAG_DEFAULT) // Default track selection behavior
				.build()
		)

		// Build media item with both video URI and subtitle configurations
		return Builder()
			.setUri(mediaDocument.uri)              // Main video content
			.setSubtitleConfigurations(subtitleConfigs) // Associated subtitle tracks
			.build()
	}

	/**
	 * Checks if the player currently has active subtitle tracks enabled.
	 *
	 * This function examines the player's current track selection to determine
	 * if any text/subtitle tracks are both available and currently selected
	 * for display during playback.
	 *
	 * @param player The ExoPlayer instance to check for subtitle tracks
	 * @return True if subtitles are available and currently active, false otherwise
	 */
	private fun playerHasActiveSubtitles(player: ExoPlayer): Boolean {
		val trackGroups = player.currentTracks.groups

		// Iterate through all track groups to find active text/subtitle tracks
		for (trackGroup in trackGroups) {
			if (trackGroup.type == C.TRACK_TYPE_TEXT && trackGroup.isSelected) {
				return true  // Found active subtitle track
			}
		}
		return false  // No active subtitle tracks found
	}

	/**
	 * Determines if the media file is audio-only and displays appropriate artwork.
	 *
	 * This function uses MediaMetadataRetriever to analyze the media file and:
	 * - Detects audio-only files by checking for missing video dimensions
	 * - Shows album art and audio visualizer for audio files
	 * - Hides visual elements for video files
	 * - Falls back to default audio artwork when no embedded art is found
	 *
	 * @param mediaFile The media file to analyze for audio/video characteristics
	 */
	private fun showAlbumArtIfAudio(mediaFile: DocumentFile?) {
		selfActivityRef?.let { safeActivityRef ->
			mediaFile?.let {
				val retriever = MediaMetadataRetriever()
				val file = mediaFile.getAbsolutePath(safeActivityRef)
				retriever.setDataSource(file)

				// Extract video dimensions to determine media type
				val width = retriever.extractMetadata(METADATA_KEY_VIDEO_WIDTH)
				val height = retriever.extractMetadata(METADATA_KEY_VIDEO_HEIGHT)

				// If no video dimensions found, treat as audio file
				if (width == null && height == null) {
					displayDefaultAudioArtwork(mediaFile)
					return
				}

				// Additional check to confirm audio file type
				if (!isAudio(mediaFile)) {
					// Hide audio-specific UI elements for video files
					hideView(audioVisualizerView, true)
					hideView(albumArtView, true)
					return
				}

				// Display audio artwork for confirmed audio files
				displayDefaultAudioArtwork(mediaFile)
			}
		}
	}

	/**
	 * Displays the default audio artwork and visualizer for audio playback.
	 *
	 * This function shows the audio-specific UI elements and attempts to load
	 * album artwork from the audio file's metadata. If no embedded artwork is
	 * found, a default placeholder is typically displayed.
	 *
	 * @param mediaFile The audio file from which to extract album art
	 */
	private fun displayDefaultAudioArtwork(mediaFile: DocumentFile) {
		selfActivityRef?.let { safeActivityRef ->
			// Show audio-specific UI elements with animation
			showView(audioVisualizerView, shouldAnimate = true)
			showView(albumArtView, shouldAnimate = true)

			// Extract file path and attempt to load embedded album artwork
			val audioFilePath = mediaFile.getAbsolutePath(safeActivityRef)
			loadAlbumArtwork(audioFilePath, albumArtView)
		}
	}

	/**
	 * Loads and displays album artwork for audio files with multiple fallback strategies.
	 *
	 * This function attempts to extract album art in the following priority order:
	 * 1. Embedded artwork from the audio file's metadata
	 * 2. External thumbnail file associated with the download
	 * 3. Default audio thumbnail resource as final fallback
	 *
	 * Handles errors gracefully and ensures MediaMetadataRetriever is properly released.
	 *
	 * @param audioFilePath The file system path to the audio file
	 * @param imageView The ImageView where the artwork should be displayed
	 */
	private fun loadAlbumArtwork(audioFilePath: String?, imageView: ImageView) {
		val metadataRetriever = MediaMetadataRetriever()
		try {
			metadataRetriever.setDataSource(audioFilePath)
			val embeddedArt = metadataRetriever.embeddedPicture

			// Priority 1: Use embedded artwork from audio file metadata
			if (embeddedArt != null) {
				val albumArtBitmap = decodeByteArray(embeddedArt, 0, embeddedArt.size)
				displayBlurredArtwork(imageView, albumArtBitmap)
			} else {
				// Priority 2: Check for external thumbnail file from download
				getCurrentPlayingDownload()?.let { currentDownloadModel ->
					if (currentDownloadModel.thumbPath.isNotEmpty()) {
						val bitmapFile = File(currentDownloadModel.thumbPath)
						val originalBitmap = ViewUtility.getBitmapFromFile(bitmapFile)
						originalBitmap?.let {
							displayBlurredArtwork(imageView, originalBitmap)
						} ?: run {
							// Fallback: Load thumbnail directly from URI
							val thumbImageUri = Uri.fromFile(bitmapFile)
							imageView.setImageURI(thumbImageUri)
						}
					} else {
						// Priority 3: Use default audio thumbnail
						imageView.setImageResource(drawable.image_audio_thumb)
					}
				} ?: run {
					// No download model found, use default thumbnail
					imageView.setImageResource(drawable.image_audio_thumb)
				}
			}
		} catch (error: Exception) {
			// Log error and show default thumbnail on failure
			logger.e("Error found in loading audio album artwork:", error)
			imageView.setImageResource(drawable.image_audio_thumb)
		} finally {
			// Always release MediaMetadataRetriever to prevent resource leaks
			metadataRetriever.release()
		}
	}

	/**
	 * Displays album artwork with optional blur effect for landscape-oriented images.
	 *
	 * This function processes album art bitmaps to ensure optimal display:
	 * - Portrait images are displayed directly without modification
	 * - Landscape images are rotated and blurred for aesthetic presentation
	 * - Processing occurs on background thread to prevent UI blocking
	 *
	 * @param targetImageView The ImageView to display the processed artwork
	 * @param originalBitmap The source bitmap to process and display
	 */
	private fun displayBlurredArtwork(targetImageView: ImageView, originalBitmap: Bitmap) {
		ThreadsUtility.executeInBackground(codeBlock = {
			val isPortrait = originalBitmap.height > originalBitmap.width

			if (isPortrait) {
				// Display portrait images directly without processing
				targetImageView.setImageBitmap(originalBitmap)
			} else {
				// Process landscape images: rotate and apply blur effect
				rotateBitmap(originalBitmap, 90f).let { rotatedBitmap ->
					val blurredBitmap = blurBitmap(bitmap = rotatedBitmap, radius = 15f)
					ThreadsUtility.executeOnMain {
						targetImageView.setImageBitmap(blurredBitmap)
					}
				}
			}
		})
	}

	/**
	 * Safely stops and releases the media player resources.
	 *
	 * This function ensures proper cleanup of the ExoPlayer instance while
	 * checking activity state to prevent operations on destroyed activities.
	 * Essential for preventing memory leaks and resource retention.
	 */
	private fun stopAndDisposePlayer() {
		if (!isFinishing && !isDestroyed) {
			player.stop()    // Stop playback immediately
			player.release() // Release all player resources
		}
	}

	/**
	 * Toggles the playback state between play and pause.
	 *
	 * Simple utility function that provides one-touch play/pause functionality.
	 * If the player is currently playing, it will pause; if paused, it will play.
	 */
	private fun togglePlaybackState() {
		if (player.isPlaying) player.pause()
		else player.play()
	}

	/**
	 * Attempts to play the next video in the playback queue.
	 *
	 * This function handles the next video navigation with the following logic:
	 * - For streaming videos: Shows message that next item is unavailable
	 * - For local downloads: Finds and plays the next video in downloaded list
	 * - Handles private video authentication requirements
	 *
	 * Skips operation if player is not properly initialized.
	 */
	private fun playNextVideoItem() {
		if (!::player.isInitialized) return

		// Streaming videos don't support next item navigation
		if (isStreamingVideoPlaying()) {
			val infoText = getString(string.title_no_next_item_to_play)
			showQuickPlayerInfo(infoText)
			return
		}

		// Extract current media URI to find position in download list
		val currentMediaUri = player.currentMediaItem?.localConfiguration?.uri.toString()
		if (currentMediaUri.isEmpty()) return

		playNextFromCompletedDownloads(currentMediaUri)
	}

	/**
	 * Handles the complex logic for playing the next downloaded video.
	 *
	 * This function manages several scenarios for next video playback:
	 * 1. Private video access requiring user authentication
	 * 2. Skipping private videos when access is denied
	 * 3. Finding the next available non-private video
	 * 4. Showing appropriate messages when no next video is available
	 *
	 * @param currentMediaUri The URI of the currently playing video for position reference
	 */
	private fun playNextFromCompletedDownloads(currentMediaUri: String) {
		val downloadedVideos = fetchAllValidMediaDownloads()
		val currentIndex = findMatchingModelIndex(currentMediaUri, downloadedVideos)

		if (currentIndex != -1) {
			val nextIndex = currentIndex + 1

			// Check if there are more videos in the list
			if (downloadedVideos.size > nextIndex) {
				val candidate = downloadedVideos[nextIndex]
				val downloadLocation = candidate.globalSettings.defaultDownloadLocation
				val isPrivateVideo = downloadLocation == AIOSettings.PRIVATE_FOLDER

				// Handle private video access decision flow
				if (isPrivateVideo && !hasMadeDecisionOverPrivateAccess) {
					// Show authentication prompt for private videos
					getMessageDialog(
						baseActivityInf = selfActivityRef,
						isTitleVisible = true,
						titleText = getText(string.title_private_videos_alert),
						messageTxt = getString(string.text_prompt_skip_or_play_private_video),
						isNegativeButtonVisible = true,
						negativeButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_cancel)
							it.text = getString(string.title_not_now)
						},
						positiveButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_media_play)
							it.text = getString(string.title_unlock_and_play_all)
						}
					)?.apply {
						setOnClickForNegativeButton {
							close()
							isPrivateSessionAllowed = false
							hasMadeDecisionOverPrivateAccess = true
							playNextVideoItem() // Retry with new permissions
						}

						setOnClickForPositiveButton {
							close()
							authenticate(activity = selfActivityRef, onResult = { isSuccess ->
								if (isSuccess) {
									isPrivateSessionAllowed = true
									hasMadeDecisionOverPrivateAccess = true
									playNextVideoItem() // Retry with authentication
								} else {
									selfActivityRef?.doSomeVibration(50)
									showToast(selfActivityRef, msgId = string.title_authentication_failed)
								}
							})
						}
					}?.show()
				} else {
					// User has already made decision about private access
					if (isPrivateSessionAllowed) {
						playDownloadedVideo(candidate)
					} else {
						// Skip private videos and find next non-private video
						if (currentIndex != -1) {
							var nextIndex = currentIndex + 1
							var nextDownloadDataModel: DownloadDataModel? = null

							// Search for next non-private video
							while (nextIndex < downloadedVideos.size) {
								val candidate = downloadedVideos[nextIndex]
								val downloadLocation = candidate.globalSettings.defaultDownloadLocation
								if (downloadLocation != AIOSettings.PRIVATE_FOLDER) {
									nextDownloadDataModel = candidate
									break
								}
								nextIndex++
							}

							// Play found video or show no-next-item message
							if (nextDownloadDataModel != null) {
								playDownloadedVideo(nextDownloadDataModel)
							} else {
								val infoText = getString(string.title_no_next_item_to_play)
								showQuickPlayerInfo(infoText)
							}
						}
					}
				}
			} else {
				// End of playlist reached
				val infoText = getString(string.title_no_next_item_to_play)
				showQuickPlayerInfo(infoText)
			}
		}
	}

	/**
	 * Attempts to play the previous video in the playback queue.
	 *
	 * This function handles the previous video navigation with the following logic:
	 * - For streaming videos: Shows message that previous item is unavailable
	 * - For local downloads: Finds and plays the previous video in downloaded list
	 * - Handles private video authentication requirements similar to next navigation
	 *
	 * Skips operation if player is not properly initialized.
	 */
	private fun playPreviousVideoItem() {
		// Ensure player is initialized before attempting navigation
		if (!::player.isInitialized) return

		// Streaming videos don't support previous item navigation
		if (isStreamingVideoPlaying()) {
			val infoText = getString(string.title_no_previous_item_to_play)
			showQuickPlayerInfo(infoText)
			return
		}

		// Extract current media URI to find position in download list
		val currentItem = player.currentMediaItem
		val currentMediaUri = currentItem?.localConfiguration?.uri.toString()
		if (currentMediaUri.isEmpty()) return

		// Delegate to the complex previous video selection logic
		playPreviousFromCompletedDownloads(currentMediaUri)
	}

	/**
	 * Handles the complex logic for playing the previous downloaded video.
	 *
	 * This function manages several scenarios for previous video playback:
	 * 1. Private video access requiring user authentication
	 * 2. Skipping private videos when access is denied
	 * 3. Finding the previous available non-private video
	 * 4. Showing appropriate messages when no previous video is available
	 *
	 * The logic mirrors playNextFromCompletedDownloads but searches backwards through the list.
	 *
	 * @param currentMediaUri The URI of the currently playing video for position reference
	 */
	private fun playPreviousFromCompletedDownloads(currentMediaUri: String) {
		val downloadedVideos = fetchAllValidMediaDownloads()
		val currentIndex = findMatchingModelIndex(currentMediaUri, downloadedVideos)

		if (currentIndex != -1) {
			val previousIndex = currentIndex - 1

			// Check if there are videos before the current one
			if (previousIndex > -1) {
				val candidate = downloadedVideos[previousIndex]
				val downloadLocation = candidate.globalSettings.defaultDownloadLocation
				val isPrivateVideo = downloadLocation == AIOSettings.PRIVATE_FOLDER

				// Handle private video access decision flow (same as next navigation)
				if (isPrivateVideo && !hasMadeDecisionOverPrivateAccess) {
					// Show authentication prompt for private videos
					getMessageDialog(
						baseActivityInf = selfActivityRef,
						isTitleVisible = true,
						titleText = getText(string.title_private_videos_alert),
						messageTxt = getString(string.text_prompt_skip_or_play_private_video),
						isNegativeButtonVisible = true,
						negativeButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_cancel)
							it.text = getString(string.title_not_now)
						},
						positiveButtonTextCustomize = {
							it.setLeftSideDrawable(drawable.ic_button_media_play)
							it.text = getString(string.title_unlock_and_play_all)
						}
					)?.apply {
						setOnClickForNegativeButton {
							close()
							isPrivateSessionAllowed = false
							hasMadeDecisionOverPrivateAccess = true
							// Retry previous navigation with new permissions
							playPreviousVideoItem()
						}

						setOnClickForPositiveButton {
							close()
							authenticate(activity = selfActivityRef, onResult = { isSuccess ->
								if (isSuccess) {
									isPrivateSessionAllowed = true
									hasMadeDecisionOverPrivateAccess = true
									// Retry previous navigation with authentication
									playPreviousVideoItem()
								} else {
									// Provide feedback for authentication failure
									selfActivityRef?.doSomeVibration(50)
									showToast(selfActivityRef, msgId = string.title_authentication_failed)
								}
							})
						}
					}?.show()
				} else {
					// User has already made decision about private access
					if (isPrivateSessionAllowed) {
						// Play the private video directly if access is granted
						playDownloadedVideo(candidate)
					} else {
						// Skip private videos and find previous non-private video
						if (currentIndex != -1) {
							var previousIndex = currentIndex - 1
							var previousDownloadDataModel: DownloadDataModel? = null

							// Search backwards for previous non-private video
							while (previousIndex >= 0) {
								val candidate = downloadedVideos[previousIndex]
								val downloadLocation = candidate.globalSettings.defaultDownloadLocation
								if (downloadLocation != AIOSettings.PRIVATE_FOLDER) {
									previousDownloadDataModel = candidate
									break
								}
								previousIndex--
							}

							// Play found video or show no-previous-item message
							if (previousDownloadDataModel != null) {
								playDownloadedVideo(previousDownloadDataModel)
							} else {
								val infoText = getString(string.title_no_previous_item_to_play)
								showQuickPlayerInfo(infoText)
							}
						}
					}
				}
			} else {
				// Beginning of playlist reached - no previous items
				val infoText = getString(string.title_no_previous_item_to_play)
				showQuickPlayerInfo(infoText)
			}
		}
	}

	/**
	 * Finds the index of a DownloadDataModel that matches the target file URI.
	 *
	 * This function compares URI paths to locate the specific download model
	 * that corresponds to the currently playing media file. Used for navigation
	 * between downloaded videos in the playlist.
	 *
	 * @param targetFileUri The URI string of the target media file to match
	 * @param dataModels List of download data models to search through
	 * @return The index of the matching model, or -1 if no match found
	 */
	private fun findMatchingModelIndex(targetFileUri: String, dataModels: List<DownloadDataModel>): Int {
		// Extract the path component from the target URI for comparison
		val targetPath = targetFileUri.toUri().path

		// Find first model whose destination file path matches the target path
		return dataModels.indexOfFirst { model ->
			val modelFilePath = Uri.fromFile(model.getDestinationFile()).path
			modelFilePath == targetPath
		}
	}

	/**
	 * Retrieves all valid media downloads that are ready for playback.
	 *
	 * This function filters the finished downloads to include only media files
	 * that actually exist on the file system and are valid audio/video files.
	 * Also ensures uniqueness by URI to prevent duplicate entries.
	 *
	 * @return A filtered list of valid, existing media downloads
	 */
	private fun fetchAllValidMediaDownloads(): List<DownloadDataModel> {
		return downloadSystem.finishedDownloadDataModels
			.filter { model ->
				val modelDestinationFile = model.getDestinationDocumentFile()
				// Only include files that exist and are valid media files
				modelDestinationFile.exists() && isMediaDocument(modelDestinationFile)
			}.distinctBy {
				// Remove duplicates based on file URI
				it.getDestinationDocumentFile().uri
			}
	}

	/**
	 * Determines if a DocumentFile is a valid media file (audio or video).
	 *
	 * This utility function checks the MIME type or file extension to
	 * identify if the file is a supported media format for playback.
	 *
	 * @param modelDestinationFile The DocumentFile to check
	 * @return True if the file is an audio or video file, false otherwise
	 */
	private fun isMediaDocument(modelDestinationFile: DocumentFile): Boolean {
		return (isAudio(modelDestinationFile) || isVideo(modelDestinationFile))
	}

	/**
	 * Updates all playback progress-related UI elements simultaneously.
	 *
	 * This function synchronizes the following UI components with current playback state:
	 * - Current time display (portrait and landscape)
	 * - Total duration display (with fallback for invalid durations)
	 * - Progress bar positions (current, buffered, and total duration)
	 * - Current playback position storage
	 *
	 * Should be called frequently during playback for smooth UI updates.
	 */
	private fun refreshPlaybackProgressUI() {
		// Update current time displays
		currentTimeText.text = formatPlaybackTime(player.currentPosition)
		currentTimeTextLand.text = formatPlaybackTime(player.currentPosition)

		// Update duration displays with validation
		val formattedDuration = formatPlaybackTime(player.duration)
		if (!formattedDuration.startsWith("-")) {
			// Valid duration - use formatted time
			durationText.text = formattedDuration
			durationTextLand.text = formattedDuration
		} else {
			// Invalid duration (negative) - show default placeholder
			durationText.text = getString(string.title_00_00)
			durationTextLand.text = getString(string.title_00_00)
		}

		// Extract current playback metrics
		val totalDuration = player.duration
		val currentPos = player.currentPosition
		val bufferedPos = player.bufferedPosition
		currentPlaybackPosition = currentPos

		// Update progress bars with current state
		mediaProgressBar.setDuration(totalDuration)
		mediaProgressBarLand.setDuration(totalDuration)

		mediaProgressBar.setPosition(currentPos)
		mediaProgressBarLand.setPosition(currentPos)

		mediaProgressBar.setBufferedPosition(bufferedPos)
		mediaProgressBarLand.setBufferedPosition(bufferedPos)
	}

	/**
	 * Handles the visibility and behavior of playback controls based on user interaction.
	 *
	 * This function manages the complex tap behavior on the video surface:
	 * - Single tap: Toggle controls visibility
	 * - Double tap: Toggle play/pause state
	 * - Locked state: Show unlock button instead of controls
	 *
	 * Uses a click counter with timeout to distinguish between single and double taps.
	 *
	 * @param shouldTogglePlayback Whether double tap should toggle play/pause (default: true)
	 */
	private fun handlePlaybackControllerVisibility(shouldTogglePlayback: Boolean = true) {
		if (areControllersLocked) {
			// Controls are locked - show unlock button
			toggleViewVisibility(unlockButton, true)
		} else {
			// Controls are unlocked - handle tap behavior
			invisibleAreaClickCount++
			if (invisibleAreaClickCount > 1) {
				// Double tap detected - toggle playback if requested
				if (shouldTogglePlayback) togglePlaybackState()
				invisibleAreaClickCount = 0
			} else {
				// Single tap detected - toggle controls visibility
				toggleViewVisibility(playbackController, true)
			}

			// Reset click counter after delay to detect new tap sequences
			delay(timeInMile = 300, listener = object : OnTaskFinishListener {
				override fun afterDelay() {
					invisibleAreaClickCount = 0
				}
			})
		}
	}

	/**
	 * Shows the playback controls with optional animation.
	 *
	 * This function displays the main playback control interface including
	 * play/pause button, progress bar, and other media controls.
	 */
	private fun showPlaybackControls() {
		showView(shouldAnimate = true, targetView = playbackController)
	}

	/**
	 * Hides the playback controls with optional animation.
	 *
	 * This function conceals the playback control interface to provide
	 * an immersive viewing experience when not actively controlling playback.
	 */
	private fun hidePlaybackControls() {
		hideView(shouldAnimate = true, targetView = playbackController)
	}

	/**
	 * Updates the play/pause button icon and audio visualizer state.
	 *
	 * This function synchronizes the visual state of play/pause buttons
	 * in both portrait and landscape orientations with the actual playback state.
	 * Also controls the audio visualizer animation based on playback.
	 *
	 * @param isCurrentlyPlaying True if media is currently playing, false if paused
	 */
	private fun updatePlayPauseIcon(isCurrentlyPlaying: Boolean) {
		// Determine appropriate icon based on playback state
		val iconResId = if (isCurrentlyPlaying) drawable.ic_button_media_pause
		else drawable.ic_button_media_play

		// Get references to both orientation's buttons
		val buttonViewId = id.btn_img_video_play_pause_toggle
		val buttonViewIdLand = id.btn_img_video_play_pause_toggle_land

		// Update icons in both orientations
		(findViewById<ImageView>(buttonViewId)).setImageResource(iconResId)
		(findViewById<ImageView>(buttonViewIdLand)).setImageResource(iconResId)

		// Control audio visualizer animation
		if (isCurrentlyPlaying) audioVisualizerView.resumeAnimation()
		else audioVisualizerView.pauseAnimation()
	}

	/**
	 * Locks the playback controls and screen orientation for immersive viewing.
	 *
	 * This function:
	 * - Locks screen orientation to prevent accidental rotation
	 * - Hides playback controls for clean viewing experience
	 * - Shows unlock button temporarily to indicate locked state
	 * - Sets the controllers locked flag
	 *
	 * The unlock button automatically hides after a brief delay.
	 */
	private fun lockPlaybackControls() {
		// Lock screen orientation
		requestedOrientation = SCREEN_ORIENTATION_LOCKED

		// Hide controls and show unlock indicator
		hidePlaybackControls()
		showView(targetView = unlockButton, shouldAnimate = true)
		areControllersLocked = true

		// Auto-hide unlock button after delay
		delay(timeInMile = 1500, listener = object : OnTaskFinishListener {
			override fun afterDelay() {
				hideView(targetView = unlockButton, shouldAnimate = true)
			}
		})
	}

	/**
	 * Unlocks the playback controls and restores normal screen orientation behavior.
	 *
	 * This function reverses the lock state by:
	 * - Restoring automatic screen orientation detection
	 * - Hiding the unlock button
	 * - Showing the playback controls
	 * - Clearing the controllers locked flag
	 *
	 * Used to exit immersive viewing mode and restore full user control.
	 */
	private fun unlockPlaybackControls() {
		// Restore automatic screen orientation handling
		requestedOrientation = SCREEN_ORIENTATION_UNSPECIFIED

		// Hide unlock indicator and show full controls
		hideView(targetView = unlockButton, shouldAnimate = true)
		showPlaybackControls()

		// Clear the locked state flag
		areControllersLocked = false
	}

	/**
	 * Shows user feedback for invalid or unplayable media files.
	 *
	 * This function provides both haptic and visual feedback to indicate
	 * that the selected media file cannot be played. Used when file corruption
	 * or format issues prevent normal playback.
	 */
	private fun showInvalidMediaToast() {
		// Provide haptic feedback to alert the user
		doSomeVibration(timeInMillis = 50)

		// Show visual toast message about the invalid file
		showToast(selfActivityRef, getString(string.title_invalid_media_file))
	}

	/**
	 * Initiates the process of attaching an external subtitle file to the current video.
	 *
	 * This function coordinates the complete subtitle attachment workflow:
	 * 1. Pauses playback to prevent sync issues
	 * 2. Prompts user to select a subtitle file
	 * 3. Validates the selected subtitle file
	 * 4. Handles any errors during the process
	 *
	 * @throws Exception if any step in the subtitle attachment process fails
	 */
	private fun attachExternalSubtitle() {
		try {
			// Pause playback to ensure smooth subtitle integration
			pausePlayback()

			// Launch file picker for subtitle selection
			promptUserToPickSubtitleFile()

			// Set up validation for when user selects a file
			validateUserSelectedSubtitle()
		} catch (error: Exception) {
			// Log the error and notify user of failure
			logger.e("Error in attaching external subtitle", error)
			notifySubtitleSelectionFailure()
		}
	}

	/**
	 * Notifies the user that subtitle file selection has failed.
	 *
	 * Shows a dialog message explaining that the selected subtitle file
	 * could not be read or processed. Used when file permissions or
	 * accessibility issues prevent subtitle loading.
	 */
	private fun notifySubtitleSelectionFailure() {
		val msgText = getString(string.text_subtitle_file_not_readable)
		showMessageDialog(baseActivityInf = selfActivityRef, messageTxt = msgText)
	}

	/**
	 * Validates the user-selected subtitle file for compatibility and accessibility.
	 *
	 * This function performs multiple checks on the selected subtitle file:
	 * - File readability and permission verification
	 * - File extension validation for supported formats
	 * - Integration with current video playback
	 *
	 * @throws Exception if the subtitle file fails any validation check
	 */
	private fun validateUserSelectedSubtitle() {
		scopedStorageHelper?.onFileSelected = { _, files ->
			val messageString = getText(string.title_file_not_readable_permission_issue)

			// Check if files are readable and accessible
			if (!canReadFiles(files)) {
				throw Exception(messageString.toString())
			}

			// Extract and validate file extension
			val subtitleFile = files.first()
			val fileExtension = subtitleFile.let {
				subtitleFile.name?.let { fileName ->
					getFileExtension(fileName) ?: ""
				}
			}

			// Validate extension presence and format support
			if (fileExtension.isNullOrEmpty()) throw Exception(messageString.toString())
			if (!isValidSubtitleExtension(fileExtension)) {
				// Notify user about unsupported subtitle format
				notifyUnsupportedSubtitleFile()
			} else {
				// Valid subtitle file - integrate with current video
				extractDownloadModelFromIntent()?.let { downloadDataModel ->
					val videoFile = downloadDataModel.getDestinationDocumentFile()
					playVideoWithExternalSubtitle(videoFile, subtitleFile)
				}
			}
		}
	}

	/**
	 * Plays a video file with an externally provided subtitle track.
	 *
	 * This function coordinates the playback of a video with user-selected
	 * subtitles by combining the video file and subtitle file into a single
	 * media playback session.
	 *
	 * @param videoFile The main video file to play
	 * @param subtitleFile The external subtitle file to display
	 */
	private fun playVideoWithExternalSubtitle(videoFile: DocumentFile, subtitleFile: DocumentFile) {
		// Start playback with integrated subtitles
		playVideoFile(videoFile, subtitleFile)

		// Resume playback from current position
		resumePlayback()
	}

	/**
	 * Notifies the user that the selected subtitle file format is not supported.
	 *
	 * Shows a dialog message explaining that the subtitle file format
	 * is not compatible with the video player. Guides user toward supported
	 * formats like SRT or VTT.
	 */
	private fun notifyUnsupportedSubtitleFile() {
		val msgText = getString(string.text_subtitle_file_not_supported)
		showMessageDialog(baseActivityInf = selfActivityRef, messageTxt = msgText)
	}

	/**
	 * Validates if a file extension represents a supported subtitle format.
	 *
	 * This function checks if the provided file extension matches known
	 * supported subtitle formats. Currently supports:
	 * - SRT (SubRip)
	 * - VTT (WebVTT)
	 *
	 * @param fileExt The file extension to validate (without dot prefix)
	 * @return True if the extension represents a supported subtitle format
	 */
	private fun isValidSubtitleExtension(fileExt: String): Boolean {
		var isValid = false

		// Only process non-empty, non-blank extensions
		if (fileExt.isNotEmpty() || fileExt.isBlank()) {
			// Check for supported subtitle formats (case-insensitive)
			if (fileExt.lowercase(Locale.getDefault()) == "srt" ||
				fileExt.lowercase(Locale.getDefault()) == "vtt"
			) isValid = true
		}
		return isValid
	}

	/**
	 * Checks if a list of DocumentFiles can be read by the application.
	 *
	 * This function verifies file accessibility by checking:
	 * - The list is not empty
	 * - The first file in the list has read permissions
	 *
	 * @param files List of DocumentFiles to check for readability
	 * @return True if files are accessible and readable, false otherwise
	 */
	private fun canReadFiles(files: List<DocumentFile>): Boolean {
		return files.isNotEmpty() && files.first().canRead()
	}

	/**
	 * Prompts the user to select a subtitle file through a system file picker.
	 *
	 * This function launches a file selection dialog with intelligent
	 * default path selection:
	 * - Prefers the public downloads directory
	 * - Falls back to the app's default download folder
	 * - Restricts to single file selection
	 */
	private fun promptUserToPickSubtitleFile() {
		selfActivityRef?.let { activityRef ->
			// Determine the best initial directory for file selection
			val pubicDownloadFolder = INSTANCE.getPublicDownloadDir()?.getAbsolutePath(INSTANCE)
			val defaultAIOFolder = getText(string.text_default_aio_download_folder_path)
			val pathToPick = pubicDownloadFolder ?: defaultAIOFolder

			// Create path wrapper and launch file picker
			val initialPath = FileFullPath(activityRef, pathToPick.toString())
			scopedStorageHelper?.openFilePicker(allowMultiple = false, initialPath = initialPath)
		}
	}

	/**
	 * Rewinds the current playback by 10 seconds (10,000 milliseconds).
	 *
	 * This function provides quick backward seeking functionality during playback:
	 * - Only operates when media is currently playing
	 * - Seeks backward by fixed 10-second intervals
	 * - Ensures new position stays within valid media bounds (0 to duration)
	 * - Provides instant feedback by immediately updating playback position
	 */
	private fun rewindPlayer() {
		if (player.isPlaying) {
			val currentPosition = player.currentPosition
			// Calculate new position with 10-second rewind, clamped to valid range
			val newPosition = (currentPosition - 10000).coerceIn(0, player.duration)
			player.seekTo(newPosition)
		}
	}

	/**
	 * Fast-forwards the current playback by 10 seconds (10,000 milliseconds).
	 *
	 * This function provides quick forward seeking functionality during playback:
	 * - Only operates when media is currently playing
	 * - Seeks forward by fixed 10-second intervals
	 * - Ensures new position doesn't exceed media duration
	 * - Provides instant feedback by immediately updating playback position
	 */
	private fun fastForwardPlayer() {
		if (player.isPlaying) {
			val currentPosition = player.currentPosition
			// Calculate new position with 10-second forward seek, clamped to duration
			val newPosition = (currentPosition + 10000).coerceAtMost(player.duration)
			player.seekTo(newPosition)
		}
	}

	/**
	 * Displays the media file options popup menu for additional actions.
	 *
	 * This function shows a contextual menu with various media file operations:
	 * - Delete file
	 * - Share file
	 * - File information
	 * - Other media-specific actions
	 *
	 * The popup is lazily initialized on first use to optimize performance.
	 */
	private fun showMediaOptionsMenu() {
		// Initialize the popup menu only once (lazy initialization)
		if (!::mediaFileOptionsPopup.isInitialized)
			mediaFileOptionsPopup = MediaFileOptionsPopup(selfActivityRef)

		// Display the media options popup to the user
		mediaFileOptionsPopup.show()
	}

	/**
	 * Toggles between fullscreen and normal display mode.
	 *
	 * This function switches the immersive fullscreen experience by manipulating
	 * system UI flags. Uses deprecated API but necessary for comprehensive
	 * fullscreen control across Android versions.
	 *
	 * Fullscreen mode hides:
	 * - Navigation bar
	 * - Status bar
	 * - System UI elements
	 *
	 * @Suppress Deprecation warning since newer API may not be available on all devices
	 */
	@Suppress("DEPRECATION")
	private fun toggleFullscreenMode() {
		selfActivityRef?.let { activityRef ->
			// Get current system UI visibility flags
			var newUiOptions = activityRef.window.decorView.systemUiVisibility

			// Toggle navigation bar visibility
			newUiOptions = newUiOptions xor SYSTEM_UI_FLAG_HIDE_NAVIGATION
			// Toggle status bar visibility
			newUiOptions = newUiOptions xor SYSTEM_UI_FLAG_FULLSCREEN
			// Toggle immersive mode (gesture-based system UI access)
			newUiOptions = newUiOptions xor SYSTEM_UI_FLAG_IMMERSIVE

			// Apply the new system UI visibility configuration
			activityRef.window.decorView.systemUiVisibility = newUiOptions
		}
	}

	/**
	 * Deletes a media file and performs complete cleanup of associated data.
	 *
	 * This function handles the complete deletion workflow:
	 * 1. Physically deletes the media file from storage
	 * 2. Removes the download model from memory
	 * 3. Sorts the remaining download models
	 * 4. Deletes model data from persistent storage
	 * 5. Provides user feedback on success
	 *
	 * @param mediaFiles The complete list of media file models
	 * @param indexToDelete The index of the specific file to delete
	 */
	private fun deleteMediaFileAndCleanDatabase(mediaFiles: List<DownloadDataModel>, indexToDelete: Int) {
		// Step 1: Delete the physical media file from storage
		val fileToDelete = mediaFiles[indexToDelete].getDestinationFile()
		fileToDelete.delete()

		// Step 2: Remove the model from active download system
		val deletedModel = mediaFiles[indexToDelete]
		downloadSystem.finishedDownloadDataModels.remove(deletedModel)

		// Step 3: Re-sort the remaining models for consistent ordering
		downloadSystem.sortFinishedDownloadDataModels()

		// Step 4: Delete persistent model data from disk
		deletedModel.deleteModelFromDisk()

		// Step 5: Notify user of successful deletion
		executeOnMainThread {
			showToast(selfActivityRef, getString(string.title_successfully_deleted))
		}
	}

	/**
	 * Automatically plays the next or previous media file after deletion or completion.
	 *
	 * This function provides intelligent media queue navigation by:
	 * - First attempting to play the next file in the list
	 * - Falling back to the previous file if next is unavailable
	 * - Closing the activity if no adjacent files exist
	 *
	 * Used to maintain continuous playback experience when files are removed
	 * or when playback completes naturally.
	 *
	 * @param matchedIndex The index of the current/media file that was playing
	 * @param mediaFiles The complete list of available media files
	 */
	private fun playNextOrPreviousMediaFromDownloads(matchedIndex: Int, mediaFiles: List<DownloadDataModel>) {
		executeOnMainThread {
			// Skip if no valid current file index
			if (matchedIndex == -1) return@executeOnMainThread

			// Calculate adjacent file indices
			val nextIndex = matchedIndex + 1
			val prevIndex = matchedIndex - 1

			when {
				// Priority 1: Play next file if available
				mediaFiles.size > nextIndex -> playDownloadedVideo(mediaFiles[nextIndex])

				// Priority 2: Play previous file if next is unavailable
				prevIndex >= 0 -> playDownloadedVideo(mediaFiles[prevIndex])

				// Final: No adjacent files - stop playback and close activity
				else -> {
					stopAndDisposePlayer()
					closeActivityWithSwipeAnimation()
				}
			}
		}
	}
}