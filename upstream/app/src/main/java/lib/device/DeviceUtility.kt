package lib.device

import android.content.*
import android.content.Context.*
import android.net.*
import android.net.NetworkCapabilities.*
import android.os.*
import android.telephony.*
import app.core.AIOApp.Companion.INSTANCE
import lib.texts.CommonTextUtils.capitalizeFirstLetter
import java.lang.ref.*
import java.util.*
import java.util.Locale.*

/**
 * Utility class for retrieving information related to the device hardware,
 * display, connectivity, and locale settings.
 */
object DeviceUtility {
	
	/**
	 * Retrieves the raw screen density of the device.
	 *
	 * @param context A context to access resources and metrics.
	 * @return A float value representing the screen density (e.g., 1.5, 2.0).
	 */
	@JvmStatic
	fun getDeviceScreenDensity(context: Context?): Float {
		WeakReference(context).get()?.let { safeRef ->
			val displayMetrics = safeRef.resources.displayMetrics
			val density = displayMetrics.density
			return density
		}; return 0.0f
	}
	
	/**
	 * Returns the screen density formatted as a human-readable bucket like `hdpi`, `xxhdpi`, etc.
	 *
	 * @param context A context to access display metrics.
	 * @return A string representing the display density category.
	 */
	@JvmStatic
	fun getDeviceScreenDensityInFormat(context: Context?): String {
		WeakReference(context).get()?.let { safeRef ->
			val displayMetrics = safeRef.resources.displayMetrics
			val density = displayMetrics.density
			val formattedDensity = when {
				density >= 4.0 -> "xxxhdpi"
				density >= 3.0 -> "xxhdpi"
				density >= 2.0 -> "xhdpi"
				density >= 1.5 -> "hdpi"
				density >= 1.0 -> "mdpi"
				else -> "ldpi"
			}
			return formattedDensity
		}; return ""
	}
	
	/**
	 * Returns the device manufacturer and model as a formatted name.
	 *
	 * @return A capitalized string combining manufacturer and model (e.g., "Samsung Galaxy S10").
	 */
	@JvmStatic
	fun getDeviceManufactureModelName(): String? {
		val manufacturer = Build.MANUFACTURER
		val model: String = Build.MODEL
		val deviceName = if (model.startsWith(manufacturer)) {
			capitalizeFirstLetter(model)
		} else capitalizeFirstLetter("$manufacturer $model")
		return deviceName
	}
	
	/**
	 * Checks whether the device has an active internet connection through
	 * any known transport methods (Wi-Fi, cellular, Ethernet, or Bluetooth).
	 *
	 * @return `true` if connected to the internet, `false` otherwise.
	 */
	@JvmStatic
	fun isDeviceConnectedToInternet(): Boolean {
		val appContext = INSTANCE
		val connectivityService = appContext.getSystemService(CONNECTIVITY_SERVICE)
		val connectivityManager = connectivityService as ConnectivityManager
		val network = connectivityManager.activeNetwork
		val nc = connectivityManager.getNetworkCapabilities(network) ?: run {
			return false
		}
		val wifiChecked = nc.hasTransport(TRANSPORT_WIFI)
		val cellularChecked = nc.hasTransport(TRANSPORT_CELLULAR)
		val ethernetChecked = nc.hasTransport(TRANSPORT_ETHERNET)
		val bluetoothChecked = nc.hasTransport(TRANSPORT_BLUETOOTH)
		
		val isOnline = wifiChecked || cellularChecked || ethernetChecked || bluetoothChecked
		return isOnline
	}
	
	/**
	 * Attempts to determine the user's country based on locale or SIM info.
	 *
	 * @return A string representing the country code (e.g., "US", "IN") or a localized unknown label.
	 */
	@JvmStatic
	fun getDeviceUserCountry(): String {
		val country = getDefault().country
		if (country.isNotEmpty()) return country
		val telephoneService = INSTANCE.getSystemService(TELEPHONY_SERVICE)
		val telephonyManager = telephoneService as TelephonyManager
		val simCountryIso = telephonyManager.simCountryIso
		if (simCountryIso.isNotEmpty()) return simCountryIso.uppercase(getDefault())
		return "Unknown"
	}
	
	/**
	 * Determines whether the user is likely located in India by evaluating
	 * multiple non-invasive device signals.
	 *
	 * The check is heuristic-based and does NOT rely on GPS or IP lookup.
	 * Signals are evaluated in order of reliability:
	 *
	 * 1. Mobile network country ISO (best signal when connected to a carrier)
	 * 2. SIM card country ISO (subscription origin)
	 * 3. Device default locale (user-selected region)
	 * 4. System time zone (last-resort heuristic)
	 *
	 * The function returns `true` as soon as any signal positively identifies
	 * India ("IN"). If none match, it returns `false`.
	 *
	 * Notes:
	 * - No runtime permissions are required.
	 * - Results are best-effort and not legally authoritative.
	 * - Suitable for feature gating, UI defaults, or regional behavior.
	 *
	 * @param context Context used to access system services (TelephonyManager).
	 * @return `true` if the device is likely associated with India, otherwise `false`.
	 */
	@JvmStatic
	fun isUserFromIndia(context: Context?): Boolean {
		if (context == null) return false
		
		val indiaCountryCode = "in"
		val indianTimeZoneId = "Asia/Kolkata"
		
		// TelephonyManager may be null on Wi-Fi–only devices (e.g., tablets)
		val tm = context.getSystemService(TELEPHONY_SERVICE) as? TelephonyManager
		
		return when {
			// 1. Country provided by the active mobile network
			tm?.networkCountryIso?.equals(indiaCountryCode, ignoreCase = true) == true -> true
			
			// 2. Country associated with the SIM card
			tm?.simCountryIso?.equals(indiaCountryCode, ignoreCase = true) == true -> true
			
			// 3. User-configured device locale
			getDefault().country.equals(indiaCountryCode, ignoreCase = true) -> true
			
			// 4. System time zone fallback
			TimeZone.getDefault().id.equals(indianTimeZoneId, ignoreCase = true) -> true
			
			else -> false
		}
	}
	
	/**
	 * Normalizes an Indian mobile number to the standard 10-digit format.
	 *
	 * This function handles common variations in Indian mobile numbers, such as:
	 * - The "+91" country code prefix.
	 * - The "0" trunk prefix often used for STD calls.
	 *
	 * It strips these prefixes to return a clean, 10-digit number.
	 * If the input number is already 10 digits or does not match the known
	 * prefixed formats (12 or 11 digits), it is returned as is.
	 *
	 * Examples:
	 * - `"+919876543210"` becomes `"9876543210"`
	 * - `"09876543210"` becomes `"9876543210"`
	 * - `"9876543210"` remains `"9876543210"`
	 * - `"12345"` remains `"12345"` (as it's not a valid prefixed format)
	 *
	 * @param number The phone number string to normalize.
	 * @return A 10-digit mobile number string, or the original string if it
	 *         cannot be normalized as expected.
	 */
	@JvmStatic
	fun normalizeIndianNumber(raw: String): String {
		val digits = raw.filter { it.isDigit() }
		return when (
			digits.length) {
			12 if digits.startsWith("91") -> digits.substring(2)
			11 if digits.startsWith("0") -> digits.substring(1)
			10 -> digits
			else -> digits
		}
	}
	
}