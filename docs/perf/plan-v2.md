# Laterbox Android & Backend Performance Audit and Optimization Plan (v2)

## Goal Description

Conduct a measurement-driven, 6-phase performance optimization pass targeting `Adetisola/Laterbox-Android` (primary) and its API contract touchpoints in `Adetisola/Laterbox-MovieBox-API`, engineered specifically for the **Samsung Galaxy A02s (SM-A025F, Android 12 / API 31, ARM32 `armeabi-v7a`, ~2 GB RAM)**.

**Hard Constraints Preserved:**
- **Zero edits** to `MovieBoxNativeStreamResolver.kt`, MovieBox signing, or resource-resolution flow.
- **No weakening** of Firebase App Check / Play Integrity (`LaterboxApplication.kt`, `LaterboxBackendAuth.kt`, `security.py`).
- **No media byte proxying** through the backend; direct OkHttp/Media3 streaming and offline playback/downloads/Continue Watching remain intact.
- **Zero breaking changes to `v1.0.79`** and older compatible Android installs on any backend endpoint.
- **Work on `main`** with checkpoint commits pushed to `origin/main` after each phase.
- **Never claim an unmeasured performance win**: mark A02s on-device metrics as **Pending (A02s)** until measured on hardware; report **Measured (VM proxy)** separately.

---

## §A Revisions Applied & Pre-Phase-2 Gate

1. **A1 (Device-Only Baseline Profile)**: No hand-authored `baseline-prof.txt` or `startup-prof.txt` rules. `:baselineprofile` (`com.android.test`) + `BaselineProfileGenerator` + `ProfileVerifier` are built and committed, while profile generation is deferred to the local A02s (or matching API 31 ARM32 device/emulator) via `./tools/perf/run_a02s_benchmarks.sh`; `baseline-prof.txt` is not committed until generated on-device.
2. **A2 (Additive-Safe Slimming for `v1.0.79` Compatibility)**: Verified against tag `v1.0.79` (`git show v1.0.79:.../MovieBoxProvider.kt`). See decision table below.
3. **A3 (`/detail/{subject_id}` & Metadata `Cache-Control` Audit)**: Zero user-scoped fields in metadata responses, but endpoints require App Check / Guest Auth (`require_api_auth`). Use `Cache-Control: private` (never `public`) so OkHttp caches on-device without Vercel Edge CDN bypassing App Check.
4. **A4 (Bounded Stale-While-Revalidate)**: Add `MAX_STALE_AGE_MS = 24L * 60 * 60 * 1000L` (24h). Entries older than `MAX_STALE_AGE_MS` show loading shell and fetch fresh; entries between `CACHE_TTL_MS` and `MAX_STALE_AGE_MS` render immediately and refresh in background.
5. **A5 (Honest Measured vs Pending Split)**: VM lacks attached A02s (`adb devices` empty) and `/dev/kvm`. Phase 3/6 report **Measured (VM proxy)** vs **Pending (A02s)** separately; user asked about `adb over Tailscale` / `scrcpy` before Phase 2/3.
6. **A6 (Subtitle & Stream Independence Audit)**: Verified `MovieBoxProvider.kt:1140-1342` and `api.py:2725-2785`. Native path (`MovieBoxNativeStreamResolver`) does not populate backend `_stream_id_cache`, so `getSubtitles()` is 100% independent and safe to parallelize with native resolution. Backend fallback `/api/stream/{id}` populates `_stream_id_cache`, so fallback `getSubtitles()` remains sequential.
7. **A7 (Honest Compose Stability)**: No `@Immutable` on classes holding `kotlin.collections.List`. Use `composeCompiler { stabilityConfigurationFile }` or `ImmutableList` and verify Compose compiler metrics.
8. **A8 (Deterministic Splash Gate)**: Gate splash on `prefsLoaded || elapsed >= 800L` (real DataStore signal + 800ms safety ceiling) and log `prefsLoaded` latency distribution.
9. **A9 (Non-Blocking Maintenance & Policy Cache)**: Cache maintenance/version policy in-memory with `15s` soft TTL + background refresh (propagating admin flips within 30s) so `enforce_maintenance_mode` never blocks hot requests.
10. **A10 (Additional Instrumentation & Audits)**: `JankStats`, `ProfileVerifier`, Room `EXPLAIN QUERY PLAN` audit + indexes (`MIGRATION_20_21`), Navigation `saveState`/`restoreState`, `app-release.apk` `<= 5%` size target, and explicit `ConnectionPool(8, 5, TimeUnit.MINUTES)` shared by `nativeOkHttpClient`.

