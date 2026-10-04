package org.websnake.vidchain.fallback.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.websnake.vidchain.fallback.core.FallbackCoordinator
import org.websnake.vidchain.fallback.core.FallbackRuntime

/**
 * The VidChain pieces inside upstream screens. Each is added at run time by one seam line, never by editing an
 * upstream layout: the trail line on a download card, two actions in the download options dialog, one Settings row.
 */
object FallbackUi {
	private const val TAG_TRAIL = "vidchain_trail"
	private const val TAG_OPTIONS = "vidchain_options"
	private const val TAG_SETTINGS = "vidchain_settings"
	internal val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

	/** download card: "Fallback: trying X (n/k)" under the status line; tap = methods tried */
	fun decorateRow(row: View, downloadId: Int, statusViewId: Int) {
		val status = row.findViewById<TextView>(statusViewId) ?: return
		val id = downloadId.toString()
		val parentId = TrailBoard.parentOf(id)
		val text = if (parentId != null) TrailText.childCard(TrailBoard.methodOfChild(id) ?: "?") else TrailBoard.of(id)?.let(TrailText::card)
		var line = row.findViewWithTag<TextView>(TAG_TRAIL)
		if (text == null) {
			line?.let { it.visibility = View.GONE; (it.parent as? ViewGroup)?.let(::restoreHeight) }
			return
		}
		if (line == null) {
			val statusBox = status.parent as? View ?: return
			val column = statusBox.parent as? ViewGroup ?: return
			line = TextView(row.context).apply {
				tag = TAG_TRAIL
				setTextSize(TypedValue.COMPLEX_UNIT_PX, status.textSize * 0.9f)
				setTextColor(status.textColors)
				typeface = status.typeface
				maxLines = 2
				setPadding(0, dp(row, 2), 0, dp(row, 2))
			}
			column.addView(line, column.indexOfChild(statusBox) + 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
		}
		line.setOnClickListener { showMethodsTried(it.context, parentId ?: id) }
		line.text = text
		line.visibility = View.VISIBLE
		(line.parent as? ViewGroup)?.let(::growHeight)
	}

	/** the card's text column has a fixed height upstream: let it grow while the trail line shows, restore it after */
	private fun growHeight(column: ViewGroup) {
		val lp = column.layoutParams ?: return
		if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) return
		column.setTag(org.websnake.vidchain.fallback.R.id.vidchain_original_height, lp.height)
		lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
		column.layoutParams = lp
	}

	private fun restoreHeight(column: ViewGroup) {
		val original = column.getTag(org.websnake.vidchain.fallback.R.id.vidchain_original_height) as? Int ?: return
		column.layoutParams = column.layoutParams?.apply { height = original }
		column.setTag(org.websnake.vidchain.fallback.R.id.vidchain_original_height, null)
	}

