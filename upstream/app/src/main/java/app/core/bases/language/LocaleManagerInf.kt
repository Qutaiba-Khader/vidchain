package app.core.bases.language

import java.util.*

/**
 * Manages the localization and language settings for the application.
 *
 * This interface provides a contract for implementations that handle locale changes,
 * ensuring that the application's UI and resources can be updated accordingly.
 */
interface LocaleManagerInf {
	
	fun setLanguageLocale(locale: Locale)
}
