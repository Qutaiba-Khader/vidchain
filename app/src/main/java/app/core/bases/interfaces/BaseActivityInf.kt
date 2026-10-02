package app.core.bases.interfaces

import app.core.bases.*
import kotlinx.coroutines.*

/**
 * Defines the core contract for base activities within the application.
 *
 * This interface outlines the essential lifecycle callbacks, UI management functions,
 * and utility methods that all activities extending from [BaseActivity] must implement or have access to.
 * It enforces a consistent structure for handling layout inflation, lifecycle events,
 * navigation, permissions, and other common tasks, promoting a standardized and robust
 * architecture across the app's activities.
 *
 * Implementations of this interface, such as [BaseActivity], provide the concrete logic
 * for these operations, simplifying activity development and ensuring predictable behavior.
 *
 * @see BaseActivity
 */
interface BaseActivityInf {
	
	/**
	 * Specifies the layout resource ID for the activity's content view.
	 * This method is called during the activity's creation process to inflate the main layout.
	 *
	 * @return The layout resource ID (e.g., `R.layout.activity_main`).
	 */
	fun onRenderingLayout(): Int
	
	/**
	 * This method is called immediately after the activity's layout has been inflated and rendered.
	 * It's the ideal place to initialize views, set up listeners, or perform any other setup
	 * that requires the view hierarchy to be fully constructed.
	 *
	 * For example, you can use this method to:
	 * - Find views by their ID (e.g., using `findViewById` or view binding).
	 * - Set click listeners on buttons.
	 * - Populate UI elements with initial data.
	 * - Start animations or transitions.
	 */
	fun onAfterLayoutRender()
	
	/**
	 * Called when the activity will start interacting with the user.
	 * At this point, the activity is at the top of the activity stack, with user input going to it.
	 * This method is a lifecycle callback and should be implemented to handle tasks
	 * that need to run when the activity becomes active, such as resuming animations,
	 * registering listeners, or acquiring resources that were released in [onPauseActivity].
	 */
	fun onResumeActivity()
	
	/**
	 * Called when the activity is no longer in the foreground, but is still visible (for example, if another
	 * activity is placed on top of it). This corresponds to the `onPause` lifecycle event.
	 *
	 * Implement this method in [BaseActivity] to release any resources that are not needed while the
	 * activity is paused, such as stopping animations or other ongoing actions that may consume CPU.
	 * It's also a good place to commit unsaved changes to persistent data, as the process hosting your
	 * activity can be killed after this method returns.
	 *
	 * @see onResumeActivity
	 * @see BaseActivity.onPause
	 */
	fun onPauseActivity()
	
	/**
	 * Launches a system permission request dialog for the specified permissions.
	 * The result of this request is handled by the `onRequestPermissionsResult`
	 * method in the implementing activity.
	 *
	 * @param permissions An ArrayList of permission constants (e.g., Manifest.permission.CAMERA)
	 * to be requested from the user.
	 */
	fun launchPermissionRequest(permissions: ArrayList<String>)
	
	/**
	 * Sets the colors for the system status bar and navigation bar.
	 *
	 * This function allows customization of the system UI bars' appearance.
	 * It also controls the color of the icons and text within these bars (light or dark)
	 * to ensure proper contrast against the chosen background colors.
	 *
	 * @param statusBarColorResId The color resource ID for the status bar background.
	 * @param navigationBarColorResId The color resource ID for the navigation bar background.
	 * @param isLightStatusBar If true, the status bar icons (like clock, battery) will be dark,
	 *   suitable for a light background color. Defaults to false (light icons for a dark background).
	 * @param isLightNavigationBar If true, the navigation bar icons (like back, home) will be dark,
	 *   suitable for a light background color. Defaults to false (light icons for a dark background).
	 */
	fun setSystemBarsColors(
		statusBarColorResId: Int,
		navigationBarColorResId: Int,
		isLightStatusBar: Boolean = false,
		isLightNavigationBar: Boolean = false
	)
	
	/**
	 * Retrieves a [CoroutineScope] that is bound to the lifecycle of the attached Activity.
	 * This scope is automatically cancelled when the Activity is destroyed, making it safe
	 * to use for launching coroutines that should not outlive the Activity.
	 *
	 * @return The [CoroutineScope] associated with the Activity's lifecycle.
	 */
	fun getAttachedCoroutineScope(): CoroutineScope
	
