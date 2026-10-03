package org.websnake.vidchain.hosts

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Recorded pages stand in for the sites; no network. */
class FileHostsTest {
	private val fetched = ArrayList<String>()
	private fun pages(vararg map: Pair<String, Page>): suspend (String) -> Page = { url ->
		fetched += url
		map.firstOrNull { url.startsWith(it.first) }?.second ?: Page(404, url, "not recorded", "text/html")
	}
	private fun resolve(url: String, vararg map: Pair<String, Page>) = runBlocking { FileHosts.resolve(url, pages(*map)) }

	@Test fun dropboxAsksForTheFileAndKeepsRlkey() {
		assertEquals(HostResult.Direct("https://www.dropbox.com/scl/fi/abc123/clip.mp4?rlkey=XYZ&dl=1", fileName = "clip.mp4"),
			resolve("https://www.dropbox.com/scl/fi/abc123/clip.mp4?rlkey=XYZ&dl=0"))
		assertTrue(resolve("https://www.dropbox.com/sh/folder/x") is HostResult.Failed)
		assertTrue(fetched.isEmpty())
	}

	@Test fun pixeldrain() {
		assertEquals(HostResult.Direct("https://pixeldrain.com/api/file/AbCd1234?download"), resolve("https://pixeldrain.com/u/AbCd1234"))
		assertTrue(resolve("https://pixeldrain.com/l/List9999") is HostResult.Failed)
	}

	@Test fun googleDriveSmallFileLargeFileAndPrivateFile() {
		val id = "1AbCdEfGhIjKlMnOp"
		val small = resolve("https://drive.google.com/file/d/$id/view?usp=sharing",
			"https://drive.usercontent.google.com/download?id=$id" to Page(200, "https://drive.usercontent.google.com/download?id=$id&export=download", "\u0000\u0001", "video/mp4"))
		assertEquals(HostResult.Direct("https://drive.usercontent.google.com/download?id=$id&export=download"), small)
		val warning = """<html><body>Google Drive can't scan this file for viruses.
			<form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
			<input type="hidden" name="id" value="$id"><input type="hidden" name="export" value="download">
			<input type="hidden" name="confirm" value="t"><input type="hidden" name="uuid" value="5f0e-uuid"></form></body></html>"""
		val large = resolve("https://drive.google.com/open?id=$id",
			"https://drive.usercontent.google.com/download?id=$id" to Page(200, "https://drive.usercontent.google.com/download?id=$id&export=download", warning, "text/html; charset=utf-8"))
		assertEquals(HostResult.Direct("https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t&uuid=5f0e-uuid"), large)
		val private = resolve("https://drive.google.com/uc?id=$id",
			"https://drive.usercontent.google.com/download?id=$id" to Page(200, "https://accounts.google.com/v3/signin/identifier?continue=x", "<html>Sign in</html>", "text/html"))
		assertTrue(private is HostResult.NeedsLogin)
		assertTrue(resolve("https://drive.google.com/drive/folders/1xyz") is HostResult.Failed)
	}

	@Test fun mediafirePlainAndScrambledLinks() {
		val page = "https://www.mediafire.com/file/k3y/clip.mp4/file"
		val plain = resolve(page, page to Page(200, page, """<a class="input popsok" aria-label="Download file" href="https://download1584.mediafire.com/abc/k3y/clip.mp4" id="downloadButton">""", "text/html"))
		assertEquals(HostResult.Direct("https://download1584.mediafire.com/abc/k3y/clip.mp4"), plain)
		val enc = Base64.getEncoder().encodeToString("https://download2.mediafire.com/zz/k3y/clip.mp4".toByteArray())
		val scrambled = resolve(page, page to Page(200, page, """<a id="downloadButton" href="javascript:void(0)" data-scrambled-url="$enc">""", "text/html"))
		assertEquals(HostResult.Direct("https://download2.mediafire.com/zz/k3y/clip.mp4"), scrambled)
		assertTrue(resolve(page, page to Page(200, page, "<div class='g-recaptcha'></div>", "text/html")) is HostResult.NeedsLogin)
		assertTrue(resolve(page, page to Page(404, page, "File Removed", "text/html")) is HostResult.Failed)
	}

	@Test fun oneDrive() {
		val share = "https://1drv.ms/v/s!AqXyZ123"
		val token = "u!" + Base64.getUrlEncoder().withoutPadding().encodeToString(share.toByteArray())
		assertEquals(HostResult.Direct("https://api.onedrive.com/v1.0/shares/$token/root/content"), resolve(share))
		assertTrue(resolve("https://contoso.sharepoint.com/:v:/g/x") is HostResult.NeedsLogin)
	}

	@Test fun otherLinksAreNotMine() {
		assertEquals(HostResult.NotMine, resolve("https://example.org/video.mp4"))
		assertEquals(HostResult.NotMine, resolve("not a url"))
	}
}
