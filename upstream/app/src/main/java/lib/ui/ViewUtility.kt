@file:Suppress("DEPRECATION")

package lib.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator.ofFloat
import android.app.Activity
import android.content.Context
import android.content.Context.INPUT_METHOD_SERVICE
import android.content.Context.WINDOW_SERVICE
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.BitmapFactory.decodeByteArray
import android.graphics.BitmapFactory.decodeFile
import android.graphics.BitmapFactory.decodeStream
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.util.DisplayMetrics
import android.view.View
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowMetrics
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.animation.AnimationUtils.loadAnimation
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.IdRes
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
import androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
import androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode
import androidx.core.animation.addListener
import androidx.core.content.ContextCompat.getColor
import androidx.core.content.ContextCompat.getDrawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import app.core.AIOApp.Companion.INSTANCE
import app.core.bases.BaseActivity
import app.core.engines.settings.AIOSettings.Companion.AIO_SETTING_DARK_MODE_FILE_NAME
import com.aio.R
import com.bumptech.glide.Glide
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import lib.files.FileSystemUtility
import lib.process.LogHelperUtils
import lib.process.ThreadsUtility
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.HttpURLConnection.HTTP_OK
import java.net.URL
import java.text.BreakIterator
import java.text.BreakIterator.getCharacterInstance
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Utility object providing commonly used view-related helper functions for Android UI components.
 *
 * This comprehensive utility class offers a wide range of static methods for efficient
 * Android UI development and maintenance. Key capabilities include:
 *
 * - **Screen Dimension Management**: Retrieving accurate device screen dimensions using both modern
 * 	 WindowMetrics API (Android R+) and legacy DisplayMetrics for backward compatibility, ensuring consistent
 * 	 layout calculations across all Android versions
 *
 * - **Dynamic Layout Control**: Automatically calculating and adjusting view dimensions based on content,
 * 	  particularly useful for GridView height calculation and column distribution to eliminate internal scrolling
 * 	  in scrollable containers
 *
 * - **Memory Optimization**: Systematic resource cleanup through drawable unbinding  and view hierarchy
 *    deconstruction to prevent memory leaks during view destruction  and configuration changes
 *
 * - **View Retrieval & Navigation**: Safe view lookup within hierarchies using tags, IDs, and parent relationships
 *   with comprehensive null safety and error handling
 *
 * - **Animation Utilities**: Pre-built animations for common UI transitions including fades, slides, rotations, and
 *   combined effects with proper lifecycle management
 *
 * - **Display Adaptation**: Tools for handling modern display features like cutouts,  notches, and safe areas to ensure
 *   content visibility across diverse device designs
 *
 * These utilities help maintain cleaner UI code, reduce boilerplate, ensure better
 * compatibility across all supported Android versions, and improve application
 * performance through optimized resource management and efficient view operations.
 */
object ViewUtility {

	/**
	 * Logger instance for tracking function execution, debugging issues, and monitoring performance.
	 *
	 * This logger provides structured logging capabilities throughout the utility class,
	 * enabling detailed tracing of method calls, parameter values, and error conditions.
	 * Essential for diagnosing layout calculation issues and screen dimension retrieval problems.
	 */
	private val logger = LogHelperUtils.from(javaClass)

	/**
	 * Retrieves the complete width of the device screen in pixels with version-appropriate APIs.
	 *
	 * This function provides a unified interface for obtaining screen width across all Android
	 * versions, using the modern WindowMetrics API on Android R+ for accurate window dimensions
	 * that account for system UI, and falling back to legacy DisplayMetrics for older devices.
	 * Returns the full screen width including any system decorations and navigation bars.
	 *
	 * @return The total width of the device screen in pixels, representing the complete
	 *         available display area from edge to edge including system UI elements.
	 */
	@JvmStatic
	fun getDeviceWidth(): Int {
		return if (VERSION.SDK_INT >= VERSION_CODES.R) {
			// Use modern WindowMetrics API for accurate window dimensions on Android R+
			getCurrentWindowMetrics().bounds.width()
		} else {
			// Fall back to legacy DisplayMetrics API for devices below Android R
			getLegacyDisplayMetrics().widthPixels
		}
	}

	/**
	 * Retrieves the current [WindowMetrics] representing the application's window dimensions and state.
	 *
	 * This function uses the modern WindowManager API introduced in Android R (API level 30)
	 * to obtain precise information about the current window's size, bounds, and configuration.
	 * Provides more accurate measurements than legacy APIs by accounting for window insets,
	 * system bars, and display cutouts in the calculation.
	 *
	 * @return The current [WindowMetrics] object containing the window's bounding rectangle
	 *         and other display characteristics for precise layout calculations.
	 * @throws ClassCastException if the window service cannot be cast to WindowManager
	 */
	@RequiresApi(VERSION_CODES.R)
	private fun getCurrentWindowMetrics(): WindowMetrics {
		// Retrieve the window service for accessing window management functionality
		val windowService = INSTANCE.getSystemService(WINDOW_SERVICE)
		val windowManager = windowService as WindowManager
		// Return current window metrics including bounds and insets
		return windowManager.currentWindowMetrics
	}

	/**
	 * Retrieves [DisplayMetrics] using the legacy window management API for pre-Android R devices.
	 *
	 * This function provides backward compatibility for devices running Android versions
	 * below API level 30. It uses the deprecated but widely supported DisplayMetrics
	 * approach to obtain screen dimensions, including system UI elements like navigation
	 * bars and status bars in the measurement (real metrics).
	 *
	 * @return [DisplayMetrics] object containing the physical display characteristics
	 *         including width, height, and density information for legacy devices.
	 * @throws ClassCastException if the window service cannot be cast to WindowManager
	 */
	@Suppress("DEPRECATION")
	private fun getLegacyDisplayMetrics(): DisplayMetrics {
		return DisplayMetrics().apply {
			// Retrieve window service and get default display for legacy devices
			val windowService = INSTANCE.getSystemService(WINDOW_SERVICE)
			val windowManager = windowService as WindowManager
			// Populate metrics with real display dimensions including system UI
			windowManager.defaultDisplay.getRealMetrics(this)
		}
	}

	/**
	 * Calculates the number of rows required to display all items in a [GridView] with proper pagination.
	 *
	 * This function determines the grid layout dimensions by first calculating the number of columns
	 * that fit the available width, then computes the row count based on total items and column count.
	 * Handles partial rows by rounding up, ensuring all items are displayed. Essential for dynamic
	 * grid layouts and height calculations in scrollable containers.
	 *
	 * @param gridView The [GridView] for which to calculate the row count. Can be null,
	 *                 in which case 0 is returned to indicate no rows needed.
	 * @return The number of rows required to display all grid items, or 0 if the gridView is null,
	 *         has no adapter, or has zero columns. Partial rows count as full rows for layout purposes.
	 */
	@JvmStatic
	fun getNumberOfGridRows(gridView: GridView?): Int {
		if (gridView == null) return 0
		// Get total number of items from adapter, default to 0 if adapter is null
		val totalItems = gridView.adapter?.count ?: 0
		// Calculate how many columns fit in the current grid layout
		val numberOfColumns = getNumberOfGridColumns(gridView)
		return if (numberOfColumns > 0) {
			// Calculate base rows and add extra row if items don't fill columns evenly
			val rows = totalItems / numberOfColumns
			if (totalItems % numberOfColumns == 0) rows else rows + 1
		} else 0
	}

	/**
	 * Determines the number of columns that can fit within the [GridView]'s available width.
	 *
	 * This function calculates the optimal column count by dividing the device screen width
	 * by a predefined column width dimension. Uses a fixed column width (150dp) to maintain
	 * consistent visual appearance across different screen sizes and orientations.
	 *
	 * @param gridView The [GridView] for which to determine the column count. Can be null,
	 *                 in which case 1 is returned as a safe default for single-column layout.
	 * @return The number of columns that fit within the device width, or 1 if the gridView is null,
	 *         column width is invalid (≤0), or calculation fails. Minimum 1 column guaranteed.
	 */
	@JvmStatic
	fun getNumberOfGridColumns(gridView: GridView?): Int {
		if (gridView == null) return 1
		// Get device screen width for column calculation
		val deviceWidth = getDeviceWidth()
		// Retrieve fixed column width from dimension resources (150dp)
		val columnWidth = gridView.resources.getDimensionPixelSize(R.dimen._150)
		// Calculate columns by dividing available width by column width
		return if (columnWidth > 0) deviceWidth / columnWidth else 1
	}

