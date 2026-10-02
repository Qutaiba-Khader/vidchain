package app.ui.main.fragments.browser.suggestions

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.drawable.toDrawable
import app.core.AIOApp.Companion.aioBookmark
import app.core.AIOApp.Companion.aioHistory
import com.aio.R
import lib.process.CommonTimeUtils
import lib.process.CommonTimeUtils.OnTaskFinishListener

class SuggestionWatcher(
	private val editTextField: TextView,
	val onClickItem: (item: String) -> Unit = {}
) : TextWatcher {

	private var popupWindow: PopupWindow? = null
	private val suggestionAdapter = ArrayAdapter(
		editTextField.context,
		R.layout.frag_brow_3_edit_url_top_pop_item_1,
		mutableListOf<String>()
	)

	init {
		editTextField.addTextChangedListener(this)
	}

	override fun onTextChanged(
		enteredText: CharSequence?, start: Int, before: Int, count: Int
	) {
		val query = enteredText?.toString() ?: ""
		if (query.isNotEmpty()) {
			fetchSuggestions(query)
		} else {
			suggestionAdapter.clear()
			dismissPopup()
		}
	}

	override fun beforeTextChanged(
		s: CharSequence?, start: Int, count: Int, after: Int
	) {
	}

	override fun afterTextChanged(editable: Editable?) {}

	private fun fetchSuggestions(query: String) {
		fetchSearchSuggestions(query, 3) { suggestions ->
			val historyResults = aioHistory.getHistoryLibrary().filter { historyModel ->
				val titleContains = historyModel.historyTitle.contains(query, true)
				val urlContains = historyModel.historyUrl.contains(query, true)
				titleContains || urlContains
			}.take(5)

			val bookmarkResults = aioBookmark.getBookmarkLibrary().filter { bookmarkModel ->
				val titleContains = bookmarkModel.bookmarkName.contains(query, true)
				val urlContains = bookmarkModel.bookmarkUrl.contains(query, true)
				titleContains || urlContains
			}.take(5)

			val historySuggestions = historyResults.map { it.historyUrl }
			val bookmarkSuggestions = bookmarkResults.map { it.bookmarkUrl }
			val combinedSuggestions = ArrayList<String>().apply {
				addAll(suggestions)
				addAll(historySuggestions)
				addAll(bookmarkSuggestions)
			}

			editTextField.post {
				suggestionAdapter.clear()
				suggestionAdapter.addAll(combinedSuggestions)
				if (combinedSuggestions.isNotEmpty()) {
					showSuggestionDropdown(editTextField, suggestionAdapter)
				} else {
					dismissPopup()
				}
			}
		}
	}

	@SuppressLint("InflateParams")
	private fun showSuggestionDropdown(
		anchorView: View, suggestionAdapter: ArrayAdapter<String>
	) {
		if (popupWindow == null) {
			val context = anchorView.context
			val layoutInflater = LayoutInflater.from(context)
			val popupView = layoutInflater.inflate(R.layout.frag_brow_3_edit_url_top_pop_1, null)
			val listView: ListView = popupView.findViewById(R.id.list_auto_complete_suggestion)
			listView.adapter = suggestionAdapter

			popupWindow = PopupWindow(popupView, MATCH_PARENT, WRAP_CONTENT).apply {
				isFocusable = false
				setBackgroundDrawable(Color.TRANSPARENT.toDrawable())
				isOutsideTouchable = true
				setOnDismissListener {
					//Todo: do whatever need to do, when the popup is closed.
				}
			}
		}

		popupWindow?.let { popup ->
			if (!popup.isShowing) {
				popup.showAsDropDown(anchorView)
			}
			val contentView = (popup.contentView as ViewGroup)
			val listView: ListView = contentView.findViewById(R.id.list_auto_complete_suggestion)
			listView.setOnItemClickListener { _, _, position, _ ->
				val selectedItem = suggestionAdapter.getItem(position)
				if (selectedItem != null) {
					dismissPopup()
					CommonTimeUtils.delay(100, object : OnTaskFinishListener {
						override fun afterDelay() {
							onClickItem(selectedItem)
						}
					})
				}
			}
		}
	}

	private fun dismissPopup() {
		popupWindow?.let { if (it.isShowing) it.dismiss() }
	}
}