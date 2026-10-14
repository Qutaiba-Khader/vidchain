package org.websnake.vidchain.app

import app.core.bases.BaseActivity
import app.core.engines.video_parser.parsers.VideoThumbGrabber.startParsingVideoThumbUrl
import app.ui.main.fragments.browser.webengine.SingleResolutionPrompter
import com.aio.R
import lib.networks.URLUtilityKT.fetchWebPageContent
import lib.networks.URLUtilityKT.getWebpageTitleOrDescription
import lib.process.ThreadsUtility
import lib.texts.CommonTextUtils.getText
import org.websnake.vidchain.fallback.trace.Trace
import org.websnake.vidchain.fallback.trace.TraceEvent

/**
 * T9 decision 2 (owner, 2026-10-14): a shared X / Instagram / Facebook / TikTok link opens its picker at once.
 *
 * The original app first fetched the page up to 3 times (then once more each for the picture and the title) and
 * showed "Analyzing URL" until all of it was back. Now the picker opens with a temporary title (the site and
 * "High Quality") and the site's icon; the link's yt-dlp analysis starts at the same moment (the download replays
 * it), and its title and picture fill the picker when they arrive. Only when that analysis gives nothing is the page
 * fetched, once. A download started before the title arrives keeps the temporary one.
 */
object VidChainSocial {
	@JvmStatic
	fun openPicker(activity: BaseActivity, url: String, closeActivityOnSuccessfulDownload: Boolean, cancelable: Boolean): SingleResolutionPrompter {
		VidChainAnalysis.prefetch(url)
		val prompter = SingleResolutionPrompter(
			baseActivity = activity,
			isDialogCancelable = cancelable,
			singleResolutionName = getText(R.string.title_high_quality),
			extractedVideoLink = url,
			currentWebUrl = url,
			videoTitle = null,
			videoUrlReferer = url,
			dontParseFBTitle = true,
			thumbnailUrlProvided = null,
			isSocialMediaUrl = true,
			isDownloadFromBrowser = false,
			closeActivityOnSuccessfulDownload = closeActivityOnSuccessfulDownload,
			deferInfo = true,
		)
		prompter.show()
		Trace.event { TraceEvent("share.social", result = "picker-open") }
		ThreadsUtility.executeInBackground(timeOutInMilli = 90_000, codeBlock = {
			val info = VidChainAnalysis.analyse(url, null, "social")?.info
			if (info != null && !info.title.isNullOrBlank()) {
				prompter.updateInfo(info.title, info.thumbnail)
				return@executeInBackground
			}
			// the analysis gave nothing: one page fetch (no retries) for the title and picture
			val html = fetchWebPageContent(url) ?: return@executeInBackground
			val thumb = startParsingVideoThumbUrl(videoUrl = url, userGivenHtmlBody = html)
			getWebpageTitleOrDescription(websiteUrl = url, userGivenHtmlBody = html) { title -> prompter.updateInfo(title, thumb ?: info?.thumbnail) }
		})
		return prompter
	}
}