	/**
	 * Sets the height of a [GridView] dynamically based on its content to eliminate internal scrolling.
	 *
	 * This function calculates the exact height needed to display all grid items without
	 * requiring scrollbars within the GridView itself. Essential for embedding grids within
	 * ScrollView containers or when fixed-height layouts are required. Updates layout parameters
	 * and triggers re-layout to apply the new height immediately.
	 *
	 * @param gridView The [GridView] whose height needs adjustment to accommodate all child items.
	 *                 Must not be null as height calculation requires valid grid view instance.
	 */
	@JvmStatic
	fun setGridViewHeightBasedOnChildren(gridView: GridView) {
		// Calculate how many rows are needed to display all items
		val rowCount = getNumberOfGridRows(gridView)
		// Retrieve fixed item height from dimension resources (135dp)
		val itemHeight = gridView.resources.getDimensionPixelSize(R.dimen._135)
		// Calculate total height by multiplying rows by item height
		val totalHeight = itemHeight * rowCount
		// Update layout parameters with calculated height
		val params = gridView.layoutParams
		params.height = totalHeight
		gridView.layoutParams = params
		// Request layout pass to apply new height parameters
		gridView.requestLayout()
	}

	/**
	 * Unbinds drawables from a [View] and its children to prevent memory leaks and free resources.
	 *
	 * This function recursively traverses the view hierarchy and systematically releases
	 * drawable references that could cause memory leaks. It handles background drawables,
	 * ImageView bitmaps, and ViewGroup children with special consideration for AdapterView
	 * types to avoid disrupting list adapters. Essential for calling in onDestroy() or
	 * when complex view hierarchies are no longer needed to ensure proper garbage collection.
	 *
	 * @param view The [View] or [ViewGroup] to recursively unbind drawables from. Can be null,
	 *             in which case the function exits silently without any operation.
	 */
	@JvmStatic
	fun unbindDrawables(view: View?) {
		try {
			// Remove callback from background drawable to prevent memory leaks
			view?.background?.callback = null

			if (view is ImageView) {
				// Clear image bitmap from ImageView to release large bitmap memory
				view.setImageBitmap(null)
			} else if (view is ViewGroup) {
				// Recursively process all child views in the ViewGroup hierarchy
				for (index in 0 until view.childCount)
					unbindDrawables(view.getChildAt(index))

				// Remove all child views except for AdapterView which manages its own children
				if (view !is AdapterView<*>) view.removeAllViews()
			}
		} catch (error: Exception) {
			// Log any errors during unbinding but don't crash the application
			logger.e("Error while unbinding drawables from views:", error)
		}
	}

	/**
	 * Sets a common [View.OnClickListener] on multiple [View]s within an [Activity].
	 *
	 * This function provides efficient batch click listener assignment for multiple views
	 * identified by their resource IDs, eliminating repetitive findViewById calls and
	 * listener assignments. Particularly useful for setting up consistent click behavior
	 * across related UI elements like button groups, navigation items, or form controls
	 * in activity layouts.
	 *
	 * @param clickListener The [View.OnClickListener] to assign to all specified views.
	 *                      Can be null to clear existing click listeners from the views,
	 *                      useful for resetting UI state or during cleanup operations.
	 * @param activity The [Activity] context containing the target views.
	 * @param ids A vararg of integer resource IDs ([IdRes]) identifying the views to receive
	 *            the click listener. Invalid or non-existent IDs are safely ignored without
	 *            affecting other view assignments in the batch operation.
	 */
	@JvmStatic
	fun setViewOnClickListener(
		clickListener: View.OnClickListener?,
		activity: Activity?, @IdRes vararg ids: Int
	) {
		for (id in ids) {
			// Safely find each view by ID and set/clear the click listener
			activity?.findViewById<View>(id).apply {
				this?.setOnClickListener(clickListener)
			}
		}
	}

	/**
	 * Sets a common [View.OnClickListener] on multiple [View]s within a parent [View] hierarchy.
	 *
	 * This function provides batch click listener assignment for multiple views within a
	 * specific layout or view hierarchy. More efficient than activity-level assignment when
	 * working with fragment layouts, custom views, or recycled list items. Handles null
	 * views gracefully and applies the same listener behavior across all specified view IDs.
	 *
	 * @param onClickListener The [View.OnClickListener] to assign to all specified views.
	 *                        Can be null to clear existing click listeners from the views.
	 * @param layout The parent [View] containing the target views to receive click listeners.
	 *               All view IDs are searched within this layout's hierarchy.
	 * @param ids A vararg of integer resource IDs ([IdRes]) identifying the views to receive
	 *            the click listener. Views not found in the layout are safely ignored.
	 */
	@JvmStatic
	fun setViewOnClickListener(
		onClickListener: View.OnClickListener?,
		layout: View, @IdRes vararg ids: Int
	) {
		for (id in ids) {
			// Find each view within the parent layout and set/clear the click listener
			layout.findViewById<View>(id).apply {
				this?.setOnClickListener(onClickListener)
			}
		}
	}

	/**
	 * Retrieves a specific [View] by its ID from a parent [ViewGroup] with null safety.
	 *
	 * This utility function provides a clean, concise way to find views within layout hierarchies
	 * while handling null parent layouts gracefully. Simplifies view lookup code and prevents
	 * NullPointerExceptions when working with dynamically inflated layouts or conditional UI.
	 *
	 * @param parentLayout The parent [ViewGroup] to search within for the target view.
	 *                     Can be null for safe handling in conditional layouts.
	 * @param id The integer resource ID ([IdRes]) of the view to locate and retrieve.
	 *           Must be a valid view ID from the layout XML or generated resources.
	 * @return The [View] with the specified ID if found, or null if the parentLayout is null
	 *         or no view exists with the given ID in the hierarchy.
	 */
	@JvmStatic
	fun getView(parentLayout: View?, @IdRes id: Int): View? {
		return parentLayout?.findViewById(id)
	}

	/**
	 * Starts an infinite clockwise rotation animation on the given [view] for loading indicators.
	 *
	 * This function applies a continuous rotating animation to create spinning loader effects,
	 * commonly used for progress indicators or loading states. The animation runs indefinitely
	 * until explicitly stopped.
	 *
	 * @param activity The [Activity] context used to load animation resources.
	 *
	 * @param view The [View] to animate with continuous clockwise rotation.
	 *             If null, no animation is started.
	 */
	@JvmStatic
	fun animateInfiniteRotation(activity: Activity?, view: View?) {
		if (activity == null) return // Early exit for null activity safely
		// Load clockwise rotation animation from XML resources
		val animResId = R.anim.anim_rotate_clockwise
		val animation = loadAnimation(activity, animResId)
		// Start infinite rotation animation on the target view
		view?.startAnimation(animation)
	}

	/**
	 * Converts Density-independent Pixels (DP) to device-specific Pixels (PX) for responsive layouts.
	 *
	 * This utility function calculates pixel values based on screen density, ensuring consistent
	 * visual sizing across different devices and screen resolutions. Essential for creating
	 * responsive UIs that maintain proportional dimensions regardless of display density.
	 *
	 * @param context The [Context] used to retrieve display density metrics.
	 *
	 * @param dp The value in Density-independent Pixels (DP) to convert to physical pixels.
	 *           Represents consistent visual size across different screen densities.
	 * @return The equivalent value in device-specific Pixels (PX) as integer, or -1 if
	 *         context is null or display metrics are unavailable for calculation.
	 */
	@JvmStatic
	fun dpToPx(context: Context?, dp: Float): Int {
		val metrics = context?.resources?.displayMetrics
		return if (metrics != null) {
			// Calculate pixels using density scaling factor (160dpi baseline)
			(dp * (metrics.densityDpi / 160f)).roundToInt()
		} else -1
	}

