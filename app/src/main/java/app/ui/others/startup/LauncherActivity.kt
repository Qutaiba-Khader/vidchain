package app.ui.others.startup

import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
import android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
import app.core.AIOApp.Companion.aioSettings
import app.core.bases.BaseActivity
import app.ui.main.MotherActivity
import app.ui.others.information.UserFeedbackActivity
import app.ui.others.information.UserFeedbackActivity.FROM_CRASH_HANDLER
import app.ui.others.information.UserFeedbackActivity.WHERE_DIS_YOU_COME_FROM
import lib.ui.ActivityAnimator.animActivityFade

class LauncherActivity : BaseActivity() {
	
	override fun onRenderingLayout(): Int {
		return -1
	}
	
	override fun onAfterLayoutRender() {
		if (aioSettings.hasAppCrashedRecently) launchFeedbackActivity()
		else launchOpeningActivity()
	}
	
	override fun onBackPressActivity() {
		exitActivityOnDoubleBackPress()
	}
	
	private fun launchFeedbackActivity() {
		getActivity()?.let { activity ->
			aioSettings.hasAppCrashedRecently = false
			aioSettings.updateInStorage()
			
			Intent(
				activity,
				UserFeedbackActivity::class.java
			).apply {
				flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
				putExtra(
					WHERE_DIS_YOU_COME_FROM,
					FROM_CRASH_HANDLER
				)
				startActivity(this)
				finish()
				animActivityFade(activity)
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
				finish()
				animActivityFade(getActivity())
			}
		}
	}
	
	private fun launchOpeningActivity() {
		getActivity()?.let { activity ->
			Intent(
				activity,
				OpeningActivity::class.java
			).apply {
				flags = FLAG_ACTIVITY_CLEAR_TOP or FLAG_ACTIVITY_SINGLE_TOP
				startActivity(this)
				finish()
				animActivityFade(getActivity())
			}
		}
	}
}