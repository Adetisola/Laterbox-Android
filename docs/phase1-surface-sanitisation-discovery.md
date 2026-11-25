# Phase 1 Discovery — User-Facing Surface Sanitisation & Force-Update Audit

**Audit Scope:** `Adetisola/Laterbox-Android` (`main`), `Adetisola/Laterbox-Website` (`main` & `control-centre`), and `Adetisola/Laterbox-MovieBox-API` (`main`).  
**Constraint:** Do **not** alter MovieBox resolver logic, HMAC/MD5 signing, endpoint paths, or backend resource-resolution flow (`v1.0.79` is closed). Surface-only copy, UI, deep-link/share metadata, telemetry boundary, and forced release (`v1.0.80`) only.

---

## 1. Android Discovery Table (`Adetisola/Laterbox-Android`)

### A. Settings & Debug UI Surfaces (Phase 2)

| File & Line | Current Behaviour / String | Classification | Planned Action |
| :--- | :--- | :--- | :--- |
| `app/src/main/java/com/laterbox/app/ui/settings/MediaDownloadsSettingsScreen.kt:256-269` | `"Diagnostics"` button opens raw on-screen diagnostics viewer | Debug / Diagnostics UI | Remove `"Diagnostics"` button and dialog; replace with single optional `"Send playback diagnostics to Laterbox"` action |
| `app/src/main/java/com/laterbox/app/ui/settings/MediaDownloadsSettingsScreen.kt:395-486` | `"Media Engine Diagnostics"` `AlertDialog` rendering `getDiagnosticsReportText()` (`MediaNetworkDiagnostics.default.exportText()` with hosts, path shapes, HTTP statuses, ranges, Copy Report, Share) | Raw diagnostic leak on screen | Remove on-screen dialog, Copy, and Share actions; transmit sanitized playback health telemetry silently via `LaterboxAnalyticsTracker` |
| `app/src/main/java/com/laterbox/app/ui/settings/SettingsViewModel.kt:505-525` | `getDiagnosticReport()` / `getDiagnosticsReportText()` wired directly to Settings UI | Debug / Diagnostics helper | Replace UI wiring with `sendPlaybackDiagnostics(onResult)` that emits silent analytics/telemetry without returning hosts/URLs/HTTP codes to UI |
| `app/src/main/java/com/laterbox/app/ui/navigation/LaterboxNavGraph.kt:1794-1795` | Wires `onGetDiagnosticReport` / `onGetDiagnosticReportText` to `MediaDownloadsSettingsScreen` | UI wiring | Wire `onSendPlaybackDiagnostics` instead |
| `app/src/main/java/com/laterbox/app/movies/ui/MovieDetailScreen.kt:2061-2119` | `"Canonical Provider Links (Debug)"` card (`detail_debug_provider_links`) | Internal debug UI | Remove completely from `MovieDetailScreen` UI |
| `app/src/main/java/com/laterbox/app/diagnostics/AppHealthChecker.kt:227, 234-245` | `"Open Settings → Media & Downloads to inspect runtime diagnostics or reinitialize yt-dlp."`, `"FFmpeg media processor failed"`, `"QuickJS runtime not detected"`, `"YouTube signature solving may be slower or incomplete without libqjs.so."` | Technical / diagnostics UI strings | Rewrite to clean neutral user guidance without exposing `diagnostics`, `libqjs.so`, `signature solving`, or internal runtime names |
| `app/src/main/java/com/laterbox/app/ai/AppCapabilityRegistry.kt:429` | `"Configure Gemini API key, Smart Reminders schedules, Movies & TV player/subtitles/endpoints, ..."` | Assistant prompt / capability copy | Replace `"player/subtitles/endpoints"` with `"player and subtitles"` |
| `app/src/main/java/com/laterbox/app/ui/chat/ChatScreen.kt:537` | `"...and app diagnostics—never API keys or private storage paths..."` | Privacy disclosure text | Replace `"app diagnostics"` with `"app status"` |

