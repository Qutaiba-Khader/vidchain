package lib.ui.builders

import android.graphics.drawable.Drawable
import android.view.Gravity.NO_GRAVITY
import android.view.LayoutInflater
import android.view.MotionEvent.ACTION_OUTSIDE
import android.view.MotionEvent.ACTION_UP
import android.view.View
import android.view.View.MeasureSpec.UNSPECIFIED
import android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
import android.view.WindowManager
import android.widget.PopupWindow
import androidx.core.content.res.ResourcesCompat
import app.core.bases.interfaces.BaseActivityInf
import com.aio.R
import lib.process.LogHelperUtils
import java.lang.ref.WeakReference

/**
 * A utility class that simplifies the creation and display of a PopupWindow anchored to a specific view.
 * It supports immersive mode, touch interactions, and flexible content initialization.
 *
 * @param activityInf A reference to the activity interface used for context and validation.
 * @param popupLayoutId Resource ID of the layout to inflate as the popup content. Optional if popupContentView is provided.
 * @param popupContentView A fully constructed View to be used as the popup content. Optional if popupLayoutId is provided.
 * @param popupAnchorView The view to which the popup will be anchored on the screen.
 */
class PopupBuilder(
	private val activityInf: BaseActivityInf?,
	private val popupLayoutId: Int = -1,
	private val popupContentView: View? = null,
	private val popupAnchorView: View
) {
	/**
	 * Logger instance for recording debug and error messages.
	 * Used for tracking popup lifecycle events and troubleshooting issues.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Holds a weak reference to the activity to avoid memory leaks.
	 * Ensures that the popup does not prevent the activity from being garbage collected.
	 */
	private val safeActivityRef = WeakReference(activityInf)

	/**
	 * The PopupWindow instance that will be displayed on the screen.
	 * It is initialized using the activity from the weak reference.
	 */
	private val popupWindow = PopupWindow(safeActivityRef.get()?.getActivity())

	/**
	 * The main content view for the popup.
	 * This is either inflated from the provided layout resource or set directly from a given view.
	 * It is initialized during popup setup.
	 */
	private lateinit var popupLayout: View

	/**
	 * Initialization block for the PopupBuilder.
	 *
	 * - Sets up the popup content by inflating a layout or using the provided view.
	 * - Validates that a valid content view is available.
	 * - Configures the PopupWindow (size, background, and touch handling).
	 *
	 * If any step fails, the error is logged and rethrown.
	 */
	init {
		try {
			initializePopupContent() // Inflate or assign the content view
			validateContentView()    // Ensure valid content is provided
			setupPopupWindow()       // Setup dimensions, background, interaction handlers
		} catch (error: Exception) {
			logger.e("Error found while initializing the Popup Builder:", error)
			throw error
		}
	}

	/**
	 * Displays the popup window at a calculated position relative to the anchor view.
	 * Optionally enables immersive mode to hide system UI.
	 *
	 * @param shouldHideStatusAndNavbar If true, enters immersive mode hiding status and nav bars.
	 */
	fun show(shouldHideStatusAndNavbar: Boolean = false) {
		try {
			if (popupWindow.isShowing) return
			if (shouldHideStatusAndNavbar) enableImmersiveMode()
			showPopupWindow()
		} catch (error: Exception) {
			logger.e("Error found while showing popup-view:", error)
		}
	}

	/**
	 * Closes the popup if it's currently showing and the activity is valid.
	 */
	fun close() {
		try {
			val activity = safeActivityRef.get()?.getActivity() ?: return
			if (activity.isValidForWindowManagement() && popupWindow.isShowing) {
				popupWindow.dismiss()
			}
		} catch (error: Exception) {
			logger.e("Error found while closing popup-view:", error)
		}
	}

	/**
	 * @return The content view displayed inside the popup.
	 */
	fun getPopupView(): View = popupWindow.contentView

	/**
	 * @return The PopupWindow instance used by this builder.
	 */
	fun getPopupWindow(): PopupWindow = popupWindow

	/**
	 * Initializes the popup layout either by inflating the given layout ID or using the provided view.
	 * Throws if both are invalid.
	 */
	private fun initializePopupContent() {
		when {
			popupLayoutId != -1 -> {
				val inflater = LayoutInflater.from(safeActivityRef.get()?.getActivity())
				popupLayout = inflater.inflate(popupLayoutId, null, false)
			}

			popupContentView != null -> popupLayout = popupContentView
		}
	}

	/**
	 * Validates that popupLayout has been initialized.
	 * Throws IllegalArgumentException if not initialized properly.
	 */
	private fun validateContentView() {
		if (!::popupLayout.isInitialized) {
			throw IllegalArgumentException(
				"Must provide valid content via popupLayoutId or popupContentView"
			)
		}
	}

	/**
	 * Sets up the popup window with required properties.
	 *
	 * - Makes the popup touchable, focusable, and dismissible when touched outside.
	 * - Sets a transparent background for proper outside-click detection.
	 * - Applies touch handling behavior for popup interactions.
	 * - Defines default width and height as `WRAP_CONTENT`.
	 * - Assigns the content view to display inside the popup.
	 */
	private fun setupPopupWindow() {
		logger.d("Setting up popup window properties")

		popupWindow.apply {
			isTouchable = true
			isFocusable = true
			isOutsideTouchable = true

			logger.d("Applying transparent background to popup window")
			setBackgroundDrawable(createTransparentBackground())

			logger.d("Configuring touch handling for popup window")
			configureTouchHandling()

			logger.d("Setting popup window width and height to WRAP_CONTENT")
			width = WindowManager.LayoutParams.WRAP_CONTENT
			height = WindowManager.LayoutParams.WRAP_CONTENT

			logger.d("Assigning content view to popup window")
			contentView = popupLayout
		}

		logger.d("Popup window setup complete")
	}

	/**
	 * Creates a transparent background drawable for the popup window.
	 *
	 * @return A transparent Drawable or null if the activity context is unavailable.
	 */
	private fun createTransparentBackground(): Drawable? {
		logger.d("Creating transparent background for popup window")
		return safeActivityRef.get()?.getActivity()?.let { ctx ->
			ResourcesCompat.getDrawable(
				ctx.resources,
				R.drawable.bg_image_transparent,
				ctx.theme
			)
		}
	}

	/**
	 * Sets up touch handling for the popup window.
	 * - Performs click action when user lifts finger inside the popup.
	 * - Dismisses popup if touched outside.
	 */
	private fun configureTouchHandling() {
		logger.d("Configuring touch behavior for popup window")
		popupWindow.setTouchInterceptor { view, event ->
			when (event.action) {
				ACTION_UP -> view.performClick().let { false }
				ACTION_OUTSIDE -> popupWindow.dismiss().let { true }
				else -> false
			}
		}
	}

	/**
	 * Enables immersive mode to hide status and navigation bars.
	 * Uses deprecated system flags for backward compatibility.
	 */
	@Suppress("DEPRECATION")
	private fun enableImmersiveMode() {
		logger.d("Enabling immersive mode for popup window")
		val s1 = SYSTEM_UI_FLAG_FULLSCREEN
		val s2 = SYSTEM_UI_FLAG_HIDE_NAVIGATION
		val s3 = SYSTEM_UI_FLAG_IMMERSIVE_STICKY
		popupWindow.contentView.systemUiVisibility = (s1 or s2 or s3)
	}

	/**
	 * Positions and shows the popup window relative to the anchor view.
	 * Calculates X offset to align to screen width minus a margin.
	 */
	private fun showPopupWindow() {
		logger.d("Measuring and positioning popup window on screen")
		val anchorLocation = IntArray(2)
		popupAnchorView.getLocationOnScreen(anchorLocation)
		val anchorY = anchorLocation[1]

		val endMarginInPx = popupLayout.resources.getDimensionPixelSize(R.dimen._10)
		val displayMetrics = popupLayout.resources.displayMetrics
		val screenWidth = displayMetrics.widthPixels

		popupLayout.measure(UNSPECIFIED, UNSPECIFIED)
		val popupWidth = popupLayout.measuredWidth

		val xOffset = screenWidth - popupWidth - endMarginInPx
		popupWindow.showAtLocation(popupAnchorView, NO_GRAVITY, xOffset, anchorY)
		logger.d("Popup window shown at X=$xOffset, Y=$anchorY")
	}

	/**
	 * Checks if the activity is valid for window operations (not finishing or destroyed).
	 *
	 * @return True if the activity is safe to show/dismiss popup, false otherwise.
	 */
	private fun BaseActivityInf?.isValidForWindowManagement(): Boolean {
		val valid = this?.getActivity()?.let { activity ->
			!activity.isFinishing && !activity.isDestroyed
		} ?: false
		logger.d("Activity valid for window management: $valid")
		return valid
	}
}