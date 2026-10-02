package lib.ui.builders

import android.widget.TextView
import app.core.bases.BaseActivity
import com.aio.R
import lib.process.LogHelperUtils
import lib.texts.CommonTextUtils.getText
import java.lang.ref.WeakReference

class FileFolderPicker(
	private val baseActivity: BaseActivity?,
	private val isCancellable: Boolean = true,
	private val isFolderPickerOnly: Boolean = true,
	private val isFilePickerOnly: Boolean = false,
	private val isMultiSelection: Boolean = false,
	private val titleText: String = getText(R.string.title_file_folder_picker),
	private val positiveButtonText: String = getText(R.string.title_select),
	private val onUserAbortedProcess: () -> Unit = {},
	private val onFileSelection: (List<String>) -> Unit = {}) {

	private val logger = LogHelperUtils.from(javaClass)
	private val activityWeakReference = WeakReference(baseActivity)
	private var hasUserAbortedTheProcess = false
	private val selectedFiles = mutableListOf<String>()
	private var dialogBuilder: DialogBuilder? = null

	init {
		dialogBuilder = DialogBuilder(getSafeBaseActivity())
		dialogBuilder?.apply { initializeDialogComponents() }

	}

	fun show() {
		dialogBuilder?.show()
	}

	fun close() {
		dialogBuilder?.close()
		dialogBuilder = null
	}

	private fun DialogBuilder.initializeDialogComponents() {
		setView(R.layout.dialog_file_folder_picker_1)
		setCancelable(isCancellable)
		configureDialogContents()
		configureCancelListener()
		configureSelectionListener()
	}

	private fun DialogBuilder.configureCancelListener() {
		dialogBuilder?.setOnClickForNegativeButton {
			hasUserAbortedTheProcess = true
			onUserAbortedProcess.invoke()
			close()
		}
	}

	private fun DialogBuilder.configureSelectionListener() {
		dialogBuilder?.setOnClickForPositiveButton {
			onFileSelection.invoke(selectedFiles)
			close()
		}
	}

	private fun DialogBuilder.configureDialogContents() {
		dialogBuilder?.view?.apply {
			val titleTextView = findViewById<TextView>(R.id.txt_dialog_title)
			val positiveButtonTextView = findViewById<TextView>(R.id.btn_dialog_positive)

			titleTextView.text = titleText
			positiveButtonTextView.text = positiveButtonText
		}
	}

	private fun getSafeBaseActivity(): BaseActivity? = activityWeakReference.get()
}