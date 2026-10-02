@file:Suppress("DEPRECATION")

package lib.ui.builders

import android.annotation.SuppressLint
import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.view.ContextThemeWrapper
import app.core.bases.BaseActivity
import app.core.bases.interfaces.BaseActivityInf
import com.aio.R
import kotlinx.coroutines.launch
import lib.networks.URLUtility.isValidURL
import lib.texts.CommonTextUtils.getText

class ToastView(context: Context) : Toast(context) {

	fun setIcon(iconResId: Int) {
		view?.findViewById<ImageView>(R.id.img_toast_app_icon)
			?.apply { setImageResource(iconResId) }
	}

	companion object {

		@JvmStatic
		fun showToast(activityInf: BaseActivityInf?, msg: String? = null, msgId: Int = -1) {
			if (activityInf == null) return
			activityInf.getAttachedCoroutineScope().launch {
				when {
					msgId != -1 -> showResourceToast(activityInf, msgId)
					msg != null -> showTextToast(activityInf, msg)
				}
			}
		}

		@JvmStatic
		fun showToast(activityInf: BaseActivityInf?, msgId: Int = -1) {
			if (activityInf == null) return
			activityInf.getAttachedCoroutineScope().launch {
				showResourceToast(activityInf, msgId)
			}
		}

		private fun showResourceToast(activityInf: BaseActivityInf, msgId: Int) {
			val message = getText(msgId)
			if (isValidURL(message)) return
			makeText(activityInf, message)?.show()
		}

		private fun showTextToast(activityInf: BaseActivityInf, msg: String) {
			if (isValidURL(msg)) return
			makeText(activityInf, msg)?.show()
		}

		private fun makeText(
			activityInf: BaseActivityInf,
			toastMessage: CharSequence?, duration: Int = LENGTH_LONG
		): ToastView? {
			return activityInf.getActivity()?.let { safeActivityContext ->
				configureToastView(safeActivityContext, toastMessage, duration)
			}
		}

		@SuppressLint("InflateParams")
		private fun configureToastView(
			activity: BaseActivity,
			toastMessage: CharSequence?,
			duration: Int
		): ToastView {
			val themedCtx = ContextThemeWrapper(activity, R.style.style_application)
			val inflater = LayoutInflater.from(themedCtx)

			return ToastView(activity.applicationContext).apply {
				val toastView = inflater.inflate(R.layout.lay_custom_toast_view_1, null)
				toastView.findViewById<TextView>(R.id.txt_toast_message).text = toastMessage
				view = toastView
				setDuration(duration)
			}
		}
	}
}
