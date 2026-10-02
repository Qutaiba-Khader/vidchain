package lib.process

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import androidx.core.net.toUri

object IntentHelperUtils {

	private val logger = LogHelperUtils.from(javaClass)

	@JvmStatic
	fun getMatchingActivities(activity: Activity?, intent: Intent?): List<ResolveInfo> {
		if (intent == null || activity == null) return emptyList()
		return activity.packageManager.queryIntentActivities(intent, 0)
	}

	@JvmStatic
	fun getIntentData(activity: Activity?): String? {
		val intent = activity?.intent
		val action = intent?.action

		return when (action) {
			Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
			Intent.ACTION_VIEW -> intent.dataString
			else -> null
		}
	}

	@JvmStatic
	fun canHandleIntent(activity: Activity?, intent: Intent?): Boolean {
		if (intent == null || activity == null) return false
		val activities = activity.packageManager.queryIntentActivities(intent, 0)
		return activities.isNotEmpty()
	}

	@JvmStatic
	fun startActivityIfPossible(activity: Activity?, intent: Intent?): Boolean {
		if (intent == null || activity == null) return false
		return if (canHandleIntent(activity, intent)) {
			activity.startActivity(intent)
			true
		} else {
			false
		}
	}

	@JvmStatic
	fun getPackageNameForIntent(activity: Activity?, intent: Intent?): String {
		if (intent == null || activity == null) return ""
		val packageManager = activity.packageManager
		val activities = packageManager.queryIntentActivities(intent, 0)
		return if (activities.isNotEmpty()) {
			activities[0].activityInfo.packageName
		} else {
			""
		}
	}

	@JvmStatic
	fun openFacebookApp(
		context: Context,
		targetUrl: String? = "https://www.facebook.com",
		onError: (() -> Unit)? = null
	): Boolean {
		return try {
			val uri = (targetUrl ?: "https://www.facebook.com").toUri()
			val intent = Intent(Intent.ACTION_VIEW, uri).apply {
				setPackage("com.facebook.katana")
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}

			if (intent.resolveActivity(context.packageManager) != null) {
				context.startActivity(intent)
				true
			} else {
				onError?.invoke()
				false
			}
		} catch (error: Exception) {
			onError?.invoke()
			false
		}
	}

	@JvmStatic
	fun openYouTubeApp(
		context: Context,
		videoOrChannelUrl: String? = "https://www.youtube.com",
		onError: (() -> Unit)? = null
	) {
		try {
			val uri = (videoOrChannelUrl ?: "https://www.youtube.com").toUri()
			val intent = Intent(Intent.ACTION_VIEW, uri).apply {
				setPackage("com.google.android.youtube")
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}

			if (intent.resolveActivity(context.packageManager) != null) {
				context.startActivity(intent)
			} else {
				onError?.invoke()
			}
		} catch (error: Exception) {
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openInstagramApp(
		context: Context,
		profileUrl: String? = "http://instagram.com",
		onError: (() -> Unit)? = null
	) {
		try {
			val uri = (profileUrl ?: "http://instagram.com").toUri()
			val intent = Intent(Intent.ACTION_VIEW, uri).apply {
				setPackage("com.instagram.android")
				addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			}

			if (intent.resolveActivity(context.packageManager) != null) {
				context.startActivity(intent)
			} else {
				onError?.invoke()
			}
		} catch (error: Exception) {
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openWhatsappApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.whatsapp"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening whatsapp:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openYouTubeMusicApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.google.android.apps.youtube.music"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening youtube music:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openSoundCloudApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.soundcloud.android"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening soundcloud:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openPinterestApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.pinterest"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening pinterest:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openTikTokApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.zhiliaoapp.musically"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening tiktok:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openDailymotionApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.dailymotion.dailymotion"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening dailymotion:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openRedditApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.reddit.frontpage"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening reddit:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openXApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.twitter.android"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening twitter:", error)
			onError?.invoke()
		}
	}

	@JvmStatic
	fun openTedTalksApp(context: Context, onError: (() -> Unit)? = null) {
		try {
			val packageManager = context.packageManager
			val packageName = "com.ted.android"
			val intent = packageManager.getLaunchIntentForPackage(packageName)
			if (intent != null) {
				intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				context.startActivity(intent)
			} else onError?.invoke()
		} catch (error: Exception) {
			logger.e("Error in opening ted-talk:", error)
			onError?.invoke()
		}
	}
}
