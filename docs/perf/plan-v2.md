# Laterbox Android & Backend Performance Audit and Optimization Plan (v2)

## Goal Description

Conduct a measurement-driven, 6-phase performance optimization pass targeting `Adetisola/Laterbox-Android` (primary) and its API contract touchpoints in `Adetisola/Laterbox-MovieBox-API`, engineered specifically for the **Samsung Galaxy A02s (SM-A025F, Android 12 / API 31, ARM32 `armeabi-v7a`, ~2 GB RAM)**.

**Hard Constraints Preserved:**
- **Zero edits** to `MovieBoxNativeStreamResolver.kt`, MovieBox signing, or resource-resolution flow.
- **No weakening** of Firebase App Check / Play Integrity (`LaterboxApplication.kt`, `LaterboxBackendAuth.kt`, `security.py`).
- **No media byte proxying** through the backend; direct OkHttp/Media3 streaming and offline playback/downloads/Continue Watching remain intact.
- **Zero breaking changes to `v1.0.80` (latest release, `versionCode = 80`), `v1.0.79`,** and older compatible Android installs on any backend endpoint.
- **Work on `main`** with checkpoint commits pushed to `origin/main` after each phase.
- **Never claim an unmeasured performance win**: mark A02s on-device metrics as **Pending (A02s)** until measured on hardware; report **Measured (VM proxy)** separately.

---

## §A Revisions Applied & Pre-Phase-2 Gate

1. **A1 (Device-Only Baseline Profile)**: No hand-authored `baseline-prof.txt` or `startup-prof.txt` rules. `:baselineprofile` (`com.android.test`) + `BaselineProfileGenerator` + `ProfileVerifier` are built and committed, while profile generation is deferred to the local A02s (or matching API 31 ARM32 device/emulator) via `./tools/perf/run_a02s_benchmarks.sh`; `baseline-prof.txt` is not committed until generated on-device.
2. **A2 (Additive-Safe Slimming for `v1.0.80` Compatibility)**: Verified against tag `v1.0.80` (`git show v1.0.80:.../MovieBoxProvider.kt`). See decision table below.
3. **A3 (`/detail/{subject_id}` & Metadata `Cache-Control` Audit)**: Zero user-scoped fields in metadata responses, but endpoints require App Check / Guest Auth (`require_api_auth`). Use `Cache-Control: private` (never `public`) so OkHttp caches on-device without Vercel Edge CDN bypassing App Check.
4. **A4 (Bounded Stale-While-Revalidate)**: Centralize per-endpoint `StaleAge` TTLs (24h for `/home`, `/movies`, `/tv-series`, `/detail`, `/episodes`, `/episodes/metadata`, `/staff/{id}/titles`; 6h for `/search`; 1h for `/search/suggest`). Entries older than endpoint `maxStaleAgeMs` show loading shell and fetch fresh; entries between `CACHE_TTL_MS` and `maxStaleAgeMs` render immediately and refresh in background.
5. **A5 (Honest Measured vs Pending Split)**: VM lacks attached A02s (`adb devices` empty) and `/dev/kvm`. A02s is user-executed locally via `tools/perf/run_a02s_benchmarks.sh` after Phase 6 ships. Antigravity does not execute A02s steps. All on-device metrics stay **Pending (A02s)** alongside **Measured (VM proxy)** numbers.
6. **A6 (Subtitle & Stream Independence Audit)**: Verified `MovieBoxProvider.kt:1140-1342` and `api.py:2725-2785`. Native path (`MovieBoxNativeStreamResolver`) does not populate backend `_stream_id_cache`, so `getSubtitles()` is 100% independent and safe to parallelize with native resolution. Backend fallback `/api/stream/{id}` populates `_stream_id_cache`, so fallback `getSubtitles()` remains sequential.
7. **A7 (Honest Compose Stability)**: No `@Immutable` on classes holding `kotlin.collections.List`. Use `composeCompiler { stabilityConfigurationFiles; metricsDestination; reportsDestination }` or `ImmutableList` and verify Compose compiler metrics.
8. **A8 (Deterministic Splash Gate)**: Gate splash on `!prefsLoaded && elapsed < 800L` (real DataStore signal + 800ms safety ceiling) and log `prefsLoaded` latency distribution (`Log.d("LaterboxSplash", ...)`).
9. **A9 (Non-Blocking Maintenance & Policy Cache)**: Cache maintenance/version policy in-memory with `15s` soft TTL + background refresh (propagating admin flips within 30s) so `enforce_maintenance_mode` never blocks hot requests.
10. **A10 (Additional Instrumentation & Audits)**: `JankStats` (`benchmarkImplementation` only), `ProfileVerifier`, Room `EXPLAIN QUERY PLAN` audit + indexes (`MIGRATION_20_21`), Navigation `saveState`/`restoreState`, `app-release.apk` (or `assembleBenchmark` proxy) `<= 5%` size target, and explicit `ConnectionPool(8, 5, TimeUnit.MINUTES)` shared by `nativeOkHttpClient`.

