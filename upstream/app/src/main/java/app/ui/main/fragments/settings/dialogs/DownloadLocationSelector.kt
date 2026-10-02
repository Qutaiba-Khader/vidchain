package app.ui.main.fragments.settings.dialogs

import android.view.*
import android.widget.*
import app.core.AIOApp.Companion.INSTANCE
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.*
import app.core.engines.settings.AIOSettings.Companion.PRIVATE_FOLDER
import app.core.engines.settings.AIOSettings.Companion.SYSTEM_GALLERY
import com.aio.*
import kotlinx.coroutines.*
import lib.files.*
import lib.process.*
import lib.texts.CommonTextUtils.getText
import lib.ui.*
import lib.ui.builders.*
import java.lang.ref.*

/**
 * Manages a dialog for selecting the default download location for files.
 *
 * This class presents a user interface with two main options for saving downloads:
 * 1.  **App's Private Folder**: Saves files to a location only accessible by this app.
 * 2.  **System Gallery**: Saves files to the public gallery, making them visible in other apps.
 *     This option requires "All Files Access" permission. If not granted, the dialog
 *     will prompt the user to grant it.
 *
 * The chosen setting is temporarily held and only persisted to storage when the "Apply"
 * button is clicked. If the user cancels or dismisses the dialog without applying,
 * the setting reverts to its original value to prevent unintended changes.
 *
 * @param baseActivity The [BaseActivity] context required for dialog creation and UI interactions.
 */
class DownloadLocationSelector(baseActivity: BaseActivity) {
	
	/** Logger for logging events within this class. */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * A weak reference to the base activity to prevent memory leaks.
	 *
	 * This reference allows the dialog to access the activity context (e.g., for showing other dialogs
	 * or toasts) without creating a strong circular reference that could prevent the activity from
	 * being garbage collected.
	 */
	private val weakReferenceOfBaseActivity = WeakReference(baseActivity)
	
	/**
	 * A weak reference to the [BaseActivity] to prevent memory leaks.
	 * This provides a safe, nullable way to access the activity context,
	 * for UI operations like showing dialogs or toasts.
	 */
	private val safeBaseActivityRef
		get() = weakReferenceOfBaseActivity.get()
	
	/**
	 * Tracks whether the user has confirmed a new setting by clicking the "Apply" button.
	 *
	 * This flag is crucial for determining whether to restore the original setting
	 * when the dialog is dismissed or canceled. If `false`, the original setting is restored.
	 * If `true`, the new setting is persisted.
	 *
	 * @see restoreIfNotApplied
	 */
	private var hasSettingApplied = false
	
	/**
	 * Stores the initial download location when the dialog is created.
	 * This value is used to restore the setting if the user cancels the dialog
	 * or dismisses it without applying a new selection.
	 */
	private val originalLocation = aioSettings.defaultDownloadLocation
	