	/**
	 * Converts device-specific Pixels (PX) to Density-independent Pixels (DP) for density-aware design.
	 *
	 * This utility function converts physical pixel measurements back to density-independent
	 * units, useful for analyzing existing layouts or working with pixel-based design assets.
	 * Helps maintain consistent spacing and sizing across different screen densities.
	 *
	 * @param context The [Context] used to retrieve display density metrics.
	 *
	 * @param px The value in device-specific Pixels (PX) to convert to density-independent units.
	 *           Represents actual physical pixels on the device screen.
	 * @return The equivalent value in Density-independent Pixels (DP) as float, or -1.0f if
	 *         context is null or display metrics are unavailable for calculation.
	 */
	@JvmStatic
	fun pxToDp(context: Context?, px: Int): Float {
		val metrics = context?.resources?.displayMetrics
		return if (metrics != null) {
			// Calculate DP using density scaling factor (160dpi baseline)
			px / (metrics.densityDpi / 160f)
		} else -1.0f
	}

	/**
	 * Shows the on-screen keyboard and requests focus for the given [focusedView] for text input.
	 *
	 * This function programmatically triggers the soft keyboard to appear and assigns focus
	 * to the specified view, enabling immediate text input without user interaction.
	 * Essential for improving user experience in forms, search interfaces, and input-heavy
	 * applications.
	 *
	 * @param activity The [Activity] context used to access system input method services.
	 *
	 * @param focusedView The [View] that should receive focus and trigger keyboard display.
	 *             Typically an EditText or other input-capable view. If null,
	 *             keyboard cannot be shown as no target view is available.
	 */
	@JvmStatic
	fun showOnScreenKeyboard(activity: Activity?, focusedView: View?) {
		if (activity == null) return // Early exit for null activity safely

		// Retrieve the input method manager system service
		val inputService = activity.getSystemService(INPUT_METHOD_SERVICE)
		val inputMethodManager = inputService as InputMethodManager
		// Request focus on target view and show soft keyboard
		focusedView?.requestFocus()
		inputMethodManager.showSoftInput(focusedView, 0)
	}

	/**
	 * Hides the on-screen keyboard by dismissing the current input method session.
	 *
	 * This function uses the InputMethodManager to programmatically close the soft keyboard
	 * that is currently associated with the focused view's window token. Essential for
	 * improving user experience when keyboard dismissal is required after text input
	 * completion or during screen transitions.
	 *
	 * @param activity The [Activity] context used to access system input method services.
	 *
	 * @param focusedView The [View] that currently has input focus, providing the window token
	 *                    needed to identify which keyboard session to dismiss. If null,
	 *                    function cannot hide keyboard as token is unavailable.
	 */
	@JvmStatic
	fun hideOnScreenKeyboard(activity: Activity?, focusedView: View?) {
		if (activity == null) return // Early exit for null activity safely

		if (focusedView != null) {
			// Retrieve the input method manager system service
			val service = activity.getSystemService(INPUT_METHOD_SERVICE)
			val inputMethodManager = service as InputMethodManager
			// Dismiss keyboard using the focused view's window token
			inputMethodManager.hideSoftInputFromWindow(focusedView.windowToken, 0)
		}
	}

	/**
	 * Reduces the size of non-Latin characters in a [TextView] to improve visual balance.
	 *
	 * This function iterates through the text of a `TextView` and identifies character clusters
	 * that are not part of the Latin script (e.g., Arabic, Cyrillic, CJK). It then applies a
	 * `RelativeSizeSpan` to these clusters, reducing their font size by a specified factor.
	 * This is useful for normalizing the appearance of mixed-script text where some characters
	 * (like those in certain Asian or Middle Eastern languages) may appear disproportionately
	 * tall compared to Latin characters, leading to uneven line height.
	 *
	 * The processing is performed asynchronously in a coroutine to avoid blocking the main thread.
	 *
	 * @param reductionFactor The factor by which to reduce the size of non-Latin characters.
	 *                        Defaults to `0.8f` (i.e., 80% of the original size).
	 * @param scope The [CoroutineScope] in which to launch the text processing task.
	 *              Defaults to a scope using the `Default` dispatcher.
	 * @param onDone A callback that is invoked on the main thread after the spannable text
	 *               has been applied, passing the generated [Spannable] as an argument.
	 */
	@JvmStatic
	fun TextView.normalizeTallSymbols(
		reductionFactor: Float = 0.8f,
		scope: CoroutineScope = CoroutineScope(Default),
		onDone: (Spannable) -> Unit = {}
	) {
		// Get the full text from the TextView.
		val fullText = this@normalizeTallSymbols.text.toString()
		// If the text is empty, there's nothing to process, so we skip execution.
		if (fullText.isEmpty()) {
			logger.d("normalizeTallSymbols: empty text, skipping")
			return
		}

		// Launch a coroutine on a background thread (Default dispatcher) to avoid blocking the UI.
		scope.launch(Default) {
			logger.d("normalizeTallSymbols: processing text=\"$fullText\"")

			// Create a SpannableStringBuilder to apply formatting changes to the text.
			val spannable = SpannableStringBuilder(fullText)
			// Use BreakIterator to correctly identify boundaries of user-perceived
			// characters (grapheme clusters). This is for handling complex scripts and emoji.
			val boundaryIterator = getCharacterInstance(Locale.getDefault())
			boundaryIterator.setText(fullText)

			// Initialize the iterator to find the first character cluster.
			var currentStart = boundaryIterator.first()
			var currentEnd = boundaryIterator.next()

			// Loop through each character cluster in the text.
			while (currentEnd != BreakIterator.DONE) {
				// Extract the current character cluster
				// (which could be a single character or a more complex grapheme).
				val cluster = fullText.substring(currentStart, currentEnd)

				// Determine if this cluster should be reduced in size.
				// A reduction is applied if the cluster contains any character that is:
				// 1. Not a space character.
				// 2. Not part of the Latin script (e.g., CJK, Arabic, Cyrillic, etc.).
				val shouldReduce = cluster.any { char ->
					!Character.isSpaceChar(char) &&
						!Character.UnicodeScript.of(char.code).isLatin()
				}

				// If the cluster needs to be reduced, apply the RelativeSizeSpan.
				if (shouldReduce) {
					logger.d("normalizeTallSymbols: reducing " +
						"cluster=\"$cluster\" at [$currentStart, $currentEnd]")
					try {
						// Apply a span that changes the font size relative to the default size.
						spannable.setSpan(
							RelativeSizeSpan(reductionFactor),
							currentStart,
							currentEnd,
							Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
						)
					} catch (error: Exception) {
						// Log any errors during the spanning process to avoid crashing the app.
						logger.d("normalizeTallSymbols: span error $error")
					}
				}

				// Move to the next character cluster for the next iteration.
				currentStart = currentEnd
				currentEnd = boundaryIterator.next()
			}

			// Switch back to the main thread to update the UI.
			withContext(Dispatchers.Main) {
				logger.d("normalizeTallSymbols: finished")
				this@normalizeTallSymbols.text = spannable
				onDone(spannable)
			}
		}
	}

	/**
	 * Checks if a `UnicodeScript` is Latin.
	 *
	 * @return `true` if the script is [Character.UnicodeScript.LATIN], `false` otherwise.
	 *         Handles `null` receivers gracefully by returning `false`.
	 */
	private fun Character.UnicodeScript?.isLatin(): Boolean {
		return this == Character.UnicodeScript.LATIN
	}

	/**
	 * Tints the given [Drawable] with the application's primary brand color.
	 *
	 * This function applies color tinting to drawables using the primary color defined
	 * in the application's theme resources. Useful for creating consistent branded
	 * icons and graphics that match the app's color scheme. Uses DrawableCompat for
	 * backward compatibility across Android versions.
	 *
	 * @param targetDrawable The [Drawable] to apply primary color tinting to. If null,
	 *                       function exits silently without any modification.
	 */
	@JvmStatic
	fun tintDrawableWithPrimaryColor(targetDrawable: Drawable?) {
		if (targetDrawable == null) return
		// Retrieve primary color from application resources
		val tintColor = getColor(INSTANCE, R.color.color_primary)
		// Apply tint using DrawableCompat for version compatibility
		DrawableCompat.setTint(targetDrawable, tintColor)
	}