---

## Pre-Execution Evidence Audits (`A2`, `A3`, `A6`, `A10`)

### A2 — Field-Consumption Decision Table (`v1.0.80` vs `main` vs `Laterbox-MovieBox-API`)

`git show v1.0.80:app/src/main/java/com/laterbox/app/movies/data/provider/MovieBoxProvider.kt` vs `main` confirmed **zero JSON field-parsing differences** (`NO_JSON_PARSING_DIFF_BETWEEN_1.0.80_AND_MAIN`; the only Phase-1 instrumentation change on `main` is `NetworkWaterfallLogger` spans).

| Endpoint | Field(s) | Read by `v1.0.80`? | Read by `main`? | Decision | Notes |
|---|---|---|---|---|---|
| `/home` | `data.operatingList[]` (`type`, `title`, `subTitle`, `description`, `opId`, `banner.items[]`, `subjects[]`) | Yes | Yes | **Keep** | Core feed structure. |
| `/home` | `data.genres` | Yes | Yes | **Keep** | Genre filter pills. |
| `/home` | `subjectId`, `subjectType`, `title`, `detailPath`, `cover {url, blurHash}`, `stills {url, blurHash}`, `image {url, blurHash}`, `releaseDate`, `duration`, `imdbRatingValue`, `genre`, `language`, `countryName`, `description`, `imdbId`, `hasResource`, `trailer.videoAddress.url`, `url` | Yes | Yes | **Keep** | Parsed by `getHomeFeed()` & `parseSubjectObject()`. |
| `/home` | Raw upstream extras (`ops`, `corner`, `postTitle`, `subtitles`, `imdbRatingCount`, `resourceDetial`, `staffList`, `delay`, `appointment`, `cover.width`/`height`/`avgHue*`, `stills.width`/`height`/`avgHue*`, `trailer.cover`, `trailer.videoAddress.videoId`/`codecName`/`size`) | **No** | **No** | **Drop** | Dead upstream bloat forwarded verbatim by `_filter_home_payload()`. |
| `/movies`, `/tv-series` | `page`, `per_page`, `total`, `items[]` (`title`, `subject_id`, `subject_type`, `detail_path`, `cover`, `stills {url, blur_hash}`, `rating`, `year`, `release_date`, `duration`, `language`, `genre`, `country`) | Yes | Yes | **Keep** | Parsed by `getCatalogue()`. |
| `/movies`, `/tv-series` | `items[].cover_width`, `items[].cover_height`, `items[].stills.width`, `items[].stills.height` | **No** | **No** | **Drop** | Never parsed by `v1.0.80` or `main`. |
| `/search` | `results[]` (`subject_id`, `title`, `type`, `subject_type`, `release_date`, `duration`, `imdb_rating`, `genre`, `country`, `cover`, `stills {url, blur_hash}`, `detail_path`, `language`) | Yes | Yes | **Keep** | Parsed by `search()`. |
| `/search` | `results[].stills.width`, `results[].stills.height` | **No** | **No** | **Drop** | Never parsed by `v1.0.80` or `main`. |
| `/detail/{subject_id}` | `subject_id`, `title`, `subject_type`, `type`, `description`, `release_date`, `duration`, `duration_sec`, `imdb_rating`, `imdb_id`, `genre`, `country`, `language`, `cover`, `cover_blur_hash`, `stills {url, blur_hash}`, `detail_path`, `trailer`, `trailer_url`, `intro_*_ms`, `recap_*_ms`, `dubs[]`, `stars[]`, `seasons[]`, `related[]` | Yes | Yes | **Keep** | `duration_sec` is parsed by `parseSummaryDurationMinutes`; `trailer.videoAddress` is fallback + tested in `test_details_and_staff.py`. |
| `/detail/{subject_id}` | `cover_width`, `cover_height`, `trailer.duration_sec`, `stars[].detail_path` | **No** | **No** | **Defer** | Asserted by existing API contract tests (`tests/test_details_and_staff.py`); keep for additive safety. |
| `/detail/{subject_id}` | `stills.width`/`height`, `related[].cover_width`/`cover_height`, `related[].stills.width`/`height` | **No** | **No** | **Drop** | Never parsed by `v1.0.80`, `main`, or API tests. |
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
- `getHistoryListForTitle` & `getWatchHistoryByCanonical`: scans low-cardinality `index_movie_watch_history_is_trailer` + `USE TEMP B-TREE FOR ORDER BY`.

