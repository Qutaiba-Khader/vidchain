package app.core.engines.supabase

import android.text.method.*
import android.view.*
import android.widget.*
import app.core.bases.*
import com.aio.*
import lib.device.*
import lib.process.*
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.ui.*
import lib.ui.builders.*
import java.lang.ref.*

/**
 * Handles the user account registration process using Supabase.
 *
 * This class encapsulates the logic and UI for registering a new user. It displays a
 * registration form dialog where the user can input their credentials (username, email,
 * phone number, and password). Upon submission, it attempts to register the account
 * with the Supabase backend.
 *
 * Callbacks are provided to handle the success or failure of the registration attempt.
 *
 * @param baseActivity The activity context used to display UI elements, such as the registration dialog.
 * @param onAccountSuccessfullyRegistered A lambda function to be executed when the user account is created successfully.
 * @param onAccountRegistrationFailed A lambda function to be executed if the registration process fails.
 */
class SupabaseUserAccRegistration(
	val baseActivity: BaseActivity,
	val onAccountSuccessfullyRegistered: () -> Unit = {},
	val onAccountRegistrationFailed: () -> Unit = {}
) {
	
	/**
	 * Logger for the [SupabaseUserAccRegistration] class.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * A [WeakReference] to the [baseActivity].
	 * This is used to avoid memory leaks by allowing the `BaseActivity`
	 * to be garbage-collected if it's no longer in use, while still
	 * providing a safe way to access it when available.
	 */
	private val weakReferenceOfBaseActivity = WeakReference(baseActivity)
	
	/**
	 * Provides a safe, nullable access to the `baseActivity` by retrieving it from a [WeakReference].
	 * This prevents memory leaks by allowing the `baseActivity` to be garbage-collected
	 * if it's no longer strongly referenced elsewhere.
	 *
	 * @return The [BaseActivity] instance if it's still available, or `null` otherwise.
	 */
	private val safeBaseActivity get() = weakReferenceOfBaseActivity.get()
	
	/**
	 * A lazy-initialized [DialogBuilder] instance for creating and managing the user registration dialog.
	 * It is tied to the lifecycle of the `safeBaseActivity`.
	 */
	private val dialogBuilder by lazy { DialogBuilder(safeBaseActivity) }
	
	/**
	 * The EditText field for the user to input their desired username.
	 * This is part of the user registration form dialog. It is initialized
	 * from the dialog's layout.
	 */
	private var editTextUsername: EditText? = null
	
	/**
	 * The EditText field for the user's email address in the registration form.
	 * It is initialized from the dialog's layout and is used to capture the
	 * user's primary contact email.
	 */
	private var editTextEmail: EditText? = null
	
	/**
	 * EditText for the user's phone number input. This field is part of the
	 * registration form and is initialized from the dialog's layout.
	 */
	private var editTextPhoneNumber: EditText? = null
	
	/**
	 * EditText for the user to input their password. This field is part of the
	 * registration form and is initialized from the dialog's layout. Its input
	 * type can be toggled between password and visible text.
	 */
	private var editTextPassword: EditText? = null
	
	/**
	 * The container view for the username EditText.
	 * It is used to set a click listener that focuses the `editTextUsername` field.
	 */
	private var containerEditTextUsername: View? = null
	
	/**
	 * The container view for the email EditText.
	 * It is used to set a click listener that focuses the `editTextEmail` field.
	 */
	private var containerEditTextEmail: View? = null
	
	/**
	 * The container view for the phone number EditText.
	 * It is used to set a click listener that focuses the `editTextPhoneNumber` field.
	 */
	private var containerEditTextPhoneNumber: View? = null
	
	/**
	 * The container view for the password EditText.
	 * It is used to set a click listener that focuses the `editTextPassword` field.
	 */
	private var containerEditTextPassword: View? = null
	
	/**
	 * The ImageView that acts as a button to toggle the visibility of the password in the `editTextPassword` field.
	 * Its icon changes to reflect the current visibility state (e.g., "eye open" vs. "eye closed").
	 */
	private var btnTogglePasswordVisibility: ImageView? = null
	
	/**
	 * The view that acts as the "Register" button.
	 * A click listener is set on this view to initiate the account registration process.
	 */
	private var btnRegisterAccount: View? = null
	
	/**
	 * Tracks the current visibility state of the password field.
	 *
	 * This boolean flag is used to determine whether the password text should be
	 * displayed as plain text or masked with dots. It is toggled by the
	 * `btnTogglePasswordVisibility` button.
	 *
	 * @see btnTogglePasswordVisibility
	 * @see editTextPassword
	 */
	private var isPasswordVisible = false
	
	init {
		dialogBuilder.setView(R.layout.dialog_user_email_registration_1)
		dialogBuilder.setCancelable(true)
		dialogBuilder.view.apply {
			editTextUsername = findViewById(R.id.edit_field_name)
			editTextEmail = findViewById(R.id.edit_field_email)
			editTextPhoneNumber = findViewById(R.id.edit_field_phone_number)
			editTextPassword = findViewById(R.id.edit_field_password)
			containerEditTextUsername = findViewById(R.id.container_edit_field_name)
			containerEditTextEmail = findViewById(R.id.container_edit_field_email)
			containerEditTextPhoneNumber = findViewById(R.id.container_edit_field_phone_number)
			containerEditTextPassword = findViewById(R.id.container_edit_field_password)
			btnTogglePasswordVisibility = findViewById(R.id.btn_toggle_password_visibility)
			btnRegisterAccount = findViewById(R.id.btn_register_account)
			
			containerEditTextPassword?.setOnClickListener { focusKeyboardOnPasswordField() }
			containerEditTextUsername?.setOnClickListener { focusKeyboardOnUsernameField() }
			containerEditTextEmail?.setOnClickListener { focusKeyboardOnEmailField() }
			
			setupPasswordVisibilityToggle()
			
			if (isUserFromIndia()) {
				setIndiaRegistrationEnabled()
			}
			
		}
	}
	
	/**
	 * Displays the user registration dialog.
	 *
	 * This method checks if the registration dialog, managed by `dialogBuilder`, is already
	 * visible. If it is not showing, it calls the `show()` method on the `dialogBuilder`
	 * to present the form to the user.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			dialogBuilder.show()
			CommonTimeUtils.delay(500, object : OnTaskFinishListener {
				override fun afterDelay() {
					focusKeyboardOnUsernameField()
				}
			})
		}
	}
	
	/**
	 * Closes and dismisses the registration dialog.
	 *
	 * This function checks if the dialog is currently being shown. If it is,
	 * the `close()` method of the `dialogBuilder` is called to dismiss it from the screen.
	 * This is a safe way to ensure the dialog is only closed when it's active.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			dialogBuilder.close()
		}
	}
	
	/**
	 * Checks if the user's current country, as determined by their device settings, is India.
	 *
	 * This function retrieves the country code from the device's telephony manager. It then
	 * compares this code against a predefined list of country codes associated with India ("in", "ind").
	 *
	 * This check is useful for enabling or disabling features specific to users in India,
	 * such as region-specific registration requirements or content.
	 *
	 * @return `true` if the device's country code matches any of the codes for India, `false` otherwise.
	 */
	private fun isUserFromIndia(): Boolean {
		return DeviceUtility.isUserFromIndia(safeBaseActivity).apply {
			logger.d("User are from ${if (this) "India" else "non-India"}")
		}
	}
	
	private fun setIndiaRegistrationEnabled() {
		containerEditTextEmail?.visibility = View.GONE
		containerEditTextPassword?.visibility = View.GONE
	}
	
	/**
	 * Configures the click listener for the password visibility toggle button.
	 *
	 * When the `btnTogglePasswordVisibility` button is clicked, this function toggles
	 * the `isPasswordVisible` state. It then updates the `editTextPassword` field to
	 * either show the password in plain text or mask it using `PasswordTransformationMethod`.
	 * The icon of the toggle button is also updated to reflect the current state
	 * (e.g., an "open eye" for visible, "closed eye" for hidden).
	 *
	 * To maintain a good user experience, it preserves the cursor's position within
	 * the `EditText` and re-requests focus on the field after the change.
	 */
	private fun setupPasswordVisibilityToggle() {
		// Set click listener for password visibility toggle button
		btnTogglePasswordVisibility?.setOnClickListener {
			// Toggle the password visibility state
			isPasswordVisible = !isPasswordVisible
			
			// Get reference to password edit text, return if null
			val editText = editTextPassword ?: return@setOnClickListener
			
			// Save current cursor position to restore later
			val selection = editText.selectionEnd
			
			// Show password (plain text)
			if (isPasswordVisible) {
				editText.transformationMethod = null  // Remove password masking
				btnTogglePasswordVisibility?.setImageResource(R.drawable.ic_button_unhide_eye)  // Show "eye open" icon
			}
			// Hide password (masked with dots)
			else {
				editText.transformationMethod = PasswordTransformationMethod.getInstance()  // Apply password masking
				btnTogglePasswordVisibility?.setImageResource(R.drawable.ic_button_hide_eye)  // Show "eye closed" icon
			}
			
			// Restore cursor position
			editText.setSelection(selection)
			
			// Keep focus on password field for better UX
			editText.requestFocus()
		}
	}
	
	/**
	 * Sets focus to the email input field and displays the on-screen keyboard.
	 *
	 * This utility function makes the email field focusable, selects any existing
	 * text within it for easy replacement, and then programmatically shows the
	 * keyboard. This improves user experience by guiding them to the correct input
	 * field, especially after a validation error.
	 */
	private fun focusKeyboardOnEmailField() {
		editTextEmail?.focusable
		editTextEmail?.selectAll()
		ViewUtility.showOnScreenKeyboard(safeBaseActivity, editTextEmail)
	}
	
	/**
	 * Sets focus to the username input field, selects its content, and shows the on-screen keyboard.
	 *
	 * This helper function streamlines the process of directing the user's attention
	 * to the username field. It makes the `editTextUsername` field focusable, highlights
	 * any existing text for quick editing or replacement, and then programmatically
	 * triggers the keyboard to appear, enhancing usability, especially for error correction.
	 */
	private fun focusKeyboardOnUsernameField() {
		editTextUsername?.focusable
		editTextUsername?.selectAll()
		ViewUtility.showOnScreenKeyboard(safeBaseActivity, editTextUsername)
	}
	
	/**
	 * Sets focus to the password input field and displays the on-screen keyboard.
	 *
	 * This utility function makes the password field focusable, selects any existing
	 * text within it for easy replacement, and then programmatically shows the
	 * keyboard, improving the user experience by guiding them to the correct input field.
	 */
	private fun focusKeyboardOnPasswordField() {
		editTextPassword?.focusable
		editTextPassword?.selectAll()
		ViewUtility.showOnScreenKeyboard(safeBaseActivity, editTextPassword)
	}
	
	/**
	 * Shows the soft keyboard and requests focus for the phone number input field.
	 * This is typically used to guide the user to the next logical input step
	 * after a preceding action, such as selecting their country code.
	 */
	private fun focusKeyboardOnPhoneNumberField() {
		editTextPhoneNumber?.focusable
		editTextPhoneNumber?.selectAll()
		ViewUtility.showOnScreenKeyboard(safeBaseActivity, editTextPhoneNumber)
	}
	
}