	/** download options dialog: "Try another method" and "Methods tried", styled like the dialog's own buttons */
	fun decorateOptions(view: View?, downloadId: Int, anchorId: Int, dismiss: () -> Unit) {
		val anchor = view?.findViewById<ViewGroup>(anchorId) ?: return
		val buttonRow = anchor.parent as? ViewGroup ?: return
		val column = buttonRow.parent as? ViewGroup ?: return
		if (column.findViewWithTag<View>(TAG_OPTIONS) != null) return
		val ctx = view.context
		val id = downloadId.toString()
		val styleFrom = firstText(anchor)
		val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; tag = TAG_OPTIONS }
		box.addView(optionButton(anchor, styleFrom, "Try another method") {
			dismiss()
			tryAnother(ctx, id)
		})
		box.addView(optionButton(anchor, styleFrom, "Methods tried") { showMethodsTried(ctx, id) })
		column.addView(box)
	}

	/** Settings: one row "VidChain fallbacks" right after the advanced-downloads row, cloned from it */
	fun addSettingsEntry(layout: View, templateRowId: Int, templateTextId: Int) {
		val template = layout.findViewById<ViewGroup>(templateRowId) ?: return
		val templateText = layout.findViewById<TextView>(templateTextId) ?: return
		val parent = template.parent as? ViewGroup ?: return
		if (parent.findViewWithTag<View>(TAG_SETTINGS) != null) return
		val ctx = layout.context
		val row = LinearLayout(ctx).apply {
			tag = TAG_SETTINGS
			orientation = LinearLayout.VERTICAL
			background = template.background?.constantState?.newDrawable()?.mutate()
			isClickable = true
			isFocusable = true
			setOnClickListener { ctx.startActivity(Intent(ctx, VidChainSettingsActivity::class.java)) }
		}
		val text = TextView(ctx).apply {
			copyStyle(templateText, this)
			val d = templateText.compoundDrawablesRelative.map { it?.constantState?.newDrawable()?.mutate() }
			setCompoundDrawablesRelativeWithIntrinsicBounds(d[0], d[1], d[2], d[3])
			compoundDrawablePadding = templateText.compoundDrawablePadding
			compoundDrawableTintList = templateText.compoundDrawableTintList
			text = "VidChain fallbacks"
		}
		row.addView(text, LinearLayout.LayoutParams(templateText.layoutParams as ViewGroup.MarginLayoutParams))
		parent.addView(row, parent.indexOfChild(template) + 1, LinearLayout.LayoutParams(template.layoutParams as ViewGroup.MarginLayoutParams))
	}

	fun tryAnother(ctx: Context, downloadId: String) {
		val app = ctx.applicationContext
		uiScope.launch {
			val c = FallbackRuntime.coordinator
			val r = if (c == null) null else withContext(Dispatchers.Default) { c.tryAnother(downloadId) }
			val msg = when (r) {
				FallbackCoordinator.Manual.STARTED -> "Trying the next method…"
				FallbackCoordinator.Manual.BUSY -> "A fallback method is already running for this download"
				FallbackCoordinator.Manual.ALREADY_DELIVERED -> "A fallback method already saved this download"
				FallbackCoordinator.Manual.NOTHING_LEFT -> "Every available method was already tried (see Settings → VidChain fallbacks)"
				FallbackCoordinator.Manual.NOT_FOUND -> "Download not found"
				null -> "Fallbacks are not running"
			}
			Toast.makeText(app, msg, Toast.LENGTH_SHORT).show()
		}
	}

	fun showMethodsTried(ctx: Context, downloadId: String) {
		uiScope.launch {
			val ledger = FallbackRuntime.ledger
			val rootId = withContext(Dispatchers.IO) { ledger?.parentOf(downloadId) } ?: downloadId
			val rows = withContext(Dispatchers.IO) { ledger?.attempts(rootId).orEmpty() }
			val body = TrailText.methodsTried(rows, TrailBoard.of(rootId))
			val tv = TextView(ctx).apply {
				text = body
				setTextIsSelectable(true)
				val pad = dp(this, 16)
				setPadding(pad, pad, pad, pad)
			}
			val scroll = ScrollView(ctx).apply { addView(tv) }
			// the AppCompat dialog follows the app's dark switch and accent (T8.1); the platform one if the context is not AppCompat
			runCatching {
				androidx.appcompat.app.AlertDialog.Builder(ctx).setTitle("Methods tried").setView(scroll)
					.setPositiveButton(android.R.string.ok, null).show()
			}.onFailure {
				(scroll.parent as? ViewGroup)?.removeView(scroll)
				AlertDialog.Builder(ctx).setTitle("Methods tried").setView(scroll)
					.setPositiveButton(android.R.string.ok, null).show()
			}
		}
	}

	private fun optionButton(anchor: ViewGroup, styleFrom: TextView?, label: String, onClick: () -> Unit): View {
		val ctx = anchor.context
		val lp = (anchor.layoutParams as? ViewGroup.MarginLayoutParams)?.let { LinearLayout.LayoutParams(it) }
			?: LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		lp.width = ViewGroup.LayoutParams.MATCH_PARENT
		lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
		lp.weight = 0f
		return TextView(ctx).apply {
			layoutParams = lp
			styleFrom?.let { copyStyle(it, this) }
			background = anchor.background?.constantState?.newDrawable()?.mutate()
			val pad = dp(anchor, 12)
			setPadding(pad, pad, pad, pad)
			text = label
			isClickable = true
			setOnClickListener { onClick() }
		}
	}

	private fun firstText(v: View): TextView? = when (v) {
		is TextView -> v
		is ViewGroup -> (0 until v.childCount).asSequence().mapNotNull { firstText(v.getChildAt(it)) }.firstOrNull()
		else -> null
	}

	private fun copyStyle(from: TextView, to: TextView) {
		to.setTextSize(TypedValue.COMPLEX_UNIT_PX, from.textSize)
		to.setTextColor(from.textColors)
		to.typeface = from.typeface ?: Typeface.DEFAULT
		to.maxLines = 1
		to.ellipsize = android.text.TextUtils.TruncateAt.END
	}

	internal fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()
}
