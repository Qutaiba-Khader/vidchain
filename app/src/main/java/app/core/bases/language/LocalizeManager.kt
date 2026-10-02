@file:Suppress("DEPRECATION")

package app.core.bases.language

import android.content.*
import android.content.res.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.language.LocalizeManager.createLocalizedContext
import app.core.bases.language.LocalizeManager.getStoredLanguageLocale
import app.core.bases.language.LocalizeManager.registerListener
import app.core.bases.language.LocalizeManager.setApplicationLanguage
import app.core.bases.language.LocalizeManager.unregisterListener
import lib.process.*
import java.util.*

/**
 * A singleton object responsible for managing the application's localization and in-app language switching.
 *
 * This manager provides a comprehensive set of tools for handling language changes at runtime.
 * It allows persisting the user's selected language, applying the locale to the application's
 * context, and notifying other components of changes to update the UI dynamically.
 *
 * ### Key Features:
 * - **Language Persistence**: Saves the user's selected language using a settings handler (`aioSettings`),
 *   ensuring the choice is remembered across app sessions.
 * - **Context Wrapping**: Provides an `onAttach` method to wrap the base `Context` of an
 *   `Activity` or `Application`. This is crucial for ensuring all resources (strings, layouts)
 *   are loaded with the correct locale.
 * - **Runtime Language Change**: The `setLocale` methods allow changing the application's
 *   language on the fly.
 * - **Listener-based Updates**: Implements a listener pattern (`registerListener`, `unregisterListener`)
 *   for components that need to react to locale changes without a full `Activity` recreation.
 *
 * ### Typical Usage:
 * 1.  **Initialization**: Call `onAttach(context)` within the `attachBaseContext` method of your
 *     base `Activity` and `Application` classes.
 *     ```kotlin
 *     override fun attachBaseContext(newBase: Context) {
 *         super.attachBaseContext(LocalizeManager.onAttach(newBase))
 *     }
 *     ```
 *
 * 2.  **Changing Language**: To change the language at runtime, call `setLocale` with the new
 *     `Locale`. This will persist the new language and update the configuration.
 *     ```kotlin
 */
object LocalizeManager {
	
	/**
	 * A private logger instance for recording events, warnings, and errors related
	 * to the localization process.
	 *
	 * This logger is used internally by the `LocalizeManager` to provide diagnostic
	 * information, which can be helpful for debugging issues with language switching,
	 * resource loading, or configuration updates. It is initialized lazily using the
	 * class name as its tag.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * A list of listeners that are invoked when the application's locale changes.
	 *
	 * This allows other parts of the application to subscribe to language changes without
	 * relying on the broadcast mechanism. When the locale is updated, each listener in this
	 * list will be called, receiving the new `Locale` object. This is useful for
	 * non-Activity components or for UI updates that need to happen immediately without
	 * an Activity recreation.
	 *
	 * Example usage:
	 * ```
	 * val listener: (Locale) -> Unit = { newLocale ->
	 *     // Update UI or state with the new locale
	 * }
	 *
	 * // On start
	 * LocalizeManager.listeners.add(listener)
	 *
	 * // On stop
	 * LocalizeManager.listeners.remove(listener)
	 * ```
	 */
	private val listeners = mutableSetOf<LanguageChangeListener>()
	
	/**
	 * Registers a listener to be notified of language changes.
	 *
	 * This method adds the provided [listener] to a set of active listeners. Whenever the
	 * application's locale is updated through this manager, all registered listeners will be
	 * invoked with the new `Locale`. This provides a direct callback mechanism for components
	 * that need to react to language changes without relying on a `BroadcastReceiver`.
	 *
	 * It is crucial to unregister the listener using [unregisterListener] when it's no longer
	 * needed (e.g., in an `onDestroy` or `onStop` lifecycle method) to prevent memory leaks.
	 *
	 * @param listener A `LanguageChangeListener` (which is a type alias for `(Locale) -> Unit`)
	 *   that will be executed when the locale changes.
	 * @see unregisterListener
	 */
	@JvmStatic
	fun registerListener(listener: LanguageChangeListener) {
		logger.d("Registering listener: $listener")
		listeners.add(listener)
	}
	
	/**
	 * Removes a previously registered [LanguageChangeListener] from the list of listeners.
	 *
	 * This function allows a component to stop receiving notifications about locale changes.
	 * It's important to call this method when the component is destroyed or no longer needs
	 * to be aware of language updates to prevent memory leaks.
	 *
	 * @param listener The listener instance to be removed.
	 *
	 * @see registerListener
	 */
	@JvmStatic
	fun unregisterListener(listener: LanguageChangeListener) {
		logger.d("Unregistering listener: $listener")
		listeners.remove(listener)
	}
	
