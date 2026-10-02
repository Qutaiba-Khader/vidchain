package lib.process

import android.os.CountDownTimer
import java.lang.ref.WeakReference

class SimpleTimerUtils(
	private var millisInFuture: Long,
	private val countDownInterval: Long
) {

	var timeRemaining: Long = millisInFuture
	var isRunning: Boolean = false
	var isPaused: Boolean = false

	private var weakListener: WeakReference<TimerListener>? = null
	private var countDownTimer: CountDownTimer? = null

	private fun createAndStartTimer() {
		countDownTimer = object : CountDownTimer(timeRemaining, countDownInterval) {
			override fun onTick(millisUntilFinished: Long) {
				timeRemaining = millisUntilFinished
				this@SimpleTimerUtils.onTick(millisUntilFinished)
				weakListener?.get()?.onTick(millisUntilFinished)
			}

			override fun onFinish() {
				this@SimpleTimerUtils.onFinish()
				weakListener?.get()?.onFinish()

				timeRemaining = millisInFuture
				isRunning = false
				isPaused = false
				countDownTimer = null
			}
		}
		countDownTimer?.start()
	}

	fun start() {
		if (isRunning) return
		countDownTimer?.cancel()

		if (isPaused) isPaused = false
		else timeRemaining = millisInFuture

		isRunning = true
		createAndStartTimer()
	}

	fun pause() {
		if (isRunning && !isPaused) {
			countDownTimer?.cancel()
			countDownTimer = null
			isPaused = true
			isRunning = false
		}
	}

	fun resume() {
		if (isPaused) start()
	}

	fun cancel() {
		countDownTimer?.cancel()
		countDownTimer = null
		isRunning = false
		isPaused = false
		timeRemaining = millisInFuture
	}

	fun updateTime(millisInFuture: Long) {
		val wasRunning = isRunning || isPaused
		cancel()

		this.millisInFuture = millisInFuture
		this.timeRemaining = millisInFuture

		if (wasRunning) {
			start()
		}
	}

	fun onTick(millisUntilFinished: Long) = Unit

	fun onFinish() = Unit

	fun setTimerListener(timerListener: TimerListener?) {
		this.weakListener = if (timerListener != null) {
			WeakReference(timerListener)
		} else {
			null
		}
	}

	interface TimerListener {
		fun onTick(millisUntilFinished: Long)
		fun onFinish()
	}
}