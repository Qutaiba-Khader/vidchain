package app.ui.main.fragments.settings

import android.os.*
import android.view.*
import android.widget.*
import androidx.lifecycle.*
import app.core.bases.*
import app.core.engines.supabase.*
import app.core.engines.supabase.SupabaseCloudServer.registerAuthOperationListener
import app.core.engines.supabase.SupabaseCloudServer.unregisterAuthOperationListener
import app.core.engines.user_profile.*
import app.ui.main.*
import com.aio.*
import kotlinx.coroutines.*
import lib.device.AppVersionUtility.versionCode
import lib.device.AppVersionUtility.versionName
import lib.process.*
import lib.texts.CommonTextUtils.fromHtmlStringToSpanned
import lib.ui.*
import lib.ui.ViewUtility.setLeftSideDrawable
import lib.ui.ViewUtility.setRightSideDrawable
import java.lang.ref.*

/**
 * Manages the application's settings user interface.
 *
 * This fragment serves as the central hub for user-configurable options, providing access to
 * various settings categories such as application behavior, downloads, and browser preferences.
 * It is responsible for displaying the current state of these settings and handling user
 * interactions to modify them.
 *
 * Key responsibilities include:
 * - **UI Initialization**: Inflates the settings layout and binds click listeners to interactive elements.
 * - **State Management**: Displays current preference values (e.g., toggle states, selected options)
 *   and updates the UI when they change.
 * - **Interaction Delegation**: Offloads the logic for handling click events to the
 *   [SettingsOnClickLogic] class to maintain a clean separation of concerns.
 * - **Lifecycle Coordination**: Registers itself with the parent [MotherActivity] to enable
 *   communication and ensures proper cleanup to prevent memory leaks.
 * - **Information Display**: Shows dynamic data like the application's version name and code.
 *
 * The implementation leverages weak references to safely interact with the parent activity and
 * other components, minimizing the risk of context leaks.
 */
class SettingsFragment : BaseFragment(), AuthOperationsListener {
	
	/**
	 * Logger utility for internal debugging, tracing component lifecycles, and
	 * recording event-driven actions within the fragment.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * A weak reference to this [SettingsFragment] instance.
	 *
	 * This is used by external classes, such as [SettingsOnClickLogic], to safely access
	 * the fragment's methods and properties without creating strong references that could
	 * lead to memory leaks when the fragment's lifecycle ends.
	 */
	private val weakReferenceOfSettingsFragment = WeakReference(this)
	
	/**
	 * Provides a weak, memory-safe reference to this [SettingsFragment] instance.
	 *
	 * This reference is immediately unwrapped (`.get()`) to provide direct, nullable
	 * access to the fragment. Using a weak reference here is crucial to prevent
	 * memory leaks by allowing the fragment to be garbage-collected if no other
	 * strong references to it exist. This is particularly useful when passing the
	 * fragment instance to helper classes (like `SettingsOnClickLogic`) that might
	 * outlive the fragment's view lifecycle.
	 */
	val safeSettingsFragmentRef get() = weakReferenceOfSettingsFragment.get()
	
	/**
	 * A weakly-referenced and lazily-initialized instance of the parent [MotherActivity].
	 *
	 * This provides a safe way to access the hosting activity's properties and methods
	 * without creating a strong reference, which could lead to context leaks. The
	 * reference is obtained by casting `safeBaseActivityRef` and is only created
	 * upon first access.
	 */
	val safeMotherActivityRef get() = safeBaseActivityRef as? MotherActivity
	
	/**
	 * Manages all user click interactions within the settings UI.
	 *
	 * This property holds an instance of [SettingsOnClickLogic], which encapsulates the
	 * business logic for handling taps on various settings options. It is initialized
	 * in `setupViewsOnClickEvents` and is responsible for triggering actions like
	 * toggling preferences, navigating to other screens, or showing dialogs.
	 *
	 * It is nullable because it's initialized only after the fragment's view is created.
	 */
	var settingsOnClickLogic: SettingsOnClickLogic? = null
	
