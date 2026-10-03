# TerminalClassifier truth table (T1.5)

Input: the persisted upstream download state (DownloadDataModel), mapped to locale-independent keys, plus the
user intent recorded at the UI seams (T1.6) and the observer's "time since last progress". Output: a verdict and
whether the fallback coordinator may start the next method. The first matching row wins.

| Row | Condition | Verdict | Fallback? |
|---|---|---|---|
| 1 | isDeleted / isRemoved / intent CANCEL, CLEAR, DELETE | UserStopped | no |
| 2 | status COMPLETE or isComplete | Success (a bad current-method file only gets a badge, Q26) | no |
| 3 | isWaitingForNetwork | Waiting(network / Wi-Fi / internet) | no |
| 4 | status DOWNLOADING, progress within 120 s (or unknown) | InProgress | no |
| 4b | status DOWNLOADING, no progress for >= 120 s | Failure STALLED (Regular retries ran out) | yes |
| 5 | intent PAUSE, RENAME_PAUSE, APP_SHUTDOWN | UserStopped | no |
| 6 | isFailedToAccessFile, storage dialog, "File IO", file deleted / destination missing | Failure STORAGE (checked before "expired": a storage FileNotFoundException can look expired) | no |
| 7 | isYtdlpHavingProblem | Failure EXTRACTOR_LOGIN / UNAVAILABLE / FORMAT / HTTP_OR_SERVER / OTHER by reason | yes |
| 8 | isFileUrlExpired or "Link Expired" | Failure EXPIRED_URL (for M3U8 this may really be offline) | yes |
| 9 | "Invalid file URL" / "Download Failed" / "Server Issue" / other texts | Failure INVALID_URL / HTTP_OR_SERVER / ... | yes |
| 9b | "Paused" or no text, no recorded user intent | Failure CRASH_OR_UNKNOWN (error pause, process death) | yes |

Why the user-intent record is required: the upstream code writes the same state for a user pause and for an
M3U8 exception pause (status CLOSE, statusInfo "Paused"), and it never persists who paused. Without the record,
row 9b would start fallbacks after every user pause.

Facts behind each row (file:line, upstream master): see the comments in TerminalClassifierTest.kt and
the T1.5 research summary in PLAN.md (T1.5 result).
