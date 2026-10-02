package app.core.engines.supabase

import app.core.engines.supabase.SupabaseCloudServer.observeSupabaseAuthState

/**
 * Interface for listening to the results of authentication operations.
 *
 * This listener provides callbacks for success and failure scenarios related to
 * user authentication actions, such as sign-in, sign-up, or sign-out.
 * Implement this interface to handle outcomes of asynchronous auth operations.
 */
interface AuthOperationsListener {
	
	/**
	 * Handles the logic to be executed after a user has been successfully authenticated.
	 * This can include synchronizing the local user profile with the data from the
	 * authenticated Supabase user.
	 *
	 * It is called when the session status changes to `Authenticated`, ensuring that the
	 * local application state reflects the signed-in user's information.
	 */
	fun onSuccessfulAuthentication()
	
	/**
	 * Handles the logic when a user's authentication session is removed or becomes invalid.
	 *
	 * This function is designed to be called when the Supabase session status changes to
	 * `NotAuthenticated`. It performs a clean-up by resetting the local user profile,
	 * effectively logging the user out of the application's local state. This ensures that
	 * the app's UI and data reflect the unauthenticated status.
	 *
	 * @see observeSupabaseAuthState where this function is typically called in response to
	 * a `SessionStatus.NotAuthenticated` event.
	 */
	fun onAuthenticationRemoved()
	
}