	/**
	 * Tints the given [Drawable] with the application's secondary accent color.
	 *
	 * This function applies color tinting to drawables using the secondary color defined
	 * in the application's theme resources. Ideal for creating visual hierarchy and
	 * accent elements that complement the primary color scheme. Uses DrawableCompat
	 * for consistent tinting behavior across different Android versions.
	 *
	 * @param targetDrawable The [Drawable] to apply secondary color tinting to. If null,
	 *                       function exits silently without any modification.
	 */
	@JvmStatic
	fun tintDrawableWithSecondaryColor(targetDrawable: Drawable?) {
		if (targetDrawable == null) return
		// Retrieve secondary color from application resources
		val tintColor = getColor(INSTANCE, R.color.color_secondary)
		// Apply tint using DrawableCompat for version compatibility
		DrawableCompat.setTint(targetDrawable, tintColor)
	}

	/**
	 * Tints the given [Drawable] with a custom color specified by resource ID.
	 *
	 * This function provides flexible color tinting for drawables using any color
	 * resource from the application's palette. Enables dynamic theming and state-based
	 * color changes for icons and graphics. Uses DrawableCompat for reliable tinting
	 * across all supported Android versions.
	 *
	 * @param targetDrawable The [Drawable] to apply custom color tinting to. If null,
	 *                       function exits silently without any modification.
	 * @param colorResId The resource ID of the color to use for tinting. Must be a
	 *                   valid color resource identifier from R.color namespace.
	 */
	@JvmStatic
	fun tintDrawableWithProvidedColor(targetDrawable: Drawable?, colorResId: Int) {
		if (targetDrawable == null) return
		// Retrieve specified color from application resources
		val tintColor = getColor(INSTANCE, colorResId)
		// Apply tint using DrawableCompat for version compatibility
		DrawableCompat.setTint(targetDrawable, tintColor)
	}

	/**
	 * Checks if the on-screen keyboard is currently visible by analyzing display frame dimensions.
	 *
	 * This function detects keyboard visibility by comparing the visible display frame height
	 * with the total screen height. When the keyboard appears, it reduces the available visible
	 * area, creating a measurable difference. Uses a threshold of 100 pixels to distinguish
	 * between keyboard presence and normal UI variations.
	 *
	 * @param activity The [Activity] context used to access window and view hierarchy.
	 *
	 * @return `true` if the on-screen keyboard is currently visible and occupying screen space,
	 *         `false` if keyboard is hidden, activity is null, or measurement fails.
	 */
	@JvmStatic
	fun isOnScreenKeyboardVisible(activity: Activity?): Boolean {
		if (activity == null) return false // Early exit for null activity safely

		// Get the root content view of the activity
		val rootView = activity.findViewById<View>(android.R.id.content)
		// Calculate the currently visible display frame
		val rect = Rect()
		rootView?.getWindowVisibleDisplayFrame(rect)
		// Get total screen height including off-screen areas
		val screenHeight = rootView?.rootView?.height
		// Calculate keyboard height as difference between total and visible height
		val keypadHeight = screenHeight?.minus(rect.bottom)
		// Return true if keyboard height exceeds threshold (typically >100px for most keyboards)
		return if (keypadHeight != null) keypadHeight > 100 else false
	}

	/**
	 * Sets the visibility of multiple [View]s to either [VISIBLE] or [GONE] with batch operation.
	 *
	 * This utility function provides efficient batch control over view visibility states,
	 * converting a boolean parameter to appropriate visibility flags. Useful for showing/hiding
	 * related UI elements simultaneously, such as form sections, loading states, or conditional
	 * layouts. Handles null views gracefully without affecting other views in the batch.
	 *
	 * @param isVisible `true` to set all views to [VISIBLE] state, `false` to set to [GONE] state.
	 *                  GONE completely removes views from layout, unlike INVISIBLE which preserves space.
	 * @param views A vararg of [View]s whose visibility needs synchronized updating.
	 *              Null views are safely ignored without interrupting batch processing.
	 */
	@JvmStatic
	fun setViewsVisibility(isVisible: Boolean, vararg views: View?) {
		// Convert boolean to appropriate Android visibility constant
		val visibility = if (isVisible) VISIBLE else GONE
		// Apply visibility to all non-null views in the batch
		for (view in views) view?.visibility = visibility
	}

	/**
	 * Sets the visibility of multiple [View]s to a specific visibility state with precise control.
	 *
	 * This function provides fine-grained batch visibility control allowing specification of
	 * exact visibility states (VISIBLE, GONE, or INVISIBLE). Useful for complex UI state
	 * management where different visibility behaviors are required. Handles null views safely
	 * and applies the same visibility state to all specified views.
	 *
	 * @param visibility The desired visibility state ([VISIBLE], [GONE], or [INVISIBLE]).
	 *                   VISIBLE shows view, GONE hides and removes from layout, INVISIBLE hides but preserves space.
	 * @param views A vararg of [View]s whose visibility needs synchronized updating.
	 *              Null views are safely ignored without affecting other views in the operation.
	 */
	@JvmStatic
	fun setViewsVisibility(visibility: Int, vararg views: View?) {
		// Apply specified visibility constant to all non-null views
		for (view in views) view?.visibility = visibility
	}

	/**
	 * Toggles the visibility of a [targetView] with optional fade animation in both directions.
	 *
	 * This function provides intelligent visibility toggling with smooth animated transitions
	 * when enabled. For showing, it uses fade-in from transparent to opaque. For hiding, it uses
	 * fade-out from opaque to transparent with proper visibility state management. Includes
	 * immediate toggle option without animation for performance-critical scenarios.
	 *
	 * @param targetView The [View] whose visibility needs to be toggled between VISIBLE and GONE.
	 * @param shouldAnimate If `true`, uses smooth fade animations for pleasant visual transitions.
	 *                      If `false`, toggles visibility immediately without animation.
	 * @param animTimeout The duration of fade animations in milliseconds (default: 300ms).
	 *                    Applies to both fade-in and fade-out transitions.
	 */
	@JvmStatic
	fun toggleViewVisibility(targetView: View, shouldAnimate: Boolean = false, animTimeout: Long = 300) {
		if (shouldAnimate) {
			if (targetView.isVisible) {
				// Animate fade-out and set GONE after animation completes
				targetView.animate().alpha(0f)
					.setDuration(animTimeout)
					.withEndAction { targetView.visibility = GONE }

			} else {
				// Set initial transparent state and animate fade-in to visible
				targetView.alpha = 0f
				targetView.visibility = VISIBLE
				targetView.animate().alpha(1f).setDuration(animTimeout)
			}
		} else {
			// Immediate toggle without animation for instant visibility change
			targetView.visibility = if (targetView.isVisible) GONE
			else VISIBLE
		}
	}

	/**
	 * Animates a view with a pop effect by scaling up slightly while fading out for dramatic disappearance.
	 *
	 * This function creates an attention-grabbing exit animation that combines scale enlargement
	 * with opacity reduction. The view briefly expands to 120% size while becoming transparent,
	 * creating a "pop" effect that draws attention before disappearance. Uses synchronized
	 * property animations for smooth, coordinated visual effects.
	 *
	 * @param targetView The [View] to animate with pop and fade-out effect. Can be null for safe handling.
	 * @param duration The duration of the complete animation sequence in milliseconds.
	 *                 Controls the speed of both scaling and fading transitions.
	 */
	@JvmStatic
	fun animatePopAndFadeOut(targetView: View?, duration: Long) {
		// Create horizontal scale animation from normal size to 120% enlarged
		val scaleUpX = ofFloat(targetView, "scaleX", 1f, 1.2f)
		// Create vertical scale animation from normal size to 120% enlarged
		val scaleUpY = ofFloat(targetView, "scaleY", 1f, 1.2f)
		// Create opacity animation from fully opaque to completely transparent
		val fadeOut = ofFloat(targetView, "alpha", 1f, 0f)

		// Combine all animations to run simultaneously
		val animatorSet = AnimatorSet()
		animatorSet.playTogether(scaleUpX, scaleUpY, fadeOut)
		animatorSet.duration = duration
		animatorSet.start()
	}

