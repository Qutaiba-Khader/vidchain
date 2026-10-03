# Golden twin and fault matrix (T1.3)

Recorded from the unchanged app (CI run 37089218000). Regenerate only with the golden-twin CI job.

## Golden: what the current method does on each fixture

| Fixture | User actions | File(s) | Stacks seen |
|---|---|---|---|
| audio-only | Download Now | audio-only.m4a (36413 B) | okhttp, other, urlconnection, webview |
| cookie-gated | - | none | media, okhttp, webview |
| dash-split | - | none | media, webview |
| direct-mp4 | Download Now | clip.mp4 (429760 B) | media, okhttp, other, urlconnection, webview |
| direct-webm | Download Now | clip.webm (162503 B) | media, okhttp, other, urlconnection, webview |
| drive-interstitial | - | none | webview |
| extensionless | Download Now | clip.mp4 (429760 B) | other, urlconnection, webview |
| hls-aes | Download Now | none | other, yt-dlp |
| hls-plain | Download Now, quality | none | other, yt-dlp |
| html5-video | - | none | media, webview |
| js-injected | - | none | media, webview |
| json-ld | - | none | webview |
| meta-refresh | Download Now | clip.mp4 (429760 B) | media, okhttp, other, urlconnection, webview |
| moov-at-end | Download Now | clip-moov-end.mp4 (429760 B) | media, okhttp, other, urlconnection, webview |
| no-head | Download Now | none | media, okhttp, webview |
| og-video | - | none | webview |
| redirect-chain | Download Now | clip.mp4 (429760 B) | media, okhttp, other, urlconnection, webview |
| referer-gated | - | none | media, okhttp, webview |
| trap-html-as-mp4 | Download Now | fake.mp4 (55 B) | media, okhttp, other, urlconnection, webview |
| trap-truncated | Download Now | truncated.mp4 (429760 B) | media, okhttp, other, urlconnection, webview |
| unknown-length | - | none | media, okhttp, webview |
| video-only | Download Now | video-only.mp4 (392141 B) | media, okhttp, other, urlconnection, webview |

## Fault matrix: what the current method does under each fault

`fault@2` = the first request (the browser sniff) works, the download request and later ones fail.

| Scenario | User actions | File(s) | Download records | Crash logs | Requests | Notable text |
|---|---|---|---|---|---|---|
| direct-mp4~403@2 | Download Now | clip.mp4 (0 B) | 1 | 0 | 19 | Download Available |
| direct-mp4~404@2 | - | none | 0 | 0 | 18 | - |
| direct-mp4~429@2 | - | none | 0 | 0 | 18 | - |
| direct-mp4~500 | - | none | 0 | 0 | 17 | - |
| direct-mp4~500@2 | - | none | 0 | 0 | 18 | - |
| direct-mp4~hang@2 | - | none | 0 | 0 | 8 | - |
| direct-mp4~html@2 | Download Now | clip.mp4 (36 B) | 1 | 0 | 9 | Download Available |
| direct-mp4~reset@2 | - | none | 0 | 0 | 18 | Webpage not available |
| hls-plain~404@2 | - | none | 0 | 2 | 25 | - |
| listed-vimeo~no-route | - | none | 0 | 0 | 27 | - |
| og-video~500 | - | none | 0 | 0 | 2 | - |

## Failure classes observed (input for T1.5)

- **No detection**: the browser sniff finds nothing for og:video, JSON-LD, HTML5 <source>, JS-injected, DASH, cookie/referer-gated and interstitial pages -> fallback targets R/S/L/H/W/M/Y.
- **Detected, never downloaded**: no-HEAD (405) and unknown-length -> the regular downloader depends on HEAD/Content-Length -> fallback O.
- **Silent bad file**: an HTML error page is saved as `clip.mp4` (36 B), the HTML-as-mp4 trap as `fake.mp4` (55 B), a truncated body as a full-size file -> DeliveryVerifier (T1.7) for fallback output; badge only for the current method (Q26).
- **Zero-byte file**: 403 after the sniff leaves `clip.mp4` (0 B) with a download record -> terminal error the classifier must see.
- **Crash**: HLS segment 404 writes crash logs -> classifier must treat the crashed job as failed; bug noted for upstream behaviour.
- **Tight retry loop**: the title fetch of a listed site retries a 404 without backoff (seen in T1.1: thousands of requests per minute).
- **Stale intercept flag**: after a browser hand-off later shares are ignored until the app restarts (T1.1).
