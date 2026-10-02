package app.core.bases.language

/**
 * Holds constant values for application-wide localization and language settings.
 *
 * This object centralizes keys for SharedPreferences, broadcast action strings,
 * and timing delays related to language and locale configuration changes.
 */
object LocalizeConstant {
	
	/**
	 * A delay in milliseconds before finishing the activity to ensure the UI has time to update
	 * after a locale change. This helps prevent visual glitches during the transition.
	 */
	const val PREPARE_DELAY = 300L
	
	/**
	 * Delay in milliseconds before finishing the activity after a language change.
	 * This provides a smoother user experience by allowing time for UI updates to complete.
	 */
	const val FINISH_DELAY = 500L
}