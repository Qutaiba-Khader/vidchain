package org.websnake.vidchain.fallback.ui

import androidx.appcompat.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import org.websnake.vidchain.fallback.core.CrashReporting
import org.websnake.vidchain.fallback.core.FirebaseAccount
import org.websnake.vidchain.fallback.core.FallbackRuntime
import org.websnake.vidchain.fallback.core.FallbackSettings
import org.websnake.vidchain.fallback.trace.TraceAndroid
import org.websnake.vidchain.fallback.trace.TraceConfig

/** Settings -> VidChain fallbacks: trace log switch + open/share, one switch per method (no master switch, decision 10), engine self-test. */
class VidChainSettingsActivity : AppCompatActivity() {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var lastReport: String? = null

	override fun onCreate(savedInstanceState: Bundle?) {
		VidChainTheme.apply(this)
		super.onCreate(savedInstanceState)
		setContentView(R.layout.vidchain_settings)
		VidChainInsets.fit(this) // T9 decision 11: nothing under the title bar or the navigation bar on Android 15+
		title = getString(R.string.vidchain_settings_title)

		findViewById<SwitchCompat>(R.id.vidchain_trace_enabled).apply {
			isChecked = TraceConfig.enabled
			setOnCheckedChangeListener { _, on -> TraceAndroid.setEnabled(this@VidChainSettingsActivity, on) }
		}
		findViewById<Button>(R.id.vidchain_trace_open).setOnClickListener { openTrace() }
		findViewById<Button>(R.id.vidchain_trace_share).setOnClickListener {
			if (!TraceShare.share(this)) Toast.makeText(this, R.string.vidchain_trace_empty, Toast.LENGTH_SHORT).show()
		}
		setupAccount()
		setupCrashReports()
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

	/** owner 2026-10-28: the optional VidChain account (Firebase: Google, e-mail + password) */
	private fun setupAccount() {
		val h = FirebaseAccount.hooks?.takeIf { it.available() } ?: return
		findViewById<View>(R.id.vidchain_account_section).visibility = View.VISIBLE
		findViewById<Button>(R.id.vidchain_account_google).setOnClickListener { busy(true); h.signInWithGoogle(this) { done(it) } }
		findViewById<Button>(R.id.vidchain_account_email).setOnClickListener { emailDialog(h) }
		findViewById<Button>(R.id.vidchain_account_sign_out).setOnClickListener { busy(true); h.signOut(this) { done(null) } }
		showAccount()
	}

	private fun showAccount() {
		val who = FirebaseAccount.hooks?.signedIn()
		findViewById<TextView>(R.id.vidchain_account_status).text =
			if (who == null) getString(R.string.vidchain_account_signed_out) else getString(R.string.vidchain_account_signed_in, who)
		findViewById<Button>(R.id.vidchain_account_google).isEnabled = who == null
		findViewById<Button>(R.id.vidchain_account_email).isEnabled = who == null
		findViewById<Button>(R.id.vidchain_account_sign_out).isEnabled = who != null
	}

	private fun busy(on: Boolean) {
		for (id in intArrayOf(R.id.vidchain_account_google, R.id.vidchain_account_email, R.id.vidchain_account_sign_out))
			findViewById<Button>(id).isEnabled = !on
	}

	private fun done(error: String?) {
		if (isFinishing || isDestroyed) return
		error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
		showAccount()
	}

	private fun emailDialog(h: FirebaseAccount.Hooks) {
		val pad = FallbackUi.dp(findViewById(R.id.vidchain_methods), 16)
		val email = EditText(this).apply { setHint(R.string.vidchain_account_email_hint); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS }
		val password = EditText(this).apply { setHint(R.string.vidchain_account_password_hint); inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
		val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad / 2, pad, 0); addView(email); addView(password) }
		AlertDialog.Builder(this).setTitle(R.string.vidchain_account_email).setView(box)
			.setPositiveButton(R.string.vidchain_account_sign_in) { _, _ -> busy(true); h.signInWithEmail(email.text.toString(), password.text.toString(), false) { done(it) } }
			.setNegativeButton(R.string.vidchain_account_create) { _, _ -> busy(true); h.signInWithEmail(email.text.toString(), password.text.toString(), true) { done(it) } }
			.setNeutralButton(R.string.vidchain_account_reset) { _, _ ->
				h.sendPasswordReset(email.text.toString()) { e -> done(e ?: getString(R.string.vidchain_account_reset_sent)) }
			}.show()
	}

	/** owner 2026-10-28: one switch for every Firebase report, log and statistic, and a test crash to see one arrive */
	private fun setupCrashReports() {
		val h = CrashReporting.hooks ?: return
		findViewById<View>(R.id.vidchain_crash_section).visibility = View.VISIBLE
		val sw = findViewById<SwitchCompat>(R.id.vidchain_crash_enabled)
		val test = findViewById<Button>(R.id.vidchain_crash_test)
		if (!h.available()) {
			sw.isChecked = false; sw.isEnabled = false; test.isEnabled = false
			findViewById<TextView>(R.id.vidchain_crash_help).setText(R.string.vidchain_crash_not_set_up)
			return
		}
		sw.isChecked = h.enabled()
		test.isEnabled = h.enabled()
		sw.setOnCheckedChangeListener { _, on -> h.setEnabled(on); test.isEnabled = on }
		test.setOnClickListener {
			AlertDialog.Builder(this).setTitle(R.string.vidchain_crash_test).setMessage(R.string.vidchain_crash_test_confirm)
				.setPositiveButton(android.R.string.ok) { _, _ -> h.testCrash() }
				.setNegativeButton(android.R.string.cancel, null).show()
		}
	}

	private fun buildMethodSwitches(box: LinearLayout) {
		val built = FallbackRuntime.methods.map { it.id }.toSet() + org.websnake.vidchain.fallback.core.OwnPath.ALL
		for (id in TrailText.ALL_METHODS) {
			box.addView(SwitchCompat(this).apply {
				text = if (id in built) TrailText.name(id) else getString(R.string.vidchain_method_not_built, TrailText.name(id))
				isEnabled = id in built
				isChecked = FallbackSettings.methodOn(this@VidChainSettingsActivity, id)
				setOnCheckedChangeListener { button, on ->
					// decision 10: the last method that is on cannot be switched off
					if (!FallbackSettings.setMethodOn(this@VidChainSettingsActivity, id, on, built)) {
						button.isChecked = true
						Toast.makeText(this@VidChainSettingsActivity, R.string.vidchain_method_last_on, Toast.LENGTH_SHORT).show()
					}
				}
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
