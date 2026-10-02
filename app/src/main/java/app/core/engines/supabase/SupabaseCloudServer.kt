package app.core.engines.supabase

import app.core.*
import app.core.AIOApp.Companion.aioUserProfile
import app.core.engines.supabase.SupabaseCloudServer.observeSupabaseAuthState
import app.core.engines.supabase.SupabaseCloudServer.registerAuthOperationListener
import app.core.engines.supabase.SupabaseCloudServer.supabaseClient
import app.core.engines.supabase.SupabaseCloudServer.updateDataModelToSupabase
import app.core.engines.user_profile.*
import app.core.engines.user_profile.AIOUserProfileManager.updateLocalUserWithSupabaseUser
import com.aio.*
import io.github.jan.supabase.*
import io.github.jan.supabase.auth.*
import io.github.jan.supabase.auth.status.*
import io.github.jan.supabase.postgrest.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import lib.process.*
import lib.texts.CommonTextUtils.getText
import java.lang.ref.*
import java.util.concurrent.*

/**
 * A singleton object that acts as a client for interacting with a Supabase cloud server.
 *
 * This object manages the connection and data transfer to a Supabase backend. It provides
 * a centralized, lazily-initialized Supabase client and utility functions to upload
 * data models to specific tables.
 *
 * The primary functionality is encapsulated in [updateDataModelToSupabase], which handles
 * asynchronous data insertion, respecting application-level settings for cloud backups.
 * It is designed to be thread-safe and can be used with or without a provided CoroutineScope.
 *
 * @see supabaseClient for the underlying Supabase client instance.
 * @see updateDataModelToSupabase for the main data upload function.
 */
object SupabaseCloudServer {
	
	/**
	 * Logger instance for this class, used for logging various events and debugging information.
	 */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * A list of listeners that react to authentication-related events.
	 *
	 * This list holds weak references to objects implementing the [AuthOperationsListener]
	 * interface. Listeners in this list are notified of significant authentication events,
	 * such as successful login or session removal. Using [WeakReference] helps prevent
	 * memory leaks by allowing the garbage collector to reclaim listener objects if they
	 * are no longer referenced elsewhere. The [CopyOnWriteArrayList] ensures thread-safe
	 * modifications and iterations, which is crucial in a concurrent environment where
	 * listeners might be added or removed from different threads.
	 *
	 * @see AuthOperationsListener
	 */
	private val authOperationListeners = CopyOnWriteArrayList<WeakReference<AuthOperationsListener>>()
	
	/**
	 * A lazily initialized Supabase client instance.
	 *
	 * This client is configured with the URL and anonymous key from string resources
	 * and includes the Postgrest plugin for database interactions. It uses the Ktor
	 * Android engine for HTTP requests. The instance is created only upon its first access.
	 */
	@JvmStatic
	val supabaseClient: SupabaseClient by lazy {
		logger.i("Initializing Supabase client…")
		createSupabaseClient(
			supabaseUrl = getText(R.string.text_supabase_client_key),
			supabaseKey = getText(R.string.text_supabase_client_anon_key)
		) {
			install(Auth)
			install(Postgrest)
		}.also {
			logger.i("Supabase client initialized successfully")
		}
	}
	
	/**
	 * Creates and configures a new Supabase client instance.
	 *
	 * This function initializes a Supabase client with the specified URL and key. It is
	 * pre-configured to use the Ktor Android engine for network requests and can be
	 * further customized by the `config` lambda. This allows for the installation of
	 * various Supabase plugins like `Auth`, `Postgrest`, `Storage`, etc., tailored to
	 * the application's needs.
	 *
	 * Example usage:
	 * ```kotlin
	 * val client = initializeSupabaseClient(
	 *     supabaseUrl = "YOUR_SUPABASE_URL",
	 *     supabaseKey = "YOUR_SUPABASE_KEY"
	 * ) {
	 *     install(Auth)
	 *     install(Postgrest)
	 * }
	 * ```
	 *
	 * @param supabaseUrl The URL of the Supabase project.
	 * @param supabaseKey The public anonymous key for the Supabase project.
	 * @param config A lambda with `SupabaseClientBuilder` as its receiver, used to
	 *               install plugins and apply further configurations.
	 * @return A configured [SupabaseClient] instance.
	 */
	@JvmStatic
	fun initializeSupabaseClient() {
		supabaseClient
	}
	