*Preserved after audit:*
- `YouTubeTranscriptSheet.kt` (`"Technical details"`): Covered explicitly by `YouTubeTranscriptUiTest` (`app/src/test/java/com/laterbox/app/transcript/YouTubeTranscriptUiTest.kt:338, 457`) for Link Saver YouTube transcripts (contains no MovieBox/streaming endpoints or signing keys).

---

### B. Provider Labels, Error Copy & Technical Jargon (Phase 3)

| File & Line | Current User-Facing String | Leak Type | Planned Rewrite |
| :--- | :--- | :--- | :--- |
| `app/src/main/java/com/laterbox/app/movies/domain/model/MoviesModels.kt:26-30` | `DataSourceBadge` labels (`"Live Catalogue"`, `"Internet Archive"`, `"TV Guide"`, `"Film Guide"`, `"Sample Fixture"`) | Provider / resolution-source label | Neutralize UI labels (`"Laterbox"`, `"Archive"`, `"TV Guide"`, `"Film Guide"`, `"Sample"`) or keep badge neutral |
| `app/src/main/java/com/laterbox/app/movies/data/provider/MovieBoxProvider.kt:1676, 1688-1740` | `"Access could not be verified right now..."`, `"Too many requests right now..."`, `"catalogue service"`, `sanitizeDetailForUser` blocklist | Error-mapping copy & filter | Expand `sanitizeDetailForUser` blocklist (`429`, `403`, `401`, `428`, `rate limit`, `throttle`, `cooldown`, `failover`, `cdn`, `host`, `endpoint`, `stub`, `quota`, `diagnostic`, `debug`, `trace`, `signature`, `hmac`, `md5`); keep neutral Laterbox copy |
| `app/src/main/java/com/laterbox/app/movies/data/repository/MoviesRepositoryImpl.kt:3057-3078` | `buildPrimaryFallbackNotice` / `MOVIEBOX_FALLBACK_NOTICE` (`"Main catalogue ... — showing archive & TV guide"`) | Internal multi-provider fallback hint | Keep distinct neutral notices aligned with `MoviesRepositoryTest` (`"Main catalogue is temporarily unavailable..."` / `"Please update Laterbox..."` without provider/technical jargon) |
| `app/src/main/java/com/laterbox/app/movies/ui/MoviePlayerScreen.kt:3036-3044` | `"Playback access expired or needs verification..."`, `"This video link expired..."`, `"Streaming server is temporarily busy (rate-limited). Please try again in ...s."` / `"...shortly."` | `"Streaming server"`, `"(rate-limited)"`, technical expiry jargon | `"Could not start playback right now. Please try again or switch quality."`, `"This video isn't available right now. Please try again or switch quality."`, `"Video playback is temporarily busy. Please try again in ...s."` / `"...shortly."` |
| `app/src/main/java/com/laterbox/app/movies/ui/MoviePlayerViewModel.kt:375-377, 576-584` | `"Catalogue service is temporarily busy..."`, `"Streaming server is temporarily busy (rate-limited)..."`, `"Playback access expired..."` | `"Streaming server"`, `"(rate-limited)"`, `"Catalogue service"` | `"Laterbox is temporarily busy. Please try again in ...s."` / `"...shortly."`; `"Video playback is temporarily busy. Please try again in ...s."` / `"...shortly."`; `"Could not start playback right now. Please try again or switch quality."` |
| `app/src/main/java/com/laterbox/app/media/data/download/HttpDownloader.kt:129-135, 181` | `"Streaming server is rate-limiting downloads (HTTP 429)..."`, `"HTTP ${resp.code}: ${resp.message}"`, `"Invalid HTTP 206 Content-Range or representation mismatch"` | `HTTP 429`, `HTTP 206`, `rate-limiting`, `"Streaming server"` | Clean neutral download copy (preserve internal `httpStatus` and `FailureCode` integers/enums for retry logic!) |
| `app/src/main/java/com/laterbox/app/media/data/download/StreamCacheReuseDownloader.kt:247-272, 429-491` | `"Streaming server is rate-limiting downloads (HTTP 429)..."`, `"HTTP ${resp.code}: ${resp.message}"`, `"HTTP 429: ${resp.message}"` | `HTTP 429`, `HTTP ${resp.code}`, `rate-limiting` | Clean neutral download copy (preserve `httpStatus` / `FailureCode` fields for `DownloadWorker`) |
| `app/src/main/java/com/laterbox/app/media/domain/model/FailureCode.kt:3-18` | `CONNECTION_TIMEOUT`, `EXTRACTION_TIMEOUT`, `PYTHON_RUNTIME_ERROR`, `YOUTUBE_CHALLENGE_ERROR`, `RATE_LIMITED`, `EXTRACTOR_BROKEN`, `HTTP_ERROR` messages | `"Python media runtime"`, `"challenge"`, `"extractor"`, `"HTTP"`, `"Too many requests"` | Neutralize user-facing `userMessage` text |
| `app/src/main/java/com/laterbox/app/ai/SmartDigestEngine.kt:131-133` | `"Rate limit reached (429). Please wait a moment."`, `"Invalid API key or unauthorized ($code)."` | `Rate limit`, `429`, raw HTTP `$code` | `"Assistant is temporarily busy. Please wait a moment and try again."`, `"Your API key could not be verified. Please check it in Settings."` |
| `app/src/main/java/com/laterbox/app/ai/SmartReminderCopy.kt:38` | `"Rate limited by AI provider ($code)"` | `Rate limited`, `$code` | `"Assistant is temporarily busy. Please try again shortly."` |
| `app/src/main/java/com/laterbox/app/ai/speech/SpeechTranscriptionCoordinator.kt:80` | `"Gemini voice quota is temporarily rate-limited. Try again shortly."` | `quota`, `rate-limited` | `"Voice transcription is temporarily busy. Please try again shortly."` |
| `app/src/main/java/com/laterbox/app/update/GitHubUpdateChecker.kt:279, 491, 518` | `"delta-manifest.json download returned HTTP $statusCode"`, `"Update server rate limit reached..."`, `"Update server returned HTTP $responseCode"` | `rate limit`, `HTTP $code`, internal filenames | `"Update checks are temporarily busy. Please try again later."`, `"Could not check for updates right now. Please try again later."` |
| `app/src/main/java/com/laterbox/app/update/AppUpdateDialog.kt:253, 476` | `"Download could not be authorized or rate limit was reached."`, `"(minimum required: $minTagLabel)"` | `rate limit`, `authorized`, min-tag implementation phrasing | `"Update download is temporarily unavailable. Please try again later."`, `"Please update to Laterbox $targetTagLabel to continue using the app."` |
| `app/src/main/java/com/laterbox/app/update/UpdateNetworkSupport.kt:112, 118, 122` | `"Update authorization failed (HTTP $it)..."`, `"The update server is having trouble (HTTP $it)..."`, `"The update download could not be started (HTTP $it)."` | `HTTP $it` | Strip `(HTTP $it)` suffixes from user-facing messages |
| `app/src/main/java/com/laterbox/app/update/ApkInstallerHelper.kt:187-189, 361, 369, 394, 445, 717` | `"Authentication failed...(HTTP $code)"`, `"Update server rate limit reached..."`, `"HTTP redirect without Location header"`, `"Redirect to a disallowed host was blocked."`, `"Download authorization failed or rate limit reached (HTTP $responseCode)."`, `"Unexpected HTTP response code: $responseCode"`, `"Signature mismatch: The update was signed with an unexpected certificate..."` | `HTTP $code`, `rate limit`, `host`, `Signature mismatch`, `certificate` | Rewrite user messages to clean neutral copy (preserve `ApkVerificationResult.SignatureMismatch` sealed class name & log lines for internal safety/tests) |
| `app/src/main/java/com/laterbox/app/transcript/data/YouTubeTranscriptDataSource.kt:839, 848, 856, 1772, 1793, 1817, 2609, 2769` | `"(HTTP 429)"`, `"(HTTP ${response.code})"`, `"rate-limited"` | `HTTP 429`, `HTTP ${code}`, `rate-limited` | Rewrite to clean neutral copy (`"Captions are temporarily unavailable. Please try again shortly."`, etc.) |
| `app/src/main/java/com/laterbox/app/transcript/data/YouTubeTranscriptExtractor.kt:1318, 1377` | `"Rate-limited by YouTube (HTTP 429); retrying..."` | `HTTP 429`, `Rate-limited` | `"Captions are temporarily busy; retrying in ${secs}s..."` |
| `app/src/main/java/com/laterbox/app/transcript/domain/TranscriptModels.kt:20` & `YouTubeUrlParser.kt:94` | `"Rate limited (HTTP 429)"`, `"Missing URL host"` | `HTTP 429`, `Rate limited`, `host` | `"Temporarily busy"`, `"Invalid video web link"` |

