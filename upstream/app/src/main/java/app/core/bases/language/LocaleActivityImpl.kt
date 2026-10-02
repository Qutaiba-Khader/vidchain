@file:Suppress("DEPRECATION")

package app.core.bases.language

import android.content.*
import android.content.res.*
import android.os.*
import androidx.appcompat.app.*
import java.util.*

/**
 * An `AppCompatActivity` that provides foundational support for in-app language switching.
 *
 * This abstract base class simplifies the process of implementing dynamic localization within an
 * Android application. It transparently handles the critical lifecycle events and context
 * wrapping required to ensure that the correct language resources are loaded and displayed.
 *
 * ### Key Responsibilities:
 * - **Context Wrapping:** In `attachBaseContext`, it wraps the activity's base context with a
 *   new context configured for the currently selected locale. This is the cornerstone of
 *   in-app language switching, ensuring all resource lookups (strings, layouts, etc.)
 *   use the correct language from the moment the activity is created.
 * - **Lifecycle Management for Locale Changes:** It implements `LanguageChangeListener` and
 *   registers itself with `LocalizeManager`. When the language is changed, `onLanguageChanged`
 *   is triggered, which updates the activity's configuration and then calls `recreate()` to
 *   redraw the UI with the new language resources.
 * - **Configuration Change Handling:** It properly handles `onConfigurationChanged` to ensure
 *   the activity is recreated smoothly when the system locale changes.
 *
 * ### Usage
 * To create an activity that supports on-the-fly language changes, simply extend this class
 * instead of the standard `AppCompatActivity`.
 *
 * ```kotlin
 * class MyProfileActivity : LocaleActivityImpl() {
 *     override fun onCreate(savedInstanceState: Bundle?) {
 *         super.onCreate(savedInstanceState)
 *         setContentView(R.layout.activity_my_profile)
 *         // The UI will automatically use the correct language.
 *     }
 * }
 * ```
 *
 * @see LocaleManagerInf
 * @see LocalizeManager
 * @see LanguageChangeListener
 */
open class LocaleActivityImpl : AppCompatActivity(), LocaleManagerInf, LanguageChangeListener {
	