	/**
	 * Registers a listener to observe authentication state changes from Supabase.
	 *
	 * This function adds a weak reference to the provided [AuthOperationsListener] to a list
	 * of listeners. These listeners are notified about authentication events, such as
	 * successful authentication or session removal, which are captured by other parts
	 * of the `SupabaseCloudServer`, like [observeSupabaseAuthState].
	 *
	 * Using a `WeakReference` ensures that the listener does not cause memory leaks. If the
	 * listener object is garbage collected, the reference will be automatically removed
	 * from the list upon the next iteration.
	 *
	 * @param listener The [AuthOperationsListener] instance to be notified of auth events.
	 */
	@JvmStatic
	@Synchronized
	fun registerAuthOperationListener(
		listener: AuthOperationsListener?,
		onSuccessRegister: () -> Unit = {},
		onFailedRegister: () -> Unit = {}
	) {
		listener?.let {
			try {
				val classSimpleName = listener.javaClass.simpleName
				if (authOperationListeners.none { it.get() == listener }) {
					authOperationListeners.add(WeakReference(listener))
					logger.d("Auth operation listener registered: $classSimpleName")
					onSuccessRegister.invoke()
				} else if (authOperationListeners.any { it.get() == listener }) {
					logger.d("Auth operation listener already registered: $classSimpleName")
				}
			} catch (error: Exception) {
				logger.e("Error registering auth operation listener", error)
				onFailedRegister.invoke()
			}
		}
	}
	
	/**
	 * Unregisters an authentication operation listener.
	 *
	 * This function removes a listener from the `authOperationListeners` list, preventing it
	 * from receiving future authentication event notifications. It iterates through the list
	 * of weak references and removes the entry whose referenced object matches the provided
	 * `listener`. This ensures that the listener is properly cleaned up and no longer held
	 * in memory by this class, which is crucial for preventing memory leaks, especially
	 * in lifecycle-aware components.
	 *
	 * The removal process is thread-safe due to the use of `CopyOnWriteArrayList`.
	 *
	 * @param listener The `AuthOperationsListener` instance to be removed.
	 * @see registerAuthOperationListener
	 */
	@JvmStatic
	@Synchronized
	fun unregisterAuthOperationListener(
		listener: AuthOperationsListener?,
		onSuccessRegister: () -> Unit = {},
		onFailedRegister: () -> Unit = {}
	) {
		listener?.let {
			try {
				val classSimpleName = listener.javaClass.simpleName
				authOperationListeners.removeIf { it.get() == listener }
				logger.d("Auth operation listener unregistered: $classSimpleName")
				onSuccessRegister.invoke()
			} catch (error: Exception) {
				logger.e("Error unregistering auth operation listener", error)
				onFailedRegister.invoke()
			}
		}
	}
	
	/**
	 * Observes the Supabase authentication state and reacts to changes in real-time.
	 *
	 * This function launches a coroutine that collects the `sessionStatus` flow from the
	 * Supabase `Auth` client, allowing the app to monitor the user's authentication status
	 * continuously.
	 *
	 * It handles the following states:
	 * - **`Authenticated`**: When a user is successfully signed in, it triggers
	 *   [AIOUserProfileManager.updateLocalUserWithSupabaseUser] to synchronize the local user
	 *   profile with the data from the authenticated Supabase user.
	 * - **`NotAuthenticated`**: When the user is signed out or the session becomes invalid,
	 *   it calls [aioUserProfile.resetUserProfile] to clear the local user data, ensuring
	 *   the app state reflects the logged-out status.
	 * - **Other statuses**: It logs any other authentication status changes for debugging purposes
	 *   but performs no specific actions.
	 *
	 * This mechanism is crucial for maintaining a consistent user state across the application,
	 * automatically updating the UI and data when the user's session changes.
	 *
	 * @param scope The [CoroutineScope] in which to launch the observer coroutine. This scope
	 *              should be tied to a lifecycle that allows the observation to persist as
	 *              long as needed (e.g., a `ViewModel`'s `viewModelScope` or a service's scope).
	 */
	@JvmStatic
	fun observeSupabaseAuthState(scope: CoroutineScope) {
		scope.launch {
			supabaseClient.auth.sessionStatus.collect { status ->
				val iterator = authOperationListeners.iterator()
				while (iterator.hasNext()) {
					val listenerRef = iterator.next()
					if (listenerRef.get() == null) {
						authOperationListeners.remove(listenerRef)
					}
				}
				
				when (status) {
					is SessionStatus.Authenticated -> {
						logger.d("Supabase session authenticated, updating local user.")
						updateLocalUserWithSupabaseUser(0L)
						authOperationListeners.forEach { listenerRef ->
							listenerRef.get()?.let { listener ->
								val classSimpleName = listener.javaClass.simpleName
								try {
									listener.onSuccessfulAuthentication()
								} catch (error: Exception) {
									logger.e("Error in auth operation listener callback: $classSimpleName", error)
								}
							}
						}
					}
					
					is SessionStatus.NotAuthenticated -> {
						logger.d("Supabase session authenticated failed, updating local user.")
						aioUserProfile.resetUserProfile()
						authOperationListeners.forEach { listenerRef ->
							listenerRef.get()?.let { listener ->
								val classSimpleName = listener.javaClass.simpleName
								try {
									listener.onAuthenticationRemoved()
								} catch (error: Exception) {
									logger.e("Error in auth operation listener callback: $classSimpleName", error)
								}
							}
						}
					}
					
					else -> {
						logger.d("Supabase session status: $status")
					}
				}
			}
		}
	}
	
