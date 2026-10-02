package app.ui.others.startup

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import android.view.View.INVISIBLE
import android.widget.TextView
import app.core.bases.BaseActivity
import app.ui.main.MotherActivity
import com.aio.R
import com.airbnb.lottie.LottieAnimationView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import lib.device.AppVersionUtility
import lib.process.LogHelperUtils
import lib.ui.ActivityAnimator.animActivityFade
import lib.ui.ViewUtility.hideView

class OpeningActivity : BaseActivity() {
	
	private val logger = LogHelperUtils.from(javaClass)
	
	override fun onRenderingLayout(): Int = R.layout.activity_opening_1
	
	override fun onAfterLayoutRender() {
		showApkVersionInfo()
		getAttachedCoroutineScope().launch {
			delay(1200)
			stopLoadingLottieAnimation()
			launchMotherActivity()
		}
	}
	
	override fun onBackPressActivity() {
		exitActivityOnDoubleBackPress()
	}
	
	private fun showApkVersionInfo() {
		val versionName = AppVersionUtility.versionName
		"${getString(R.string.title_version)} : $versionName".apply {
			findViewById<TextView>(R.id.txt_version_info).text = this
		}
	}
	
	private fun stopLoadingLottieAnimation() {
		getActivity()?.let { activity ->
			val viewId = R.id.img_loading_lottie_anim
			with(activity.findViewById<LottieAnimationView>(viewId)) {
				hideView(
					targetView = this,
					visibility = INVISIBLE,
					shouldAnimate = true,
					animTimeout = 300
				)
				pauseAnimation()
			}
		}
	}
	
	private fun launchMotherActivity() {
		getActivity()?.let { activity ->
			Intent(
				activity,
				MotherActivity::class.java
			).apply {
				flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
				startActivity(this)
				animActivityFade(getActivity())
				finish()
			}
		}
	}
}