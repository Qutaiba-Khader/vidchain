package lib.ui.builders;

import static android.view.LayoutInflater.from;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static lib.ui.ViewUtility.hideOnScreenKeyboard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.aio.R;

import java.lang.ref.WeakReference;

import app.core.bases.interfaces.BaseActivityInf;
import lib.process.LogHelperUtils;

/**
 * A comprehensive builder class for creating and managing highly customizable,
 * memory-leak-safe dialogs throughout the application.
 * <p>
 * This class provides a robust wrapper around Android's native AlertDialog system,
 * addressing common pain points like memory leaks, lifecycle management, and
 * consistent styling. It implements the builder pattern for fluent API design
 * while ensuring safe usage across configuration changes and activity destruction.
 * <p>
 * Key Features:
 * - Memory-leak prevention through WeakReference usage
 * - Lifecycle-aware dialog display and dismissal
 * - Consistent Material Design styling and animations
 * - Bottom-sheet style positioning with slide animations
 * - Custom view inflation and management
 * - Automatic cleanup and resource management
 * - Type-safe view referencing and click handling
 * <p>
 * Usage Pattern:
 * ```java
 * new DialogBuilder(activity)
 * .setView(R.layout.dialog_custom)
 * .setOnClickForPositiveButton(v -> handleAction())
 * .setCancelable(true)
 * .show();
 * ```
 */
public class DialogBuilder {

	/**
	 * Logger instance for tracking dialog lifecycle events, user interactions,
	 * and potential errors during dialog creation and management. Provides
	 * detailed diagnostics for debugging dialog-related issues.
	 */
	private final LogHelperUtils logger = LogHelperUtils.from(getClass());

	/**
	 * Weak reference to the activity interface ensuring the dialog doesn't prevent
	 * garbage collection if the activity is destroyed. This is crucial for preventing
	 * memory leaks when dialogs are referenced during configuration changes or
	 * when activities are destroyed while dialogs are still showing.
	 */
	private final WeakReference<BaseActivityInf> weakReferenceBaseActivity;

	/**
	 * Weak reference to the custom view displayed within the dialog, allowing
	 * proper cleanup and preventing view leaks. The view is automatically
	 * cleared when the dialog is closed or when the activity is destroyed.
	 */
	private WeakReference<View> weakCustomView;

	/**
	 * The underlying AlertDialog instance managed by this builder. This reference
	 * is carefully managed to ensure proper lifecycle handling and prevent
	 * WindowManager leaks that can occur with improperly dismissed dialogs.
	 */
	private AlertDialog dialog;

	/**
	 * Constructs a new DialogBuilder with lifecycle-safe activity reference.
	 *
	 * @param baseActivityInf The activity context that will host the dialog,
	 *                        wrapped in a weak reference to prevent memory leaks.
	 *                        If null, dialog operations will fail gracefully.
	 */
	public DialogBuilder(@Nullable BaseActivityInf baseActivityInf) {
		this.weakReferenceBaseActivity = new WeakReference<>(baseActivityInf);
	}

	/**
	 * Displays the dialog with comprehensive lifecycle safety checks and error handling.
	 * <p>
	 * This method performs multiple safety checks before showing the dialog:
	 * - Validates activity existence and non-destruction state
	 * - Ensures dialog is not already showing to prevent duplicates
	 * - Handles edge cases like rapid successive calls
	 * - Provides graceful failure with detailed logging
	 * <p>
	 * The dialog is automatically created if it hasn't been initialized yet,
	 * and all window parameters are applied before display.
	 */
	public void show() {
		try {
			if (dialog == null) dialog = create();
			BaseActivityInf activityInf = weakReferenceBaseActivity.get();
			if (activityInf == null || activityInf.getActivity() == null) return;

			Activity activity = activityInf.getActivity();
			if (!activity.isFinishing() && !activity.isDestroyed() && !dialog.isShowing()) {
				dialog.show();
			}
		} catch (Exception error) {
			logger.d("Dialog show failed: " + error.getMessage());
		}
	}

	/**
	 * Retrieves the managed AlertDialog instance with state validation.
	 *
	 * @return The currently managed AlertDialog instance
	 * @throws IllegalStateException if the dialog hasn't been initialized via show()
	 */
	@NonNull
	public AlertDialog getDialog() {
		if (dialog == null) {
			throw new IllegalStateException("Dialog not initialized. Call show() first.");
		}
		return this.dialog;
	}

