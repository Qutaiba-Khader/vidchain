package app.ui.main.fragments.settings.dialogs

import android.view.*
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import app.core.AIOApp.Companion.aioLanguage
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.*
import app.core.bases.language.*
import com.aio.*
import lib.ui.builders.*
import java.lang.ref.*

/**
 * A dialog for selecting the application's user interface language.
 *
 * This dialog presents a list of available languages in a `RadioGroup`.
 * When a language is selected and applied, it updates the application's
 * settings and restarts the current activity to reflect the change.
 *
 * Example usage:
 * ```
 * val dialog = LanguagePickerDialog(this) // In a BaseActivity
 * dialog.onApplyListener = {
 *     // Optional: Code to run after the language is applied and activity is recreated.
 * }
 * dialog.show()
 * ```
 *
 * @param baseActivity The host activity for this dialog. It is used for context
 *                     and will be restarted when the language is changed.
 */
class LanguagePickerDialog(baseActivity: BaseActivity) {
	
	/**
	 * A weak reference to the `BaseActivity` to avoid memory leaks.
	 * This is used when applying the selected language, as the activity might be
	 * destroyed before the language change is complete.
	 */
	val weakReferenceOfActivity = WeakReference(baseActivity)
	
	/**
	 * A listener that is invoked after a new language has been selected and applied.
	 * This is typically used to trigger UI updates, such as restarting an activity,
	 * to reflect the language change throughout the application.
	 */
	var onApplyListener: () -> Unit? = {}
	
	/**
	 * Lazily initialized [DialogBuilder] for the language selection dialog.
	 *
	 * This dialog is created using [DialogBuilder] and its view is set to `R.layout.dialog_language_pick_1`.
	 * The `lazy` delegate ensures that the dialog is instantiated only once, the first time it is accessed.
	 */
	private val languageSelectionDialog by lazy {
		DialogBuilder(getActivity()).apply {
			setView(R.layout.dialog_language_pick_1)
		}
	}
	
	init {
		languageSelectionDialog.setCancelable(false)
		languageSelectionDialog.view.apply {
			setAvailableLanguages(this)
			setButtonOnClickListeners(this)
		}
	}
	
	/**
	 * Retrieves the activity instance from the provided `baseActivity` property.
	 * This is a helper function to ensure a consistent way of accessing the activity context.
	 *
	 * @return The [BaseActivity] instance, or null if it's not available.
	 */
	private fun getActivity(): BaseActivity? {
		return weakReferenceOfActivity.get()?.getActivity()
	}
	
	/**
	 * Retrieves the underlying [DialogBuilder] instance used to create and manage the
	 * language selection dialog. This can be used for more advanced customizations or
	 * to access the dialog's properties directly.
	 *
	 * @return The [DialogBuilder] instance for this dialog.
	 */
	fun getDialogBuilder(): DialogBuilder {
		return languageSelectionDialog
	}
	
	/**
	 * Closes the language selection dialog if it is currently showing.
	 */
	fun close() {
		if (languageSelectionDialog.isShowing) {
			languageSelectionDialog.close()
		}
	}
	
	/**
	 * Displays the language selection dialog.
	 * If the dialog is already showing, this method does nothing.
	 */
	fun show() {
		if (!languageSelectionDialog.isShowing) {
			languageSelectionDialog.show()
		}
	}
	
	/**
	 * Checks if the language selection dialog is currently showing.
	 *
	 * @return `true` if the dialog is visible, `false` otherwise.
	 */
	fun isShowing(): Boolean {
		return languageSelectionDialog.isShowing
	}
	
	/**
	 * Populates the language selection dialog with available languages.
	 *
	 * This function clears any existing language options, then retrieves the list of
	 * available languages from `aioLanguage`. It dynamically creates a `RadioButton`
	 * for each language, sets its text to the language name, and adds it to the
	 * `RadioGroup` within the dialog. Finally, it checks the radio button that
	 * corresponds to the currently selected user language from `aioSettings`.
	 *
	 * @param dialogView The root view of the language picker dialog.
	 */
	private fun setAvailableLanguages(dialogView: View) {
		getActivity()?.let { activityRef ->
			removeAllRadioSelectionViews(dialogView)
			
			val groupView = getLanguageRadioGroupView(dialogView)
			val languagesList = aioLanguage.languagesList
			languagesList.forEachIndexed { index, (_, name) ->
				View.inflate(activityRef, R.layout.dialog_language_pick_1_item_1, null).apply {
					(this as RadioButton).apply {
						id = index
						text = name
						
						val itemHeight = resources.getDimensionPixelSize(R.dimen._36)
						layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, itemHeight)
						val hp = resources.getDimensionPixelSize(R.dimen._5)
						val vp = resources.getDimensionPixelSize(R.dimen._5)
						setPadding(hp, vp, hp, vp)
					}
					
					groupView.addView(this)
				}
			}
			
			val langCode = aioSettings.userSelectedUILanguage
			val languageIndex = languagesList.indexOfFirst { it.first == langCode }
			if (languageIndex < 0) return
			
			groupView.findViewById<RadioButton>(languageIndex)?.isChecked = true
		}
	}
	
	/**
	 * Removes all radio button views from the language selection `RadioGroup`.
	 * This is used to clear the list before repopulating it with the available languages.
	 *
	 * @param dialogLayoutView The root view of the dialog, used to find the `RadioGroup`.
	 */
	private fun removeAllRadioSelectionViews(dialogLayoutView: View) {
		getLanguageRadioGroupView(dialogLayoutView).removeAllViews()
	}
	
	/**
	 * Retrieves the [RadioGroup] that contains the language options from the dialog's view.
	 *
	 * @param view The root view of the language picker dialog.
	 * @return The [RadioGroup] instance used for displaying language choices.
	 */
	private fun getLanguageRadioGroupView(view: View): RadioGroup {
		return view.findViewById(R.id.language_options_container)
	}
	
	/**
	 * Sets the on-click listener for the positive button in the dialog.
	 * When clicked, it triggers the process of applying the selected language.
	 *
	 * @param dialogLayoutView The root view of the language picker dialog.
	 */
	private fun setButtonOnClickListeners(dialogLayoutView: View) {
		dialogLayoutView.findViewById<View>(R.id.btn_dialog_positive_container).apply {
			setOnClickListener {
				applySelectedApplicationLanguage(dialogLayoutView)
			}
		}
	}
	
	/**
	 * Applies the language selected by the user from the radio button group.
	 * It retrieves the selected language code, saves it to the application settings,
	 * closes the dialog, and then triggers the language change for the entire app.
	 * A listener is called after the language has been successfully applied.
	 *
	 * @param dialogLayoutView The root view of the language picker dialog,
	 * used to find the `RadioGroup` containing the language options.
	 */
	private fun applySelectedApplicationLanguage(dialogLayoutView: View) {
		val languageRadioGroup = getLanguageRadioGroupView(dialogLayoutView)
		val selectedLanguageId = languageRadioGroup.checkedRadioButtonId
		
		if (selectedLanguageId == -1) return
		val (selectedLanguageCode, _) = aioLanguage.languagesList[selectedLanguageId]
		
		aioSettings.userSelectedUILanguage = selectedLanguageCode
		LocalStoredLangPref.languageCode = selectedLanguageCode
		aioSettings.updateInStorage()
		
		close()
		aioLanguage.applyUserSelectedLanguage(
			weakReferenceOfActivity.get(),
			afterApplyingLanguage = {
				onApplyListener()
			})
	}
}