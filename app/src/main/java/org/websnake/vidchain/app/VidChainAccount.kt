package org.websnake.vidchain.app

import android.app.Activity
import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.websnake.vidchain.fallback.core.FirebaseAccount

/**
 * The VidChain account (owner, 2026-10-28; new file, not upstream code): Firebase Authentication with e-mail +
 * password and with Google (Credential Manager, the web client id from google-services.json), and the user's own
 * profile in Firestore at users/{uid} (firebase/firestore.rules: only its owner reads or writes it). Signing in is
 * optional; nothing else in the app needs an account. No password, token or ID token is ever logged.
 */
object VidChainAccount : FirebaseAccount.Hooks {
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	@Volatile private var ready = false
	@Volatile private var appContext: Context? = null

	fun init(context: Context) {
		val app = context.applicationContext
		appContext = app
		ready = com.aio.BuildConfig.VIDCHAIN_FIREBASE && runCatching { FirebaseApp.getApps(app).isNotEmpty() }.getOrDefault(false)
		FirebaseAccount.hooks = this
	}

	override fun available(): Boolean = ready

	override fun signedIn(): String? = user()?.let { u -> u.email?.takeIf { it.isNotBlank() } ?: u.displayName ?: "signed in" }

	override fun signInWithGoogle(activity: Activity, done: (String?) -> Unit) {
		if (!ready) return done(NOT_SET_UP)
		// the web client id is a resource the google-services plugin generates from google-services.json
		val id = activity.resources.getIdentifier("default_web_client_id", "string", activity.packageName)
		if (id == 0) return done("Google sign-in is not set up for this build")
		scope.launch {
			try {
				val request = GetCredentialRequest.Builder()
					.addCredentialOption(GetSignInWithGoogleOption.Builder(activity.getString(id)).build()).build()
				val credential = CredentialManager.create(activity).getCredential(activity, request).credential
				if (credential !is CustomCredential || credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
					return@launch done("Google did not return an account")
				val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
				auth().signInWithCredential(GoogleAuthProvider.getCredential(token, null)).addOnCompleteListener { t ->
					if (t.isSuccessful) { saveProfile("google"); done(null) } else done(message(t.exception))
				}
			} catch (e: GetCredentialCancellationException) {
				done("Cancelled")
			} catch (e: NoCredentialException) {
				done("No Google account on this phone")
			} catch (e: GetCredentialException) {
				VidChainLog.error(e, "Google sign-in")
				done("Google sign-in failed")
			} catch (e: Exception) {
				VidChainLog.error(e, "Google sign-in")
				done("Google sign-in failed")
			}
		}
	}

	override fun signInWithEmail(email: String, password: String, create: Boolean, done: (String?) -> Unit) {
		if (!ready) return done(NOT_SET_UP)
		val e = email.trim()
		if (e.isEmpty() || password.isEmpty()) return done("Enter the e-mail address and the password")
		val task = if (create) auth().createUserWithEmailAndPassword(e, password) else auth().signInWithEmailAndPassword(e, password)
		task.addOnCompleteListener { t ->
			if (!t.isSuccessful) return@addOnCompleteListener done(message(t.exception))
			if (create) t.result?.user?.sendEmailVerification()
			saveProfile("password")
			done(null)
		}
	}

	override fun sendPasswordReset(email: String, done: (String?) -> Unit) {
		if (!ready) return done(NOT_SET_UP)
		val e = email.trim()
		if (e.isEmpty()) return done("Enter the e-mail address")
		auth().sendPasswordResetEmail(e).addOnCompleteListener { t -> done(if (t.isSuccessful) null else message(t.exception)) }
	}

	override fun signOut(activity: Activity, done: () -> Unit) {
		if (!ready) return done()
		auth().signOut()
		scope.launch {
			runCatching { CredentialManager.create(activity).clearCredentialState(ClearCredentialStateRequest()) }
			done()
		}
	}

	/**
	 * users/{uid}: created once (createdAt), then lastSignInAt, providers and appVersion on every sign-in. Only the
	 * fields firebase/firestore.rules allows; best effort (a profile that could not be saved never blocks signing in).
	 */
	private fun saveProfile(method: String) {
		val u = user() ?: return
		VidChainLog.key("signed_in", "yes")
		runCatching {
			if (VidChainLog.enabled()) appContext?.let { c ->
				FirebaseAnalytics.getInstance(c).logEvent(FirebaseAnalytics.Event.LOGIN, android.os.Bundle().apply { putString(FirebaseAnalytics.Param.METHOD, method) })
			}
		}
		val doc = FirebaseFirestore.getInstance().collection("users").document(u.uid)
		val fields = profileFields(u)
		doc.get().addOnCompleteListener { t ->
			if (!t.isSuccessful) return@addOnCompleteListener VidChainLog.error(t.exception ?: RuntimeException("profile read"), "profile read")
			val write = if (t.result?.exists() == true) doc.set(fields, SetOptions.merge())
				else doc.set(fields + ("createdAt" to FieldValue.serverTimestamp()))
			write.addOnFailureListener { VidChainLog.error(it, "profile write") }
		}
	}

	private fun profileFields(u: FirebaseUser): Map<String, Any> = buildMap {
		u.email?.takeIf { it.isNotBlank() }?.let { put("email", it) }
		u.displayName?.takeIf { it.isNotBlank() }?.let { put("displayName", it.take(100)) }
		put("providers", u.providerData.map { it.providerId }.filter { it in PROVIDERS }.distinct())
		put("lastSignInAt", FieldValue.serverTimestamp())
		put("appVersion", appVersion())
	}

	private fun message(e: Exception?): String = when (e) {
		is FirebaseAuthWeakPasswordException -> "The password is too weak (at least 6 characters)"
		is FirebaseAuthUserCollisionException -> "An account with this e-mail address already exists"
		is FirebaseAuthInvalidUserException -> "No account with this e-mail address, or it is disabled"
		is FirebaseAuthInvalidCredentialsException -> "Wrong e-mail address or password"
		is FirebaseNetworkException -> "No connection"
		null -> "Failed"
		else -> { VidChainLog.error(e, "account"); "Failed: " + (e.message?.let { org.websnake.vidchain.fallback.core.CrashReporting.clean(it) }?.take(120) ?: e.javaClass.simpleName) }
	}

	private fun appVersion(): String = runCatching {
		val c = appContext ?: return@runCatching "?"
		c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"
	}.getOrDefault("?").take(32)

	private fun auth() = FirebaseAuth.getInstance()
	private fun user(): FirebaseUser? = if (ready) runCatching { auth().currentUser }.getOrNull() else null

	private const val NOT_SET_UP = "This build has no Firebase project"
	private val PROVIDERS = setOf("password", "google.com")
}
