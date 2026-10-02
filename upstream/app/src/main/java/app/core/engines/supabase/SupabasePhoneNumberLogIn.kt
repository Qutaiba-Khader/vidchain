package app.core.engines.supabase

import android.view.*
import android.widget.*
import app.core.*
import app.core.AIOTimer.*
import app.core.bases.*
import app.core.engines.supabase.SupabaseCloudServer.supabaseClient
import app.core.engines.user_profile.*
import com.aio.*
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.providers.builtin.*
import kotlinx.coroutines.*
import lib.device.DeviceUtility.normalizeIndianNumber
import lib.process.*
import lib.process.CommonTimeUtils.OnTaskFinishListener
import lib.texts.CommonTextUtils.getText
import lib.ui.*
import lib.ui.builders.*
import lib.ui.builders.ToastView.Companion.showToast
import java.lang.ref.*

/**
 * Manages the user authentication process via phone number and OTP (One-Time Password) using Supabase.
 *
 * This class encapsulates the entire UI and logic for a phone number-based login/registration flow.
 * It presents a dialog where users can enter their phone number to receive an OTP. After receiving the OTP,
 * they can enter it in the same dialog to verify their identity and log in.
 *
 * The class handles:
 * - Displaying and managing the login dialog.
 * - Sending an OTP to the user's phone number via Supabase Auth.
 * - Verifying the OTP entered by the user.
 * - A countdown timer for resending the OTP.
 * - UI state changes (e.g., showing/hiding input fields, updating button text).
 * - Callbacks for successful or failed registration attempts.
 *
 * It uses a `WeakReference` to the `BaseActivity` to prevent memory leaks and implements `AIOTimerListener`
 * to manage the OTP resend countdown.
 *
 * @param baseActivity The activity context required for creating dialogs, accessing resources, and running coroutines.
 * @param onAccountSuccessfullyRegistered A lambda function to be executed upon successful user login/registration.
 * @param onAccountRegistrationFailed A lambda function to be executed if the registration process fails.
 */
