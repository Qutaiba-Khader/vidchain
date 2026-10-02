package app.core.bases.dialogs

import android.text.Html
import android.text.Html.FROM_HTML_MODE_COMPACT
import android.text.method.LinkMovementMethod
import android.view.View
import android.widget.TextView
import app.core.bases.BaseActivity
import app.core.engines.updater.AIOUpdater.UpdateInfo
import com.aio.R
import lib.device.ShareUtility.openApkFile
import lib.process.LogHelperUtils
import lib.ui.ViewUtility.setViewOnClickListener
import lib.ui.builders.DialogBuilder
import lib.ui.builders.ToastView.Companion.showToast
import java.io.File
import java.lang.ref.WeakReference

/**
 * Displays a user-friendly dialog for application updates with secure APK installation handling.
 *
 * This dialog bridges the gap between downloading an update and actually installing it by:
 * - Presenting version information in a clear, non-technical manner
 * - Providing secure APK installation through Android's file provider system
 * - Preventing memory leaks during configuration changes via weak references
 * - Ensuring only one instance is visible at a time to avoid user confusion
 *
 * ## Why This Approach Matters:
 *
 * **Weak References**: Android activities can be destroyed during configuration changes
 * (like screen rotation). Using WeakReference prevents memory leaks by allowing the
 * garbage collector to reclaim the activity if needed, while still providing access
 * when the activity exists.
 *
 * **File Provider Security**: Direct file:// URIs are blocked in modern Android.
 * This dialog uses a FileProvider to create content:// URIs, ensuring secure APK
 * installation while complying with Android's security model.
 *
 * **Single Instance Enforcement**: Prevents multiple update dialogs from stacking,
 * which could confuse users and create inconsistent application state.
 */
class UpdaterDialog(
	private val weakReferenceOfActivity: WeakReference<BaseActivity>?,
	private val latestVersionApkFile: File,
	private val versionInfo: UpdateInfo
) {

	/**
	 * Logger instance for tracking dialog lifecycle events, user interactions,
	 * and potential errors during the update presentation process.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Safely accesses the activity reference with leak protection.
	 *
	 * **How it works**: The weak reference may return null if the activity was garbage collected
	 * (during memory pressure or configuration changes). This property acts as a safe gateway
	 * that automatically handles the null case without crashing.
	 */
	private val safeBaseActivityRef: BaseActivity?
		get() = weakReferenceOfActivity?.get()

	/**
	 * Dialog builder that manages the actual Android dialog lifecycle.
	 *
	 * **Why separate from direct Dialog usage**: DialogBuilder provides consistent theming
	 * across the app and abstracts away the complexity of DialogFragment management,
	 * especially important for handling orientation changes properly.
	 */
	private val dialogBuilder: DialogBuilder = DialogBuilder(safeBaseActivityRef)

	/**
	 * Initializes the dialog content and behavior when the object is created.
	 *
	 * **Execution Flow**:
	 * 1. Checks if activity reference is valid (early exit if not)
	 * 2. Configures dialog appearance and non-cancelable behavior
	 * 3. Formats version information with HTML styling for better readability
	 * 4. Sets up click handling for the installation action
	 */
	init {
		safeBaseActivityRef?.let { activity ->
			logger.d("Building update dialog for version ${versionInfo.latestVersion}")

			// Non-cancelable prevents users from accidentally dismissing during critical update flow
			dialogBuilder.setView(R.layout.dialog_new_version_updater_1)
			dialogBuilder.setCancelable(false)

			// Format version information with HTML for rich text display
			dialogBuilder.view.findViewById<TextView>(R.id.txt_dialog_message)?.let { textView ->
				val htmlMsg = activity.getString(
					/* resId = */ R.string.title_b_latest_version_b,
					/* ...formatArgs = */ versionInfo.latestVersion
				).trimIndent()

				// FROM_HTML_MODE_COMPACT removes excess whitespace for cleaner appearance
				textView.text = Html.fromHtml(htmlMsg, FROM_HTML_MODE_COMPACT)

				// Enables clickable links in the text view for changelog or release notes
				textView.movementMethod = LinkMovementMethod.getInstance()
			}

			// Wire up the installation button with click handling
			setViewOnClickListener(
				{ button: View -> this.setupClickEvents(button) },
				dialogBuilder.view,
				R.id.btn_dialog_positive_container
			)
		} ?: logger.d("Activity reference unavailable - dialog initialization aborted")
	}

	/**
	 * Handles user interaction with dialog buttons and routes to appropriate actions.
	 *
	 * **Current Implementation**:
	 * - Installation button: Dismisses dialog and triggers APK installation
	 *
	 * **Extensibility**: The when() structure makes it easy to add more buttons
	 * (like "Remind me later" or "Skip this version") in the future.
	 *
	 * @param button The clicked view that triggered this event, identified by resource ID
	 */
	private fun setupClickEvents(button: View) {
		logger.d("Processing click event for view ID: ${button.id}")
		when (button.id) {
			R.id.btn_dialog_positive_container -> {
				logger.d("User initiated update installation")
				close() // Clean up dialog before proceeding to system installer

				safeBaseActivityRef?.let { activity ->
					// Generate content URI through FileProvider for secure APK access
					val authority = "${activity.packageName}.provider"
					openApkFile(activity, latestVersionApkFile, authority)
				} ?: showToast(safeBaseActivityRef, msgId = R.string.title_something_went_wrong)
			}
		}
	}

	/**
	 * Displays the update dialog to the user with duplicate prevention.
	 *
	 * **Why check isShowing**: Android doesn't prevent multiple show() calls on the same
	 * dialog instance. This check ensures we don't create stacked dialogs that would
	 * require multiple back button presses to dismiss.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			logger.d("Presenting update dialog to user")
			dialogBuilder.show()
		} else {
			logger.d("Update dialog already visible - ignoring duplicate show request")
		}
	}

	/**
	 * Safely dismisses the dialog and releases resources.
	 *
	 * **Error Prevention**: Checking isShowing before dismissal avoids WindowManager
	 * exceptions that can occur when trying to dismiss a already-dismissed dialog.
	 * This is particularly important during activity lifecycle transitions.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			logger.d("Closing update dialog and cleaning up resources")
			dialogBuilder.close()
		} else {
			logger.d("Dialog already closed - no action needed")
		}
	}
}