	/**
	 * Initializes the activity and registers it to listen for language changes.
	 *
	 * This implementation first calls the superclass's `onCreate` method. It then registers
	 * this activity as a listener with the `LocalizeManager`. This ensures that when the
	 * application's language is changed via `LocalizeManager`, this activity will be notified
	 * through its `onLanguageChanged` method, allowing it to recreate itself with the
	 * updated locale.
	 *
	 * @param savedInstanceState If the activity is being re-initialized after a previous
	 * shutdown, this `Bundle` contains the most recent data supplied in
	 * `onSaveInstanceState(Bundle)`. Otherwise, it is null.
	 * @see onLanguageChanged
	 * @see LocalizeManager.registerListener
	 */
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		LocalizeManager.registerListener(this)
	}
	
	/**
	 * Callback method invoked when the application's language is changed.
	 *
	 * This function is triggered by the [LocalizeManager] whenever a new locale is set.
	 * This implementation updates the current activity's configuration to the `newLocale` and then
	 * schedules the activity to be recreated after a short delay. The recreation process
	 * ensures that the entire UI is reloaded with the correct language resources.
	 *
	 * Subclasses can override this method to add custom behavior, but it is crucial to call
	 * `super.onLanguageChanged(newLocale)` to maintain the core recreation logic.
	 *
	 * @param newLocale The new [Locale] that has been set for the application.
	 * @see LocalizeManager.setApplicationLanguage
	 * @see LanguageChangeListener
	 */
	override fun onLanguageChanged(newLocale: Locale) {
		updateActivityConfiguration(newLocale)
		Handler(Looper.getMainLooper())
			.postDelayed({ recreate() }, LocalizeConstant.PREPARE_DELAY)
	}
	
	/**
	 * Immediately updates the activity's configuration to reflect a new locale.
	 *
	 * This function is called in `onLanguageChanged` to manually update the `resources`
	 * configuration of the current activity. By doing so, it ensures that any resources
	 * accessed *between* the language change event and the `recreate()` call will resolve
	 * to the new language. This helps prevent inconsistencies, such as a new `Activity`
	 * being started with the old language's resources before this one has been fully recreated.
	 *
	 * While `recreate()` is the primary mechanism for a full UI refresh, this function provides
	 * an immediate, intermediate update to the existing activity instance's context.
	 *
	 * @param newLocale The new [Locale] to apply to the activity's resource configuration.
	 */
	private fun updateActivityConfiguration(locale: Locale) {
		val configuration = resources.configuration
		configuration.setLocale(locale)
		configuration.setLocales(LocaleList(locale))
		resources.updateConfiguration(configuration, resources.displayMetrics)
	}
	
	/**
	 * Attaches the base context to the activity, wrapping it with localization support.
	 *
	 * This method is called by the Android system *before* `onCreate()` and is the cornerstone
	 * for applying the correct language configuration to the activity's resources. It intercepts
	 * the original `baseContext` and uses [LocalizeManager.onAttach] to create a new,
	 * locale-aware context configured with the user's selected language. This ensures that all
	 * subsequent resource lookups (e.g., strings, layouts) within the activity are resolved
	 * using the correct translation.
	 *
	 * @param baseContext The new base context for this activity, provided by the system.
	 */
	override fun attachBaseContext(baseContext: Context) {
		super.attachBaseContext(LocalizeManager.onAttach(baseContext))
	}
	
	/**
	 * Called when the activity is being destroyed.
	 *
	 * This implementation unregisters the activity as a [LanguageChangeListener] from the
	 * [LocalizeManager] to prevent memory leaks and ensure that no further locale change
	 * events are sent to this defunct activity instance.
	 */
	public override fun onDestroy() {
		LocalizeManager.unregisterListener(this)
		super.onDestroy()
	}
	
	/**
	 * Sets and applies a new language locale for the application.
	 *
	 * This method initiates the language change process by delegating to
	 * [LocalizeManager.setApplicationLanguage]. This central manager handles persisting
	 * the new locale preference and notifying registered listeners (like this activity)
	 * via [onLanguageChanged] to update their UI accordingly.
	 *
	 * This is the primary method to call when a user selects a new language within the app.
	 *
	 * @param locale The new [Locale] to be set for the application.
	 * @see LocalizeManager.setApplicationLanguage
	 * @see onLanguageChanged
	 */
	override fun setLanguageLocale(locale: Locale) {
		LocalizeManager.setApplicationLanguage(this, locale)
	}
	
	/**
	 * Handles device configuration changes that occur while the activity is running.
	 *
	 * This method is called by the Android system when a configuration change occurs, such as a
	 * device orientation change or a system-level language update. This override ensures that
	 * the activity's UI is correctly updated to reflect the new configuration.
	 *
	 * It first calls the superclass implementation to handle standard configuration updates.
	 * Then, it schedules the activity to be recreated after a short delay. This `recreate()` call
	 * is essential for ensuring that the entire activity, including all its resources and views,
	 * is re-inflated using the new configuration, preventing UI inconsistencies. The delay provides
	 * a buffer for the system to finalize the configuration change before the UI is redrawn.
	 *
	 * @param newConfiguration The new device configuration provided by the system.
	 */
	override fun onConfigurationChanged(newConfiguration: Configuration) {
		super.onConfigurationChanged(newConfiguration)
		Handler(Looper.getMainLooper())
			.postDelayed({ recreate() }, LocalizeConstant.PREPARE_DELAY)
	}
	
	/**
	 * Navigates to a dedicated language selection screen, allowing the user to change the
	 * application's locale.
	 *
	 * This function provides a standardized way to initiate the language-switching UI flow.
	 * It calls `LanguagePrepareActivity.navigate()`, which starts the `LanguagePrepareActivity`.
	 * This activity is typically responsible for displaying a list of available languages and
	 * handling the user's selection.
	 *
	 * @see LanguagePrepareActivity
	 */
	open fun openPrepareLocalize() {
		LanguagePrepareActivity.navigate(this)
	}
}