---

## Pre-Execution Evidence Audits (`A2`, `A3`, `A6`, `A10`)

### A2 — Field-Consumption Decision Table (`v1.0.79` vs `main` vs `Laterbox-MovieBox-API`)

`diff -u` of `MovieBoxProvider.kt` between git tag `v1.0.79` and `main` confirmed **zero JSON field-parsing differences** (`NO_JSON_PARSING_DIFF_BETWEEN_1.0.79_AND_MAIN`).

| Endpoint | Field(s) | Read by `v1.0.79`? | Read by `main`? | Decision | Notes |
|---|---|---|---|---|---|
| `/home` | `data.operatingList[]` (`type`, `title`, `subTitle`, `description`, `opId`, `banner.items[]`, `subjects[]`) | Yes | Yes | **Keep** | Core feed structure. |
| `/home` | `data.genres` | Yes | Yes | **Keep** | Genre filter pills. |
| `/home` | `subjectId`, `subjectType`, `title`, `detailPath`, `cover {url, blurHash}`, `stills {url, blurHash}`, `image {url, blurHash}`, `releaseDate`, `duration`, `imdbRatingValue`, `genre`, `language`, `countryName`, `description`, `imdbId`, `hasResource`, `trailer.videoAddress.url`, `url` | Yes | Yes | **Keep** | Parsed by `getHomeFeed()` & `parseSubjectObject()`. |
| `/home` | Raw upstream extras (`ops`, `corner`, `postTitle`, `subtitles`, `imdbRatingCount`, `resourceDetial`, `staffList`, `delay`, `appointment`, `cover.width`/`height`/`avgHue*`, `stills.width`/`height`/`avgHue*`, `trailer.cover`, `trailer.videoAddress.videoId`/`codecName`/`size`) | **No** | **No** | **Drop** | Dead upstream bloat forwarded verbatim by `_filter_home_payload()`. |
| `/movies`, `/tv-series` | `page`, `per_page`, `total`, `items[]` (`title`, `subject_id`, `subject_type`, `detail_path`, `cover`, `stills {url, blur_hash}`, `rating`, `year`, `release_date`, `duration`, `language`, `genre`, `country`) | Yes | Yes | **Keep** | Parsed by `getCatalogue()`. |
| `/movies`, `/tv-series` | `items[].cover_width`, `items[].cover_height`, `items[].stills.width`, `items[].stills.height` | **No** | **No** | **Drop** | Never parsed by `v1.0.79` or `main`. |
| `/search` | `results[]` (`subject_id`, `title`, `type`, `subject_type`, `release_date`, `duration`, `imdb_rating`, `genre`, `country`, `cover`, `stills {url, blur_hash}`, `detail_path`, `language`) | Yes | Yes | **Keep** | Parsed by `search()`. |
| `/search` | `results[].stills.width`, `results[].stills.height` | **No** | **No** | **Drop** | Never parsed by `v1.0.79` or `main`. |
| `/detail/{subject_id}` | `subject_id`, `title`, `subject_type`, `type`, `description`, `release_date`, `duration`, `duration_sec`, `imdb_rating`, `imdb_id`, `genre`, `country`, `language`, `cover`, `cover_blur_hash`, `stills {url, blur_hash}`, `detail_path`, `trailer`, `trailer_url`, `intro_*_ms`, `recap_*_ms`, `dubs[]`, `stars[]`, `seasons[]`, `related[]` | Yes | Yes | **Keep** | `duration_sec` is parsed by `parseSummaryDurationMinutes`; `trailer.videoAddress` is fallback + tested in `test_details_and_staff.py`. |
| `/detail/{subject_id}` | `cover_width`, `cover_height`, `trailer.duration_sec`, `stars[].detail_path` | **No** | **No** | **Defer** | Asserted by existing API contract tests (`tests/test_details_and_staff.py`); keep for additive safety. |
| `/detail/{subject_id}` | `stills.width`/`height`, `related[].cover_width`/`cover_height`, `related[].stills.width`/`height` | **No** | **No** | **Drop** | Never parsed by `v1.0.79`, `main`, or API tests. |
| `/episodes/{subject_id}` | `subject_id`, `seasons[]` (`season`, `episode_count`, `all_ep`, `resolutions`) | Yes | Yes | **Keep** | Parsed by `getSeasons()`. |
| `/share/v1/title/{subject_id}` | All fields (`cover_width`, `cover_height`, etc.) | Website | Website | **Keep** | Consumed by `Laterbox-Website`. |