	/**
	 * Specifies the layout resource ID for this fragment.
	 *
	 * This method is overridden to link the fragment with its corresponding XML layout file.
	 * The Android framework calls this during the fragment's creation process to inflate
	 * the user interface.
	 *
	 * @return The integer ID of the layout resource (e.g., `R.layout.frag_settings_1_main_1`).
	 */
	override fun getLayoutResId(): Int {
		logger.d("Providing layout resource ID for SettingsFragment")
		return R.layout.frag_settings_1_main_1
	}
	
	/**
	 * Called after the fragment's layout has been successfully inflated and is ready for use.
	 *
	 * This method serves as the primary entry point for initializing the UI. It orchestrates
	 * critical setup tasks, including:
	 * - Registering the fragment with its parent [MotherActivity] to enable communication.
	 * - Displaying dynamic information, such as the application's version.
	 * - Attaching all necessary click listeners to the interactive UI elements by delegating
	 *   to [setupViewsOnClickEvents].
	 *
	 * This function ensures that all view-related logic is executed only after the view
	 * hierarchy is guaranteed to be available, preventing `NullPointerException`s.
	 *
	 * @param layoutView The root [View] of the fragment's inflated layout.
	 * @param state A [Bundle] containing the saved state, or null if there is none.
	 */
	override fun onAfterLayoutLoad(layoutView: View, state: Bundle?) {
		logger.d("onAfterLayoutLoad() called: Initializing views and listeners")
		try {
			safeSettingsFragmentRef?.let { fragmentRef ->
				safeFragmentLayoutRef?.let { layoutRef ->
					registerSelfReferenceInMotherActivity()
					registerAuthOperationListener(fragmentRef)
					hideActualLayout()
					setupViewsOnClickEvents(fragmentRef, layoutRef)
				}
			}
		} catch (error: Exception) {
			logger.e("Exception during onAfterLayoutLoad()", error)
		}
	}
	
	/**
	 * Called when the fragment becomes visible to the user.
	 *
	 * This lifecycle method is triggered when the fragment resumes, such as when returning
	 * from another screen or when the app is brought back to the foreground. It performs
	 * two key tasks to ensure the UI is current and correctly integrated with the host activity:
	 *
	 * 1.  **Re-registers with the host activity**: Calls `registerSelfReferenceInMotherActivity()`
	 *     to re-establish its reference in the parent `MotherActivity`. This is crucial for
	 *     maintaining communication, especially if the reference was cleared during a previous
	 *     lifecycle state.
	 * 2.  **Refreshes UI state**: Invokes `settingsOnClickLogic?.updateSettingStateUI()` to
	 *     synchronize the visual state of all settings controls (e.g., toggles, text labels)
	 *     with their underlying preference values. This ensures that any changes made
	 *     elsewhere in the app are immediately reflected in the settings screen.
	 */
	override fun onResumeFragment() {
		logger.d("onResumeFragment() called: Updating UI and re-registering references")
		registerSelfReferenceInMotherActivity()
		try {
			safeSettingsFragmentRef?.let { fragmentRef ->
				safeFragmentLayoutRef?.let { layoutRef ->
					settingsOnClickLogic?.updateSettingStateUI()
					updateViewsWithCurrentData(layoutRef)
					fragmentRef.viewLifecycleOwner.lifecycleScope.launch {
						delay(500)
						if (fragmentRef.isResumed) {
							releaseActualLayout()
						}
					}
				}
			}
		} catch (error: Exception) {
			logger.e("Exception while updating settings state UI", error)
		}
	}
	
