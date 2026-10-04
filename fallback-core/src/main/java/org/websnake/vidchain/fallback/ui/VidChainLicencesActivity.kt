package org.websnake.vidchain.fallback.ui

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject

/**
 * Settings -> VidChain fallbacks -> Open-source licences (T6.3, Q2 = A: no About/branding screen). The text comes from
 * the asset tools/licences.py writes (every library of the release classpath, every native and Python component).
 */
class VidChainLicencesActivity : AppCompatActivity() {
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val text = runCatching { LicencesText.render(JSONObject(assets.open(LicencesText.ASSET).bufferedReader().use { it.readText() })) }
			.getOrElse { "The licence list could not be read: ${it.javaClass.simpleName}" }
		val pad = (16 * resources.displayMetrics.density).toInt()
		setContentView(ScrollView(this).apply {
			addView(TextView(this@VidChainLicencesActivity).apply {
				this.text = text
				textSize = 13f
				setTextIsSelectable(true)
				setPadding(pad, pad, pad, pad)
			})
		})
	}
}

/** Pure text of the licences screen (JVM-tested). */
object LicencesText {
	const val ASSET = "vidchain-licences.json"

	fun render(doc: JSONObject): String = buildString {
		val app = doc.getJSONObject("original_app")
		append("VidChain is based on ").append(app.getString("name")).append(" by ").append(app.getString("author"))
			.append(" (").append(app.getString("url")).append("), ").append(app.getString("licence")).append(". ")
			.append(app.optString("note")).append("\n\n")
		val native = doc.getJSONArray("native_and_python")
		append("Native and Python components (").append(native.length()).append(")\n\n")
		for (i in 0 until native.length()) {
			val c = native.getJSONObject(i)
			append("• ").append(c.getString("name"))
			c.optString("version").takeIf { it.isNotEmpty() }?.let { append(' ').append(it) }
			append(" — ").append(c.getString("licence")).append("\n   ").append(c.getString("kind"))
			c.optString("url").takeIf { it.isNotEmpty() }?.let { append("\n   ").append(it) }
			append("\n")
		}
		val libs = doc.getJSONArray("libraries")
		append("\nLibraries (").append(libs.length()).append(")\n\n")
		for (i in 0 until libs.length()) {
			val l = libs.getJSONObject(i)
			val lic = l.getJSONArray("licences").let { a -> (0 until a.length()).joinToString(" / ") { a.getJSONObject(it).getString("name") } }
			append("• ").append(l.getString("id")).append(' ').append(l.getString("version")).append(" — ").append(lic)
			l.optString("url").takeIf { it.isNotEmpty() }?.let { append("\n   ").append(it) }
			append("\n")
		}
	}
}
