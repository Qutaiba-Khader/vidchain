package app.ui.others.startup

import app.core.bases.BaseActivity
import java.lang.ref.WeakReference

/**
 * EmptyLayoutActivity
 *
 * A minimal/no-UI activity that:
 * - Renders no layout.
 * - Provides a safe weak reference to itself.
 * - Handles back press with double-exit logic.
 * - Cleans up references on destruction to prevent leaks.
 */
class EmptyLayoutActivity : BaseActivity() {

	/** Weak self-reference to prevent memory leaks */
	val safeSelfReference = WeakReference(this)

	/** Safe resolved reference from [safeSelfReference] */
	private val safeEmptyLayoutActivityRef = safeSelfReference.get()

	/**
	 * Specifies which layout to render.
	 * Returns -1 meaning no layout is rendered.
	 */
	override fun onRenderingLayout(): Int {
		return -1
	}

	/**
	 * Called after layout render.
	 * No UI logic needed for this activity.
	 */
	override fun onAfterLayoutRender() {}

	/**
	 * Handles back button press.
	 * Exits only if back is pressed twice quickly.
	 */
	override fun onBackPressActivity() {
		exitActivityOnDoubleBackPress()
	}

	/**
	 * Cleans up references on activity destruction.
	 * Ensures weak references are cleared before GC.
	 */
	override fun onDestroy() {
		safeSelfReference.clear()
		clearWeakActivityReference()
		super.onDestroy()
	}
}