	/**
	 * Executes a given code block on a specific thread associated with the activity's lifecycle.
	 *
	 * This function provides a convenient way to switch between the UI thread and a background
	 * thread managed by the activity's CoroutineScope. The execution is tied to the
	 * lifecycle of the attached activity, ensuring that the coroutine is automatically
	 * cancelled when the activity is destroyed, preventing memory leaks.
	 *
	 * @param isUIThread If true, the `codeBlock` will be executed on the main (UI) thread.
	 *                   If false (default), it will be executed on a background thread
	 *                   (typically `Dispatchers.IO` or `Dispatchers.Default` depending on the scope's context).
	 * @param codeBlock The lambda function containing the code to be executed.
	 */
	fun runCodeOnAttachedThread(isUIThread: Boolean = false, codeBlock: () -> Unit)
	
	/**
	 * Navigates to another activity.
	 *
	 * @param targetActivity The class of the activity to open (e.g., `MyActivity::class.java`).
	 * @param shouldAnimate If true, a transition animation will be applied. Defaults to true.
	 */
	fun openActivity(targetActivity: Class<*>, shouldAnimate: Boolean = true)
	
	/**
	 * Closes the current activity using a swipe-out (right to left) animation.
	 * This is typically used for navigating back or dismissing a screen.
	 *
	 * @param shouldAnimate A boolean flag to determine if the closing animation should be played.
	 *                      If `true`, the activity will animate out. Defaults to `false`.
	 */
	fun closeActivityWithSwipeAnimation(shouldAnimate: Boolean = false)
	
	/**
	 * Closes the current activity, optionally applying a fade-out animation.
	 *
	 * This method finishes the current activity. If `shouldAnimate` is true,
	 * it overrides the default transition with a fade-in/fade-out effect.
	 *
	 * @param shouldAnimate If true, a fade animation is applied upon closing the activity.
	 *                      Defaults to false, resulting in the system's default closing animation.
	 */
	fun closeActivityWithFadeAnimation(shouldAnimate: Boolean = false)
	
	/**
	 * Triggers the default back press action for the activity.
	 * This is equivalent to programmatically pressing the back button,
	 * typically closing the current activity or popping a fragment from the back stack.
	 */
	fun onBackPressActivity()
	
	/**
	 * Exits the current activity if the back button is pressed twice within a short interval (e.g., 2 seconds).
	 * This is typically used on the main or root activity of an application to prevent accidental closure.
	 * On the first back press, a toast or snack-bar is usually shown to instruct the user to press back again to exit.
	 */
	fun exitActivityOnDoubleBackPress()
	
	/**
	 * Forcibly terminates the application process.
	 *
	 * This is a drastic measure and should be used only as a last resort,
	 * such as in critical error scenarios where the application cannot recover.
	 * It immediately kills the app's process without going through the normal activity lifecycle,
	 * meaning `onDestroy()` will not be called.
	 */
	fun forceQuitApplication()
	
	/**
	 * Opens the application's information settings screen in the Android system settings.
	 * This is useful for directing the user to a place where they can manage app permissions,
	 * notifications, data usage, and other system-level settings.
	 */
	fun openAppInfoSetting()
	
	/**
	 * Opens the application's official website in the default web browser.
	 * The specific URL is expected to be defined within the implementation of this method.
	 */
	fun openApplicationOfficialSite()
	
	/**
	 * Retrieves the device's current time zone ID as a string.
	 * For example, "America/Los_Angeles" or "Europe/Berlin".
	 *
	 * @return A string representing the system's time zone ID.
	 */
	fun getTimeZoneId(): String
	
	/**
	 * Retrieves the instance of the BaseActivity this interface is attached to.
	 *
	 * @return The [BaseActivity] instance, or null if the context is not available or not a BaseActivity.
	 */
	fun getActivity(): BaseActivity?
	
	/**
	 * Triggers a simple haptic feedback vibration.
	 *
	 * Requires the `android.permission.VIBRATE` permission in the AndroidManifest.xml.
	 * This method provides a short, tactile response, useful for acknowledging user actions
	 * like button presses or successful operations. The duration is configurable but defaults
	 * to a brief pulse.
	 *
	 * @param timeInMillis The duration of the vibration in milliseconds. Defaults to 20ms.
	 */
	fun doSomeVibration(timeInMillis: Int = 20)
	
	/**
	 * Provides intent flags to launch an activity as a single top instance.
	 * If the activity is already at the top of the stack, a new instance will not be created.
	 * Instead, the existing instance will receive the new intent in its `onNewIntent` method.
	 *
	 * This is useful for preventing multiple instances of the same activity from being stacked
	 * on top of each other, for example, when handling notifications or deep links.
	 *
	 * @return An integer representing the combined bitmask of `Intent.FLAG_ACTIVITY_SINGLE_TOP`
	 * and `Intent.FLAG_ACTIVITY_CLEAR_TOP`.
	 */
	fun getSingleTopIntentFlags(): Int
}
