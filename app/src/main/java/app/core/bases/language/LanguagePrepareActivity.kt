@file:Suppress("DEPRECATION")

package app.core.bases.language

import android.app.*
import android.content.*
import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.*
import androidx.core.view.*
import com.aio.*

/**
 * An activity that serves as a temporary screen, often used during transitions
 * such as a language change. It displays a simple layout (`activity_prepare`) with a
 * transparent status bar.
 *
 * This activity is designed to be short-lived. It automatically finishes itself
 * after a brief delay specified by `LocalizeConstant.FINISH_DELAY` in the `onResume`
 * lifecycle method. This provides a smooth visual transition for the user while
 * background tasks (like recreating the previous activity with a new configuration)
 * can complete.
 *
 * It includes a static `navigate` method to easily start this activity from another.
 */
class LanguagePrepareActivity : AppCompatActivity() {
	
	/**
	 * Called when the activity is first created.
	 *
	 * This method initializes the activity, sets its content view to the `activity_prepare` layout,
	 * and configures the status bar to be transparent.
	 *
	 * @param savedInstanceState If the activity is being re-initialized after previously being shut down,
	 * this Bundle contains the data it most recently supplied in [onSaveInstanceState].
	 * Otherwise, it is null.
	 */
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_locale_change_prepare)
		setUpStatusBarTransparent()
	}
	
	/**
	 * Configures the status bar to be transparent and overlays the activity's layout.
	 *
	 * This function makes the status bar transparent, allowing the activity's content to be drawn
	 * underneath it (edge-to-edge). It handles different Android versions to apply the correct flags
	 * and system UI visibility settings.
	 *
	 * - For all versions with a `rootView`, it sets an `OnApplyWindowInsetsListener` to consume
	 *   the system window insets, preventing the root view from being padded to account for the status bar.
	 * - For Android Lollipop (API 21) and above, it clears the `FLAG_TRANSLUCENT_STATUS` and adds
	 *   `FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS` to allow custom drawing of the status bar background.
	 * - For Android Marshmallow (API 23) and above, it also sets `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`
	 *   to ensure status bar icons are dark and visible against a light background.
	 * - Finally, it sets the `statusBarColor` to `Color.TRANSPARENT`.
	 */
	private fun setUpStatusBarTransparent() {
		findViewById<RelativeLayout>(R.id.rootView)?.let { rootView ->
			ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
				insets.consumeSystemWindowInsets()
			}
		}
		window?.apply {
			clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
			addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
			decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
				View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
			statusBarColor = Color.TRANSPARENT
		}
	}
	
	/**
	 * Called when the activity will start interacting with the user.
	 *
	 * This implementation schedules the activity to finish after a short delay,
	 * creating a transient "prepare" or "splash" screen effect. This is typically used
	 * during transitions, such as when changing the application's language, to provide a
	 * smooth visual experience before the main UI is re-rendered. A fade transition is
	 * applied upon finishing.
	 */
	override fun onResume() {
		super.onResume()
		Handler(Looper.getMainLooper()).postDelayed({
			finish()
			overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
		}, LocalizeConstant.FINISH_DELAY)
	}
	
	/**
	 * Provides a convenient way to start the [LanguagePrepareActivity].
	 *
	 * This activity acts as a transitional screen, often used during processes like
	 * language changes. It displays a temporary view (`activity_locale_change_prepare`)
	 * and then finishes itself after a short delay ([LocalizeConstant.FINISH_DELAY]).
	 * This creates a smooth visual transition, preventing jarring UI shifts while the main
	 * application components are being recreated with the new configuration.
	 *
	 * The `navigate` function encapsulates the intent creation and starting of this activity.
	 */
	companion object {
		
		fun navigate(activity: Activity) {
			val intent = Intent(activity, LanguagePrepareActivity::class.java)
			activity.apply {
				startActivity(intent)
				overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
			}
		}
	}
}
