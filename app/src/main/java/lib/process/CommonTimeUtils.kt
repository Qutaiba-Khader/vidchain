package lib.process

import android.os.CountDownTimer
import java.lang.ref.WeakReference

object CommonTimeUtils {

	private val logger = LogHelperUtils.from(javaClass)

	@JvmStatic
	fun delay(timeInMile: Int, listener: OnTaskFinishListener): CountDownTimer {
		val safeTaskRef = WeakReference(listener)
		return object : CountDownTimer(timeInMile.toLong(), timeInMile.toLong()) {
			override fun onTick(millisUntilFinished: Long) = Unit

			override fun onFinish() {
				safeTaskRef.get()?.afterDelay()
			}
		}.start()
	}

	@JvmStatic
	fun startCountDown(
		totalTime: Long,
		interval: Long,
		listener: OnCountDownListener
	): CountDownTimer {
		val safeTaskRef = WeakReference(listener)
		return object : CountDownTimer(totalTime, interval) {
			override fun onTick(millisUntilFinished: Long) {
				safeTaskRef.get()?.onTick(millisUntilFinished)
			}

			override fun onFinish() {
				safeTaskRef.get()?.onFinish()
			}
		}.start()
	}

	@JvmStatic
	fun setInterval(interval: Long, listener: OnIntervalListener): CountDownTimer {
		val safeTaskRef = WeakReference(listener)
		return object : CountDownTimer(Long.MAX_VALUE, interval) {
			override fun onTick(millisUntilFinished: Long) {
				safeTaskRef.get()?.onInterval()
			}

			override fun onFinish() = Unit
		}.start()
	}

	@JvmStatic
	fun startStopWatch(interval: Long, listener: OnStopWatchListener): CountDownTimer {
		val safeTaskRef = WeakReference(listener)
		return object : CountDownTimer(Long.MAX_VALUE, interval) {
			private val startTime: Long = System.currentTimeMillis()

			override fun onTick(millisUntilFinished: Long) {
				val elapsedTime = System.currentTimeMillis() - startTime
				safeTaskRef.get()?.onTick(elapsedTime)
			}

			override fun onFinish() = Unit
		}.start()
	}

	@JvmStatic
	fun cancelTimer(timer: CountDownTimer?) {
		timer?.cancel()
	}

	interface OnTaskFinishListener {
		fun afterDelay()
	}

	interface OnCountDownListener {
		fun onTick(millisUntilFinished: Long)
		fun onFinish()
	}

	interface OnIntervalListener {
		fun onInterval()
	}

	interface OnStopWatchListener {
		fun onTick(elapsedTime: Long)
	}
}