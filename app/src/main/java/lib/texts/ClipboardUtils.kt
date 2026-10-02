package lib.texts

import android.content.ClipData.newHtmlText
import android.content.ClipData.newPlainText
import android.content.ClipboardManager
import android.content.ClipboardManager.OnPrimaryClipChangedListener
import android.content.Context
import android.content.Context.CLIPBOARD_SERVICE
import java.lang.ref.WeakReference

object ClipboardUtils {

	private val activeListeners = mutableMapOf<OnPrimaryClipChangedListener, OnPrimaryClipChangedListener>()

	private class ListenerProxy(
		originalListener: OnPrimaryClipChangedListener
	) : OnPrimaryClipChangedListener {
		private val weakListener = WeakReference(originalListener)
		override fun onPrimaryClipChanged() {
			weakListener.get()?.onPrimaryClipChanged()
		}
	}

	@JvmStatic
	fun clearClipboard(context: Context?) {
		(context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).apply {
			setPrimaryClip(newPlainText("", ""))
		}
	}

	@JvmStatic
	fun hasTextInClipboard(context: Context?): Boolean {
		val clipboard = context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
		return clipboard.primaryClip?.takeIf { clip ->
			clip.itemCount > 0 && clip.getItemAt(0).text?.isNotEmpty() == true
		} != null
	}

	@JvmStatic
	fun getHtmlFromClipboard(context: Context?): String {
		val clipboard = context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
		return clipboard.primaryClip?.takeIf { clip ->
			clip.itemCount > 0
		}?.getItemAt(0)?.htmlText ?: ""
	}

	@JvmStatic
	fun copyHtmlToClipboard(context: Context?, html: String?) {
		html?.takeIf { it.isNotEmpty() }?.let { validHtml ->
			(context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).apply {
				setPrimaryClip(newHtmlText("html_clip", validHtml, validHtml))
			}
		}
	}

	@JvmStatic
	fun appendTextToClipboard(context: Context?, text: String?) {
		text?.takeIf { it.isNotEmpty() }?.let { validText ->
			val current = getTextFromClipboard(context)
			copyTextToClipboard(context, current + validText)
		}
	}

	@JvmStatic
	fun getTextFromClipboard(context: Context?): String {
		val clipboard = context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
		return clipboard.primaryClip?.takeIf { clip ->
			clip.itemCount > 0
		}?.getItemAt(0)?.text?.toString() ?: ""
	}

	@JvmStatic
	fun copyTextToClipboard(context: Context?, text: String?) {
		text?.takeIf { it.isNotEmpty() }?.let { validText ->
			(context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).apply {
				setPrimaryClip(newPlainText("text_clip", validText))
			}
		}
	}

	@JvmStatic
	fun setClipboardListener(
		context: Context?,
		listener: OnPrimaryClipChangedListener? = null
	) {
		listener?.let { originalListener ->
			val proxy = ListenerProxy(originalListener)
			val clipboard = context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
			clipboard.addPrimaryClipChangedListener(proxy)
			activeListeners[originalListener] = proxy
		}
	}

	@JvmStatic
	fun removeClipboardListener(
		context: Context?,
		listener: OnPrimaryClipChangedListener? = null
	) {
		listener?.let { originalListener ->
			val proxy = activeListeners.remove(originalListener)

			if (proxy != null) {
				val clipboard = context?.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
				clipboard.removePrimaryClipChangedListener(proxy)
			}
		}
	}
}