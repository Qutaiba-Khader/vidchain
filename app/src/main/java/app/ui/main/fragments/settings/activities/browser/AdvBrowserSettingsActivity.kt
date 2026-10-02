package app.ui.main.fragments.settings.activities.browser

import android.view.View
import app.core.bases.BaseActivity
import com.aio.R
import lib.process.LogHelperUtils
import java.lang.ref.WeakReference

class AdvBrowserSettingsActivity : BaseActivity() {

	private val logger = LogHelperUtils.from(javaClass)

	/** Weak reference to avoid memory leaks. */
	private val safeSelfReference = WeakReference(this)
	private val safeAdvBrowserActivityRef = safeSelfReference.get()

	override fun onRenderingLayout(): Int {
		return R.layout.activity_adv_browser_settings_1
	}

	override fun onAfterLayoutRender() {
		safeAdvBrowserActivityRef?.apply {
			initializeViewClickListeners(this)
		}
	}

	override fun onBackPressActivity() {
		closeActivityWithFadeAnimation(true)
	}

	private fun initializeViewClickListeners(safeActivityRef: AdvBrowserSettingsActivity) {
		safeActivityRef.findViewById<View>(R.id.btn_left_actionbar).setOnClickListener {
			onBackPressActivity()
		}

	}
}