	/**
	 * Asynchronously inserts a data model into a Supabase table.
	 *
	 * This function launches a coroutine in the provided `coroutineScope` to perform a network
	 * operation on an IO-optimized thread. It attempts to insert a JSON string representation
	 * of a data model into the specified Supabase table.
	 *
	 * The result of the operation is handled via optional callback functions, `onSuccess` and `onError`,
	 * which are executed on the main thread.
	 *
	 * @param coroutineScope The CoroutineScope to launch the network request in.
	 * @param dataModelName The name of the Supabase table (data model) to insert into.
	 * @param dataModelJsonString A JSON formatted string representing the data to be inserted.
	 * @param onSuccess An optional lambda function to be invoked on the main thread upon successful insertion.
	 * @param onError An optional lambda function to be invoked on the main thread if an error occurs,
	 *                passing the caught [Throwable] as an argument.
	 */
	@JvmStatic
	@Synchronized
	fun updateDataModelToSupabase(
		coroutineScope: CoroutineScope? = null,
		dataModelName: String,
		dataModelJsonString: String,
		onSuccess: (() -> Unit)? = null,
		onError: ((Throwable) -> Unit)? = null
	) {
		logger.i("Request to update Supabase model: $dataModelName")
		
		if (!AIOApp.IS_CLOUD_BACKUP_ENABLED) {
			val error = Throwable("Cloud backup is disabled. Upload blocked.")
			logger.e("Upload aborted:", error)
			onError?.invoke(error)
			return
		}
		
		logger.i("Cloud backup enabled. Starting upload…")
		logger.d("JSON Payload for $dataModelName:\n$dataModelJsonString")
		val jsonObj = Json.parseToJsonElement(dataModelJsonString).jsonObject
		
		if (coroutineScope == null) {
			logger.w("No coroutine scope provided → using ThreadsUtility background thread")
			ThreadsUtility.executeInBackground(
				timeOutInMilli = 1200,
				codeBlock = {
					runBlocking {
						uploadToSupabaseServer(
							dataModelName = dataModelName,
							jsonObjectPayload = jsonObj,
							onSuccess = onSuccess,
							onError = onError
						)
					}
				}
			)
		} else {
			coroutineScope.launch(Dispatchers.IO) {
				logger.i("Coroutine launched on IO thread")
				uploadToSupabaseServer(
					dataModelName = dataModelName,
					jsonObjectPayload = jsonObj,
					onSuccess = onSuccess,
					onError = onError
				)
			}
		}
	}
	
	/**
	 * Performs the actual network operation to insert a data model into a Supabase table.
	 *
	 * This suspending function uses the Postgrest plugin to insert a JSON string
	 * into the specified table. It handles the success and failure of the operation by
	 * invoking the provided callbacks on the main thread. This function is intended
	 * to be called from a coroutine on an IO-optimized dispatcher.
	 *
	 * @param dataModelName The name of the target Supabase table.
	 * @param dataModelJsonString A JSON formatted string representing the data to be inserted.
	 * @param onSuccess An optional callback to be executed on the main thread upon successful insertion.
	 * @param onError An optional callback to be executed on the main thread if an error occurs,
	 *                passing the caught [Throwable] as an argument.
	 * @throws Throwable if the network request fails and is not caught internally.
	 */
	private suspend fun uploadToSupabaseServer(
		dataModelName: String,
		jsonObjectPayload: JsonObject,
		onSuccess: (() -> Unit)?,
		onError: ((Throwable) -> Unit)?
	) {
		logger.i("Uploading to Supabase → Table: $dataModelName")
		val payload = buildJsonObject { put("json_data", jsonObjectPayload) }
		
		try {
			supabaseClient
				.postgrest[dataModelName]
				.insert(payload)
			
			logger.i("Upload successful for table: $dataModelName")
			
			withContext(Dispatchers.Main) {
				logger.d("Executing onSuccess callback on Main thread")
				onSuccess?.invoke()
			}
			
		} catch (error: Throwable) {
			logger.e("Upload failed for table: $dataModelName", error)
			
			withContext(Dispatchers.Main) {
				logger.d("Executing onError callback on Main thread")
				onError?.invoke(error)
			}
		}
	}
}