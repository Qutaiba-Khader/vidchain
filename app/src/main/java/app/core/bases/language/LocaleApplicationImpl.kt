package app.core.bases.language

import android.app.*
import android.content.*
import java.util.*

/**
 * A base [Application] class that provides localization support.
 *
 * This class should be extended by the main Application class of the project
 * to enable automatic handling of locale changes and context wrapping. It integrates
 * with a [LocalizeManager] to manage the application's locale.
 *
 * It overrides key [Application] lifecycle methods:
 * - `onCreate()`: Initializes the locale when the application starts.
 * - `attachBaseContext()`: Wraps the base context with a context that respects the
 *   currently selected locale. This is crucial for ensuring all resources (strings, layouts, etc.)
 *   are loaded with the correct language and region.
 *
 * It also implements the [LocaleManagerInf] interface, providing a standardized
 * way to change the application's locale via the `setLocale` method.
 *
 * Example Usage in `AndroidManifest.xml`:
 * ```xml
 * <application
 *     android:name=".YourCustomApplication"
 *     ... >
 *     ...
 * </application>
 * ```
 *
 * And in your custom Application class:
 * ```kotlin
 * class YourCustomApplication : LocalizationApplication() {
 *     // Your application-specific logic here
 * }
 * ```
 */
open class LocaleApplicationImpl : Application(), LocaleManagerInf, LanguageChangeListener {
	
	/**
	 * Called when the application is starting, before any other application objects have been created.
	 * This is where we initialize the localization manager to set up the initial locale
	 * for the application.
	 */
	override fun onCreate() {
		super.onCreate()
		LocalizeManager.initializeLocale(this)
	}
	
	/**
	 * Attaches the base context to the application, wrapping it with localization support.
	 *
	 * This method is called by the system when the application is first created, before `onCreate()`.
	 * It intercepts the original context and uses [LocalizeManager.onAttach] to provide a
	 * context that respects the application's currently selected locale. This ensures that all
	 * parts of the application, including those created before `onCreate()`, use the correct
	 * language and resources.
	 *
	 * @param base The base context provided by the system.
	 */
	override fun attachBaseContext(base: Context) {
		super.attachBaseContext(LocalizeManager.onAttach(base))
	}
	
	/**
	 * Sets the application's locale.
	 *
	 * This method delegates the locale change logic to [LocalizeManager.updateApplicationLocale],
	 * which will update the application's configuration and resources to reflect the new language.
	 * This change will typically be applied to all components within the application.
	 *
	 * @param locale The new [Locale] to be set for the application.
	 */
	override fun setLanguageLocale(locale: Locale) {
		LocalizeManager.updateApplicationLocale(this, locale)
	}
	
	/**
	 * Callback method invoked when the application's language has been successfully changed.
	 *
	 * This method provides a hook for the application to react to locale updates. Subclasses
	 * can override this method to perform specific actions after a language change, such as
	 * restarting services, refreshing data that depends on the locale, or updating any
	 * global state that is language-sensitive.
	 *
	 * This is called by the [LocalizeManager] after the new locale has been applied.
	 *
	 * @param newLocale The new [Locale] that has been set for the application.
	 */
	override fun onLanguageChanged(newLocale: Locale) {
		// Application can react to language changes if needed
		// For example, restart services or update global state
	}
}