### A3 — `/detail/{subject_id}` & Metadata Endpoint User-Scoping + `Cache-Control` Audit

- **User-Scoping**: None of `/home`, `/movies`, `/tv-series`, `/search`, `/search/suggest`, `/detail/{subject_id}`, `/episodes/{subject_id}`, `/episodes/{subject_id}/metadata`, `/episodes/{subject_id}/sizes`, or `/staff/{staff_id}/titles` contain user-scoped fields (watchlist, playback progress, and history reside exclusively in local Room).
- **Security Interaction**: Because all these endpoints enforce `Depends(security.require_api_auth)` (App Check / Guest Auth / Version Gate HTTP 426), returning `Cache-Control: public` would risk Vercel Edge CDN caching authenticated responses and bypassing App Check.
- **Policy**:
  - `/home`, `/movies`, `/tv-series`, `/detail/{subject_id}`, `/staff/{staff_id}/titles`: `Cache-Control: private, max-age=120, stale-while-revalidate=300`
  - `/episodes/{subject_id}`, `/episodes/{subject_id}/metadata`: `Cache-Control: private, max-age=60, stale-while-revalidate=120`
  - `/search/suggest`: `Cache-Control: private, max-age=30`
  - `/api/stream/*`, `/episodes/{subject_id}/sizes`: `Cache-Control: no-store`

### A10 — Room v20 `EXPLAIN QUERY PLAN` Findings

Verified in SQLite 3.50.6 against `app/schemas/.../LaterboxDatabase/20.json`:
- `observeIsInWatchlist`: `LIST SUBQUERY 1 -> SCAN l` (full table scan on `media_provider_links` due to string concatenation `(l.provider_id || ':' || l.provider_title_id) = :compositeId`).
- `observeContinueWatching`: scans low-selectivity `index_movie_watch_history_hidden_from_continue_watching` + `USE TEMP B-TREE FOR ORDER BY`.
- `getHistoryListForTitle` & `getWatchHistoryByCanonical`: scans low-selectivity `index_movie_watch_history_is_trailer` + `USE TEMP B-TREE FOR ORDER BY`.

---

## Phased Execution Plan

- **Phase 1 (Baseline Infrastructure)**: `:benchmark` module (`StartupBenchmark`, `ScrollBenchmark`, `DetailLoadBenchmark`), `benchmark` buildType, `testTagsAsResourceId = true` on `LaterboxApp`, `NetworkWaterfallLogger` instrumentation, `tools/perf/run_a02s_benchmarks.sh`, `docs/perf/baseline-metrics.md` (VM proxy measurements + backend `perf_harness.py`).
- **Phase 2 (Startup, Pending Re-Review Gate)**: `:baselineprofile` module + `BaselineProfileGenerator` (profile `.txt` generated only on A02s), `ProfileVerifier` logging, deterministic DataStore splash gate (`prefsLoaded || elapsed >= 800L`), deferred non-frame startup I/O, destination-scoped ViewModels with `saveState`/`restoreState` navigation preservation.
- **Phase 3 (UI & Compose)**: Stability configuration / `ImmutableList`, `JankStats` integration, `remember(...)` on `renderSlots` & related filter, `contentType` on all lazy lists, lazy `DropdownMenu`, gradient scrim replacing `.blur()` on scrolling cards/API<=31, bounded Coil `RGB_565` decode + shared `OkHttpClient`. Marked *code-complete, verification pending A02s* unless connected via ADB/Tailscale.
- **Phase 4 (Network & Room)**: Shared 40 MB OkHttp `Cache` + `ConnectionPool(8, 5, TimeUnit.MINUTES)` (inherited by `nativeOkHttpClient`), native-only parallel `getSubtitles()`, bounded `MAX_STALE_AGE_MS` (24h) stale-while-revalidate + Room `LruCache`, Room `MIGRATION_20_21` indexes + `observeIsInWatchlist` fix.
- **Phase 5 (Backend Touchpoints)**: `GZipMiddleware(minimum_size=1000)`, A2 additive-safe payload projection on `/home`/`/movies`/`/tv-series`/`/search`, A3 `Cache-Control: private` headers, A9 15s background-refreshed policy cache, parallel upstream detail fetches, async route audit, Vercel `sin1` deploy + `v1.0.79` compatibility check.
- **Phase 6 (Verification & Report)**: Full Android & pytest suites, `app-release.apk` size check (`<= 5%` target), Measured (VM proxy) vs Pending (A02s) comparison report.