	/**
	 * Wraps the base context of an `Activity` or `Application` to apply the appropriate locale.
	 *
	 * This method is a crucial part of the localization setup and should be called from
	 * the `attachBaseContext` method. It retrieves the user's persisted language preference.
	 * If no language has been saved, it falls back to the device's default language.
	 * It then returns a new `Context` with the correct locale configuration applied, ensuring
	 * that all resources (like strings and layouts) are loaded in the selected language.
	 *
	 * ### Usage
	 * In your `Activity` or `Application` class:
	 * ```kotlin
	 * override fun attachBaseContext(newBase: Context) {
	 *     super.attachBaseContext(LocalizeManager.onAttach(newBase))
	 * }
	 * ```
	 *
	 * @param context The base context to be wrapped.
	 * @return A new `Context` with the updated locale configuration.
	 */
	@JvmStatic
	fun onAttach(context: Context): Context {
		val storedLanguageCode = getStoredLanguageCode(context)
		return setApplicationLanguage(context, storedLanguageCode)
	}
	
	/**
	 * Retrieves the currently selected language code.
	 *
	 * This function fetches the language code that has been persisted in SharedPreferences.
	 * If no language has been explicitly set and persisted, it defaults to the
	 * current language of the device's default locale.
	 *
	 * @param context The context to use for accessing SharedPreferences.
	 * @return A string representing the language code (e.g., "en", "fr").
	 */
	@JvmStatic
	fun getLanguageCode(context: Context): String {
		return getStoredLanguageCode(context)
	}
	
	/**
	 * Sets the application's locale.
	 *
	 * This function persists the chosen language and then updates the application's
	 * configuration to reflect the new locale. It handles different Android versions
	 * by calling the appropriate resource update method.
	 *
	 * @param context The context from which to get resources and SharedPreferences.
	 * @param languageCode The language code (e.g., "en", "fr") to set.
	 * @return A new context with the updated configuration.
	 */
	@JvmStatic
	fun setApplicationLanguage(context: Context, languageCode: String): Context {
		storeLanguageCode(context, languageCode)
		return createLocalizedContext(context, languageCode)
	}
	
	/**
	 * Sets the application's locale by persisting it and notifying listeners of the change.
	 *
	 * This method takes a `Locale` object, saves its language code to `SharedPreferences`
	 * for persistence across app sessions, and then updates the application's resources
	 * to reflect the new language. It also invokes all registered `LanguageChangeListener`
	 * instances, providing them with the new locale.
	 *
	 * This is a key method for triggering a runtime language switch. After calling this,
	 * you typically need to recreate UI components (like Activities) for the changes
	 * to take full effect visually.
	 *
	 * @param context The context used for accessing `SharedPreferences` and updating resources.
	 * @param locale The new `Locale` to be set for the application.
	 * @see createLocalizedContext
	 * @see registerListener
	 */
	@JvmStatic
	fun setApplicationLanguage(context: Context, locale: Locale) {
		storeLanguageCode(context, locale.language)
		createLocalizedContext(context, locale.language)
		listeners.forEach { listener ->
			listener.onLanguageChanged(locale)
		}
	}
	
	/**
	 * Retrieves the persisted language code from shared settings.
	 *
	 * This function accesses the application's shared settings (via `aioSettings`) to
	 * retrieve the user's selected UI language. If no language has been explicitly
	 * stored, it returns the provided `defaultLanguage`.
	 *
	 * @param context The context, which is unused in the current implementation but kept
	 *   for potential future use or API consistency.
	 * @param defaultLanguage The language code to return if no language code is found in
	 *   the settings. This value is also unused as `aioSettings` handles its own default.
	 * @return The stored language code (e.g., "en", "fr") or a default value if not set.
	 */
	@JvmStatic
	private fun getStoredLanguageCode(context: Context): String {
		return LocalStoredLangPref.languageCode
	}
	
	/**
	 * Persists the selected language code using `SharedPreferences`.
	 *
	 * This function saves the provided language code (e.g., "en", "fr") to the
	 * default `SharedPreferences` of the application. This ensures that the user's
	 * language choice is remembered across application sessions. The language is stored
	 * under the `SELECTED_LANGUAGE` key.
	 *
	 * @param context The context used to access `SharedPreferences`.
	 * @param language The language code string to save (e.g., "en", "es").
	 */
	@JvmStatic
	private fun storeLanguageCode(context: Context, languageCode: String) {
		ThreadsUtility.executeInBackground(timeOutInMilli = 100, codeBlock = {
			LocalStoredLangPref.languageCode = languageCode
			try {
				if (INSTANCE.isAIOSettingLoaded()) {
					aioSettings.userSelectedUILanguage = languageCode
					aioSettings.updateInStorage()
				}
			} catch (error: Exception) {
				logger.e("Error storing language code:", error)
			}
		})
	}
	