	/**
	 * Called when the fragment is no longer in the foreground and visible to the user.
	 *
	 * This lifecycle callback is triggered when the user navigates away from the fragment,
	 * but before it is stopped. It's a suitable place for pausing ongoing operations
	 * that should not run in the background, such as animations or UI updates.
	 *
	 * Currently, no specific actions are needed during this phase, so the implementation
	 * only logs the event for debugging purposes.
	 */
	override fun onPauseFragment() {
		hideActualLayout()
		logger.d("onPauseFragment() called: No cleanup necessary")
	}
	
	/**
	 * Called when the view previously created by `onAfterLayoutLoad` has been detached from the fragment.
	 *
	 * This method is the counterpart to `onAfterLayoutLoad` and is a critical part of the fragment's
	 * lifecycle for preventing memory leaks. It ensures that all resources associated with the view
	 * are released.
	 *
	 * Key cleanup actions include:
	 * - Unregistering the fragment's reference from the parent `MotherActivity` to break the link.
	 * - Nullifying references to view-related objects to allow them to be garbage-collected.
	 *
	 * This process helps avoid context leaks and ensures the application remains memory-efficient
	 * as the user navigates through different screens.
	 */
	override fun onDestroyView() {
		logger.d("onDestroyView() called: Cleaning up fragment references")
		unregisterSelfReferenceInMotherActivity()
		unregisterAuthOperationListener(safeSettingsFragmentRef)
		settingsOnClickLogic = null
		super.onDestroyView()
	}
	
	/**
	 * Callback triggered when the user's authentication session is removed (i.e., they are logged out).
	 *
	 * This method is invoked by the authentication engine when a logout event occurs. It ensures
	 * the settings UI is updated to reflect the change in authentication state. Specifically, it
	 * calls [updateUserAccountCard] to switch from displaying user-specific details to showing
	 * the "Login/Register" prompt.
	 */
	override fun onAuthenticationRemoved() {
		safeFragmentLayoutRef?.let { layoutRef ->
			updateUserAccountCard(layoutRef)
		}
	}
	
	/**
	 * Callback triggered when a user successfully signs in or registers a new account.
	 *
	 * This method is part of the [AuthOperationsListener] interface and is invoked by the
	 * authentication flow upon successful completion. Its primary role is to refresh the
	 * settings UI to reflect the user's new authenticated state.
	 *
	 * It ensures that the user account card is updated to display the user's name and
	 * replaces the "Login/Register" button with options relevant to a logged-in user.
	 */
	override fun onSuccessfulAuthentication() {
		safeFragmentLayoutRef?.let { layoutRef ->
			updateUserAccountCard(layoutRef)
		}
	}
	
	/**
	 * Establishes a communication link with the parent [MotherActivity].
	 *
	 * This function registers a weak reference of this `SettingsFragment` instance with the
	 * hosting [MotherActivity]. This allows the activity to access the fragment's
	 * public properties and methods, facilitating inter-component communication (e.g.,
	 * for coordinating UI updates or handling back-press events).
	 *
	 * Additionally, it ensures the side navigation drawer is closed upon entering the
	 * settings screen, providing a consistent user experience. The use of weak references
	 * is a deliberate choice to prevent memory leaks by not creating a strong circular
	 * dependency between the activity and the fragment.
	 */
	private fun registerSelfReferenceInMotherActivity() {
		logger.d("Registering SettingsFragment reference with MotherActivity")
		try {
			safeMotherActivityRef?.settingsFragment = safeSettingsFragmentRef
			safeMotherActivityRef?.sideNavigation?.closeDrawerNavigation()
		} catch (error: Exception) {
			logger.e("Error while registering fragment with MotherActivity", error)
		}
	}
	
	/**
	 * Clears this fragment's reference from the parent [MotherActivity].
	 *
	 * This is a critical cleanup step, typically called during `onDestroyView`, to prevent memory
	 * leaks. By setting the `settingsFragment` property in the activity to `null`, it breaks
	 * the circular reference between the activity and the fragment, allowing the fragment
	 * instance to be garbage-collected after it is destroyed.
	 */
	private fun unregisterSelfReferenceInMotherActivity() {
		logger.d("Unregistering SettingsFragment reference from MotherActivity")
		try {
			safeMotherActivityRef?.settingsFragment = null
		} catch (error: Exception) {
			logger.e("Error during fragment unregistration", error)
		}
	}
	