---

## §Addendum (C1–C12)

1. **C1 — Backend Cold-Start Target & `importtime` Discovery (Phase 5 Exit Gate)**:
   - Run `python -X importtime -c "import api" 2>&1 | sort -k2 -n | tail -40` during Phase 5 discovery and attach the top 40 modules by cumulative import cost.
   - Defer `firebase_admin` from module scope (lazy-import inside `security.require_api_auth`, or isolate `/health` + pure-metadata paths without weakening App Check).
   - **Mandatory Exit Gate**: `import api` median **< 800 ms** (5 runs) and live `sin1` `/health` cold **< 1000 ms**; if missed, report the blocking module and root cause.
2. **C2 — Per-Endpoint `StaleAge` Constants (Phase 4)**:
   - Replace flat 24h stale ceiling with a centralized `StaleAge` object:
     - `/home`, `/movies`, `/tv-series`: `24h`
     - `/detail/{id}`, `/episodes/{id}`, `/episodes/{id}/metadata`: `24h`
     - `/search`: `6h`
     - `/search/suggest`: `1h`
     - `/staff/{id}/titles`: `24h`
3. **C3 — Post-Deploy `NetworkWaterfallBaselineTest` vs Production (Phase 5 Exit Gate)**:
   - After `sin1` deploy, run `NetworkWaterfallBaselineTest` against production and confirm native-path subtitle timing is unchanged from the Phase 4 baseline (ensuring Phase 5 did not populate `_stream_id_cache` unexpectedly).
4. **C4 — `tools/perf/run_a02s_benchmarks.sh` 4 Explicit Modes (Pre-Phase-2)**:
   - Drop rooted/userdebug framing; Baseline Profile generation uses `androidx.baselineprofile` (`com.android.test`) in the `benchmark` build variant without root.
   - Support `--baseline` (benchmarks with `CompilationMode.None()`, writes `docs/perf/a02s-baseline.json`), `--generate-profile` (runs `:baselineprofile` generator and prints `baseline-prof.txt` output path), `--with-profile` (benchmarks with `CompilationMode.Partial()`, writes `docs/perf/a02s-with-profile.json`), `--compare` (prints before/after delta table), and `--help`.
5. **C5 — `JankStats` Restricted to `benchmark` Variant (Phase 3)**:
   - Use `benchmarkImplementation("androidx.metrics:metrics-performance:1.0.0")` only (never `implementation`), guarded by `BuildConfig.ENABLE_JANK_STATS` (`true` only in `benchmark`).
6. **C6 — Compose Compiler Metrics Gate (Phase 3 Exit Gate)**:
   - Enable `composeCompiler { metricsDestination; reportsDestination; stabilityConfigurationFiles }`, run `:app:compileBenchmarkKotlin --rerun-tasks`, and attach before/after unstable->stable and skippable composable counts.
7. **C7 — Splash `prefsLoaded` Telemetry & Theme Fallback Note (Phase 2 Exit Gate)**:
   - Log `Log.d("LaterboxSplash", ...)` capturing `splash_start_elapsed_ms`, `prefs_first_emission_elapsed_ms`, `prefs_loaded_before_ceiling`, and `elapsed_ms`; report sample distribution in Phase 2 exit gate and document that hitting the 800ms safety ceiling renders default theme briefly until DataStore emits.
8. **C8 — Pre-Flight APK Denominator (Phase 2 Pre-Flight)**:
   - Check `:app:assembleRelease --dry-run`; since signing env vars (`KEYSTORE_PATH`, etc.) are absent in this VM, `app-armeabi-v7a-benchmark.apk` (`57,014,650 bytes`) and `app-universal-benchmark.apk` (`107,170,014 bytes`) from `assembleBenchmark` (`isMinifyEnabled = true`, `isShrinkResources = true`) serve as the documented R8 APK denominator.
