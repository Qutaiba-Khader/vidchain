package lib.ui

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aio.R

/**
 * A custom Android View that displays a vertical progress bar.
 * The progress fills from bottom to top.
 *
 * This view can be used in layouts to show progress in a vertical orientation,
 * such as for volume controls, level indicators, or timers.
 *
 * @constructor Creates an instance of VerticalProgressBar.
 * @param context The Context the view is running in, through which it can access the current theme, resources, etc.
 * @param attrs The attributes of the XML tag that is inflating the view. May be null.
 * @param defStyleAttr An attribute in the current theme that contains a reference to a style resource
 *                     that supplies default values for the view. Can be 0 to not look for defaults.
 */
class VerticalProgressBar @JvmOverloads constructor(
	context: Context,
	attrs: AttributeSet? = null,
	defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

	private var progress = 0     /** Current progress value. Should be between 0 and [max]. */
	private var max = 100        /** Maximum progress value. Defaults to 100. */

	/** Paint object used for drawing the background of the progress bar. */
	private val backgroundPaint = Paint().apply {
		color = ContextCompat.getColor(context, R.color.color_primary_variant)
		style = Paint.Style.FILL
		isAntiAlias = true
	}

	/** Paint object used for drawing the progress fill of the progress bar. */
	private val progressPaint = Paint().apply {
		color = ContextCompat.getColor(context, R.color.color_secondary)
		style = Paint.Style.FILL
		isAntiAlias = true
	}

	/**
	 * Sets the current progress value.
	 * The value will be clamped between 0 and the current maximum value.
	 * Calling this method will trigger a redraw of the view.
	 *
	 * @param value The new progress value.
	 */
	fun setProgress(value: Int) {
		progress = value.coerceIn(0, max)
		invalidate()
	}

	/**
	 * Sets the maximum progress value.
	 * The default is 100.
	 * Calling this method will trigger a redraw of the view.
	 *
	 * @param value The new maximum value.
	 */
	fun setMax(value: Int) {
		max = value
		invalidate()
	}

	/**
	 * Measures the view and its content to determine the measured width and height.
	 * This implementation sets a default desired size and resolves it against the
	 * provided measure specifications.
	 *
	 * @param widthMeasureSpec Horizontal space requirements as imposed by the parent.
	 * @param heightMeasureSpec Vertical space requirements as imposed by the parent.
	 */
	override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
		val desiredWidth = dpToPx(10)
		val desiredHeight = dpToPx(200)
		val width = resolveSize(desiredWidth, widthMeasureSpec)
		val height = resolveSize(desiredHeight, heightMeasureSpec)
		setMeasuredDimension(width, height)
	}

	/**
	 * Renders the view on the provided canvas.
	 * This method first draws the background bar and then draws the progress bar
	 * on top of it, from the bottom upwards.
	 *
	 * @param canvas The canvas on which the background will be drawn.
	 */
	override fun onDraw(canvas: Canvas) {
		super.onDraw(canvas)

		// Draw the background rounded rectangle.
		canvas.drawRoundRect(
			0f, 0f, width.toFloat(), height.toFloat(),
			dpToPx(10).toFloat(), dpToPx(10).toFloat(),
			backgroundPaint
		)

		// Draw the progress fill from bottom to top
		val progressHeight = if (max > 0) (height * progress / max.toFloat()) else 0f
		canvas.drawRoundRect(
			0f,
			height - progressHeight,   // The top Y coordinate of the progress rectangle.
			width.toFloat(),
			height.toFloat(),
			dpToPx(10).toFloat(),
			dpToPx(10).toFloat(),
			progressPaint
		)
	}

	/**
	 * Helper function to convert a dimension from density-independent pixels (dp) to pixels (px).
	 *
	 * @param dp The value in dp.
	 * @return The converted value in pixels.
	 */
	private fun dpToPx(dp: Int): Int {
		return (dp * Resources.getSystem().displayMetrics.density).toInt()
	}
}