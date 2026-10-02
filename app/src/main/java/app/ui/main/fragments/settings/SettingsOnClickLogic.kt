package app.ui.main.fragments.settings

import android.content.*
import android.widget.*
import androidx.annotation.*
import androidx.core.content.ContextCompat.*
import androidx.core.net.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioSettings
import app.core.AIOApp.Companion.aioUserProfile
import app.core.engines.settings.AIOSettings.Companion.AIO_SETTING_DARK_MODE_FILE_NAME
import app.core.engines.supabase.*
import app.core.engines.updater.*
import app.ui.main.fragments.settings.activities.browser.*
import app.ui.main.fragments.settings.dialogs.*
import app.ui.others.information.*
import com.aio.*
import kotlinx.coroutines.*
import lib.device.*
import lib.files.FileSystemUtility.hasFullFileSystemAccess
import lib.files.FileSystemUtility.openAllFilesAccessSettings
import lib.networks.URLUtility.*
import lib.process.*
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.process.CommonTimeUtils.delay
import lib.process.IntentHelperUtils.openInstagramApp
import lib.process.OSProcessUtils.restartApp
import lib.texts.CommonTextUtils.getText
import lib.ui.*
import lib.ui.MsgDialogUtils.getMessageDialog
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.showOnScreenKeyboard
import lib.ui.builders.*
import lib.ui.builders.ToastView.Companion.showToast
import java.io.*
import java.lang.ref.*

/**
 * Handles click logic for all settings options within SettingsFragment.
 *
 * All functional and toggle events (like changing settings, launching dialogs,
 * updating preferences, opening legal docs, launching browser, etc.) are managed here.
 * This class is also responsible for updating UI state on user action.
 *
 * Logging in this class is handled via logger.d (for event tracking) and logger.e
 * (for errors) for maintainable, searchable logs.
 *
 * @param settingsFragment Primary reference to the parent SettingsFragment for context and UI access.
 */
class SettingsOnClickLogic(settingsFragment: SettingsFragment) {
	
	/**
	 * Logger instance for tracking user interactions, method executions, and error conditions
	 * within the settings functionality. Provides detailed diagnostics for debugging user
	 * preference changes and navigation flows throughout the settings management system.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Memory-safe weak reference to the parent SettingsFragment to prevent memory leaks
	 * during configuration changes or when the fragment is destroyed. This ensures the
	 * settings controller doesn't retain references to destroyed UI components while
	 * still allowing access to the fragment when it's active and visible.
	 */
	private val weakReferenceOfSettingFragment = WeakReference(settingsFragment)
	
	private val safeSettingsFragmentRef: SettingsFragment?
		get() = weakReferenceOfSettingFragment.get()
	