	/**
	 * Creates a new context with an updated configuration for the specified language.
	 *
	 * This function takes a language code, creates a `Locale` from it, and sets this
	 * new locale as the default for the JVM. It then updates the `Configuration` of the
	 * provided context to use the new locale and its corresponding layout direction.
	 * Finally, it returns a new `Context` with this updated configuration, which should
	 * be used to ensure all resources are loaded correctly for the new language.
	 *
	 * This method is the standard way to update resources for Android API 17 (Jelly Bean MR1)
	 * and above.
	 *
	 * @param context The current context.
	 * @param language The language code (e.g., "en", "ar") to apply.
	 * @return A new [Context] with the locale configuration updated.
	 */
	@JvmStatic
	private fun createLocalizedContext(context: Context, language: String): Context {
		val locale = Locale(language)
		Locale.setDefault(locale)
		val configuration = context.resources.configuration
		configuration.setLocale(locale)
		configuration.setLayoutDirection(locale)
		return context.createConfigurationContext(configuration)
	}
	
	/**
	 * Updates the application's locale for a given [Context] to the specified language.
	 *
	 * This function creates a new `Locale` from the provided [language] code. It then generates
	 * an updated `Configuration` with this new locale and applies it to the context, returning
	 * a new `Context` instance that is properly configured for the new language. This is the
	 * core mechanism for applying a language change to a `Context`, which is essential for
	 * ensuring that resources like strings and layouts are loaded correctly.
	 *
	 * This method is suitable for modern Android versions (API 17+).
	 *
	 * @param context The base `Context` whose configuration needs to be updated.
	 * @param language The language code (e.g., "en", "fr", "ar") to apply.
	 * @return A new `Context` with the updated locale configuration.
	 */
	@JvmStatic
	fun updateApplicationLocale(context: Context, locale: Locale): Configuration? {
		setApplicationLanguage(context, locale)
		return setApplicationLanguage(context)
	}
	
	/**
	 * Retrieves the persisted `Locale` based on the stored language code.
	 *
	 * This function fetches the language code from the application's shared settings
	 * via `aioSettings`. It then constructs and returns a `Locale` object from this code.
	 * If no language has been explicitly stored, the default value from `aioSettings` is used.
	 *
	 * @param context The context, which is unused in the current implementation but kept
	 *   for potential future use or API consistency.
	 * @return The `Locale` object corresponding to the user's selected language.
	 */
	@JvmStatic
	fun getStoredLanguageLocale(context: Context): Locale {
		val lang = getStoredLanguageCode(context)
		return Locale(lang)
	}
	
	/**
	 * Updates the application's configuration with the currently persisted locale.
	 *
	 * This function retrieves the saved locale using [getStoredLanguageLocale]. If the current
	 * configuration's locale is different from the saved one, it updates the
	 * configuration with the new locale, persists the change, and applies it to the
	 * application's resources.
	 *
	 * This method is deprecated and uses `updateConfiguration` which is deprecated on
	 * newer API levels. Consider using `Context.createConfigurationContext` instead.
	 *
	 * @param context The context from which to access resources and SharedPreferences.
	 * @return A new [Configuration] object with the updated locale if a change was made,
	 *         otherwise `null`.
	 */
	@JvmStatic
	fun setApplicationLanguage(context: Context): Configuration? {
		val resources: Resources = context.resources
		val configuration: Configuration = resources.configuration
		val locale: Locale = getStoredLanguageLocale(context)
		if (configuration.locale != locale) {
			configuration.setLocale(locale)
			setApplicationLanguage(context, locale)
			resources.updateConfiguration(configuration, resources.displayMetrics)
			return configuration
		}
		return null
	}
	
	/**
	 * Initializes the application's locale based on the persisted language setting.
	 *
	 * This function retrieves the saved locale using [getStoredLanguageLocale] and then applies it
	 * to the application's context by calling [setApplicationLanguage] with the corresponding
	 * language code. It's a convenient way to ensure the correct language is set
	 * when the application starts or when a configuration change requires it.
	 *
	 * @param context The context used for retrieving the saved locale and applying the new one.
	 */
	@JvmStatic
	fun initializeLocale(context: Context) {
		LocalStoredLangPref.init(context)
		val locale = getStoredLanguageLocale(context)
		setApplicationLanguage(context, locale.language)
	}
}
