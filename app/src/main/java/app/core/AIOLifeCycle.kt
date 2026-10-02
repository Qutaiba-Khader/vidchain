package app.core

import android.app.*
import android.app.Application.*
import android.os.*

/**
 * A simplified version of [Application.ActivityLifecycleCallbacks] with default empty method implementations.
 *
 * This interface allows for selective overriding of activity lifecycle methods. Implementers
 * can choose to only override the specific callbacks they need, avoiding the boilerplate of
 * implementing all methods from the base [Application.ActivityLifecycleCallbacks] interface.
 *
 * Example usage:
 * ```kotlin
 * class MyLifecycleTracker : AIOLifeCycle {
 *     override fun onActivityResumed(activity: Activity) {
 *         Log.d("Lifecycle", "${activity.simpleName} resumed")
 *     }
 *
 *     override fun onActivityPaused(activity: Activity) {
 *         Log.d("Lifecycle", "${activity.simpleName} paused")
 *     }
 * }
 * ```
 */
interface AIOLifeCycle : ActivityLifecycleCallbacks {
	
	/**
	 * Called when an activity is first created. This is where you should do all of your normal
	 * static set up: create views, bind data to lists, etc. This method also provides you with a
	 * Bundle containing the activity's previously frozen state, if there was one.
	 *
	 * Always followed by `onActivityStarted()`.
	 *
	 * @param activity The activity being created.
	 * @param savedInstanceState If the activity is being re-initialized after
	 *     previously being shut down then this Bundle contains the data it most
	 *     recently supplied in `onActivitySaveInstanceState(Activity, Bundle)`.
	 *     **Note: Otherwise it is null.**
	 */
	override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
	
	/**
	 * Called when the activity has become visible to the user.
	 *
	 * This is followed by [onActivityResumed] if the activity comes to the foreground,
	 * or [onActivityStopped] if it becomes hidden by another dialog or activity.
	 *
	 * @param activity The activity that has started.
	 */
	override fun onActivityStarted(activity: Activity) {}
	
	/**
	 * Called when the activity will start interacting with the user. This is a good place
	 * to start animations, open exclusive-access devices (such as the camera), etc.
	 *
	 * @param activity The activity that is resuming.
	 */
	override fun onActivityResumed(activity: Activity) {}
	
	/**
	 * Called when the activity is no longer in the foreground. This can happen, for example,
	 * when another activity is launched on top of it, the screen is turned off, or the user
	 * navigates away.
	 *
	 * This is a good place to stop animations, release resources that are not needed while
	 * the activity is paused, and commit unsaved changes. Any CPU-intensive operations
	 * should be stopped here to ensure a smooth transition to the next activity.
	 *
	 * @param activity The activity being paused.
	 */
	override fun onActivityPaused(activity: Activity) {}
	
	/**
	 * Called when the activity is no longer visible to the user. This may happen because
	 * a new activity is being started, an existing one is being brought in front of this one,
	 * or this one is being destroyed.
	 *
	 * @param activity The activity that is no longer visible.
	 */
	override fun onActivityStopped(activity: Activity) {}
	
	/**
	 * Called to retrieve per-instance state from an activity before it is killed
	 * so that the state can be restored in {@link #onActivityCreated} or
	 * {@link Activity#onRestoreInstanceState}.
	 *
	 * This callback is called between {@link #onActivityPaused} and {@link #onActivityStopped}.
	 *
	 * @param activity The activity being saved.
	 * @param outState A [Bundle] in which to place your saved state.
	 */
	override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
	
	/**
	 * Called when an activity is being destroyed. This is the final call
	 * the activity will receive. It could be called because the activity is
	 * finishing (someone called finish() on it), or because the system is
	 * temporarily destroying this instance of the activity to save space.
	 *
	 * @param activity The activity being destroyed.
	 */
	override fun onActivityDestroyed(activity: Activity) {}
}