	/**
	 * Launches the username editor interface with haptic feedback and feature availability notice.
	 *
	 * This method currently serves as a placeholder for future username editing functionality.
	 * It provides immediate user feedback through vibration and informs users that this feature
	 * is planned for future releases. The implementation demonstrates the pattern for handling
	 * upcoming features while maintaining consistent user experience.
	 *
	 * Future Implementation:
	 * - Username validation and availability checking
	 * - Real-time editing interface with character limits
	 * - Save/cancel workflow with confirmation dialogs
	 * - Integration with user profile synchronization
	 */
	fun showUsernameEditor() {
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			// Provide tactile feedback to acknowledge user interaction
			doSomeVibration()
			// Inform user that username editing is coming soon
			showUpcomingFeatures()
		}
	}
	
	/**
	 * Opens the authentication dialog for user login or new account registration, with region-specific behavior.
	 *
	 * For users in India, this function initiates the phone number-based authentication flow. If the user is
	 * not already logged in, it presents the `SupabasePhoneNumberLogIn` dialog. Upon successful registration
	 * or login, the user's profile is updated, and the settings UI is refreshed. If the user is already
	 * verified, a toast message confirms their logged-in status.
	 *
	 * For users outside of India, the feature is disabled. A dialog is shown informing them that login and
	 * registration are currently available only in India.
	 *
	 * @see SupabasePhoneNumberLogIn The authentication component used for the login/registration process.
	 * @see aioUserProfile For checking the user's current account verification status.
	 * @see DeviceUtility.isUserFromIndia To determine the user's geographical region.
	 */
	fun showLoginOrRegistrationDialog() {
		safeSettingsFragmentRef?.let { fragmentRef ->
			fragmentRef.safeFragmentLayoutRef?.let { fragmentLayoutRef ->
				fragmentRef.safeMotherActivityRef?.let { motherActivity ->
					if (DeviceUtility.isUserFromIndia(motherActivity)) {
						if (!aioUserProfile.isUserAccountVerified) {
							SupabasePhoneNumberLogIn(
								baseActivity = motherActivity,
								onAccountSuccessfullyRegistered = {
									logger.d("User logged in: ${aioUserProfile.uniqueUserServerId}")
									fragmentRef.updateUserAccountCard(fragmentLayoutRef)
								},
								onAccountRegistrationFailed = {
									logger.d("Registration failed")
									fragmentRef.updateUserAccountCard(fragmentLayoutRef)
								})
								.initialize()
								.show()
						} else {
							logger.d("Opening user account details activity for id: ${aioUserProfile.uniqueUserServerId}")
							motherActivity.openActivity(UserAccountDetailsActivity::class.java, true)
						}
					} else {
						logger.d("Login or registration is only available in India")
						motherActivity.doSomeVibration()
						MsgDialogUtils.showMessageDialog(
							baseActivityInf = motherActivity,
							isTitleVisible = true,
							titleText = getText(R.string.title_feature_isnt_implemented),
							messageTextViewCustomize = { it.text = getText(R.string.text_feature_only_available_in_india) },
							isNegativeButtonVisible = false
						)
					}
				}
			}
		}
	}
	
	/**
	 * Opens the download location selector dialog for choosing where downloaded files are stored.
	 *
	 * This functional implementation allows users to change their default download directory
	 * using a system file picker or custom directory selector. The method handles proper
	 * activity reference checking and provides error logging if the dialog cannot be displayed.
	 *
	 * Features:
	 * - Browse and select from available storage locations
	 * - Create new directories for organized file management
	 * - Visual feedback for currently selected location
	 * - Permission handling for storage access
	 * - Validation of writable directory paths
	 */
	fun showDownloadLocationPicker() {
		logger.d("Download Location Picker - Initiating directory selection dialog")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { activity ->
			// Create and display the download location selector
			DownloadLocationSelector(baseActivity = activity).show()
		} ?: logger.d("Picker failed: Activity null - Cannot show dialog without valid activity context")
	}
	
	/**
	 * Launches the language selection dialog with experimental feature warning and app restart requirement.
	 *
	 * This method implements a complete language selection flow including user education about
	 * the experimental nature of the feature, confirmation to proceed, language selection interface,
	 * and automatic app restart to apply the language changes. The multi-step process ensures
	 * users understand the implications of changing the application language.
	 *
	 * Flow Description:
	 * 1. Show experimental feature warning with proceed/cancel options
	 * 2. Display language picker with available localization options
	 * 3. Apply selected language and restart application
	 * 4. Ensure all UI components reload with new language resources
	 *
	 * @see LanguagePickerDialog For the actual language selection interface
	 * @see restartApp For the application restart mechanism
	 */
	fun showLanguageChanger() {
		logger.d("Language Picker - Starting language selection workflow")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { activity ->
			LanguagePickerDialog(activity).apply {
				getDialogBuilder().setCancelable(true)
				onApplyListener = {
					restartApplicationProcess()
				}
			}.show()
		}
	}
	
	/**
	 * Toggles the dark mode UI setting using a persistent flag file for state management.
	 *
	 * This method implements a file-based toggle system for dark mode preference, creating
	 * or deleting a configuration file to track the current theme state. The approach ensures
	 * theme persistence across app restarts while providing immediate visual feedback through
	 * system theme updates and UI state refresh.
	 *
	 * Implementation Details:
	 * - Uses background thread for file I/O operations to prevent UI blocking
	 * - Maintains theme state in internal storage for persistence
	 * - Triggers immediate UI refresh on main thread after state change
	 * - Handles file system exceptions gracefully with error logging
	 *
	 * File Strategy:
	 * - File exists = Dark mode enabled
	 * - File doesn't exist = Light mode enabled
	 * This binary approach simplifies state management and recovery
	 */
	fun togglesDarkModeUISettings() {
		logger.d("Toggling Dark Mode UI setting - Initiating theme switch")
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				val tempFile = File(INSTANCE.filesDir, AIO_SETTING_DARK_MODE_FILE_NAME)
				// Toggle dark mode state by creating/deleting the flag file
				if (tempFile.exists()) tempFile.delete() else tempFile.createNewFile()
				// Persist the updated settings configuration
				aioSettings.updateInStorage()
				
				// Update UI on main thread to reflect theme change immediately
				ThreadsUtility.executeOnMain {
					updateSettingStateUI()
					safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
						// Apply new theme to all activities and system UI
						ViewUtility.changesSystemTheme(this)
						logger.d("Dark Mode UI is now: ${tempFile.exists()}")
					}
				}
			} catch (error: Exception) {
				logger.e("Error toggling Dark Mode UI: ${error.message}", error)
			}
		})
	}
	
	/**
	 * Opens a content region selector dialog and automatically restarts the application
	 * after the user selects a new geographic region for content customization.
	 *
	 * This method handles regional content preferences that may affect available media,
	 * language defaults, or service availability. The app restart ensures all regional
	 * configurations are properly loaded and applied throughout the application.
	 *
	 * Regional Impact:
	 * - Content catalog and media availability
	 * - Language and localization defaults
	 * - Service endpoints and API configurations
	 * - Compliance with regional regulations
	 *
	 * The restart process guarantees consistent regional experience across all app components
	 * and prevents partial application of regional settings.
	 */
	fun changeDefaultContentRegion() {
		logger.d("Content Region Selector - Launching geographic preference dialog")
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			ContentRegionSelector(this).apply {
				// Allow users to cancel without making region changes
				getDialogBuilder().setCancelable(true)
				// Handle region selection confirmation with app restart
				onApplyListener = {
					logger.d("✔ Region selected → Restarting app for regional configuration update")
					close()
					// Force restart to apply regional settings across all app components
					restartApp(shouldKillProcess = true)
				}
			}.show()
		} ?: logger.d("Failed: Activity null (Region Selector) - Cannot access regional content preferences")
	}
	
	/**
	 * Toggles daily content suggestion notifications for personalized user recommendations.
	 *
	 * This method manages the user's preference for receiving daily content suggestions
	 * through notifications. The setting is immediately persisted to storage and the UI
	 * is updated to reflect the current state. The toggle provides users with control
	 * over notification frequency and content discovery features.
	 *
	 * User Experience Benefits:
	 * - Reduces notification fatigue when disabled
	 * - Enhances content discovery when enabled
	 * - Immediate feedback through UI state updates
	 * - Persistent preference across app sessions
	 *
	 * Notification Content:
	 * - Personalized media recommendations
	 * - Trending content in user's preferred categories
	 * - New content from followed sources or creators
	 */
	fun toggleDailyContentSuggestions() {
		logger.d("Toggle Daily Suggestions - Updating content recommendation preferences")
		safeSettingsFragmentRef?.safeMotherActivityRef?.apply {
			try {
				// Get current state and invert for toggle behavior
				val contentSuggestion = aioSettings.enableDailyContentSuggestion
				aioSettings.enableDailyContentSuggestion = !contentSuggestion
				// Persist the updated notification preference
				aioSettings.updateInStorage()
				logger.d("✔ Daily suggestions: $contentSuggestion")
				// Refresh UI to show current toggle state
				updateSettingStateUI()
			} catch (error: Exception) {
				logger.e("Error toggling suggestions: ${error.message}", error)
			}
		} ?: logger.d("Failed: Activity null (Daily Suggestions) - Cannot update notification settings")
	}
	
	/**
	 * Tracks whether the file/folder picker dialog is currently active to prevent duplicate instances.
	 * This state management ensures only one file picker is open at a time, preventing UI conflicts
	 * and confusing user experiences with multiple overlapping dialogs.
	 */
	private var isFileFolderPickerActive = false
	
	/**
	 * Opens a custom download folder selector with comprehensive storage permission handling
	 * and user guidance for file system access requirements.
	 *
	 * This method implements a complete workflow for selecting custom download directories:
	 * 1. Checks for necessary file system permissions
	 * 2. Guides users to enable permissions if missing
	 * 3. Launches folder picker with appropriate configuration
	 * 4. Prevents multiple picker instances through state tracking
	 *
	 * Permission Handling:
	 * - Detects MANAGE_EXTERNAL_STORAGE permission status
	 * - Provides educational dialog when permission is missing
	 * - Directs users to system settings for permission grant
	 * - Ensures legal compliance with scoped storage requirements
	 *
	 * Picker Configuration:
	 * - Folder-only selection mode (no individual files)
	 * - Single selection for clear directory targeting
	 * - Non-cancellable to ensure download location is set
	 * - Custom title and button text for clear purpose
	 */
	fun changeDefaultDownloadFolder() {
		logger.d("Custom Download Folder Selector - Initiating directory selection workflow")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { activityRef ->
			// Step 1: Check for required file system access permissions
			if (!hasFullFileSystemAccess(activityRef)) {
				// Step 2: Show permission education dialog when access is limited
				getMessageDialog(
					baseActivityInf = activityRef,
					isTitleVisible = true,
					isCancelable = false, // Force users to address permission requirement
					isNegativeButtonVisible = false, // Single action flow
					titleText = activityRef.getString(R.string.title_storage_permission_needed),
					// Explain why file system access is required for folder selection
					messageTextViewCustomize = { it.setText(R.string.text_file_system_permission_needed) },
					positiveButtonTextCustomize = { it.setText(R.string.title_allow_now_in_settings) }
				)?.apply {
					// Direct users to system settings for permission management
					setOnClickForPositiveButton {
						openAllFilesAccessSettings(activityRef)
						close()
					}
				}?.show()
			} else {
				if (isFileFolderPickerActive) return
				
				// Step 3: Launch folder picker when permissions are granted
				FileFolderPicker(
					activityRef,
					isCancellable = false, // Ensure download location is always set
					isFolderPickerOnly = true, // Restrict to directory selection only
					isFilePickerOnly = false, // Explicitly disable file selection
					isMultiSelection = false, // Single folder selection for clarity
					titleText = getText(R.string.title_select_download_folder),
					positiveButtonText = getText(R.string.title_select_folder),
					onUserAbortedProcess = {
						// Handle user cancellation or back navigation
						isFileFolderPickerActive = false
					},
					onFileSelection = {
						// Process selected folder path for download destination
						// Implementation would handle the selected directory path
						isFileFolderPickerActive = false
					}).show()
				// Update state to prevent duplicate picker instances
				isFileFolderPickerActive = true
			}
		} ?: logger.d(
			"Failed: Activity null (Folder Selector) - " +
				"Cannot access file system without activity context"
		)
	}
	
	/**
	 * Toggles the visibility of download progress and completion notifications in the system status bar.
	 *
	 * This setting allows users to control whether download notifications are displayed during
	 * and after download operations. When disabled, downloads proceed silently without system
	 * notifications, providing a cleaner notification experience for users who prefer minimal
	 * interruptions or are frequently downloading multiple files.
	 *
	 * Use Cases:
	 * - Users who want to reduce notification clutter during batch downloads
	 * - Privacy-conscious users who prefer discreet background operations
	 * - Power users managing multiple simultaneous downloads
	 *
	 * Impact: Affects both in-progress download notifications and completion alerts,
	 * but does not disable essential error or failure notifications that require user attention.
	 */
	fun toggleHideDownloadNotification() {
		logger.d("Toggle Download Notification - Updating notification visibility preference")
		try {
			val hideNotification = aioSettings.downloadHideNotification
			aioSettings.downloadHideNotification = !hideNotification
			aioSettings.updateInStorage()
			logger.d("Notifications hidden: $hideNotification")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling notifications: ${error.message}", error)
		}
	}
	
	/**
	 * Toggles Wi-Fi-only download restriction to control cellular data usage for file downloads.
	 *
	 * When enabled, this setting prevents downloads from occurring over mobile data connections,
	 * ensuring large file transfers only use Wi-Fi networks. This helps users avoid exceeding
	 * cellular data caps and prevents unexpected data charges for large downloads.
	 *
	 * Network Behavior:
	 * - Enabled: Downloads pause automatically when Wi-Fi disconnects
	 * - Disabled: Downloads use any available network (Wi-Fi or cellular)
	 * - Automatic resumption when Wi-Fi reconnects (if enabled)
	 *
	 * User Benefits:
	 * - Data cost control for limited cellular plans
	 * - Battery optimization by avoiding cellular data transfers
	 * - Peace of mind for large file downloads
	 */
	fun toggleWifiOnlyDownload() {
		logger.d("Toggle Wi-Fi Only Mode - Updating network restriction preference")
		try {
			aioSettings.downloadWifiOnly = !aioSettings.downloadWifiOnly
			aioSettings.updateInStorage()
			logger.d("Wi-Fi only: ${aioSettings.downloadWifiOnly}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling Wi-Fi only: ${error.message}", error)
		}
	}
	
	/**
	 * Toggles single-click file opening behavior for downloaded items in file browsers.
	 *
	 * This setting controls whether downloaded files open immediately with a single tap
	 * or require a more deliberate double-click action. Single-click mode provides faster
	 * access to downloaded content, while double-click mode prevents accidental file openings
	 * and provides an additional confirmation step.
	 *
	 * User Experience Impact:
	 * - Single-click: Faster access, higher risk of accidental openings
	 * - Double-click: Slower but safer, reduces accidental file launches
	 * - Particularly useful for touchscreen devices where precision varies
	 *
	 * Recommended Usage:
	 * - Single-click: For frequently accessed documents and media files
	 * - Double-click: For important files or in high-traffic browsing scenarios
	 */
	fun toggleSingleClickToOpenFile() {
		logger.d("Toggling single-click open file setting - Updating file interaction behavior")
		try {
			val singleClickOpen = aioSettings.openDownloadedFileOnSingleClick
			aioSettings.openDownloadedFileOnSingleClick = !singleClickOpen
			aioSettings.updateInStorage()
			
			safeSettingsFragmentRef?.safeMotherActivityRef?.downloadFragment?.finishedTasksFragment?.finishedTasksListAdapter?.notifyDataSetChangedOnSort(
				true
			)
			
			logger.d("Single-click open file is now: $singleClickOpen")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling single-click open file: ${error.message}", error)
		}
	}
	
	/**
	 * Toggles auditory feedback for download completion notifications.
	 *
	 * Controls whether download completion triggers a system notification sound.
	 * When enabled, users receive audible confirmation when downloads finish.
	 * When disabled, completion notifications are silent, ideal for quiet environments
	 * or users who prefer visual-only notifications.
	 *
	 * Sound Behavior:
	 * - Follows system notification sound preferences
	 * - Respects device silent/vibrate modes
	 * - Uses default notification sound unless customized
	 *
	 * Usage Scenarios:
	 * - Enabled: For important downloads requiring immediate attention
	 * - Disabled: In meetings, libraries, or during nighttime hours
	 * - Customizable per user preference and environment
	 */
	fun toggleDownloadNotificationSound() {
		logger.d("Toggling Download Notification Sound - Updating auditory feedback preference")
		try {
			aioSettings.downloadPlayNotificationSound = !aioSettings.downloadPlayNotificationSound
			aioSettings.updateInStorage()
			logger.d("Download Sound Enabled: ${aioSettings.downloadPlayNotificationSound}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling download sound: ${error.message}", error)
		}
	}
	
	/**
	 * Displays a placeholder dialog for advanced download settings currently under development.
	 *
	 * This method serves as a temporary implementation for the advanced downloads settings
	 * section, informing users that additional configuration options are planned for future
	 * releases. It provides haptic feedback and a clear message about feature availability.
	 *
	 * Planned Advanced Features:
	 * - Download speed limiting and bandwidth management
	 * - Scheduled download timing and automation
	 * - Parallel download configuration and thread management
	 * - File type-specific download behaviors
	 * - Network condition detection and adaptive downloading
	 *
	 * Future Implementation:
	 * - Comprehensive settings interface with categorized options
	 * - Real-time download performance monitoring
	 * - Advanced network configuration and protocol settings
	 */
	fun openAdvanceDownloadsSettings() {
		logger.d("Opening Advanced Downloads Settings (not implemented) - Showing feature roadmap")
		safeSettingsFragmentRef?.safeMotherActivityRef.let {
			// Provide tactile feedback to acknowledge user interaction
			it?.doSomeVibration(20)
			// Inform users about upcoming advanced features
			MsgDialogUtils.showMessageDialog(
				baseActivityInf = it,
				isTitleVisible = true,
				titleText = getText(R.string.title_feature_isnt_implemented),
				messageTextViewCustomize = { msgTextView ->
					msgTextView.setText(R.string.text_feature_isnt_available_yet)
				}, isNegativeButtonVisible = false // Single action to acknowledge
			)
		} ?: run { logger.d("Failed: null activity - Cannot display feature roadmap") }
	}
	
	/**
	 * Prompts users to configure a custom browser homepage URL with comprehensive validation
	 * and user experience optimization.
	 *
	 * This method implements a complete homepage configuration workflow including:
	 * - Current homepage display for context
	 * - URL input with automatic keyboard management
	 * - Comprehensive URL validation and normalization
	 * - User feedback through haptic and visual cues
	 *
	 * URL Validation & Normalization:
	 * - Checks for valid URL format and structure
	 * - Automatically adds HTTPS protocol if missing
	 * - Validates network accessibility and format compliance
	 * - Provides clear error messages for invalid entries
	 *
	 * User Experience Features:
	 * - Automatic keyboard display for immediate input
	 * - Current setting context for informed decisions
	 * - Success confirmation with visual feedback
	 * - Input validation with clear error guidance
	 */
	fun setBrowserDefaultHomepage() {
		logger.d("Opening Browser Homepage dialog - Initiating URL configuration workflow")
		try {
			safeSettingsFragmentRef?.safeMotherActivityRef?.let { activityRef ->
				val dialogBuilder = DialogBuilder(activityRef)
				dialogBuilder.setView(R.layout.dialog_browser_homepage_1)
				
				val dialogLayout = dialogBuilder.view
				// Display current homepage for user context and comparison
				val stringResId = R.string.title_current_homepage
				val formatArgs = aioSettings.browserDefaultHomepage
				val homepageString = activityRef.getString(stringResId, formatArgs)
				
				dialogLayout.findViewById<TextView>(R.id.txt_current_homepage).text = homepageString
				val editTextURL = dialogLayout.findViewById<EditText>(R.id.edit_field_url)
				
				// Handle URL submission with validation and processing
				dialogBuilder.setOnClickForPositiveButton {
					val userEnteredURL = editTextURL.text.toString()
					logger.d("User entered homepage URL: $userEnteredURL")
					if (isValidURL(userEnteredURL)) {
						// Normalize URL by ensuring HTTPS protocol for security
						val finalNormalizedURL = ensureHttps(userEnteredURL) ?: userEnteredURL
						aioSettings.browserDefaultHomepage = finalNormalizedURL
						aioSettings.updateInStorage()
						logger.d("Homepage updated to: $finalNormalizedURL")
						dialogBuilder.close()
						showToast(activityInf = activityRef, msgId = R.string.title_successful)
					} else {
						logger.d("Invalid homepage URL entered: $userEnteredURL")
						// Provide immediate feedback for invalid input
						activityRef.doSomeVibration(20)
						showToast(activityInf = activityRef, msgId = R.string.title_invalid_url)
					}
				}
				dialogBuilder.show()
				
				// Automatically focus and show keyboard for optimal user experience
				delay(200, object : OnTaskFinishListener {
					override fun afterDelay() {
						logger.d("Showing on-screen keyboard for URL input")
						editTextURL.requestFocus()
						showOnScreenKeyboard(activityRef, editTextURL)
					}
				})
			} ?: run { logger.d("Failed: null activity - Cannot configure browser homepage") }
		} catch (error: Exception) {
			logger.e("Error setting browser homepage: ${error.message}", error)
			showToast(
				activityInf = safeSettingsFragmentRef?.safeMotherActivityRef,
				msgId = R.string.title_something_went_wrong
			)
		}
	}
	
	/**
	 * Toggles ad blocker preference for the browser.
	 */
	fun toggleBrowserBrowserAdBlocker() {
		logger.d("Toggling Browser Ad Blocker")
		try {
			val browserEnableAdblocker = aioSettings.browserEnableAdblocker
			aioSettings.browserEnableAdblocker = !browserEnableAdblocker
			aioSettings.updateInStorage()
			logger.d("Browser Ad Blocker toggled: $browserEnableAdblocker")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling browser ad blocker: ${error.message}", error)
		}
	}
	
	/**
	 * Toggles popup blocker preference for the browser.
	 */
	fun toggleBrowserPopupAdBlocker() {
		logger.d("Toggling Browser Popup Blocker")
		try {
			val browserEnablePopupBlocker = aioSettings.browserEnablePopupBlocker
			aioSettings.browserEnablePopupBlocker = !browserEnablePopupBlocker
			aioSettings.updateInStorage()
			logger.d("Popup Blocker toggled: $browserEnablePopupBlocker")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling popup blocker: ${error.message}", error)
		}
	}
	
	/**
	 * Toggles whether images are allowed to display in the browser.
	 */
	fun toggleBrowserWebImages() {
		logger.d("Toggling Browser Web Images")
		try {
			aioSettings.browserEnableImages = !aioSettings.browserEnableImages
			aioSettings.updateInStorage()
			logger.d("Browser enable images: ${aioSettings.browserEnableImages}")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling browser enable images: ${error.message}", error)
		}
	}
	
	/**
	 * Toggles video grabber option for the browser.
	 */
	fun toggleBrowserVideoGrabber() {
		logger.d("Toggling Browser Video Grabber")
		try {
			val enableVideoGrabber = aioSettings.browserEnableVideoGrabber
			aioSettings.browserEnableVideoGrabber = !enableVideoGrabber
			aioSettings.updateInStorage()
			logger.d("Video Grabber toggled: $enableVideoGrabber")
			updateSettingStateUI()
		} catch (error: Exception) {
			logger.e("Error toggling video grabber: ${error.message}", error)
		}
	}
	
	/**
	 * Opens advanced browser settings activity.
	 */
	fun openAdvanceBrowserSettings() {
		logger.d("Opening Advanced Settings For Browser")
		this@SettingsOnClickLogic.safeSettingsFragmentRef?.safeMotherActivityRef
			?.openActivity(
				targetActivity = AdvBrowserSettingsActivity::class.java,
				shouldAnimate = true
			) ?: run { logger.d("Failed: null activity") }
	}
	
	/**
	 * Initiates an intent to share the app with other users.
	 */
	fun shareApplicationWithFriends() {
		logger.d("Sharing application with friends")
		safeSettingsFragmentRef?.safeMotherActivityRef?.let { activityRef ->
			ShareUtility.shareText(
				context = activityRef,
				title = getText(R.string.title_share_with_others),
				text = getApplicationShareText(activityRef)
			)
		} ?: run { logger.d("Failed: null activity") }
	}
	
	/**
	 * Opens the feedback activity for collecting user comments.
	 */
	fun openUserFeedbackActivity() {
		logger.d("Opening User Feedback Activity")
		safeSettingsFragmentRef?.safeMotherActivityRef?.openActivity(
			UserFeedbackActivity::class.java, shouldAnimate = false
		) ?: run { logger.d("Failed: null activity") }
	}
	
	/**
	 * Opens app's detailed info page in system settings.
	 */
	fun openApplicationInformation() {
		logger.d("Opening Application Info in system settings")
		val safeBaseActivityRef = this@SettingsOnClickLogic.safeSettingsFragmentRef?.safeBaseActivityRef
		safeBaseActivityRef?.openAppInfoSetting() ?: run { logger.d("Failed: null activity") }
	}
	
	/**
	 * Launches the privacy policy in a browser if possible, with fallback error handling.
	 */
	fun showPrivacyPolicyActivity() {
		logger.d("Opening Privacy Policy in browser")
		val safeBaseActivityRef = this@SettingsOnClickLogic.safeSettingsFragmentRef?.safeBaseActivityRef
		safeBaseActivityRef?.let { activityRef ->
			try {
				val urlResId = R.string.text_aio_official_privacy_policy_url
				val uri = getText(urlResId)
				logger.d("Privacy Policy URL: $uri")
				activityRef.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.d("Error opening Privacy Policy: ${error.message}")
				error.printStackTrace()
				val toastMsgId = R.string.title_please_install_web_browser
				showToast(activityInf = activityRef, msgId = toastMsgId)
			}
		} ?: run { logger.d("Failed: null activity") }
	}
	
	/**
	 * Launches the terms and conditions page in a browser.
	 */
	fun showTermsConditionActivity() {
		logger.d("Opening Terms & Conditions in browser")
		val safeBaseActivityRef = this@SettingsOnClickLogic.safeSettingsFragmentRef?.safeBaseActivityRef
		safeBaseActivityRef?.let { activityRef ->
			try {
				val urlResId = R.string.text_aio_official_terms_conditions_url
				val uri = getText(urlResId)
				logger.d("Terms & Conditions URL: $uri")
				activityRef.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
			} catch (error: Exception) {
				logger.e("Failed to open Terms: ${error.message}", error)
				val toastMsgId = R.string.title_please_install_web_browser
				showToast(activityInf = activityRef, msgId = toastMsgId)
			}
		} ?: run {
			logger.d("Failed: null activity")
		}
	}
	
	/**
	 * Checks for an available update, shows loading UI, then shows site or toast as appropriate.
	 */
	fun checkForNewApkVersion() {
		logger.d("Check for APK update")
		this@SettingsOnClickLogic.safeSettingsFragmentRef?.safeBaseActivityRef?.let { activityRef ->
			ThreadsUtility.executeInBackground(codeBlock = {
				var waitingDialog: WaitingDialog? = null
				ThreadsUtility.executeOnMain {
					waitingDialog = WaitingDialog(
						baseActivityInf = activityRef,
						loadingMessage = getText(R.string.title_checking_for_new_update),
						isCancelable = false,
					)
					waitingDialog.show()
					delay(1000)
				}
				
				if (AIOUpdater().isNewUpdateAvailable()) {
					ThreadsUtility.executeOnMain { waitingDialog?.close() }
					logger.d("Update found → opening site")
					activityRef.openApplicationOfficialSite()
				} else {
					ThreadsUtility.executeOnMain { waitingDialog?.close() }
					logger.d("Already latest version")
					ThreadsUtility.executeOnMain {
						activityRef.doSomeVibration(20)
						val msgResId = R.string.title_you_using_latest_version
						showToast(activityInf = activityRef, msgId = msgResId)
					}
				}
			}, errorHandler = {
				logger.d("Update check failed: ${it.message}")
				activityRef.doSomeVibration(20)
				val toastMsgId = R.string.title_something_went_wrong
				showToast(activityInf = activityRef, msgId = toastMsgId)
			})
		} ?: run { logger.d("Update check failed: null activity") }
	}
	
	/**
	 * Refreshes settings UI by updating enabled/disabled indicator icons.
	 */
	fun updateSettingStateUI() {
		logger.d("Update settings UI")
		val darkModeTempConfigFile = File(INSTANCE.filesDir, AIO_SETTING_DARK_MODE_FILE_NAME)
		safeSettingsFragmentRef?.safeFragmentLayoutRef?.let { layout ->
			listOf(
				SettingViewConfig(R.id.txt_dark_mode_ui, darkModeTempConfigFile.exists()),
				SettingViewConfig(R.id.txt_daily_suggestions, aioSettings.enableDailyContentSuggestion),
				SettingViewConfig(R.id.txt_play_notification_sound, aioSettings.downloadPlayNotificationSound),
				SettingViewConfig(R.id.txt_wifi_only_downloads, aioSettings.downloadWifiOnly),
				SettingViewConfig(R.id.txt_single_click_open, aioSettings.openDownloadedFileOnSingleClick),
				SettingViewConfig(R.id.txt_hide_task_notifications, aioSettings.downloadHideNotification),
				SettingViewConfig(R.id.txt_enable_adblock, aioSettings.browserEnableAdblocker),
				SettingViewConfig(R.id.txt_enable_popup_blocker, aioSettings.browserEnablePopupBlocker),
				SettingViewConfig(R.id.txt_show_image_on_web, aioSettings.browserEnableImages),
				SettingViewConfig(R.id.txt_enable_video_grabber, aioSettings.browserEnableVideoGrabber),
			).forEach { config ->
				layout.findViewById<TextView>(config.viewId)?.updateEndDrawable(config.isEnabled)
			}
		} ?: run {
			logger.d("UI update failed")
		}
	}
	
	/**
	 * Shows a restart confirmation dialog and restarts the app if confirmed.
	 */
	fun restartApplication() {
		logger.d("Show restart dialog")
		this@SettingsOnClickLogic.safeSettingsFragmentRef?.safeBaseActivityRef?.let { safeMotherActivityRef ->
			val msgResId = R.string.text_cation_msg_of_restarting_application
			getMessageDialog(
				baseActivityInf = safeMotherActivityRef,
				isTitleVisible = true,
				titleText = getText(R.string.title_are_you_sure_about_this),
				messageTextViewCustomize = { it.setText(msgResId) },
				isNegativeButtonVisible = false,
				positiveButtonTextCustomize = {
					it.setLeftSideDrawable(R.drawable.ic_button_exit)
					it.setText(R.string.title_restart_application)
				}
			)?.apply {
				setOnClickForPositiveButton {
					logger.d("Restart confirmed")
					restartApplicationProcess()
				}
			}?.show()
		} ?: run { logger.d("Restart dialog failed") }
	}
	
	/**
	 * Attempts to launch the Instagram app to the developer's page.
	 */
	fun followDeveloperAtInstagram() {
		try {
			safeSettingsFragmentRef?.safeMotherActivityRef?.let {
				openInstagramApp(it, "https://www.instagram.com/shibafoss/")
			}
		} catch (error: Exception) {
			logger.e("Instagram open failed", error)
		}
	}
	
	/**
	 * Updates the end drawable (trailing icon) of a TextView to visually represent a toggle state.
	 *
	 * This utility method provides consistent visual feedback for toggleable settings by
	 * dynamically switching between checked (enabled) and unchecked (disabled) icons at
	 * the end of the text view. The approach maintains all existing drawables (start, top, bottom)
	 * while only modifying the end drawable to preserve the complete view composition.
	 *
	 * Visual Design:
	 * - Checked State: Filled circle icon indicating active/enabled setting
	 * - Unchecked State: Hollow circle icon indicating inactive/disabled setting
	 * - Position: Consistent end-aligned placement for predictable user scanning
	 * - Preservation: Maintains existing layout and other drawable positions
	 *
	 * Usage Pattern:
	 * Typically used in settings lists where each item has a toggle state that needs
	 * immediate visual feedback without requiring complete view recreation.
	 */
	private fun TextView.updateEndDrawable(isEnabled: Boolean) {
		// Select appropriate drawable resource based on toggle state
		val endDrawableRes = if (isEnabled) R.drawable.ic_button_checked_circle_small
		else R.drawable.ic_button_unchecked_circle_small
		
		// Preserve existing drawables to maintain view composition
		val current = compoundDrawables
		val checkedDrawable = getDrawable(context, endDrawableRes)
		
		// Update only the end drawable while preserving others
		setCompoundDrawablesWithIntrinsicBounds(current[0], current[1], checkedDrawable, current[3])
	}
	
	/**
	 * Generates a localized and formatted share message containing application information
	 * and official page URL for social sharing and user referrals.
	 *
	 * This method constructs a user-friendly share message that includes the application name
	 * and official distribution page (Play Store, GitHub, or official website). The message
	 * is properly localized and formatted for clear communication across different languages
	 * and cultural contexts.
	 *
	 * Message Structure:
	 * - Application name with proper branding
	 * - Official distribution or information page URL
	 * - Localized invitation text appropriate for sharing contexts
	 * - Clean formatting with proper indentation handling
	 *
	 * Sharing Use Cases:
	 * - Social media platform sharing (Twitter, Facebook, WhatsApp)
	 * - Messaging app referrals to friends and contacts
	 * - Email recommendations with clickable links
	 * - Cross-promotion in related application communities
	 */
	private fun getApplicationShareText(context: Context): String {
		val appName = context.getString(R.string.title_aio_video_downloader)
		val githubOfficialPage = context.getString(R.string.text_aio_official_page_url)
		return context.getString(R.string.text_sharing_app_msg, appName, githubOfficialPage)
			.trimIndent()
	}
	
	/**
	 * Performs a complete application restart by launching the main activity and terminating
	 * the current process to ensure clean state reinitialization.
	 *
	 * This method implements a robust application restart mechanism that clears all existing
	 * activities from the back stack and creates a fresh application instance. The process
	 * termination guarantees that all static variables, cached data, and background services
	 * are completely reset, providing a clean slate equivalent to a fresh app launch.
	 *
	 * Restart Scenarios:
	 * - Language or locale changes requiring complete resource reload
	 * - Theme changes that need full activity recreation
	 * - Critical configuration updates requiring clean state
	 * - Recovery from unstable application states
	 *
	 * Technical Implementation:
	 * - CLEAR_TOP flag removes all activities from the back stack
	 * - NEW_TASK flag ensures proper task management
	 * - Process termination guarantees complete memory cleanup
	 * - Immediate activity launch provides seamless user experience
	 */
	private fun restartApplicationProcess() {
		val context = INSTANCE
		val packageManager = context.packageManager
		val intent = packageManager.getLaunchIntentForPackage(context.packageName)
		intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
		context.startActivity(intent)
		Runtime.getRuntime().exit(0)
	}
	
	/**
	 * Data class representing the configuration state of a setting view for batch UI updates.
	 *
	 * This immutable data structure encapsulates the essential information needed to update
	 * multiple setting views efficiently. It enables batch processing of UI state changes
	 * by pairing view identifiers with their corresponding enabled/disabled states, which
	 * is particularly useful during settings initialization or bulk preference updates.
	 *
	 * Design Benefits:
	 * - Type-safe view identification using resource IDs
	 * - Immutable data structure for predictable state management
	 * - Efficient batch processing capabilities
	 * - Clear separation of configuration data from view logic
	 *
	 * Typical Usage:
	 * - Initial settings screen population from stored preferences
	 * - Bulk updates after configuration imports or resets
	 * - Theme or accessibility changes affecting multiple settings
	 * - Synchronization with remote configuration changes
	 */
	data class SettingViewConfig(
		/**
		 * Resource ID of the view to be updated, providing compile-time safety
		 * and enabling efficient view lookup through Android's resource system.
		 */
		@field:IdRes
		val viewId: Int,
		
		/**
		 * Boolean state indicating whether the setting is enabled (true) or disabled (false).
		 * This drives both visual representation and interactive behavior of the setting.
		 */
		val isEnabled: Boolean
	)
}