	/**
	 * Configures all onClick listeners for the settings controls.
	 *
	 * This function initializes the [SettingsOnClickLogic] handler and then constructs a map
	 * linking each interactive view's resource ID to a corresponding action lambda. It iterates
	 * through this map, attaching a `setOnClickListener` to each view.
	 *
	 * The logic for handling these clicks is delegated to [SettingsOnClickLogic] to maintain
	 * a clean separation between UI setup and business logic. This centralized approach
	 * simplifies listener management and improves code readability.
	 *
	 * @param settingsFragmentRef A reference to the current [SettingsFragment], passed to the
	 *        logic handler to enable context-aware operations like starting activities or
	 *        accessing resources.
	 * @param fragmentLayout The root view of the fragment, used to find the interactive
	 *        UI elements by their IDs.
	 */
	private fun setupViewsOnClickEvents(settingsFragmentRef: SettingsFragment, fragmentLayout: View) {
		logger.d("Setting up onClick listeners for settings actions")
		try {
			settingsOnClickLogic = SettingsOnClickLogic(settingsFragmentRef)
			
			val clickActions = mapOf(
				// Application settings
				R.id.btn_user_info to { settingsOnClickLogic?.showUsernameEditor() },
				R.id.btn_login_register_to_cloud to { settingsOnClickLogic?.showLoginOrRegistrationDialog() },
				R.id.btn_default_download_location to { settingsOnClickLogic?.showDownloadLocationPicker() },
				R.id.btn_language_picker to { settingsOnClickLogic?.showLanguageChanger() },
				R.id.btn_dark_mode_ui to { settingsOnClickLogic?.togglesDarkModeUISettings() },
				R.id.btn_content_location to { settingsOnClickLogic?.changeDefaultContentRegion() },
				R.id.btn_daily_suggestions to { settingsOnClickLogic?.toggleDailyContentSuggestions() },
				
				// Download settings
				R.id.btn_default_download_folder to { settingsOnClickLogic?.changeDefaultDownloadFolder() },
				R.id.btn_hide_task_notifications to { settingsOnClickLogic?.toggleHideDownloadNotification() },
				R.id.btn_wifi_only_downloads to { settingsOnClickLogic?.toggleWifiOnlyDownload() },
				R.id.btn_single_click_open to { settingsOnClickLogic?.toggleSingleClickToOpenFile() },
				R.id.btn_play_notification_sound to { settingsOnClickLogic?.toggleDownloadNotificationSound() },
				R.id.btn_adv_downloads_settings to { settingsOnClickLogic?.openAdvanceDownloadsSettings() },
				
				// Browser settings
				R.id.btn_browser_homepage to { settingsOnClickLogic?.setBrowserDefaultHomepage() },
				R.id.btn_enable_adblock to { settingsOnClickLogic?.toggleBrowserBrowserAdBlocker() },
				R.id.btn_enable_popup_blocker to { settingsOnClickLogic?.toggleBrowserPopupAdBlocker() },
				R.id.btn_show_image_on_web to { settingsOnClickLogic?.toggleBrowserWebImages() },
				R.id.btn_enable_video_grabber to { settingsOnClickLogic?.toggleBrowserVideoGrabber() },
				R.id.btn_adv_browser_settings to { settingsOnClickLogic?.openAdvanceBrowserSettings() },
				
				// Custom services
				R.id.btn_share_with_friends to { settingsOnClickLogic?.shareApplicationWithFriends() },
				R.id.btn_open_feedback to { settingsOnClickLogic?.openUserFeedbackActivity() },
				R.id.btn_open_about_info to { settingsOnClickLogic?.openApplicationInformation() },
				R.id.btn_open_privacy_policy to { settingsOnClickLogic?.showPrivacyPolicyActivity() },
				R.id.btn_open_terms_condition to { settingsOnClickLogic?.showTermsConditionActivity() },
				
				// Updates and reset
				R.id.btn_check_new_update to { settingsOnClickLogic?.checkForNewApkVersion() },
				R.id.btn_restart_application to { settingsOnClickLogic?.restartApplication() },
				
				// Developer acknowledgements
				R.id.btn_follow_shibafoss to { settingsOnClickLogic?.followDeveloperAtInstagram() },
			)
			
			// Apply click actions to respective view elements
			clickActions.forEach { (id, action) ->
				fragmentLayout.setClickListener(id) {
					logger.d("Click triggered for viewId=$id")
					try {
						action()
					} catch (error: Exception) {
						logger.e("Error executing click action for viewId=$id", error)
					}
				}
			}
		} catch (error: Exception) {
			logger.e("Error during click listener setup", error)
		}
	}
	