	/**
	 * Animates a view by fading it out and sliding it to the left for smooth horizontal exit.
	 *
	 * This function creates a combined animation that reduces opacity while translating the view
	 * leftward by its full width. The view becomes INVISIBLE after animation completion, making
	 * it suitable for swipe-to-dismiss effects or horizontal navigation transitions.
	 *
	 * @param targetView The [View] to animate with fade-out and left slide effect. If null,
	 *                   function returns immediately without animation.
	 * @param duration The duration of the combined animation in milliseconds (default: 300ms).
	 *                 Controls the speed of both fade and slide transitions.
	 */
	@JvmStatic
	fun fadeOutAndSlideLeft(targetView: View?, duration: Long = 300) {
		if (targetView == null) return  // Early exit for null safety

		// Create opacity animation from fully opaque to completely transparent
		val fadeOut = ofFloat(targetView, "alpha", 1f, 0f)
		// Create horizontal translation animation moving view leftward by its width
		val slideLeft = ofFloat(targetView, "translationX", 0f, -targetView.width.toFloat())

		// Combine both animations to run simultaneously
		val animatorSet = AnimatorSet()
		animatorSet.playTogether(fadeOut, slideLeft)
		animatorSet.duration = duration
		animatorSet.start()
		// Set view to INVISIBLE after animation completes
		animatorSet.addListener(onEnd = { targetView.visibility = INVISIBLE })
	}

	/**
	 * Animates a view by sliding it in from the left while fading in for elegant horizontal entrance.
	 *
	 * This function creates a combined animation that translates the view from left outside the
	 * screen to its final position while increasing opacity from transparent to opaque. The view
	 * is set to VISIBLE before animation starts. Ideal for slide-in menus, navigation drawers,
	 * or horizontal content transitions.
	 *
	 * @param targetView The [View] to animate with left slide-in and fade-in effect. If null,
	 *                   function returns immediately without animation.
	 * @param duration The duration of the combined animation in milliseconds (default: 300ms).
	 *                 Controls the speed of both slide and fade transitions.
	 */
	@JvmStatic
	fun slideInFromLeftAndFadeIn(targetView: View?, duration: Long = 300) {
		if (targetView == null) return  // Early exit for null safety

		// Set initial state: positioned left outside view area and completely transparent
		targetView.translationX = -targetView.width.toFloat()
		targetView.alpha = 0f
		targetView.visibility = VISIBLE  // Make visible before animation starts

		// Create horizontal translation animation from left to final position
		val slideIn = ofFloat(targetView, "translationX", -targetView.width.toFloat(), 0f)
		// Create opacity animation from transparent to fully opaque
		val fadeIn = ofFloat(targetView, "alpha", 0f, 1f)

		// Combine both animations to run simultaneously
		val animatorSet = AnimatorSet()
		animatorSet.playTogether(slideIn, fadeIn)
		animatorSet.duration = duration
		animatorSet.start()
	}

	/**
	 * Animates a view by simultaneously fading it out and sliding it upward for smooth disappearance.
	 *
	 * This function creates a combined animation that reduces opacity to transparent while
	 * translating the view upward by its own height. The view becomes INVISIBLE after animation
	 * completion, preserving layout space while hiding content. Uses AnimatorSet for synchronized
	 * property animations with precise timing control.
	 *
	 * @param targetView The [View] to animate with fade-out and slide-up effect. If null,
	 *                   function returns immediately without any animation.
	 * @param duration The duration of the combined animation in milliseconds (default: 300ms).
	 *                 Controls the speed of both fade and slide transitions simultaneously.
	 */
	@JvmStatic
	fun fadeOutAndSlideUp(targetView: View?, duration: Long = 300) {
		if (targetView == null) return  // Early exit for null safety

		// Create alpha animation from fully opaque to completely transparent
		val fadeOut = ofFloat(targetView, "alpha", 1f, 0f)
		// Create vertical translation animation moving view upward by its height
		val slideUp = ofFloat(targetView, "translationY", 0f, -targetView.height.toFloat())

		// Combine both animations to run simultaneously
		AnimatorSet().apply {
			playTogether(fadeOut, slideUp)
			this.duration = duration
			addListener(object : AnimatorListenerAdapter() {
				override fun onAnimationEnd(animation: Animator) {
					// Set view to INVISIBLE after animation to preserve layout space
					targetView.visibility = INVISIBLE
				}
			})
			start()
		}
	}

	/**
	 * Animates a view by simultaneously fading it in and sliding it downward for elegant appearance.
	 *
	 * This function creates a combined animation that increases opacity from transparent to opaque
	 * while translating the view downward from above its position to its final location. The view
	 * is set to VISIBLE before animation starts and uses synchronized property animations for
	 * smooth, coordinated entrance effects.
	 *
	 * @param targetView The [View] to animate with fade-in and slide-down effect. If null,
	 *                   function returns immediately without any animation.
	 * @param duration The duration of the combined animation in milliseconds (default: 300ms).
	 *                 Controls the speed of both fade and slide transitions simultaneously.
	 */
	@JvmStatic
	fun fadeInAndSlideDown(targetView: View?, duration: Long = 300) {
		if (targetView == null) return  // Early exit for null safety

		// Set initial state: positioned above view area and completely transparent
		targetView.translationY = -targetView.height.toFloat()
		targetView.alpha = 0f
		targetView.visibility = VISIBLE  // Make visible before animation starts

		// Create alpha animation from transparent to fully opaque
		val fadeIn = ofFloat(targetView, "alpha", 0f, 1f)
		// Create vertical translation animation moving view downward to final position
		val slideDown = ofFloat(targetView, "translationY", -targetView.height.toFloat(), 0f)

		// Combine both animations to run simultaneously
		AnimatorSet().apply {
			playTogether(fadeIn, slideDown)
			this.duration = duration
			start()
		}
	}

	/**
	 * Hides a [targetView] with optional fade-out animation and duplicate state prevention.
	 *
	 * This function efficiently manages view hiding with intelligent state checking to
	 * avoid redundant operations. When animation is enabled, it creates a smooth fade-out
	 * effect before setting visibility to GONE. Includes automatic early return if the
	 * view is already hidden to optimize performance and prevent unnecessary animations.
	 *
	 * @param targetView The [View] to hide with optional transition effect.
	 * @param shouldAnimate If `true`, applies smooth fade-out animation for pleasant visual transition.
	 *                      If `false`, immediately sets visibility to GONE without animation.
	 * @param animTimeout The duration of the fade-out animation in milliseconds (default: 500ms).
	 *                    Longer durations create more gradual, noticeable fade effects.
	 */
	@JvmStatic
	fun hideView(targetView: View?, shouldAnimate: Boolean = false, animTimeout: Long = 500) {
		//Early return if null target view is passed
		if (targetView == null) return

		// Early return if view is already hidden to prevent redundant operations
		if (!targetView.isVisible) return

		if (shouldAnimate) {
			// Animate to transparent state with specified duration
			targetView.animate().alpha(0f)
				.setDuration(animTimeout)
			// Set to GONE after animation starts (consider using withEndAction for better timing)
			targetView.visibility = GONE
		} else {
			// Immediate hiding without animation for instant disappearance
			targetView.visibility = GONE
		}
	}

	@JvmStatic
	fun hideView(targetView: View?, visibility: Int = GONE, shouldAnimate: Boolean = false, animTimeout: Long = 500) {
		//Early return if null target view is passed
		if (targetView == null) return

		// Early return if view is already hidden to prevent redundant operations
		if (!targetView.isVisible) return

		if (shouldAnimate) {
			// Animate to transparent state with specified duration
			targetView.animate().alpha(0f)
				.setDuration(animTimeout)
			// Set to GONE after animation starts (consider using withEndAction for better timing)
			targetView.visibility = visibility
		} else {
			// Immediate hiding without animation for instant disappearance
			targetView.visibility = visibility
		}
	}

