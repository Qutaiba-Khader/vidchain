package app.ui.main.fragments.settings.dialogs

import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.BaseActivity
import com.aio.R
import lib.process.LogHelperUtils
import lib.ui.builders.DialogBuilder
import java.lang.ref.WeakReference

/**
 * Dialog for selecting the application's theme appearance
 * (System default, Dark mode, or Light mode).
 *
 * Handles UI binding, persistence of selection, and optional callbacks
 * when a theme is applied. Keeps logs minimal but informative.
 */
class ThemeAppearanceSelector(private val baseActivity: BaseActivity) {

	/** Logger for short lifecycle tracking. */
	private val logger = LogHelperUtils.from(javaClass)

	/** Prevents memory leaks by holding a weak reference. */
	private val safeBaseActivityRef by lazy { WeakReference(baseActivity).get() }

	/** Builds the theme selection dialog with appropriate layout. */
	private val themeDialog by lazy {
		logger.d("Init Theme Dialog")
		DialogBuilder(safeBaseActivityRef).apply {
			setView(R.layout.dialog_dark_theme_picker_1)
		}
	}

	/** UI references for theme radio buttons. */
	private val sysDefaultBtn by lazy {
		themeDialog.view.findViewById<RadioButton>(R.id.btn_system_default)
	}
	private val darkModeBtn by lazy {
		themeDialog.view.findViewById<RadioButton>(R.id.btn_dark_mode_ui)
	}
	private val lightModeBtn by lazy {
		themeDialog.view.findViewById<RadioButton>(R.id.btn_light_mode_ui)
	}

	/** Optional external callback triggered after apply. */
	var onApplyListener: () -> Unit? = {}

	init {
		logger.d("Setup click listeners")
		themeDialog.view.apply { setDialogActionListeners(this) }
	}

	/** Returns dialog builder for further customization. */
	fun getDialogBuilder(): DialogBuilder {
		logger.d("getDialogBuilder()")
		return themeDialog
	}

	/** Shows dialog; sets initial state based on stored preference. */
	fun show() {
		if (!themeDialog.isShowing) {
			// Apply last saved theme selection
			when (aioSettings.themeAppearance) {
				-1 -> sysDefaultBtn.isChecked = true
				1 -> darkModeBtn.isChecked = true
				2 -> lightModeBtn.isChecked = true
				else -> sysDefaultBtn.isChecked = true
			}
			logger.d("Show Theme Dialog")
			themeDialog.show()
		} else logger.d("Already showing")
	}

	/** Closes dialog if visible. */
	fun close() {
		if (themeDialog.isShowing) {
			logger.d("Close Dialog")
			themeDialog.close()
		} else logger.d("Dialog not showing")
	}

	/** Returns dialog visibility state. */
	fun isShowing(): Boolean {
		val showing = themeDialog.isShowing
		logger.d("isShowing=$showing")
		return showing
	}

	/**
	 * Sets up UI button click listeners inside dialog.
	 * @param dialogLayoutView The dialog's main layout view.
	 */
	private fun setDialogActionListeners(dialogLayoutView: View) {
		val btnViewId = R.id.btn_dialog_positive_container
		dialogLayoutView.findViewById<View>(btnViewId)?.setOnClickListener {
			logger.d("Apply clicked")
			applySelectedTheme(dialogLayoutView)
		}
	}

	/**
	 * Returns RadioGroup containing all theme options.
	 * @param view The dialog layout view.
	 */
	private fun getThemesRadioGroup(view: View): RadioGroup {
		return view.findViewById(R.id.theme_options_container)
	}

	/**
	 * Applies selected theme, saves it to preferences,
	 * closes dialog, and invokes external callback.
	 * @param dialogLayoutView The root layout of the dialog.
	 */
	private fun applySelectedTheme(dialogLayoutView: View) {
		logger.d("Apply theme")
		val themeGroup = getThemesRadioGroup(dialogLayoutView)
		val selectedId = themeGroup.checkedRadioButtonId

		// Abort if nothing selected
		if (selectedId == -1) {
			logger.d("No selection")
			return
		}

		// Store user selection
		when {
			sysDefaultBtn.isChecked -> {
				logger.d("System Default")
				aioSettings.themeAppearance = -1
			}
			darkModeBtn.isChecked -> {
				logger.d("Dark Mode")
				aioSettings.themeAppearance = 1
			}
			lightModeBtn.isChecked -> {
				logger.d("Light Mode")
				aioSettings.themeAppearance = 2
			}
		}

		// Commit and notify
		logger.d("Save theme")
		aioSettings.updateInStorage()

		// Close dialog and call listener
		close()
		onApplyListener()
		logger.d("Done")
	}
}