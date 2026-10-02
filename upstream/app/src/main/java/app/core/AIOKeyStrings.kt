package app.core

import lib.process.*

/**
 * A central repository for constant keys and action strings used throughout the application.
 *
 * This object consolidates string constants to prevent typos and improve maintainability.
 * These constants are essential for inter-component communication, including:
 * - Passing data in `Intent` extras or `Bundle`s.
 * - Identifying actions in `BroadcastReceiver`s.
 * - Handling results between `Activity` or `Fragment` instances.
 * - Controlling application flow, such as URL parsing or download retries.
 *
 * Each constant is documented to clarify its specific purpose and context of use.
 *
 * @see android.content.Intent
 * @see android.os.Bundle
 */
object AIOKeyStrings {

    /**
     * Logger for this class. Used for debugging purposes related to key usage.
     */
    private val logger = LogHelperUtils.from(javaClass)

    /**
     * Key used to indicate that a URL should no longer be parsed.
     * This flag can be passed in intents or bundles to prevent further parsing attempts.
     */
    const val DONT_PARSE_URL_ANYMORE = "DONT_PARSE_URL_ANYMORE"

    /**
     * Key used for passing or retrieving results between activities.
     * Typically used in conjunction with [Intent.putExtra] and [Intent.getStringExtra].
     */
    const val ACTIVITY_RESULT_KEY = "ACTIVITY_RESULT_KEY"
}