	/**
	 * Shows a [targetView] with optional fade-in animation and duplicate visibility prevention.
	 *
	 * This function efficiently manages view visibility with intelligent state checking
	 * to avoid redundant operations. When animation is enabled, it creates a smooth
	 * fade-in effect from completely transparent to fully opaque. Includes automatic
	 * early return if the view is already visible to optimize performance.
	 *
	 * @param targetView The [View] to make visible with optional transition effect.
	 * @param shouldAnimate If `true`, applies smooth fade-in animation for pleasant visual transition.
	 *                      If `false`, immediately sets visibility without animation.
	 * @param animTimeout The duration of the fade-in animation in milliseconds (default: 500ms).
	 *                    Longer durations create more gradual, noticeable fade effects.
	 */
	@JvmStatic
	fun showView(targetView: View?, shouldAnimate: Boolean = false, animTimeout: Long = 500) {
		//Early return if null target view is passed
		if (targetView == null) return

		// Early return if view is already visible to prevent redundant operations
		if (targetView.isVisible) return

		if (shouldAnimate) {
			// Set initial transparent state and make view visible before animation
			targetView.alpha = 0f
			targetView.visibility = VISIBLE
			// Animate to fully opaque state with specified duration
			targetView.animate().alpha(1f).setDuration(animTimeout)
		} else {
			// Immediate visibility without animation for instant display
			targetView.visibility = VISIBLE
		}
	}

	/**
	 * Gets the height of the top display cutout (notch) for safe area calculation.
	 *
	 * This function retrieves the safe inset measurement from the device's display cutout
	 * to determine the vertical space occupied by notches, camera holes, or other display
	 * obstructions. Essential for creating notch-aware layouts that prevent content
	 * from being obscured by modern screen designs.
	 *
	 * @param activity The [Activity] context used to access window insets and display metrics.
	 *
	 * @return The height of the top cutout area in pixels, or 0 if no cutout exists,
	 *         activity is null, or device doesn't support display cutout API.
	 */
	@JvmStatic
	fun getTopCutoutHeight(activity: Activity?): Int {
		if (activity == null) return 0 // Early exit for null activity safely

		// Access window insets to retrieve display cutout information
		val windowInsets = activity.window?.decorView?.rootWindowInsets
		val displayCutout = windowInsets?.displayCutout
		// Return safe inset top which represents the cutout height
		return displayCutout?.safeInsetTop ?: 0
	}

	/**
	 * Sets the top margin of a [view] to accommodate display cutout (notch) safe area.
	 *
	 * This function automatically calculates the required top margin by measuring the
	 * device's display cutout height and applies it to the view's layout parameters.
	 * Ensures content appears below the cutout area for proper visibility and prevents
	 * content clipping or obstruction by notches and camera cutouts.
	 *
	 * @param view The [View] to adjust top margin for cutout accommodation. If null,
	 *             function exits silently without any layout modifications.
	 * @param activity The [Activity] context used to determine cutout dimensions.
	 */
	@JvmStatic
	fun setTopMarginWithCutout(view: View?, activity: Activity?) {
		if (view == null) return  // Early exit for null view safety
		if (activity == null) return // Early exit for null activity safely

		// Retrieve and modify the view's layout parameters
		val layoutParams = view.layoutParams as ViewGroup.MarginLayoutParams
		// Calculate required top margin based on cutout height
		val topCutoutHeight = getTopCutoutHeight(activity)
		layoutParams.topMargin = topCutoutHeight
		// Apply modified layout parameters to the view
		view.layoutParams = layoutParams
	}

	/**
	 * Enables or disables multiple [View]s with comprehensive null safety.
	 *
	 * This utility function provides batch control over view interactivity states,
	 * efficiently handling null views to prevent NullPointerExceptions. Useful for
	 * form validation, loading states, or conditional UI control scenarios where
	 * multiple views need synchronized enabled/disabled states.
	 *
	 * @param enabled `true` to enable interactive state for all views, `false` to disable them.
	 *                Disabled views appear grayed out and don't respond to user input.
	 * @param views A vararg of [View]s to modify enabled state. Null views are safely ignored
	 *              without affecting other views in the batch operation.
	 */
	@JvmStatic
	fun setViewsEnabled(enabled: Boolean, vararg views: View?) {
		for (view in views) if (view != null) view.isEnabled = enabled
	}

	/**
	 * Sets the transparency (alpha) of multiple [View]s with batch operation efficiency.
	 *
	 * This function allows synchronized alpha adjustments across multiple views for
	 * consistent visual effects. Handles null views gracefully and applies the same
	 * transparency level to all specified views. Useful for creating disabled states,
	 * overlay effects, or gradual appearance transitions.
	 *
	 * @param alpha The alpha value to set (0.0f for fully transparent, 1.0f for fully opaque).
	 *              Values outside 0-1 range are automatically clamped by Android system.
	 * @param views A vararg of [View]s to apply transparency to. Null views are safely
	 *              skipped without affecting transparency of other views.
	 */
	@JvmStatic
	fun setViewsTransparency(alpha: Float, vararg views: View?) {
		for (view in views) if (view != null) view.alpha = alpha
	}

	/**
	 * Checks if a [targetView] is currently visible within its parent layout.
	 *
	 * This utility wrapper provides direct access to view visibility state with
	 * clear intent. Handles the combined check of VISIBLE visibility flag and
	 * non-zero dimensions to determine if view is actually visible to users.
	 *
	 * @param targetView The [View] to check for current visibility state.
	 * @return `true` if the view has VISIBLE visibility flag and non-zero dimensions,
	 *         `false` if view is GONE, INVISIBLE, or has zero size.
	 */
	@JvmStatic
	fun isViewVisible(targetView: View): Boolean = targetView.isVisible

	/**
	 * Recursively finds a [View] within a view hierarchy by its tag using depth-first search.
	 *
	 * This function traverses the entire view tree starting from the root view, checking
	 * each view's tag against the target value. Returns the first matching view found.
	 * Useful for locating specific views in complex layouts when view IDs are not available.
	 *
	 * @param rootView The root [View] of the hierarchy to search within. Can be null for safe handling.
	 * @param tag The tag object to search for in the view hierarchy. Uses standard equality comparison.
	 * @return The first [View] found with matching tag, or null if no match found or rootView is null.
	 */
	@JvmStatic
	fun findViewByTag(rootView: View?, tag: Any): View? {
		if (rootView == null) return null
		if (tag == rootView.tag) return rootView
		if (rootView is ViewGroup) {
			for (index in 0 until rootView.childCount) {
				val child = findViewByTag(rootView.getChildAt(index), tag)
				if (child != null) {
					return child
				}
			}
		}
		return null
	}

	/**
	 * Measures the intrinsic size (width and height) of a [view] without layout constraints.
	 *
	 * This function performs measurement with UNSPECIFIED spec to determine the view's
	 * natural dimensions based on its content and layout parameters. Useful for calculating
	 * required space before actual layout, or for custom layout implementations.
	 *
	 * @param view The [View] to measure for intrinsic width and height dimensions.
	 * @return An [IntArray] containing measured width at index 0 and height at index 1 in pixels,
	 *         or the view's current dimensions if measurement cannot be performed.
	 */
	@JvmStatic
	fun measureViewSize(view: View): IntArray {
		view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
		return intArrayOf(view.measuredWidth, view.measuredHeight)
	}

	@JvmStatic
	fun animateViewVisibility(targetView: View, visibility: Int, duration: Int) {
		val alpha = if (visibility == VISIBLE) 1f else 0f
		targetView
			.animate()
			.alpha(alpha)
			.setDuration(duration.toLong())
			.withStartAction { if (visibility == VISIBLE) targetView.visibility = VISIBLE }
			.withEndAction { if (visibility != VISIBLE) targetView.visibility = visibility }
	}

	@JvmStatic
	fun shakeAnimationOnView(targetView: View, durationOfShake: Long, durationOfAnim: Long) {
		val shakeX = ofFloat(
			targetView, "translationX",
			0f, 10f, -10f, 10f, -10f, 5f, -5f, 0f
		)
		shakeX.duration = durationOfShake

		val animatorSet = AnimatorSet()
		animatorSet.play(shakeX)

		val handler = Handler(Looper.getMainLooper())
		val endTime = System.currentTimeMillis() + durationOfAnim

		fun startShaking() {
			if (System.currentTimeMillis() < endTime) {
				animatorSet.start()
				handler.postDelayed({ startShaking() }, shakeX.duration)
			}
		}

		startShaking()
	}

