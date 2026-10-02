package app.core.bases.language

import java.util.*

/**
 * Interface definition for a callback to be invoked when the application's language changes.
 * Implement this interface to receive notifications about language updates, allowing components
 * like Activities or Fragments to refresh their UI accordingly.
 */
interface LanguageChangeListener {
	
	fun onLanguageChanged(newLocale: Locale)
}