package app.core

import android.os.*
import app.core.AIOTimer.Companion.GC_INTERVAL_MILLIS
import app.core.AIOTimer.Companion.GC_INTERVAL_SECONDS
import kotlinx.coroutines.*
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.IO
import lib.process.*
import java.lang.ref.*
import java.util.concurrent.*

/**
 * An advanced, coroutine-based timer that extends Android's [CountDownTimer] to provide a robust,
 * lifecycle-aware, and efficient ticking mechanism for multiple listeners.
 *
 * This timer is designed to run continuously by automatically restarting itself upon completion.
 * It manages listeners using [WeakReference]s, which prevents memory leaks by allowing listeners
 * to be garbage-collected if they are no longer referenced elsewhere.
 *
 * Key Features:
 * - **Continuous Ticking:** Automatically restarts after finishing, creating an endless loop.
 * - **Weak-Referenced Listeners:** Prevents memory leaks by not holding strong references to listeners.
 * - **Concurrent Listener Notification:** Uses a [CoroutineScope] with a limited parallelism dispatcher
 *   to notify listeners concurrently without overwhelming system resources.
 * - **UI-Safe Callbacks:** Listener callbacks (`onAIOTimerTick`) are executed on the main thread,
 *   making it safe to perform UI updates.
 * - **Fault Tolerance:** A failure in one listener's callback does not affect other listeners or stop the timer,
 *   thanks to the use of a [SupervisorJob].
 * - **Automatic Cleanup:** Periodically cleans its internal list of listeners to remove references
 *   to garbage-collected objects.
 * - **Proactive Garbage Collection:** Triggers `System.gc()` at a configurable interval to help manage memory,
 *   especially in long-running scenarios.
 *
 * @param millisInFuture The number of milliseconds in the future from the call to [start]
 *   until the countdown is done and [onFinish] is called. This also defines the loop duration
 *   before the timer restarts.
 * @param countDownInterval The interval along the way to receive [onTick] callbacks, in milliseconds.
 * @param maxConcurrentListeners The maximum number of listeners that can be notified concurrently.
 *   This helps to control resource usage and prevent the system from being overloaded if many
 */
