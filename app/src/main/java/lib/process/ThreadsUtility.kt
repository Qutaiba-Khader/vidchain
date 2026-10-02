package lib.process

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext

object ThreadsUtility : CoroutineScope {

	private val logger = LogHelperUtils.from(javaClass)

	private const val JOB_TIMEOUT = (30 * 1000L)
	private const val DEFAULT_RETRY_COUNT = 3
	private const val DEFAULT_RETRY_DELAY = 1000L

	private val job = SupervisorJob()

	override val coroutineContext: CoroutineContext
		get() = Dispatchers.Default + job + CoroutineExceptionHandler { _, throwable ->
			logger.d("Coroutine error: ${throwable.message}")
		}

	fun executeInBackground(
		timeOutInMilli: Long = JOB_TIMEOUT,
		codeBlock: suspend () -> Unit,
		errorHandler: ((Throwable) -> Unit)? = null,
	): Job = launch(Dispatchers.IO) {
		try {
			withTimeout(timeOutInMilli) {
				codeBlock()
			}
		} catch (error: Exception) {
			logger.e("Error in executing code in background thread:", error)
			errorHandler?.invoke(error)
		}
	}

	fun <T> executeWithRetry(
		retryCount: Int = DEFAULT_RETRY_COUNT,
		retryDelay: Long = DEFAULT_RETRY_DELAY,
		shouldRetry: (Throwable) -> Boolean = { true },
		codeBlock: suspend () -> T,
		onSuccess: (T) -> Unit = {},
		onFinalError: (Throwable) -> Unit = {}
	): Job = launch {
		var currentRetry = 0
		var lastError: Throwable? = null

		while (currentRetry <= retryCount) {
			try {
				val result = codeBlock()
				withContext(Dispatchers.Main) { onSuccess(result) }
				return@launch
			} catch (error: Exception) {
				lastError = error
				logger.e("Attempt ${currentRetry + 1} failed:", error)

				if (currentRetry == retryCount || !shouldRetry(error)) {
					break
				}

				currentRetry++
				delay(retryDelay)
			}
		}

		lastError?.let { error ->
			withContext(Dispatchers.Main) {
				onFinalError(error)
			}
		} ?: withContext(Dispatchers.Main) {
			onFinalError(Exception("Unknown error after $retryCount retries"))
		}
	}

	suspend fun executeOnMain(codeBlock: suspend () -> Unit) {
		withContext(Dispatchers.Main) {
			codeBlock()
		}
	}

	suspend fun <T> executeOnDefault(codeBlock: suspend () -> T): T {
		return withContext(Dispatchers.Default) {
			codeBlock()
		}
	}

	fun <T> executeAsync(
		backgroundTask: suspend () -> T,
		uiTask: suspend (T) -> Unit
	): Job = launch {
		try {
			val result = withContext(Dispatchers.IO) {
				backgroundTask()
			}
			executeOnMain {
				uiTask(result)
			}
		} catch (error: Exception) {
			logger.e("Error in executing code in async background thread:", error)
		}
	}

	fun <T> executeAsyncWithProgress(
		backgroundTask: suspend ((Int) -> Unit) -> T,
		onProgress: (Int) -> Unit,
		onResult: (T) -> Unit
	): Job = launch {
		try {
			val result = withContext(Dispatchers.IO) {
				backgroundTask { progress ->
					launch(Dispatchers.Main) {
						onProgress(progress)
					}
				}
			}
			executeOnMain {
				onResult(result)
			}
		} catch (error: Exception) {
			logger.e("Error in async operation with progress:", error)
		}
	}

	fun <T> createSafeFlow(block: () -> Flow<T>): Flow<T> {
		return block()
			.flowOn(Dispatchers.IO)
			.catch { error ->
				logger.e("Error in flow:", error)
			}
	}

	fun <T> collectFlowOnLifecycle(
		lifecycleOwner: LifecycleOwner,
		flow: Flow<T>,
		action: (T) -> Unit
	): Job {
		return lifecycleOwner.lifecycleScope.launch {
			flow.collect { value ->
				action(value)
			}
		}
	}

	fun <T> debounced(delayMs: Long, block: (T) -> Unit): (T) -> Unit {
		var job: Job? = null

		return { input: T ->
			job?.cancel()
			job = launch(Dispatchers.Main) {
				delay(delayMs)
				block(input)
			}
		}
	}

	fun cancelAll() = job.cancelChildren()

	fun lifecycleScope(lifecycleOwner: LifecycleOwner) = lifecycleOwner.lifecycleScope
}

fun CoroutineScope.executeInIO(block: suspend CoroutineScope.() -> Unit): Job {
	return launch(Dispatchers.IO) {
		block()
	}
}

suspend fun <T> withMainContext(block: suspend CoroutineScope.() -> T): T {
	return withContext(Dispatchers.Main) {
		block()
	}
}

suspend fun <T> withIOContext(block: suspend CoroutineScope.() -> T): T {
	return withContext(Dispatchers.IO) {
		block()
	}
}