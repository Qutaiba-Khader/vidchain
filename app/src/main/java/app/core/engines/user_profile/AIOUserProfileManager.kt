@file:OptIn(ExperimentalTime::class)

package app.core.engines.user_profile

import android.os.*
import app.core.AIOApp.Companion.aioUserProfile
import app.core.engines.supabase.SupabaseCloudServer.supabaseClient
import io.github.jan.supabase.auth.*
import kotlinx.coroutines.*
import lib.process.*
import kotlin.time.*

/**
 * Manages the local user profile, acting as a bridge between the application's
 * local user data (`AIOUserProfile`) and the remote user data stored in Supabase.
 *
 * This singleton object provides utilities to synchronize user profile information,
 * ensuring that the local state is up-to-date with the authenticated user's session
 * details from the Supabase backend. It handles fetching user data upon successful
 * authentication and populating the local profile model.
 *
 * @see AIOUserProfile
 * @see app.core.engines.supabase.SupabaseCloudServer
 */
object AIOUserProfileManager {
	
	/**
	 * Logger for the [AIOUserProfileManager] object.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Retrieves the singleton instance of the local user profile.
	 *
	 * This function provides a synchronized, static-accessible method to obtain the
	 * `AIOUserProfile` object, which holds the current user's profile data within the application.
	 *
	 * @return The singleton [AIOUserProfile] instance.
	 * @see AIOUserProfile
	 */
	@JvmStatic
	@Synchronized
	fun getAIOUserProfile(): AIOUserProfile = aioUserProfile
	
	/**
	 * Synchronizes the local user profile with data from the current Supabase session.
	 *
	 * This function operates asynchronously on a background thread. It retrieves the
	 * current Supabase session and its associated user object. If a session and a verified
	 * user exist (i.e., email or phone is confirmed), it updates the local `aioUserProfile`
	 * singleton with details from the Supabase user, such as ID, contact info,
	 * metadata, and various timestamps.
	 *
	 * After updating, it sets the `isUserCurrentlyLoggedIn` and `isSupabaseLinked` flags
	 * to true and persists the changes to local storage. If no active session is found,
	 * the user is null, or the user is unverified, the local profile is reset.
	 *
	 * A rate-limiting mechanism is in place to prevent this function from running too
	 * frequently. By default, it will not execute if the last update was less than 5 seconds ago.
	 * This interval can be configured via the `updateMinInternal` parameter.
	 *
	 * @param updateMinInternal The minimum time interval in milliseconds that must pass
	 *        since the last successful update for a new synchronization to occur.
	 *        Defaults to 2000 milliseconds (2 seconds).
	 *
	 * @see AIOUserProfile
	 * @see ThreadsUtility.executeInBackground
	 */
	@JvmStatic
	@Synchronized
	fun updateLocalUserWithSupabaseUser(updateMinInternal: Long = 2_000L) {
		logger.d("updateLocalUserWithSupabaseUser() called")
		
		val now = SystemClock.elapsedRealtime()
		val minInterval = updateMinInternal // 2 seconds
		if (now - aioUserProfile.lastTimeUpdatedWithSupabase < minInterval) {
			logger.d("Supabase sync skipped (throttled)")
			return
		}
		
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 1000,
			codeBlock = {
				val session = supabaseClient.auth.currentSessionOrNull()
				if (session == null) {
					logger.d("No active Supabase session, skipping sync")
					return@executeInBackground
				}
				
				val supabaseUser = session.user
				if (supabaseUser == null) {
					logger.d("Session exists but user is null, skipping sync")
					return@executeInBackground
				}
				
				val isVerified =
					supabaseUser.phoneConfirmedAt != null ||
						supabaseUser.emailConfirmedAt != null
				
				if (!isVerified) {
					logger.d("Supabase user not verified, local profile will NOT be synced")
					return@executeInBackground
				}
				
				logger.d("Verified Supabase user found, syncing local profile: ${supabaseUser.id}")
				aioUserProfile.apply {
					uniqueUserServerId = supabaseUser.id
					userEmailAddress = supabaseUser.email ?: ""
					userPhoneNumber = supabaseUser.phone ?: ""
					
					isEmailVerified = supabaseUser.emailConfirmedAt != null
					isPhoneVerified = supabaseUser.phoneConfirmedAt != null
					emailVerifiedAt = supabaseUser.emailConfirmedAt?.toString() ?: ""
					phoneVerifiedAt = supabaseUser.phoneConfirmedAt?.toString() ?: ""
					
					isUserAccountVerified = isEmailVerified || isPhoneVerified
					supabaseUserMetadata = supabaseUser.userMetadata?.toString() ?: "{}"
					supabaseAppMetadata = supabaseUser.appMetadata?.toString() ?: "{}"
					
					supabaseAccountCreatedAt = supabaseUser.createdAt.toString()
					lastSupabaseLoginAt = supabaseUser.lastSignInAt?.toString() ?: ""
					
					isUserCurrentlyLoggedIn = true
					isSupabaseLinked = true
					aioUserProfile.lastTimeUpdatedWithSupabase = SystemClock.elapsedRealtime()
					updateInStorage()
				}
				
				logger.d("Local user profile fully synced with Supabase")
			}
		)
	}
	
	/**
	 * Logs the user out from both the Supabase session and the local user profile.
	 *
	 * This function performs a comprehensive logout by first invalidating the user's
	 * session on the Supabase server. After a successful remote logout, it proceeds
	 * to reset the local `aioUserProfile` singleton to its default, logged-out state.
	 *
	 * The entire operation is executed asynchronously within the provided [CoroutineScope].
	 * Any exceptions during the Supabase sign-out process are caught and logged,
	 * ensuring the local profile is reset regardless of network failures.
	 *
	 * @param scope The [CoroutineScope] in which to launch the asynchronous logout process.
	 *              This is typically a scope tied to a ViewModel or a lifecycle-aware component.
	 *
	 * @see AIOUserProfile.resetUserProfile
	 * @see io.github.jan.supabase.gotrue.GoTrue.signOut
	 */
	@JvmStatic
	@Synchronized
	fun logOutFromSupabaseAndLocalUser(scope: CoroutineScope, onLogout: (() -> Unit)? = null) {
		scope.launch {
			try {
				supabaseClient.auth.signOut()
				aioUserProfile.resetUserProfile()
				onLogout?.invoke()
			} catch (error: Exception) {
				logger.e("Supabase logout failed", error)
			}
		}
	}
	
	@JvmStatic
	@Synchronized
	fun updateAppSettingsFromSupabaseSettings() {
		ThreadsUtility.executeInBackground(
			timeOutInMilli = 1000,
			codeBlock = {
				logger.d("updateAppSettingsFromSupabaseSettings() called")
				
			}
		)
	}
	
}