	@JvmStatic
	fun animateFadInOutAnim(targetView: View?) {
		if (targetView == null) return

		val current = targetView.animation
		if (current != null && !current.hasEnded()) {
			return
		}

		val anim = AlphaAnimation(0f, 1f).apply {
			duration = 500
			repeatCount = Animation.INFINITE
			repeatMode = Animation.REVERSE
		}

		targetView.startAnimation(anim)
	}

	@JvmStatic
	fun closeAnyAnimation(view: View?) = view?.clearAnimation()

	@JvmStatic
	fun fadeOutView(
		view: View?, duration: Long = 300L,
		onAnimationEnd: (() -> Unit)? = null
	) {
		if (view == null) return
		val fadeOut = AlphaAnimation(1.0f, 0.0f).apply {
			this.duration = duration
			fillAfter = true
			setAnimationListener(object : Animation.AnimationListener {
				override fun onAnimationStart(animation: Animation?) {}
				override fun onAnimationEnd(animation: Animation?) {
					onAnimationEnd?.invoke()
				}

				override fun onAnimationRepeat(animation: Animation?) {}
			})
		}
		view.startAnimation(fadeOut)
	}

	@JvmStatic
	fun setTextViewText(activity: Activity?, @IdRes id: Int, text: String) {
		activity?.findViewById<TextView>(id)?.text = text
	}

	@JvmStatic
	fun setImageViewDrawable(activity: Activity?, @IdRes id: Int, drawable: Drawable?) {
		activity?.findViewById<ImageView>(id)?.setImageDrawable(drawable)
	}

	@JvmStatic
	fun getTextViewText(activity: Activity?, @IdRes id: Int): String? {
		return activity?.findViewById<TextView>(id)?.text.toString()
	}

	@JvmStatic
	fun getImageViewBitmap(activity: Activity?, @IdRes id: Int): Bitmap? {
		val imageView = activity?.findViewById<ImageView>(id)
		if (imageView != null) {
			val drawable = imageView.drawable
			if (drawable is BitmapDrawable) return drawable.bitmap
		}
		return null
	}

	@JvmStatic
	fun getId(view: View?): Int? = view?.id

	@JvmStatic
	fun loadThumbnailFromUrl(
		thumbnailUrl: String,
		targetImageView: ImageView,
		placeHolderDrawableId: Int? = null
	) {
		ThreadsUtility.executeInBackground(codeBlock = {
			try {
				val url = URL(thumbnailUrl)
				val connection = url.openConnection() as HttpURLConnection
				connection.doInput = true
				connection.connectTimeout = 5000
				connection.readTimeout = 5000
				connection.connect()

				val input: InputStream = connection.inputStream
				val bitmap = decodeStream(input) ?: return@executeInBackground

				val isPortrait = bitmap.height > bitmap.width

				val rotatedBitmap = if (isPortrait) {
					rotateBitmap(bitmap, 90f)
				} else bitmap

				ThreadsUtility.executeOnMain {
					Glide.with(targetImageView.context)
						.load(rotatedBitmap).into(targetImageView)
				}
				input.close()
				connection.disconnect()
			} catch (error: Exception) {
				logger.e("Error while loading thumbnail from a remote url:", error)
				if (placeHolderDrawableId != null) {
					targetImageView.setImageResource(placeHolderDrawableId)
				}
			}
		})
	}

	@JvmStatic
	fun rotateBitmap(bitmap: Bitmap, angle: Float): Bitmap {
		val matrix = Matrix().apply { postRotate(angle) }

		val rotatedBitmap = Bitmap.createBitmap(
			bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
		)

		if (!bitmap.isRecycled) {
			bitmap.recycle()
		}

		return rotatedBitmap
	}