	/**
	 * The lazily-initialized dialog instance for selecting the download location.
	 *
	 * This `DialogBuilder` is configured with a custom layout (`R.layout.dialog_default_location_1`)
	 * and handles user interactions for selecting between the app's private folder and the
	 * system gallery. The selection is only persisted when the "Apply" button is clicked.
	 *
	 * It also includes logic to restore the original setting if the dialog is
	 * canceled or dismissed without applying a new choice.
	 */
	private val dialog by lazy {
		logger.d("Initializing Download Location dialog")
		DialogBuilder(safeBaseActivityRef).apply {
			setView(R.layout.dialog_default_location_1)
			setCancelable(true)
			
			view.apply {
				val privateBtn = findViewById<View>(R.id.button_app_private_folder)
				val galleryBtn = findViewById<View>(R.id.btn_system_gallery)
				val applyBtn = findViewById<View>(R.id.btn_dialog_positive_container)
				val privateRadio = findViewById<ImageView>(R.id.btn_app_private_folder_radio)
				val galleryRadio = findViewById<ImageView>(R.id.button_system_gallery_radio)
				
				updateRadioButtons(privateRadio, galleryRadio)
				
				privateBtn.setOnClickListener {
					logger.d("User selected: Private Folder")
					aioSettings.defaultDownloadLocation = PRIVATE_FOLDER
					updateRadioButtons(privateRadio, galleryRadio)
				}
				
				galleryBtn.setOnClickListener {
					logger.d("User selected: System Gallery")
					if (FileSystemUtility.hasFullFileSystemAccess(INSTANCE)) {
						aioSettings.defaultDownloadLocation = SYSTEM_GALLERY
						updateRadioButtons(privateRadio, galleryRadio)
					} else {
						safeBaseActivityRef?.doSomeVibration()
						MsgDialogUtils.getMessageDialog(
							baseActivityInf = safeBaseActivityRef,
							titleText = getText(R.string.title_permission_needed),
							isTitleVisible = true,
							isNegativeButtonVisible = false,
							messageTextViewCustomize = {
								it.setText(R.string.text_app_dont_have_write_permission_msg)
							}
						)?.let { msgDialogBuilder ->
							msgDialogBuilder.setOnClickForPositiveButton {
								CoroutineScope(Dispatchers.Main).launch {
									msgDialogBuilder.close()
									delay(500)
									safeBaseActivityRef?.let {
										FileSystemUtility.openAllFilesAccessSettings(it)
									}
								}
							}
						}?.show()
					}
				}
				
				applyBtn.setOnClickListener {
					logger.d("Apply clicked, saving setting")
					hasSettingApplied = true
					aioSettings.updateInStorage()
					safeBaseActivityRef?.doSomeVibration()
					ToastView.showToast(
						activityInf = safeBaseActivityRef,
						msgId = R.string.title_setting_applied
					)
					close()
				}
			}
			
			dialog.setOnCancelListener {
				logger.d("Dialog cancelled, restoring original setting if needed")
				restoreIfNotApplied()
			}
			dialog.setOnDismissListener {
				logger.d("Dialog dismissed, restoring original setting if needed")
				restoreIfNotApplied()
			}
		}
	}
	
	/**
	 * Updates the visual state of the radio buttons to reflect the currently selected
	 * download location preference stored in [aioSettings].
	 *
	 * It checks the value of [aioSettings.defaultDownloadLocation] and sets the appropriate
	 * checked or unchecked icon for each radio button.
	 *
	 * @param privateRadio The [ImageView] representing the radio button for the private folder option.
	 * @param galleryRadio The [ImageView] representing the radio button for the system gallery option.
	 */
	private fun updateRadioButtons(privateRadio: ImageView, galleryRadio: ImageView) {
		val isPrivate = aioSettings.defaultDownloadLocation == PRIVATE_FOLDER
		logger.d(
			"Updating radio buttons: current " +
				"= ${if (isPrivate) "Private Folder" else "System Gallery"}"
		)
		privateRadio.setImageResource(
			if (isPrivate) R.drawable.ic_button_checked_circle
			else R.drawable.ic_button_unchecked_circle
		)
		galleryRadio.setImageResource(
			if (isPrivate) R.drawable.ic_button_unchecked_circle
			else R.drawable.ic_button_checked_circle
		)
	}
	
	/**
	 * Restores the original download location setting if the user dismisses or cancels the dialog
	 * without applying a new choice.
	 *
	 * This function is triggered by `setOnCancelListener` and `setOnDismissListener`. It checks
	 * the `hasSettingApplied` flag. If a new setting hasn't been explicitly applied, it reverts
	 * the `defaultDownloadLocation` to its state before the dialog was shown (`originalLocation`)
	 * and saves this change.
	 */
	private fun restoreIfNotApplied() {
		if (!hasSettingApplied) {
			logger.d("Restoring original location: $originalLocation")
			aioSettings.defaultDownloadLocation = originalLocation
			aioSettings.updateInStorage()
		} else {
			logger.d("Setting already applied, no restore needed")
		}
	}
	
	/**
	 * Displays the download location selection dialog.
	 *
	 * This function will only show the dialog if it is not already visible,
	 * preventing multiple instances from appearing.
	 */
	fun show() = takeIf { !dialog.isShowing }?.run {
		logger.d("Showing dialog")
		dialog.show()
	}
	
	/**
	 * Closes the dialog if it is currently showing.
	 *
	 * This function checks if the underlying dialog is active before attempting to close it,
	 * preventing potential errors if the dialog is already dismissed.
	 */
	fun close() = takeIf { dialog.isShowing }?.run {
		logger.d("Closing dialog")
		dialog.close()
	}
}
