package app.core

import app.core.AIOApp.Companion.aioSettings
import app.core.bases.*
import app.core.bases.language.*
import lib.process.*
import java.util.*

/**
 * Serves as the central coordinator for application-wide language management.
 *
 * This class orchestrates the process of changing the application's display language at runtime.
 * It bridges user preferences (stored in `aioSettings`) with the underlying Android framework,
 * ensuring a seamless and consistent multi-language experience. It manages the complex
 * activity lifecycle transitions required for language changes to take effect reliably.
 *
 * ## Core Responsibilities
 * - **Language Application**: Applies the user's selected language to the application context.
 * - **Lifecycle Coordination**: Manages activity restarts and application-level refreshes to ensure
 *   the UI is redrawn correctly after a language change.
 * - **State Management**: Uses flags and commands (`finishActivityOnResume`, `quitApplicationCommand`)
 *   to orchestrate the language change flow, preventing race conditions and ensuring a clean
 *   transition.
 * - **Language Catalog**: Provides a list of supported languages with user-friendly display names
 *   for use in selection menus.
 *
 * This class works in tandem with lower-level locale management systems and `aioSettings` for
 * persisting the user's language choice.
 *
 * ## Usage Flow
 * 1. A user selects a new language from the UI.
 * 2. `applyUserSelectedLanguage()` is called, which sets up the new locale and flags the current
 *    activity for a restart.
 * 3. In the activity's `onResume()` method, `closeActivityIfLanguageChanged()` is called.
 * 4. This method detects the flag, finishes the current activity stack, and schedules a
 *    full application restart to ensure the new language is applied everywhere.
 */
open class AIOLanguage {
	
	/**
	 * Logger for this class, used for debugging and tracing the language change lifecycle.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Provides static constants for supported ISO 639-1 language codes.
	 *
	 * This companion object holds the language codes as compile-time constants,
	 * ensuring type-safe and consistent referencing of languages throughout the application.
	 * Using these constants prevents typos and enhances code readability when setting or
	 * checking language preferences.
	 *
	 * ## Example Usage:
	 * ```kotlin
	 * if (aioSettings.userSelectedUILanguage == AIOLanguage.ENGLISH) {
	 *     // Handle English-specific logic
	 * }
	 * ```
	 */
	companion object {
		
		/** ISO 639-1 language codes for supported languages */
		const val ENGLISH = "en"
		const val BENGALI = "bn"
		const val HINDI = "hi"
		const val TELUGU = "te"
		const val JAPANESE = "ja"
		const val DANISH = "da"
		const val GERMAN = "de"
	}
	
	/**
	 * Provides a catalog of supported languages for the application's UI.
	 *
	 * This list is used to populate language selection menus, allowing users to choose their
	 * preferred language. Each entry combines a system-level language code with a human-readable
	 * display name.
	 *
	 * ### Structure
	 * Each element is a `Pair<String, String>`:
	 * - **`first` (Language Code):** The ISO 639-1 code for the language (e.g., "en", "hi").
	 *   This code is used to configure the application's `Locale`.
	 * - **`second` (Display Name):** A user-friendly string that includes both the English name
	 *   and the native script representation (e.g., "Hindi (हिंदी)").
	 *
	 * ### Rationale for Native Script
	 * Including the language's name in its own script is crucial for usability. It helps users
	 * who may not be fluent in English to easily identify and select their native language.
	 */
	open val languagesList: List<Pair<String, String>> = listOf(
		ENGLISH to "English (Default)",
		HINDI to "Hindi (हिंदी)",
		TELUGU to "Telugu (తెలుగు)",
		BENGALI to "Bengali (বাংলা)",
		JAPANESE to "Japanese (日本語)",
		DANISH to "Danish (Dansk)",
		GERMAN to "German (Deutsch)"
	)
	
	/**
	 * Applies the user-selected language to the application and triggers a UI refresh.
	 *
	 * This function orchestrates the runtime language change. It performs the following steps:
	 * 1.  Retrieves the desired language code from persistent settings ([aioSettings]).
	 * 2.  Creates a `Locale` object from the language code.
	 * 3.  Applies the new `Locale` to the current activity's context.
	 * 4.  Calls `activity.openPrepareLocalize()`, which is responsible for recreating the activity
	 *     to ensure all UI elements are redrawn with the new language strings.
	 * 5.  Invokes an optional callback after the process is initiated.
	 *
	 * This method ensures that the language change is applied correctly within the Android
	 * lifecycle, leading to a seamless user experience. It handles null activity references
	 * gracefully by logging an error and invoking a failure callback.
	 *
	 * @param baseActivity The reference to the current activity, required to apply the locale
	 *   and trigger the recreation process. If `null`, the operation is aborted.
	 * @param afterApplyingLanguage An optional lambda function that is executed immediately after the
	 *   language change process has been successfully initiated.
	 * @param onLanguageChangeFailed An optional lambda function that is invoked if the operation
	 *   fails, for instance, due to a `null` activity reference. It receives an error message.
	 */
	fun applyUserSelectedLanguage(
		baseActivity: BaseActivity?,
		afterApplyingLanguage: () -> Unit = {},
		onLanguageChangeFailed: (String) -> Unit = {}
	) {
		if (baseActivity?.getActivity() == null) {
			val errorMessage = "skipped — activity reference is null"
			logger.d(errorMessage)
			onLanguageChangeFailed.invoke(errorMessage)
			return
		}
		
		// Retrieve user's language preference from persistent storage
		val languageCode = LocalStoredLangPref.languageCode
		val locale = Locale.forLanguageTag(languageCode)
		
		logger.d("Initiating language change to: $languageCode")
		baseActivity.getActivity()?.let { safeActivityRef ->
			// Apply the locale technically through the locale management system
			baseActivity.getActivity()?.setLanguageLocale(locale)
			logger.d("Language applied successfully. System locale set to: $locale")
			afterApplyingLanguage()
		}
	}
}