	/**
	 * Updates the UI elements to reflect the current state of user preferences and application data.
	 *
	 * This function is responsible for synchronizing the visual state of all settings controls—such
	 * as switches, text fields, and buttons—with their underlying stored values. It queries the
	 * relevant preference managers and data sources to fetch the latest data and applies it to
	 * the corresponding views.
	 *
	 * Key responsibilities include:
	 * - **Displaying User Information**: Sets the text for the user's name.
	 * - **Reflecting Toggle States**: Updates the visual state (e.g., checked/unchecked) of
	 *   switches for settings like dark mode, Wi-Fi only downloads, ad-blocking, etc.
	 * - **Showing Selected Values**: Updates text fields to display the currently configured
	 *   download location, content region, and browser homepage.
	 * - **Managing Visibility**: Hides the loading indicator and shows the main content layout
	 *   once all data has been loaded and applied.
	 *
	 * This method is typically called during the fragment's `onResume` or after an initial setup
	 * to ensure the UI is always up-to-date.
	 */
	fun updateViewsWithCurrentData(fragmentLayout: View) {
		displayApplicationVersion(fragmentLayout)
		updateUserAccountCard(fragmentLayout)
	}
	
	/**
	 * Updates the user account information card in the UI based on the user's login status.
	 *
	 * This function dynamically adjusts the visibility and content of several UI elements related
	 * to the user account:
	 * - If a non-null `username` is provided, it displays the user's name, shows the "PRO" badge
	 *   if `isPro` is true, and reveals the "User Info" button while hiding the "Login/Register" button.
	 * - If `username` is null, it indicates a logged-out state by hiding the "User Info" button and
	 *   displaying the "Login/Register" button instead.
	 *
	 * This allows the settings screen to reflect the current authentication state in real-time.
	 *
	 * @param username The display name of the logged-in user, or `null` if the user is not logged in.
	 * @param isPro A boolean flag indicating whether the user has a "PRO" subscription. This is only
	 *              relevant if `username` is not null.
	 */
	fun updateUserAccountCard(fragmentLayout: View) {
		safeMotherActivityRef?.activityScope?.launch(Dispatchers.Main) {
			fragmentLayout.findViewById<TextView>(R.id.txt_connect_to_cloud).let {
				if (AIOUserProfileManager.getAIOUserProfile().isUserAccountVerified) {
					it.text = getText(R.string.title_view_account_details)
					it.setLeftSideDrawable(R.drawable.ic_button_account)
					it.setRightSideDrawable(R.drawable.ic_button_arrow_next, true)
				} else {
					it.text = getText(R.string.title_sign_in_register)
					it.setCompoundDrawables(null, null, null, null)
					it.setLeftSideDrawable(R.drawable.ic_button_connect)
				}
			}
		}
	}
	
