package app.ui.main.fragments.settings.dialogs

import android.view.*
import android.view.View.*
import android.view.ViewGroup.*
import android.view.ViewGroup.LayoutParams.*
import android.widget.*
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.*
import app.core.engines.youtube.ContentRegionsList.regionsList
import com.aio.*
import lib.process.*
import lib.ui.builders.*
import java.lang.ref.*

/**
 * A dialog component that allows users to select a content region.
 * It dynamically generates radio buttons for all supported regions and
 * applies the selected region to the application settings.
 */
class ContentRegionSelector(val baseActivity: BaseActivity) {
	
	private val logger = LogHelperUtils.from(javaClass)
	private val weakReferenceOfActivity = WeakReference(baseActivity)
	private val safeBaseActivityRef get() = weakReferenceOfActivity.get()
	
	private val contentRegionSelectionDialog by lazy {
		DialogBuilder(safeBaseActivityRef).apply {
			setView(R.layout.dialog_content_regions_1)
		}
	}
	
	/** Callback triggered when user confirms region selection */
	var onApplyListener: () -> Unit? = {}
	
	init {
		logger.d("Initializing ContentRegionSelector dialog.")
		contentRegionSelectionDialog.setCancelable(false)
		contentRegionSelectionDialog.view.apply {
			setAvailableRegions(this)
			setButtonOnClickListeners(this)
		}
	}
	
	/** Returns the underlying dialog builder */
	fun getDialogBuilder(): DialogBuilder {
		return contentRegionSelectionDialog
	}
	
	/** Closes the dialog if visible */
	fun close() {
		if (contentRegionSelectionDialog.isShowing) {
			logger.d("Closing content region selection dialog.")
			contentRegionSelectionDialog.close()
			weakReferenceOfActivity.clear()
		}
	}
	
	/** Shows the dialog if not already visible */
	fun show() {
		if (!contentRegionSelectionDialog.isShowing) {
			logger.d("Showing content region selection dialog.")
			contentRegionSelectionDialog.show()
		}
	}
	
	/** Returns true if the dialog is currently visible */
	fun isShowing(): Boolean {
		return contentRegionSelectionDialog.isShowing
	}
	
	/**
	 * Dynamically populates the dialog with available region options.
	 * Uses lazy batching to avoid blocking the UI when loading large lists.
	 */
	private fun setAvailableRegions(dialogLayoutView: View) {
		safeBaseActivityRef?.let { safeActivityRef ->
			val radioGroup = getRegionsRadioGroupView(dialogLayoutView)
			removeAllRadioSelectionViews(dialogLayoutView)
			
			val batchSize = 10   // how many RadioButtons to add per frame
			var currentIndex = 0
			
			fun addBatch() {
				val end = (currentIndex + batchSize).coerceAtMost(regionsList.size)
				logger.d("Adding regions batch: $currentIndex to $end")
				for (i in currentIndex until end) {
					val (_, name) = regionsList[i]
					inflate(safeActivityRef, R.layout.dialog_content_regions_item_1, null).apply {
						(this as RadioButton).apply {
							id = i
							text = name
							
							val radioButtonHeight = resources.getDimensionPixelSize(R.dimen._40)
							layoutParams = LayoutParams(MATCH_PARENT, radioButtonHeight)
							
							val horizontalPadding = resources.getDimensionPixelSize(R.dimen._5)
							val verticalPadding = resources.getDimensionPixelSize(R.dimen._5)
							setPadding(
								horizontalPadding,
								verticalPadding,
								horizontalPadding,
								verticalPadding
							)
						}
						radioGroup.addView(this)
					}
				}
				currentIndex = end
				if (currentIndex < regionsList.size) {
					// Post next batch to the UI thread queue
					radioGroup.post { addBatch() }
				} else {
					// After all added → highlight selected region
					val currentRegionCode = aioSettings.userSelectedContentRegion
					val selectedIndex = regionsList.indexOfFirst { it.first == currentRegionCode }
					logger.d(
						"Finished populating regions. " +
							"Current region: $currentRegionCode (index: $selectedIndex)"
					)
					if (selectedIndex >= 0) {
						radioGroup.findViewById<RadioButton>(selectedIndex)?.isChecked = true
					}
				}
			}
			
			// Start first batch
			addBatch()
		}
	}
	
	/** Clears all previously added radio buttons from the region list container */
	private fun removeAllRadioSelectionViews(dialogLayoutView: View) {
		logger.d("Clearing all previous region radio buttons.")
		getRegionsRadioGroupView(dialogLayoutView).removeAllViews()
	}
	
	/** Returns the RadioGroup container from the dialog layout */
	private fun getRegionsRadioGroupView(view: View): RadioGroup {
		return view.findViewById(R.id.content_region_options_container)
	}
	
	/** Sets up the action button listeners inside the dialog */
	private fun setButtonOnClickListeners(dialogLayoutView: View) {
		dialogLayoutView.findViewById<View>(R.id.btn_dialog_positive_container).apply {
			setOnClickListener { applySelectedApplicationContentRegion(dialogLayoutView) }
		}
	}
	
	/**
	 * Applies the region selected by the user and persists it into storage.
	 * Triggers the onApplyListener callback after successful save.
	 */
	private fun applySelectedApplicationContentRegion(dialogLayoutView: View) {
		val contentRegionRadioGroup = getRegionsRadioGroupView(dialogLayoutView)
		val selectedRegionId = contentRegionRadioGroup.checkedRadioButtonId
		
		if (selectedRegionId == -1) {
			logger.d("No region selected. Skipping apply.")
			return // No selection
		}
		
		val (selectedRegionCode, selectedRegionName) = regionsList[selectedRegionId]
		logger.d("Applying selected region: $selectedRegionCode ($selectedRegionName)")
		
		// Save the new content region preference and persist it
		aioSettings.userSelectedContentRegion = selectedRegionCode
		aioSettings.updateInStorage()
		
		close()             // Close the dialog
		onApplyListener()   // Notify listener
	}
}
