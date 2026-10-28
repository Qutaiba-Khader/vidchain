package org.websnake.vidchain.fallback.core

import android.app.Activity

/**
 * The VidChain account (owner, 2026-10-28): Firebase Authentication (e-mail + password, Google) and the user's own
 * Firestore profile live in the app module (it carries the Firebase configuration); VidChain's settings screen reaches
 * them through [hooks]. Every callback runs on the main thread with null on success or a short message to show.
 */
object FirebaseAccount {
	interface Hooks {
		/** this build carries a Firebase configuration */
		fun available(): Boolean
		/** the signed-in user as shown (e-mail, else name), null = signed out */
		fun signedIn(): String?
		fun signInWithGoogle(activity: Activity, done: (String?) -> Unit)
		/** [create] = a new account (a verification e-mail is sent); else signs in to an existing one */
		fun signInWithEmail(email: String, password: String, create: Boolean, done: (String?) -> Unit)
		fun sendPasswordReset(email: String, done: (String?) -> Unit)
		fun signOut(activity: Activity, done: () -> Unit)
	}

	@Volatile var hooks: Hooks? = null
}