*XML Resources (`app/src/main/res/values/strings.xml`):* Scanned clean — zero hits for any forbidden terms.

---

### C. Deep Links, Share URLs & Website (`Adetisola/Laterbox-Website`)

| Surface | Status / Findings | Planned Action |
| :--- | :--- | :--- |
| `LaterboxDeepLinks.kt` & `LaterboxShareHelper.kt` | New shares already emit `/movie/{id}` and `/series/{id}` with `"Watch \"$title\" on Laterbox\n$url"`; inbound `/movie/moviebox/{id}` and `/series/moviebox/{id}` remain backward-compatible. | Keep intact; add static/unit verification that share output contains no `moviebox`. |
| `Laterbox-Website` (`main`: `src/lib/shared-title.ts`, `SharedTitleFallbackView.tsx`, `src/app/movie/**`, `src/app/series/**`) | Already canonicalizes legacy `/movie/moviebox/{id}` to `/movie/{id}` ("Laterbox Movies & TV"). | Clean except `src/lib/releases.ts:412` (`"temporarily rate-limited by GitHub"` -> `"temporarily busy"`). |
| `Laterbox-Website` (`control-centre`: `VersionPolicySection.tsx`) | Already supports `"disabled"`, `"monitor"`, and `"enforce"` modes returning HTTP 426 + non-dismissible Update Required modal. | Keep ready; after `v1.0.80` publishes, set `ENFORCED` with min/target `v1.0.80`. |