	/**
	 * Attaches a click listener to any view within the dialog's custom layout.
	 * <p>
	 * This method provides type-safe access to dialog subviews while handling
	 * potential null references gracefully. The listener is attached directly
	 * to the view found by resource ID.
	 *
	 * @param viewId   The resource ID of the view to attach the listener to
	 * @param listener The click listener implementation
	 */
	public void setOnClickListener(int viewId, @NonNull OnClickListener listener) {
		if (weakCustomView.get() == null) return;
		View view = weakCustomView.get().findViewById(viewId);
		if (view != null) {
			view.setOnClickListener(listener);
		}
	}

	/**
	 * Applies a slide-up entrance animation to the dialog for bottom-positioned dialogs.
	 * <p>
	 * This animation style is particularly effective for bottom-sheet style dialogs
	 * and creates a smooth, modern user experience. The animation is defined in
	 * the style resources and can be customized globally.
	 */
	public void enableSlideUpAnimation() {
		setDialogAnimation(R.style.style_dialog_animation);
	}

	/**
	 * Configures the dialog to appear at the bottom of the screen with full width.
	 * <p>
	 * This method creates a bottom-sheet style dialog that slides up from the bottom
	 * of the screen. It sets appropriate gravity, transparent background, and layout
	 * parameters to achieve the modern bottom-sheet appearance commonly used in
	 * Material Design applications.
	 */
	public void enableBottomPosition() {
		if (dialog.getWindow() != null) {
			dialog.getWindow().setGravity(Gravity.BOTTOM);
			LayoutParams params = dialog.getWindow().getAttributes();
			params.y = 0;
			dialog.getWindow().setAttributes(params);
			dialog.getWindow().setBackgroundDrawableResource(R.color.transparent);
			dialog.getWindow().setLayout(MATCH_PARENT, WRAP_CONTENT);
		}
	}

	/**
	 * Controls whether the dialog can be dismissed by user interaction.
	 *
	 * @param cancelable True to allow dismissal by back press or outside tap,
	 *                   false to create a persistent dialog that must be
	 *                   explicitly dismissed programmatically
	 */
	public void setCancelable(boolean cancelable) {
		if (dialog == null) create();
		dialog.setCancelable(cancelable);
		dialog.setCanceledOnTouchOutside(cancelable);
	}

	/**
	 * Checks the current visibility state of the dialog.
	 *
	 * @return True if the dialog is currently showing to the user,
	 * false if it's not initialized, not showing, or already dismissed
	 */
	public boolean isShowing() {
		return dialog != null && dialog.isShowing();
	}

	/**
	 * Safely closes and cleans up the dialog with comprehensive resource management.
	 * <p>
	 * This method performs complete cleanup to prevent memory leaks:
	 * - Removes all listeners to break reference cycles
	 * - Properly dismisses the dialog if showing
	 * - Clears all click listeners from the view hierarchy
	 * - Nullifies references to allow garbage collection
	 * - Handles exceptions gracefully to prevent crashes
	 */
	public void close() {
		try {
			if (dialog != null) {
				dialog.setOnDismissListener(null);
				dialog.setOnCancelListener(null);
				if (dialog.isShowing()) dialog.dismiss();
				dialog = null;
			}
			View view = weakCustomView != null ? weakCustomView.get() : null;
			if (view != null) clearAllClickListeners(view);
			weakCustomView = null;
		} catch (Exception error) {
			logger.e("Dialog close failed: " + error.getMessage());
		}
	}

	/**
	 * Retrieves the custom view set for this dialog with state validation.
	 *
	 * @return The root custom view of the dialog
	 * @throws IllegalStateException if no view has been set or the view was cleared
	 */
	@NonNull
	public View getView() {
		View view = weakCustomView != null ? weakCustomView.get() : null;
		if (view == null) throw new IllegalStateException("No view set or view released.");
		return view;
	}

	/**
	 * Sets a layout resource as the dialog's content by inflating it.
	 *
	 * @param layoutResId The layout resource ID to inflate
	 * @return This DialogBuilder instance for method chaining
	 */
	@NonNull
	public DialogBuilder setView(int layoutResId) {
		Activity activity = getActivity();
		if (activity == null) return this;
		View view = from(activity).inflate(layoutResId, null);
		this.weakCustomView = new WeakReference<>(view);
		return this;
	}

	/**
	 * Sets a pre-inflated view as the dialog's content.
	 *
	 * @param view The view to use as dialog content
	 * @return This DialogBuilder instance for method chaining
	 */
	@NonNull
	public DialogBuilder setView(@NonNull View view) {
		this.weakCustomView = new WeakReference<>(view);
		return this;
	}