class SupabasePhoneNumberLogIn(
	private val baseActivity: BaseActivity,
	private val onAccountSuccessfullyRegistered: () -> Unit = {},
	private val onAccountRegistrationFailed: () -> Unit = {}
) : AIOTimerListener {
	
	/**
	 * Logger instance for this class. Used for logging debug, error, and informational messages
	 * to help with development and troubleshooting. The logger is initialized using `LogHelperUtils`
	 * and is tagged with the simple name of this class (`SupabasePhoneNumberLogIn`).
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * A weak reference to the `BaseActivity` instance.
	 *
	 * This is used to prevent memory leaks by allowing the `BaseActivity` to be garbage collected
	 * even if this `SupabasePhoneNumberLogIn` instance is still held in memory.
	 * Operations that require a context or activity reference should use [safeBaseActivity]
	 * to safely access the activity, which will be `null` if the activity has been destroyed.
	 */
	private val weakReferenceOfBaseActivity = WeakReference(baseActivity)
	
	/**
	 * A property to safely access the `BaseActivity` instance from the `WeakReference`.
	 * This helps prevent memory leaks by not holding a strong reference to the activity,
	 * allowing it to be garbage collected if it's destroyed. It returns a nullable
	 * `BaseActivity?`, so checks are needed before use.
	 */
	private val safeBaseActivity get() = weakReferenceOfBaseActivity.get()
	
	/**
	 * Lazily initialized [DialogBuilder] for creating and managing the phone number login dialog.
	 * This ensures the dialog is only created when it's first needed.
	 */
	private val dialogBuilder by lazy { DialogBuilder(safeBaseActivity) }
	
	/**
	 * The [EditText] view for entering the user's phone number.
	 * This field is part of the phone registration dialog.
	 */
	private var editTextPhoneNumber: EditText? = null
	
	/**
	 * The EditText field for the user to input the One-Time Password (OTP).
	 * This field is initially hidden and becomes visible only after an OTP has been sent
	 * to the user's phone number.
	 */
	private var editTextPassword: EditText? = null
	
	/**
	 * The container view for the phone number input field.
	 * This view acts as a clickable area to focus on the `editTextPhoneNumber` field.
	 */
	private var containerEditTextPhoneNumber: View? = null
	
	/**
	 * The container view for the password/OTP (One-Time Password) input field.
	 * This view acts as a clickable area to focus on the password field and manage its visibility.
	 */
	private var containerEditTextPassword: View? = null
	
	/**
	 * The button responsible for handling the account registration process.
	 * Its behavior changes based on whether an OTP has been sent.
	 * - Initially, it sends a verification code (OTP) to the user's phone number.
	 * - After an OTP is sent, it triggers the OTP verification process.
	 */
	private var btnRegisterAccount: View? = null
	
	/**
	 * The [TextView] inside the registration button. Its text changes based on the current step
	 * (e.g., "Send Verification Code", "Verify & Login", "Resend Verification Code").
	 */
	private var btnTextRegisterAccount: TextView? = null
	
	/**
	 * TextView that displays the remaining time before the user can request another OTP (One-Time Password).
	 * It shows a countdown timer, and its visibility is managed based on whether an OTP has been sent.
	 */
	private var txtResendOptTimerCount: TextView? = null
	
	/**
	 * A flag to track the state of the One-Time Password (OTP) process.
	 *
	 * This boolean is `true` if an OTP has been successfully sent to the user's phone number,
	 * and `false` otherwise. It helps the UI and logic to differentiate between two states:
	 * 1.  When `false`: The user needs to enter their phone number to receive an OTP.
	 * 2.  When `true`: The user needs to enter the OTP they received to verify their identity.
	 */
	private var hasOptBeenSent = false
	
	/**
	 * The total duration in seconds for the OTP (One-Time Password) countdown timer.
	 * This determines how long the user must wait before they can request a new OTP.
	 * Currently set to 3 minutes (3 * 60 seconds).
	 */
	private val totalSeconds = 3 * 60
	
	/**
	 * Tracks the last second value displayed in the OTP timer countdown.
	 * This is used to prevent redundant UI updates on every timer tick,
	 * ensuring the timer text is only updated once per second.
	 */
	private var lastShownSecond = -1
	
	/**
	 * The timestamp in milliseconds when the OTP (One-Time Password) was sent.
	 * This is used as the starting point for the resend OTP countdown timer.
	 * A value of 0L indicates that the timer is not active.
	 */
	private var otpStartTimeMillis = 0L
	
	/**
	 * Initializes the dialog's view components and sets up the initial UI state and listeners.
	 *
	 * This function is called to prepare the dialog for display. It performs the following actions:
	 * 1.  Inflates the dialog's layout from `R.layout.dialog_user_phone_registration_1`.
	 * 2.  Finds and assigns all necessary UI views (e.g., `EditText`, `Button`, `TextView`) to their
	 *     corresponding class properties.
	 * 3.  Sets the initial visibility of UI elements:
	 *     - Hides the password/OTP input field (`containerEditTextPassword`).
	 *     - Hides the OTP resend timer text (`txtResendOptTimerCount`).
	 * 4.  Sets the initial text of the main action button to "Send Verification Code".
	 * 5.  Attaches `OnClickListener`s to:
	 *     - The input field containers to focus the keyboard on the respective `EditText` when clicked.
	 *     - The main action button (`btnRegisterAccount`) to handle both sending and verifying the OTP,
	 *        based on the `hasOptBeenSent` flag.
	 *
	 * @return The current instance of `SupabasePhoneNumberLogIn` to allow for method chaining.
	 */
	fun initialize(): SupabasePhoneNumberLogIn {
		logger.d("Initializing SupabasePhoneNumberLogIn dialog")
		dialogBuilder.setView(R.layout.dialog_user_phone_registration_1)
		dialogBuilder.setCancelable(true)
		
		dialogBuilder.view.apply {
			logger.d("Binding dialog views")
			
			editTextPhoneNumber = findViewById(R.id.edit_field_phone_number)
			editTextPassword = findViewById(R.id.edit_field_password)
			containerEditTextPhoneNumber = findViewById(R.id.container_edit_field_phone_number)
			containerEditTextPassword = findViewById(R.id.container_edit_field_password)
			btnRegisterAccount = findViewById(R.id.btn_register_account)
			btnTextRegisterAccount = findViewById(R.id.txt_register_account)
			txtResendOptTimerCount = findViewById(R.id.txt_resend_opt_timer_info)
			
			containerEditTextPhoneNumber?.setOnClickListener {
				logger.v("Phone number field container clicked")
				focusKeyboardOnPhoneNumberField()
			}
			
			containerEditTextPassword?.setOnClickListener {
				logger.v("OTP field container clicked")
				focusKeyboardOnPasswordField()
			}
			
			btnTextRegisterAccount?.text =
				getText(R.string.title_send_verification_code)
			
			containerEditTextPassword?.visibility = View.GONE
			txtResendOptTimerCount?.visibility = View.GONE
			
			logger.d("Initial UI state set (OTP hidden, timer hidden)")
			btnRegisterAccount?.setOnClickListener {
				val rawNumber = editTextPhoneNumber?.text.toString()
				val phone = normalizeIndianNumber(rawNumber)
				
				logger.d(
					"Register button clicked → raw='$rawNumber', " +
						"normalized='$phone', otpSent=$hasOptBeenSent"
				)
				
				if (!hasOptBeenSent) {
					logger.d("Triggering OTP send flow")
					sendUserOTP(phone)
				} else {
					logger.d("Triggering OTP verification flow")
					verifyUserOTP(phone, editTextPassword?.text.toString())
				}
			}
		}
		
		logger.d("SupabasePhoneNumberLogIn initialization completed")
		return this@SupabasePhoneNumberLogIn
	}
	
	/**
	 * Displays the phone number login dialog if it is not already showing.
	 *
	 * This function handles the presentation of the dialog, registers a timer listener
	 * for OTP countdowns, and, after a brief delay, automatically focuses the
	 * keyboard on the phone number input field to improve user experience.
	 */
	fun show() {
		if (!dialogBuilder.isShowing) {
			dialogBuilder.show()
			AIOApp.aioTimer.register(this)
			
			CommonTimeUtils.delay(500, object : OnTaskFinishListener {
				override fun afterDelay() {
					focusKeyboardOnPhoneNumberField()
				}
			})
		}
	}
	
	/**
	 * Closes the phone number registration dialog.
	 *
	 * This method checks if the dialog is currently showing. If it is,
	 * it closes the dialog and unregisters the class from the global
	 * [AIOTimer] to stop receiving timer ticks.
	 */
	fun close() {
		if (dialogBuilder.isShowing) {
			dialogBuilder.close()
			AIOApp.aioTimer.unregister(this)
		}
	}
	
	/**
	 * Focuses the keyboard on the phone number input field.
	 * It selects all the existing text in the field and then shows the on-screen keyboard,
	 * making it ready for user input.
	 */
	private fun focusKeyboardOnPhoneNumberField() {
		editTextPhoneNumber?.selectAll()
		ViewUtility.showOnScreenKeyboard(safeBaseActivity, editTextPhoneNumber)
	}
	
	/**
	 * Focuses the keyboard on the password/OTP input field.
	 *
	 * This function performs two main actions:
	 * 1. Selects all the text currently in the `editTextPassword` field, making it easy for the user to overwrite.
	 * 2. Programmatically shows the on-screen keyboard, directing the user's input to the password field.
	 */
	private fun focusKeyboardOnPasswordField() {
		editTextPassword?.selectAll()
		ViewUtility.showOnScreenKeyboard(safeBaseActivity, editTextPassword)
	}
	
	/**
	 * Resets the OTP (One-Time Password) timer.
	 *
	 * This function sets the start time for the OTP countdown to the current system time
	 * and resets the `lastShownSecond` tracker to its initial state. This is typically
	 * called when a new OTP is sent, to restart the resend countdown timer.
	 */
	private fun resetOtpTimer() {
		otpStartTimeMillis = System.currentTimeMillis()
		lastShownSecond = -1
	}
	
	/**
	 * Callback function that is triggered on each tick of the global `AIOTimer`.
	 *
	 * This function is responsible for updating the countdown timer UI for OTP (One-Time Password)
	 * resend functionality. It calculates the elapsed time since the OTP was sent and updates
	 * a `TextView` to show the remaining time before the user can request a new OTP.
	 *
	 * The timer starts when `otpStartTimeMillis` is set. It calculates the remaining seconds
	 * out of a total duration (`totalSeconds`). To avoid unnecessary UI updates, it only
	 * refreshes the display when the remaining second's value changes.
	 *
	 * When the countdown reaches zero, it resets the UI to allow the user to request a new OTP:
	 * - Hides the password input field and the timer text.
	 * - Sets `hasOptBeenSent` to `false`.
	 * - Changes the button text to "Resend verification code".
	 *
	 * @param loopCount The number of times the timer has looped, provided by `AIOTimer`.
	 *        Not used in this implementation.
	 */
	override fun onAIOTimerTick(loopCount: Double) {
		if (otpStartTimeMillis == 0L) {
			logger.v("Timer tick ignored: OTP timer not started")
			return
		}
		
		if (txtResendOptTimerCount?.visibility != View.VISIBLE) {
			logger.v("Timer tick ignored: resend timer not visible")
			return
		}
		
		val elapsedSeconds = ((System.currentTimeMillis() - otpStartTimeMillis) / 1000).toInt()
		val remainingSeconds = totalSeconds - elapsedSeconds
		
		if (remainingSeconds < 0) {
			logger.d("OTP timer expired (remainingSeconds < 0)")
			return
		}
		
		if (remainingSeconds == lastShownSecond) {
			logger.v("Timer tick skipped: already rendered second=$remainingSeconds")
			return
		}
		
		lastShownSecond = remainingSeconds
		val minutes = remainingSeconds / 60
		val seconds = remainingSeconds % 60
		
		logger.d("OTP timer tick → remaining=$remainingSeconds sec ($minutes:$seconds)")
		safeBaseActivity?.getAttachedCoroutineScope()?.launch(Dispatchers.Main) {
			val remainingMgs = "${getText(R.string.title_you_can_resend_otp_after)} " +
				"$minutes:$seconds ${getText(R.string.title_minutes)}"
			txtResendOptTimerCount?.text = remainingMgs
			
			if (remainingSeconds == 0) {
				logger.d("OTP timer reached zero, resetting UI state")
				containerEditTextPassword?.visibility = View.GONE
				txtResendOptTimerCount?.visibility = View.GONE
				hasOptBeenSent = false
				btnTextRegisterAccount?.text =
					getText(R.string.title_resend_verification_code)
			}
		}
	}
	
	/**
	 * Validates the phone number and initiates the OTP sending process.
	 *
	 * It first checks if the provided phone number is exactly 10 digits long. If not, it shows a toast
	 * message and returns.
	 *
	 * If the number is valid, it updates the UI to show the password/OTP input field and a resend timer.
	 * It then makes an asynchronous call to the Supabase authentication service to send an OTP
	 * to the user's phone number, prefixed with the country code "91".
	 *
	 * @param userPhoneNumber The 10-digit user phone number without the country code.
	 */
	private fun sendUserOTP(userPhoneNumber: String) {
		logger.d("sendUserOTP() called with number=$userPhoneNumber")
		
		if (userPhoneNumber.length != 10) {
			logger.e("Invalid phone number length: ${userPhoneNumber.length}")
			safeBaseActivity?.doSomeVibration()
			showToast(safeBaseActivity, R.string.title_enter_valid_phone_number)
			return
		}
		
		safeBaseActivity
			?.getAttachedCoroutineScope()
			?.launch(Dispatchers.IO) {
				logger.d("Preparing UI for OTP input")
				
				withContext(Dispatchers.Main) {
					resetOtpTimer()
					containerEditTextPassword?.visibility = View.VISIBLE
					txtResendOptTimerCount?.visibility = View.VISIBLE
					btnTextRegisterAccount?.text = getText(R.string.title_verify_and_login)
					hasOptBeenSent = true
					focusKeyboardOnPasswordField()
				}
				
				try {
					logger.d("Requesting OTP from Supabase for phone=91$userPhoneNumber")
					supabaseClient.auth.signInWith(OTP) { phone = "91$userPhoneNumber" }
					logger.d("OTP request sent successfully")
					
				} catch (error: Exception) {
					logger.e("Failed to send OTP", error)
					
					withContext(Dispatchers.Main) {
						hasOptBeenSent = false
						containerEditTextPassword?.visibility = View.GONE
						txtResendOptTimerCount?.visibility = View.GONE
						
						onAccountRegistrationFailed.invoke()
						safeBaseActivity?.doSomeVibration()
						showToast(safeBaseActivity, R.string.title_failed_to_send_otp)
					}
				}
			}
	}
	
	/**
	 * Verifies the One-Time Password (OTP) entered by the user.
	 *
	 * This function first performs a basic validation on the OTP length. If valid, it launches a coroutine
	 * to communicate with the Supabase authentication server to verify the OTP against the provided phone number.
	 * Upon successful verification, it notifies the user, vibrates the device, and closes the dialog.
	 * If the OTP is invalid (less than 6 digits), it shows a toast message and vibrates the device.
	 *
	 * @param userPhoneNumber The user's 10-digit phone number, without the country code.
	 * @param otp The 6-digit One-Time Password entered by the user.
	 */
	private fun verifyUserOTP(userPhoneNumber: String, otp: String) {
		if (otp.length < 6) {
			safeBaseActivity?.doSomeVibration()
			showToast(safeBaseActivity, R.string.title_enter_valid_otp_number)
			return
		}
		
		safeBaseActivity
			?.getAttachedCoroutineScope()
			?.launch(Dispatchers.IO) {
				logger.d("Starting OTP verification for phone=91$userPhoneNumber")
				
				try {
					supabaseClient.auth.verifyPhoneOtp(
						type = OtpType.Phone.SMS,
						phone = "91$userPhoneNumber",
						token = otp
					)
					
					logger.d("OTP verification successful")
					AIOUserProfileManager.updateLocalUserWithSupabaseUser()
					withContext(Dispatchers.Main) {
						onAccountSuccessfullyRegistered.invoke()
						safeBaseActivity?.doSomeVibration()
						showToast(safeBaseActivity, R.string.title_login_successful)
						close()
					}
					
				} catch (error: Exception) {
					logger.e("OTP verification failed", error)
					withContext(Dispatchers.Main) {
						onAccountRegistrationFailed.invoke()
						safeBaseActivity?.doSomeVibration()
						showToast(safeBaseActivity, R.string.title_invalid_or_expired_otp)
					}
				}
			}
	}
	
}