---

### D. Backend (`Adetisola/Laterbox-MovieBox-API`)

- **Hard constraint honored:** `api.py` MovieBox resolver, HMAC-MD5 signing, headers, and endpoints will **not** be modified.
- `analytics/routes.py` & `analytics/models.py`: Existing `/analytics/v1/ingest` accepts `app_error_observed` events (`stream_resolution_failed`, `playback_error`, `download_error`, etc.) — suitable for silent `"Send playback diagnostics to Laterbox"` submission from `SettingsViewModel` without touching resolver endpoints.

---

## 2. Force-Update Release Plan (Phase 6 & 7)

1. Execute Phases 2–5 sanitisation edits + unit/UI tests across `Laterbox-Android` (and `Laterbox-Website/src/lib/releases.ts` if needed).
2. Run Android full unit tests, lint, and `assembleRelease` + `aapt2 dump strings` scan over built APK strings to verify zero user-visible leaks.
3. Bump `app/build.gradle.kts` (`versionCode = 80`, `versionName = "1.0.80"`), create and validate `release-notes/v1.0.80.md`, commit cleanly, create annotated tag `v1.0.80`, and push `main` + `v1.0.80`.
4. Verify GitHub Release `v1.0.80` assets (`Laterbox-ARM32.apk`, SHA-256 checksums, manifests) and switch Control Centre / advise user to publish `ENFORCED` version policy targeting `v1.0.80`.
