package lib.device

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.AuthenticationResult
import androidx.core.content.ContextCompat
import app.core.bases.BaseActivity
import com.aio.R
import lib.texts.CommonTextUtils.getText
import lib.ui.builders.ToastView.Companion.showToast

object SecureFileUtil {
	private const val KEY_ALIAS = "PrivateFileKey"
	private const val ANDROID_KEYSTORE = "AndroidKeyStore"

	fun authenticate(activity: BaseActivity?, onResult: (Boolean) -> Unit) {
		if (activity == null) return
		// Use AndroidX Biometric PromptInfo
		val promptInfo = BiometricPrompt.PromptInfo.Builder()
			.setTitle(getText(R.string.title_unlock_requires))
			.setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
			.build()

		val executor = ContextCompat.getMainExecutor(activity)
		val biometricPrompt = BiometricPrompt(activity, executor,
			object : BiometricPrompt.AuthenticationCallback() {
				override fun onAuthenticationSucceeded(result: AuthenticationResult) {
					onResult(true)
				}

				override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
					showToast(activity, msgId = R.string.title_authentication_failed)
				}
			}
		)

		biometricPrompt.authenticate(promptInfo)
	}
}