9. **C9 — Sync `get_detail` & Async Handler Audit (Phase 5)**:
   - `get_detail` in `api.py` is a sync `def` handler, so `ThreadPoolExecutor(max_workers=3)` in `_get_details_uncached` is safe and does not block the asyncio event loop. Explicitly audit the 4 `async def` handlers (`dashboard`, `post_chat_stream`, `invalidate_update_cache`, `upload_hero_asset`).
10. **C10 — Main-Thread JSON Parsing Verification (Phase 3 / Phase 4)**:
    - Confirm `/home`, `/detail`, and `/episodes` `JSONObject` parsing in `MovieBoxProvider.kt` executes strictly on `Dispatchers.IO`, never on the main thread.
11. **C11 — Vercel Preview + `v1.0.80` Smoke Check Before Production Promote (Phase 5)**:
    - Deploy to Vercel preview first, verify `v1.0.80`-compatible (`versionCode = 80`) endpoints + headers, then promote to production before running C3.
12. **C12 — User-Executed A02s Responsibility Split**:
    - Antigravity delivers instrumented code, `tools/perf/run_a02s_benchmarks.sh`, and VM proxy measurements; the user runs the script locally on the SM-A025F after Phase 6 ships.

---

## Phased Execution Plan

- **Phase 1 (Baseline Infrastructure)**: `:benchmark` module (`StartupBenchmark`, `ScrollBenchmark`, `DetailLoadBenchmark`), `benchmark` buildType, `testTagsAsResourceId = true` on `LaterboxApp`, `NetworkWaterfallLogger` instrumentation, `tools/perf/run_a02s_benchmarks.sh`, `docs/perf/baseline-metrics.md` (VM proxy measurements + backend `perf_harness.py`).
- **Phase 2 (Startup & `:baselineprofile`)**: `:baselineprofile` module + `BaselineProfileGenerator` (profile `.txt` generated only on A02s via `--generate-profile`), `ProfileVerifier` logging, deterministic DataStore splash gate (`!prefsLoaded && elapsed < 800L`) + `Log.d("LaterboxSplash", ...)` telemetry (`C7`), deferred non-frame startup I/O, destination-scoped ViewModels with `saveState`/`restoreState` navigation preservation.
- **Phase 3 (UI & Compose)**: `composeCompiler` stability configuration + compiler metrics/reports (`A7`/`C6`), `JankStats` (`benchmarkImplementation` + `ENABLE_JANK_STATS`, `C5`), `remember(...)` on `renderSlots` & related filter, `contentType` on all lazy lists, lazy `DropdownMenu`, gradient scrim replacing `.blur()` on scrolling cards/API<=31, bounded Coil `RGB_565` decode + shared `OkHttpClient`, C10 IO-dispatcher verification. Marked *code-complete, verification pending A02s*.
- **Phase 4 (Network & Room)**: Shared 40 MB OkHttp `Cache` + `ConnectionPool(8, 5, TimeUnit.MINUTES)` (inherited by `nativeOkHttpClient`), native-only parallel `getSubtitles()`, centralized per-endpoint `StaleAge` (`C2`: 24h home/catalogue/detail/episodes/staff, 6h search, 1h suggest) + Room `LruCache`, Room `MIGRATION_20_21` indexes + `observeIsInWatchlist` fix.
- **Phase 5 (Backend Touchpoints)**: `importtime` profiling + lazy `firebase_admin` import targeting `import api` `< 800 ms` & live `sin1` cold `/health` `< 1000 ms` (`C1`), `GZipMiddleware(minimum_size=1000)`, A2 additive-safe payload projection on `/home`/`/movies`/`/tv-series`/`/search`, A3 `Cache-Control: private` headers, A9 15s background-refreshed policy cache, `ThreadPoolExecutor(max_workers=3)` in sync `_get_details_uncached` (`C9`), async route audit, Vercel preview deploy + `v1.0.80` smoke check before prod promote (`C11`), post-deploy `NetworkWaterfallBaselineTest` vs prod (`C3`).
- **Phase 6 (Verification & Report)**: Full Android & pytest suites, `app-release.apk` (or `assembleBenchmark` proxy per C8) size check (`<= 5%` target), `v1.0.80` client smoke test, Measured (VM proxy) vs Pending (A02s) comparison report (`C12`, plus §17 Master Context baseline update to `v1.0.80`).