open class AIOTimer(
	millisInFuture: Long,
	countDownInterval: Long,
	maxConcurrentListeners: Int = 10
) : CountDownTimer(millisInFuture, countDownInterval) {
	
	/** A logging utility for this class, used for debugging and informational messages. */
	private val logger = LogHelperUtils.from(javaClass)
	
	/**
	 * Tracks the number of ticks that have occurred since the timer was started.
	 * This value is incremented at the beginning of each `onTick` call and is
	 * passed to listeners. It is a `Double` to support a very large number of
	 * ticks over the application's lifetime without overflowing.
	 */
	private var loopCount = 0.0
	
	/**
	 * Tracks the timer's state. `true` if the timer has started and is currently ticking,
	 * `false` otherwise. This flag is set to `true` in `onTick` and reset to `false`
	 * only when the timer is explicitly stopped via the `stop()` method.
	 */
	private var hasTimerActive = false
	
	/**
	 * A thread-safe list of weak references to [AIOTimerListener]s.
	 * Using [WeakReference] prevents the timer from holding strong references to listeners,
	 * allowing them to be garbage-collected if no longer used elsewhere.
	 */
	private val timerListeners = CopyOnWriteArrayList<WeakReference<AIOTimerListener>>()
	
	/**
	 * A coroutine dispatcher that limits the number of concurrent coroutines used for listener notifications.
	 * This is configured by `maxConcurrentListeners` to prevent resource exhaustion when notifying
	 * a large number of listeners simultaneously.
	 *
	 * @see [CoroutineDispatcher.limitedParallelism]
	 */
	private val limitedParallelism = Default.limitedParallelism(maxConcurrentListeners)
	
	/**
	 * A dedicated [CoroutineScope] for managing listener notifications.
	 *
	 * This scope uses a [SupervisorJob] to ensure that failures in one listener's coroutine
	 * do not affect others. It also employs a dispatcher with limited parallelism
	 * (configured by `maxConcurrentListeners`) to control the number of listeners
	 * being notified concurrently, preventing resource exhaustion.
	 */
	private val listenerScope = CoroutineScope(SupervisorJob() + limitedParallelism)
	
	/**
	 * The interval, in number of ticks, at which to force garbage collection.
	 * This is calculated based on [GC_INTERVAL_MILLIS] and the [countDownInterval]
	 * provided to the timer. For example, if the garbage collection interval is
	 * 30 seconds and the timer ticks every 1 second, this value will be 30,
	 * meaning garbage collection will be triggered every 30 ticks.
	 *
	 * @see GC_INTERVAL_MILLIS
	 */
	private val gcIntervalTicks: Int = (GC_INTERVAL_MILLIS / countDownInterval).toInt()
	
	companion object {
		
		/**
		 * The base interval, in seconds, at which periodic garbage collection is triggered.
		 * This helps in reclaiming memory from listeners that have been nulled out in their WeakReferences.
		 * This value is used to calculate [GC_INTERVAL_MILLIS].
		 */
		private const val GC_INTERVAL_SECONDS = 30
		
		/**
		 * The interval, in milliseconds, at which a garbage collection (`System.gc()`)
		 * is suggested to the JVM. This is used to periodically clean up expired
		 * `WeakReference`s in the `timerListeners` list, ensuring that memory used by
		 * destroyed listeners is reclaimed promptly.
		 *
		 * @see GC_INTERVAL_SECONDS
		 * @see gcIntervalTicks
		 */
		private const val GC_INTERVAL_MILLIS: Long = GC_INTERVAL_SECONDS * 1000L
	}
	
	/**
	 * Callback fired on each timer tick. This method orchestrates the core logic for each interval.
	 * The responsibilities of this method include:
	 * 1.  Incrementing the internal `loopCount` to track the number of ticks.
	 * 2.  Periodically triggering a garbage collection cycle (`System.gc()`) at an interval defined
	 *     by `gcIntervalTicks`. This is done asynchronously on the IO dispatcher.
	 * 3.  Cleaning up the `timerListeners` list by removing any `WeakReference` whose referent has
	 *     been garbage collected (is `null`).
	 * 4.  Iterating through the registered listeners and notifying each one of the tick event.
	 * 5.  Each listener notification (`listener.onAIOTimerTick(loopCount)`) is dispatched within
	 *     its own coroutine on the Main thread to ensure UI safety.
	 * 6.  Error handling is included within each listener's coroutine to prevent a single failing
	 *     listener from crashing the entire timer process.
	 *
	 * @param millisUntilFinished The amount of time until the timer is finished in milliseconds.
	 * This is a standard parameter from `CountDownTimer` but is not used in this implementation,
	 * as the timer is designed to restart on finish.
	 */
	override fun onTick(millisUntilFinished: Long) {
		loopCount++
		hasTimerActive = true
		logger.d("Tick $loopCount (GC check every $gcIntervalTicks ticks)")
		
		// Periodically trigger garbage collection to manage memory.
		// This helps in reclaiming memory from objects that are no longer referenced,
		// particularly the WeakReferences to listeners that have been nulled out.
		if (loopCount.toInt() % gcIntervalTicks == 0) {
			listenerScope.launch(IO) {
				logger.d("Forcing Garbage Collection via System.gc() at tick $loopCount")
				System.gc()
			}
		}
		
		// Clean up the listeners list: remove any WeakReference whose listener has been garbage collected.
		val iterator = timerListeners.iterator()
		while (iterator.hasNext()) {
			val listenerRef = iterator.next()
			// If the weak reference's get() returns null, the listener object has been GC'd.
			if (listenerRef.get() == null) {
				timerListeners.remove(listenerRef)
			}
		}
		
		timerListeners.forEach { listenerRef ->
			listenerRef.get()?.let { listener ->
				// Retrieve the simple name of the listener's class for logging purposes.
				val classSimpleName = listener.javaClass.simpleName
				
				// Launch a new coroutine for each listener to notify it of the tick.
				// This prevents a slow or misbehaving listener from blocking notifications to others.
				listenerScope.launch {
					try {
						withContext(Dispatchers.Main) { // Switch to the Main thread for UI safety.
							logger.d("Notifying $classSimpleName @ $loopCount")
							listener.onAIOTimerTick(loopCount)
						}
					} catch (error: Exception) {
						logger.e("Error in listener callback: $classSimpleName", error)
					}
				}
			}
		}
	}
	
	/**
	 * Callback fired when the time is up.
	 * This implementation automatically restarts the timer, creating a continuous loop.
	 */
	override fun onFinish() {
		logger.d("Timer finished. Restarting.")
		this.start()
	}
	
	/**
	 * Registers a listener to receive timer tick updates.
	 *
	 * This method adds the provided listener to a list of weak references. This ensures that the
	 * `AIOTimer` does not prevent the listener from being garbage collected if it's no longer
	 * held by any other part of the application. The method prevents duplicate registrations;
	 * if a listener is already registered, it will not be added again.
	 *
	 * @param listener The [AIOTimerListener] instance to be registered.
	 */
	fun register(listener: AIOTimerListener) {
		val classSimpleName = listener.javaClass.simpleName
		if (timerListeners.none { it.get() == listener }) {
			timerListeners.add(WeakReference(listener))
			logger.d("Listener registered: $classSimpleName")
		} else if (timerListeners.any { it.get() == listener }) {
			logger.d("Listener already registered: $classSimpleName")
		}
	}
	
	/**
	 * Unregisters a listener from this timer.
	 *
	 * This method removes the specified listener from the list of subscribers,
	 * so it will no longer receive tick notifications. The removal is based on
	 * object identity. A log message is printed upon successful unregistration.
	 *
	 * @param listener The [AIOTimerListener] instance to remove.
	 */
	fun unregister(listener: AIOTimerListener) {
		val classSimpleName = listener.javaClass.simpleName
		timerListeners.removeIf { it.get() == listener }
		logger.d("Listener unregistered: $classSimpleName")
	}
	
	/**
	 * Stops the timer and cancels all associated coroutines and listeners.
	 *
	 * This function performs the following actions:
	 * 1.  Cancels the underlying `CountDownTimer`, stopping future `onTick` and `onFinish` calls.
	 * 2.  Cancels the `listenerScope`, which terminates all active and pending coroutine jobs
	 *     responsible for notifying listeners.
	 * 3.  Clears the list of `timerListeners`, removing all registered listeners.
	 * 4.  Sets the `hasTimerActive` flag to `false`.
	 *
	 * This effectively performs a hard reset of the timer, releasing all associated resources.
	 */
	fun stop() {
		this.cancel()
		listenerScope.cancel(message = "AIOTimer stopped")
		timerListeners.clear()
		hasTimerActive = false
		logger.d("Timer stopped. Resources cleared.")
	}
	
	/**
	 * Clears the resources used by the timer, specifically the list of listeners.
	 * This method is intended to be called when the timer is not active. If the timer is
	 * currently running (`hasTimerActive` is true), the operation is aborted to prevent
	 * unexpected behavior, and a log message is printed. This ensures that listeners
	 * are not cleared while they might still be needed by an active timer tick.
	 *
	 * To stop the timer and clear all associated resources including the coroutine scope,
	 * use the [stop] method instead.
	 */
	@Deprecated(
		message = "This method is redundant. The stop() function already provides this " +
			"functionality and is the recommended way to clear resources."
	)
	fun clearResources() {
		if (hasTimerActive) {
			logger.d("Timer is active. Stopping and clearing resources.")
			return
		}
		timerListeners.clear()
		logger.d("Timer resources cleared.")
	}
	
	/**
	 * Interface for listeners that want to receive updates from an [AIOTimer].
	 * Implement this interface to perform actions on each timer tick.
	 * Listeners are held by [WeakReference]s in the [AIOTimer], so they are
	 * automatically garbage collected if no other strong references to them exist.
	 */
	interface AIOTimerListener {
		
		fun onAIOTimerTick(loopCount: Double)
	}
}