	@JvmStatic
	fun getThumbnailFromFile(
		targetFile: File,
		thumbnailUrl: String? = null,
		requiredThumbWidth: Int
	): Bitmap? {
		if (FileSystemUtility.isAudioByName(targetFile.name)) {
			extractAudioAlbumArt(targetFile)?.let { return it }
		} else if (FileSystemUtility.isImageByName(targetFile.name)) {
			getBitmapFromFile(imageFile = targetFile)?.let {
				return scaleBitmap(it, requiredThumbWidth)
			}
		} else if (targetFile.name.endsWith(".apk", true)) {
			var apkBitmap: Bitmap? = null
			getApkThumbnail(targetFile, onApkIconFound = { bmp -> apkBitmap = bmp; bmp })
			if (apkBitmap != null) return apkBitmap
		}

		val retriever = MediaMetadataRetriever()
		try {
			var originalBitmap: Bitmap? = null
			if (!thumbnailUrl.isNullOrEmpty()) {
				originalBitmap = getBitmapFromThumbnailUrl(thumbnailUrl)
			}

			if (originalBitmap == null) {
				retriever.setDataSource(targetFile.absolutePath)
				originalBitmap = retriever
					.getFrameAtTime(5_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
					?: retriever.frameAtTime
			}

			originalBitmap?.let {
				if (it.width <= 0) return null
				val aspectRatio = it.height.toFloat() / it.width
				val targetHeight = (requiredThumbWidth * aspectRatio).toInt()
				return it.scale(requiredThumbWidth, targetHeight, false)
			}
		} catch (error: Exception) {
			logger.e("Error while retrieving thumbnail from a file:", error)
		} finally {
			retriever.release()
		}

		return null
	}

	@JvmStatic
	fun getApkThumbnail(
		apkFile: File,
		imageViewHolder: ImageView? = null,
		defaultThumbDrawable: Drawable? = null,
		onApkIconFound: ((Bitmap) -> Bitmap)? = null
	): Boolean {
		val apkExtension = ".apk".lowercase(Locale.ROOT)
		if (!apkFile.exists() || !apkFile.name.endsWith(apkExtension)) {
			imageViewHolder?.apply { scaleType = ImageView.ScaleType.CENTER_INSIDE }
			return false
		}

		val packageManager = INSTANCE.packageManager
		return try {
			val apkPath = apkFile.absolutePath

			val packageInfo = packageManager.getPackageArchiveInfo(
				apkPath, PackageManager.GET_META_DATA
			)

			packageInfo?.applicationInfo?.let { appInfo ->
				appInfo.sourceDir = apkPath
				appInfo.publicSourceDir = apkPath

				val drawableIcon = appInfo.loadIcon(packageManager)

				imageViewHolder?.setImageDrawable(drawableIcon)
				imageViewHolder?.scaleType = ImageView.ScaleType.CENTER_INSIDE
				imageViewHolder?.setPadding(0, 0, 0, 0)

				val bitmap = drawableToBitmap(drawableIcon)
				if (bitmap != null) {
					onApkIconFound?.invoke(bitmap)
				}

				return true
			}

			imageViewHolder?.setImageDrawable(defaultThumbDrawable)
			false

		} catch (error: Exception) {
			logger.e("Error found while extracting app icon thumbnail from an apk file:", error)
			imageViewHolder?.apply {
				scaleType = ImageView.ScaleType.FIT_CENTER
				setPadding(0, 0, 0, 0)
				setImageDrawable(defaultThumbDrawable)
			}
			false
		}
	}

	@JvmStatic
	fun drawableToBitmap(drawable: Drawable): Bitmap? {
		if (drawable is BitmapDrawable) return drawable.bitmap

		val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 1
		val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 1

		return try {
			val bitmap = createBitmap(width, height)
			val canvas = Canvas(bitmap)

			drawable.setBounds(0, 0, canvas.width, canvas.height)
			drawable.draw(canvas)

			bitmap
		} catch (error: Exception) {
			logger.e("Error found while converting drawable to a bitmap:", error)
			null
		}
	}

	@JvmStatic
	fun scaleBitmap(targetBitmap: Bitmap, requiredThumbWidth: Int): Bitmap {
		if (requiredThumbWidth <= 0 || targetBitmap.width <= 0) return targetBitmap

		val aspectRatio = targetBitmap.height.toFloat() / targetBitmap.width
		val targetHeight = (requiredThumbWidth * aspectRatio).toInt()

		if (targetBitmap.width == requiredThumbWidth && targetBitmap.height == targetHeight) {
			return targetBitmap
		}

		return targetBitmap.scale(requiredThumbWidth, targetHeight, false)
	}

	@JvmStatic
	fun extractAudioAlbumArt(audioFile: File): Bitmap? {
		if (!audioFile.exists()) return null

		val retriever = MediaMetadataRetriever()
		return try {
			retriever.setDataSource(audioFile.absolutePath)
			val embeddedPicture = retriever.embeddedPicture ?: return null

			val optionsBounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
			decodeByteArray(embeddedPicture, 0, embeddedPicture.size, optionsBounds)

			val maxSize = 412
			val scale = maxOf(1, maxOf(optionsBounds.outWidth, optionsBounds.outHeight) / maxSize)

			val decodeOptions = BitmapFactory.Options().apply {
				inSampleSize = scale
			}

			decodeByteArray(embeddedPicture, 0, embeddedPicture.size, decodeOptions)
		} catch (error: Exception) {
			logger.e("Error found while extracting audio album-art frm an audio file", error)
			null
		} finally {
			retriever.release()
		}
	}

	@JvmStatic
	fun getBitmapFromThumbnailUrl(thumbnailUrl: String?): Bitmap? {
		if (thumbnailUrl.isNullOrEmpty()) return null

		var connection: HttpURLConnection? = null
		var inputStream: InputStream? = null

		return try {
			val url = URL(thumbnailUrl)
			connection = url.openConnection() as? HttpURLConnection
			connection?.apply {
				connectTimeout = 5000
				readTimeout = 5000
				doInput = true
				connect()
			}

			if (connection?.responseCode == HTTP_OK &&
				connection.contentType?.contains("image", true) == true) {
				inputStream = BufferedInputStream(connection.inputStream)
				decodeStream(inputStream)
			} else null
		} catch (error: Exception) {
			logger.e("Error found while getting thumbnail from a remote url:", error)
			null
		} finally {
			try {
				inputStream?.close()
			} catch (_: IOException) {
			}
			connection?.disconnect()
		}
	}

	@JvmStatic
	fun saveBitmapToFile(
		bitmapToSave: Bitmap,
		fileName: String,
		format: CompressFormat = CompressFormat.JPEG,
		quality: Int = 60
	): String? {
		return try {
			val modePrivate = Context.MODE_PRIVATE
			val appContext = INSTANCE
			appContext.openFileOutput(fileName, modePrivate).use { outputStream ->
				if (!bitmapToSave.compress(format, quality, outputStream)) return null
			}

			"${appContext.filesDir}/$fileName"
		} catch (error: Throwable) {
			logger.e("Error found while saving bitmap to a file:", error)
			null
		}
	}

	@JvmStatic
	fun getBitmapFromFile(imageFile: File): Bitmap? {
		return try {
			if (imageFile.exists() && imageFile.isFile) {
				decodeFile(imageFile.absolutePath)
			} else {
				null
			}
		} catch (error: Exception) {
			logger.e("Error found while decoding bitmap from a image file:", error)
			null
		}
	}

	@JvmStatic
	fun isBlackThumbnail(targetImageFile: File?): Boolean {
		if (targetImageFile == null || !targetImageFile.exists()) return false

		val options = BitmapFactory.Options().apply {
			inJustDecodeBounds = true
		}
		decodeFile(targetImageFile.absolutePath, options)

		val maxSize = 64
		val scale = maxOf(1, maxOf(options.outWidth, options.outHeight) / maxSize)

		val decodeOptions = BitmapFactory.Options().apply {
			inSampleSize = scale
		}

		val bitmap = decodeFile(targetImageFile.absolutePath, decodeOptions)
			?: return false

		for (x in 0 until bitmap.width) {
			for (y in 0 until bitmap.height) {
				if (bitmap[x, y] != Color.BLACK) {
					bitmap.recycle()
					return false
				}
			}
		}

		bitmap.recycle()
		return true
	}

	@Suppress("DEPRECATION")
	fun blurBitmap(bitmap: Bitmap, radius: Float = 20f): Bitmap {
		val safeConfig = bitmap.config ?: Bitmap.Config.ARGB_8888
		val rs = RenderScript.create(INSTANCE)

		val input = Allocation.createFromBitmap(rs, bitmap)
		val output = Allocation.createTyped(rs, input.type)

		val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
		script.setRadius(radius.coerceIn(0f, 25f))
		script.setInput(input)
		script.forEach(output)

		val blurred = createBitmap(bitmap.width, bitmap.height, safeConfig)
		output.copyTo(blurred)

		rs.destroy()

		return blurred
	}

	@JvmStatic
	fun TextView.setLeftSideDrawable(drawableResIdRes: Int) {
		val drawable = getDrawable(INSTANCE, drawableResIdRes)
		drawable?.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
		this.setCompoundDrawables(drawable, null, null, null)
	}

	@JvmStatic
	fun TextView.setRightSideDrawable(
		drawableResId: Int,
		preserveExistingDrawables: Boolean = false
	) {
		val newDrawable = getDrawable(INSTANCE, drawableResId)
		newDrawable?.setBounds(0, 0, newDrawable.intrinsicWidth, newDrawable.intrinsicHeight)
		if (preserveExistingDrawables) {
			val (left, top, _, bottom) = this.compoundDrawables
			this.setCompoundDrawables(left, top, newDrawable, bottom)
		} else {
			this.setCompoundDrawables(null, null, newDrawable, null)
		}
	}

	@JvmStatic
	fun View.matchHeightToTopCutout() {
		doOnLayout { updateCutoutHeight() }
	}

	@JvmStatic
	fun View.updateCutoutHeight() {
		val rootWindowInsets = rootWindowInsets
		val cutout = rootWindowInsets?.displayCutout

		if (cutout != null) {
			val cutoutHeight = cutout.boundingRects
				.firstOrNull { it.top == 0 }
				?.height() ?: 0

			val params = layoutParams
			params.height = cutoutHeight
			layoutParams = params
		}
	}

	@JvmStatic
	fun TextView.setTextColorKT(colorResId: Int) {
		this.setTextColor(INSTANCE.getColor(colorResId))
	}

	@JvmStatic
	fun Int.dpToPx(): Int = (this * Resources.getSystem().displayMetrics.density).toInt()

	@JvmStatic
	fun changesSystemTheme(activity: BaseActivity) {
		val tempFile = File(INSTANCE.filesDir, AIO_SETTING_DARK_MODE_FILE_NAME)
		when (tempFile.exists()) {
			true -> {
				setDefaultNightMode(MODE_NIGHT_YES)
				activity.setDarkSystemBarTheme()
			}

			false -> {
				setDefaultNightMode(MODE_NIGHT_NO)
				activity.setLightSystemBarTheme()
			}
		}
	}

	@JvmStatic
	fun getCurrentOrientation(activity: Activity): String {
		return when (activity.resources.configuration.orientation) {
			Configuration.ORIENTATION_LANDSCAPE -> "landscape"
			Configuration.ORIENTATION_PORTRAIT -> "portrait"
			else -> "undefined"
		}
	}

	@JvmStatic
	fun shrinkTextToFitView(textView: TextView?, text: String, endMatch: String) {
		if (textView == null) return

		val availableWidth = textView.width - textView.paddingStart - textView.paddingEnd
		logger.d("Fit text: \"$text\" endMatch=\"$endMatch\"")

		if (availableWidth <= 0) {
			textView.doOnLayout { shrinkTextToFitView(textView, text, endMatch) }
			return
		}

		var newText = text
		val paint = Paint(textView.paint)

		try {
			if (newText.endsWith(endMatch, ignoreCase = true)) {
				logger.d("Trimming text end \"$endMatch\" if needed")
				while (paint.measureText(newText) > availableWidth && newText.length > 4) {
					newText = if (newText.endsWith(endMatch, ignoreCase = true)) {
						newText.dropLast(endMatch.length)
					} else {
						newText.dropLast(1)
					}
				}
				logger.d("Trimmed text: \"$newText\"")
			}
			textView.text = newText
		} catch (error: Exception) {
			logger.e("ShrinkText : Font measurement failed for text=$text", error)
			textView.text = text
		}
	}
}