	/**
	 * Populates the designated `TextView` with the application's version information.
	 *
	 * This function retrieves the `versionName` and `versionCode` from `AppVersionUtility`
	 * and formats them into a two-line HTML string. The resulting `Spanned` text is then
	 * set on the `TextView` identified by `R.id.txt_version_info`. This provides users
	 * with clear visibility of the current application build.
	 *
	 * If the `TextView` cannot be found or an error occurs during formatting, the
	 * exception is logged without crashing the application.
	 *
	 * @param fragmentLayout The root view of the fragment, used to locate the `TextView`
	 *                       where the version information will be displayed.
	 */
	private fun displayApplicationVersion(fragmentLayout: View) {
		logger.d("Setting version display: versionName=$versionName, versionCode=$versionCode")
		try {
			with(fragmentLayout) {
				findViewById<TextView>(R.id.txt_version_info)?.apply {
					val versionNameText = "${getString(R.string.title_version_number)} $versionName"
					val versionCodeText = "${getString(R.string.title_build_version)} $versionCode"
					text = fromHtmlStringToSpanned("${versionNameText}<br/>${versionCodeText}")
				}
			}
		} catch (error: Exception) {
			logger.e("Error initializing version info view", error)
		}
	}
	
	/**
	 * Attaches a click listener to a child view within this [View] container, identified by its resource ID.
	 *
	 * This extension function simplifies the process of setting an `OnClickListener` by encapsulating
	 * the `findViewById` call and the listener attachment in a single, expressive line. It also includes
	 * error handling to prevent crashes if the view ID is invalid or another issue occurs.
	 *
	 * Example usage:
	 * ```
	 * fragmentLayout.setClickListener(R.id.my_button) {
	 *     // Action to perform on click
	 * }
	 * ```
	 *
	 * @receiver The parent [View] from which the child view will be found.
	 * @param id The resource ID of the target child view.
	 * @param action The lambda function to be executed when the view is clicked.
	 */
	private fun View.setClickListener(id: Int, action: () -> Unit) {
		try {
			findViewById<View>(id)?.setOnClickListener { action() }
		} catch (error: Exception) {
			logger.e("Error setting click listener for id=$id", error)
		}
	}
	
	/**
	 * Unbinds views and releases resources to prevent memory leaks when the fragment's
	 * view is destroyed. This method is part of the `BaseFragment` lifecycle and is
	 * called automatically to nullify references to the fragment's layout and its
	 * associated click logic handler.
	 *
	 * Key actions performed:
	 * - Nullifies the `settingsOnClickLogic` instance, breaking its reference to the
	 *   fragment and allowing it to be garbage-collected.
	 * - Calls the superclass implementation to perform standard `BaseFragment` cleanup,
	 *   which includes releasing the reference to the fragment's layout view.
	 *
	 * This cleanup is crucial for fragments with complex view hierarchies and helper
	 * classes to ensure that no context or view references are held beyond their
	 * intended lifecycle, which is a common source of memory leaks in Android.
	 */
	private fun releaseActualLayout() {
		safeFragmentLayoutRef?.let { fragLayout ->
			fragLayout.findViewById<View>(R.id.container_layout_loading).let {
				ViewUtility.hideView(it, true)
			}
			fragLayout.findViewById<View>(R.id.container_main_layout).let {
				ViewUtility.showView(it, true)
			}
		}
	}
	
	/**
	 * Hides the main content layout and displays the loading indicator.
	 *
	 * This method is intended to be called when the fragment needs to perform a
	 * background task and wants to provide visual feedback to the user that
	 * something is happening. It makes the main settings container invisible
	 * and shows a loading spinner in its place.
	 *
	 * It is the counterpart to [releaseActualLayout], which performs the opposite action.
	 */
	private fun hideActualLayout() {
		safeFragmentLayoutRef?.let { fragLayout ->
			fragLayout.findViewById<View>(R.id.container_layout_loading).let {
				ViewUtility.showView(it, true)
			}
			fragLayout.findViewById<View>(R.id.container_main_layout).let {
				ViewUtility.hideView(it, true)
			}
		}
	}
}