	/**
	 * Retrieves the positive button container view from the dialog layout.
	 *
	 * @return The view containing the positive action button
	 */
	@NonNull
	public View getPositiveButtonView() {
		return getView().findViewById(R.id.btn_dialog_positive_container);
	}

	/**
	 * Attaches a click listener to the positive button using weak reference.
	 *
	 * @param listener The listener to execute when positive button is clicked
	 * @return This DialogBuilder instance for method chaining
	 */
	@NonNull
	public DialogBuilder setOnClickForPositiveButton(@NonNull OnClickListener listener) {
		WeakReference<OnClickListener> weakReference = new WeakReference<>(listener);
		if (weakReference.get() != null) {
			getPositiveButtonView().setOnClickListener(weakReference.get());
		}
		return this;
	}

	/**
	 * Retrieves the negative button container view from the dialog layout.
	 *
	 * @return The view containing the negative/cancel action button
	 */
	@NonNull
	public View getNegativeButtonView() {
		return getView().findViewById(R.id.button_dialog_negative_container);
	}

	/**
	 * Attaches a click listener to the negative button using weak reference.
	 *
	 * @param listener The listener to execute when negative button is clicked
	 * @return This DialogBuilder instance for method chaining
	 */
	@NonNull
	public DialogBuilder setOnClickForNegativeButton(@NonNull OnClickListener listener) {
		WeakReference<OnClickListener> weakReference = new WeakReference<>(listener);
		if (weakReference.get() != null) {
			getNegativeButtonView().setOnClickListener(weakReference.get());
		}
		return this;
	}

	/**
	 * Safely retrieves the associated activity from the weak reference.
	 *
	 * @return The activity instance or null if the activity was garbage collected
	 */
	@Nullable
	public Activity getActivity() {
		BaseActivityInf inf = weakReferenceBaseActivity.get();
		return inf != null ? inf.getActivity() : null;
	}

	/**
	 * Hides the on-screen keyboard from specified focused views.
	 * <p>
	 * This is particularly useful when closing dialogs that contain input fields
	 * to ensure the keyboard doesn't persist after dialog dismissal.
	 *
	 * @param focusedView One or more views that may have input focus
	 */
	public void hideKeyboard(@NonNull View... focusedView) {
		Activity act = getActivity();
		if (act == null) return;
		for (View view : focusedView) hideOnScreenKeyboard(act, view);
	}

	/**
	 * Internally creates the AlertDialog instance with proper configuration.
	 * <p>
	 * This method applies all the configured settings:
	 * - Custom view from weak reference
	 * - Material Design style theme
	 * - Bottom positioning and animations
	 * - Auto-dismissal cleanup
	 *
	 * @return The created AlertDialog or null if creation fails
	 */
	private AlertDialog create() {
		Activity activity = getActivity();
		if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;

		Builder builder = new Builder(activity, R.style.style_dialog);
		View view = weakCustomView != null ? weakCustomView.get() : null;
		if (view == null) return null;

		builder.setView(view);
		dialog = builder.create();
		dialog.setOnDismissListener(d -> close());
		applyBottomPositioning();
		return dialog;
	}

	/**
	 * Applies both bottom positioning and slide animation to the dialog.
	 */
	private void applyBottomPositioning() {
		enableBottomPosition();
		enableSlideUpAnimation();
	}

	/**
	 * Applies a window animation style to the dialog.
	 *
	 * @param animationResId The animation style resource ID
	 */
	private void setDialogAnimation(int animationResId) {
		if (dialog != null && dialog.getWindow() != null) {
			dialog.getWindow().getAttributes().windowAnimations = animationResId;
		}
	}

	/**
	 * Recursively clears all click listeners from a view hierarchy.
	 * <p>
	 * This prevents memory leaks by breaking reference cycles between
	 * views and their listeners during dialog cleanup.
	 *
	 * @param view The root view to clear listeners from
	 */
	private void clearAllClickListeners(@NonNull View view) {
		view.setOnClickListener(null);
		if (view instanceof ViewGroup group) {
			for (int i = 0; i < group.getChildCount(); i++) {
				clearAllClickListeners(group.getChildAt(i));
			}
		}
	}

	/**
	 * Listener interface for receiving dialog cancellation events.
	 * <p>
	 * This extends the native Android OnCancelListener to provide
	 * consistent typing within the DialogBuilder ecosystem.
	 */
	public interface OnCancelListener extends DialogInterface.OnCancelListener {
		@Override
		void onCancel(DialogInterface dialog);
	}
}