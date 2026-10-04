package org.websnake.vidchain.fallback.ui

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.websnake.vidchain.fallback.R
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.core.FallbackSettings
import org.websnake.vidchain.fallback.trace.TraceAndroid
import org.websnake.vidchain.fallback.trace.TraceConfig

/** Settings -> VidChain fallbacks: master switch, trace log switch + open/share, one switch per method, engine self-test. */
class VidChainSettingsActivity : AppCompatActivity() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var lastReport: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		VidChainTheme.apply(this)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.vidchain_settings)
		title = getString(R.string.vidchain_settings_title)

		findViewById<SwitchCompat>(R.id.vidchain_fallbacks_enabled).apply {
			isChecked = FallbackSettings.enabled(this@VidChainSettingsActivity)
			setOnCheckedChangeListener { _, on -> FallbackSettings.setEnabled(this@VidChainSettingsActivity, on) }
		}
		findViewById<SwitchCompat>(R.id.vidchain_trace_enabled).apply {
			isChecked = TraceConfig.enabled
			setOnCheckedChangeListener { _, on -> TraceAndroid.setEnabled(this@VidChainSettingsActivity, on) }
		}
		findViewById<Button>(R.id.vidchain_trace_open).setOnClickListener { openTrace() }
		findViewById<Button>(R.id.vidchain_trace_share).setOnClickListener {
			if (!TraceShare.share(this)) Toast.makeText(this, R.string.vidchain_trace_empty, Toast.LENGTH_SHORT).show()
		}
		buildMethodSwitches(findViewById(R.id.vidchain_methods))
		findViewById<Button>(R.id.vidchain_self_test).setOnClickListener { runSelfTest() }
		findViewById<Button>(R.id.vidchain_licences).setOnClickListener { startActivity(Intent(this, VidChainLicencesActivity::class.java)) }
		findViewById<Button>(R.id.vidchain_self_test_export).setOnClickListener {
			val r = lastReport ?: return@setOnClickListener Toast.makeText(this, R.string.vidchain_self_test_first, Toast.LENGTH_SHORT).show()
			startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, r), getString(R.string.vidchain_self_test_export)))
		}
	}

	override fun onDestroy() {
		scope.cancel()
		super.onDestroy()
	}

	private fun buildMethodSwitches(box: LinearLayout) {
		val built = FallbackRuntime.methods.map { it.id }.toSet()
		for (id in TrailText.ALL_METHODS) {
			box.addView(SwitchCompat(this).apply {
				text = if (id in built) TrailText.name(id) else getString(R.string.vidchain_method_not_built, TrailText.name(id))
				isEnabled = id in built
				isChecked = FallbackSettings.methodOn(this@VidChainSettingsActivity, id)
				setOnCheckedChangeListener { _, on -> FallbackSettings.setMethodOn(this@VidChainSettingsActivity, id, on) }
				val pad = FallbackUi.dp(box, 6)
				setPadding(0, pad, 0, pad)
			})
		}
	}

	private fun openTrace() {
		scope.launch {
			val text = withContext(Dispatchers.IO) { TraceShare.tail(this@VidChainSettingsActivity, 200_000) }
			if (text.isEmpty()) { Toast.makeText(this@VidChainSettingsActivity, R.string.vidchain_trace_empty, Toast.LENGTH_SHORT).show(); return@launch }
			val tv = TextView(this@VidChainSettingsActivity).apply {
				this.text = text
				typeface = Typeface.MONOSPACE
				textSize = 11f
				setTextIsSelectable(true)
				val pad = FallbackUi.dp(this, 12)
				setPadding(pad, pad, pad, pad)
			}
			AlertDialog.Builder(this@VidChainSettingsActivity).setTitle(R.string.vidchain_trace_title)
				.setView(ScrollView(this@VidChainSettingsActivity).apply { addView(tv); post { fullScroll(ScrollView.FOCUS_DOWN) } })
				.setPositiveButton(android.R.string.ok, null).show()
		}
	}

	private fun runSelfTest() {
		val out = findViewById<TextView>(R.id.vidchain_self_test_result)
		out.setText(R.string.vidchain_self_test_running)
		scope.launch {
			val report = SelfTest.run(applicationContext)
			lastReport = report
			out.text = report
		}
	}
}
