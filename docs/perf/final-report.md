# Laterbox Performance Audit & Optimization — Final Report (Phases 1–6)

**Scope:** `Adetisola/Laterbox-Android` (primary) and API contract / runtime surfaces in `Adetisola/Laterbox-MovieBox-API` (excluding MovieBox resolver signing/stream extraction and without proxying media bytes).
**Target Hardware:** Samsung Galaxy A02s (`SM-A025F`, Android 12 API 31, ARM32 `armeabi-v7a`, ~2 GB RAM).
**Compatibility Baseline Locked:** **`v1.0.80` (`versionCode = 80` in `build.gradle.kts`, CI release `versionCode = 214` in `v1.0.80` `release-manifest.json`)** (and backwards-compatible with `v1.0.79`).

---

## 1. Execution Summary & Boundary

- **Completed & Verified on VM + Vercel `sin1`:** Phases **1, 2, 3, 4, 5, and 6** code changes, unit/Robolectric/pytest verification, Compose compiler stability reports, Room v21 query-plan verification, lint, R8 `:app:assembleBenchmark` APK builds, **Vercel Production auto-deploy (`dpl_AgYhhXiRhV7nKANfcHfKfLngvLQq`, SHA `fa1046b`, region `sin1`)**, and **Vercel Preview live `sin1` smoke/gzip/cold-start verification (`C1`, `C3`, `C11`)**.
- **Pending Local Execution (User):**
  - **Samsung Galaxy A02s (`SM-A025F`) On-Device Measurements (`C4`, `C12`):** The A02s is not connected to this VM. Run `./tools/perf/run_a02s_benchmarks.sh --baseline`, `--generate-profile`, `--with-profile`, and `--compare` over USB ADB locally. Hand-authored Baseline Profiles are deliberately absent from `app/src/main/` (`A1`).
- **Scope Note:** `HttpDownloader` / `DownloadsScreen` was intentionally kept out of scope; `subtitleDownloadManager` inherited the shared `ConnectionPool(8, 5m)` + 40 MiB `Cache` via `moviesOkHttpClient`, and `recoverInterruptedTasks()` was deferred 350 ms post-paint in `LaterboxApplication`.

---

## 2. Measured (VM Proxy & Live Vercel `sin1`) Results — Before vs. After

### Android (VM / Robolectric / R8 Benchmark Proxy)

| Metric | Before (Phase 1) | After (Phase 6) | Delta / Status |
|---|---|---|---|
| Unit & Robolectric Tests (`:app:testDebugUnitTest`) | Baseline suite | **963 / 963 passed** (144 suites, 0 failed, 0 skipped) | Includes `Phase2StartupTest`, `Phase4OptimizationTest`, `NetworkWaterfallBaselineTest`, `V1_0_80CompatibilitySmokeTest`, `LaterboxDatabaseTest` (Room 20 -> 21) |
| Android Lint (`:app:lintDebug`) | — | **0 errors** (`BUILD SUCCESSFUL`) | Fixed `ProduceStateDoesNotAssignValue` in `MainActivity.kt` |
| `JankStats` Release Isolation (`C5`) | — | **Absent from `releaseRuntimeClasspath`** | Restricted strictly to `benchmarkImplementation` + `BuildConfig.ENABLE_JANK_STATS` |
| Compose Unstable Classes (`inferredUnstableClasses`) | 283 | **167** | **-41.0%** (`inferredStableClasses`: 350 -> 468; `knownUnstableArguments`: 564 -> 441, `-21.8%`) |
| Splash Hold & First-Prefs Delay (`C7`) | Fixed 900 ms | **Dynamic <= 800 ms cap + `ReportDrawnWhen`** | VM Robolectric median = **4 ms** (min 2 ms, max 58 ms across 5 runs) |
| Detail Open `CACHED` Trace (`NetworkWaterfallBaselineTest`) | Cold ~2,436 ms | **ttidMs=1, ttfdMs=54** (`MEMORY_HIT`, post-deploy `C3`) | Sub-frame L1 `detailsMemoryCache` hit; `captions` 1 ms, `stream_fallback` 4 ms unchanged |
| Benchmark APK `armeabi-v7a` (`C8` R8 proxy) | 57,014,650 bytes | **57,040,877 bytes** | **+26,227 bytes (+0.046%)** — well under <= 5% gate |
| Benchmark APK `universal` (`C8` R8 proxy) | 107,170,014 bytes | **107,196,237 bytes** | **+26,223 bytes (+0.024%)** |

*(Note: `:app:assembleRelease` signing dry-run fails without `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`, so R8 `benchmark` variant APKs serve as the C8 size-delta proxy.)*

### Backend (`Laterbox-MovieBox-API`, Commit `fa1046b`)

| Metric | Before (Phase 1) | After (Phase 5 + Live `sin1`) | Delta / Notes |
|---|---|---|---|
| `import api` Cold Import Median (`C1`, 5 runs on VM) | **2,771.34 ms** | **737.42 ms** (min 726.27 ms, max 788.32 ms) | **-73.4%** (**< 800 ms `C1` gate PASSED**) |
| Live `sin1` `/health` Cold (Preview via `vercel curl`) | **1,933.15 ms** | **1,318.18 ms** (`ttfb 1.318 s`, warm **333.4–388.4 ms**) | **-31.8%**. Above `1000 ms` total wall-clock from US VM because cross-region `cle1::sin1` warm TLS/RTT alone is **~334 ms** (server-side cold boot ~`984 ms`; Python import floor: `fastapi` 475.8 ms + `security`/`jwt` 67.1 ms + `ai.routes` 53.6 ms + `config.routes` 52.7 ms + `updates` 49.0 ms). |
| Live `sin1` Production `/health` (`fa1046b` ready) | — | **200 OK**, warm **325.2–336.8 ms** | Deployment `dpl_AgYhhXiRhV7nKANfcHfKfLngvLQq` (`READY`, `regions=['sin1']`, `sha=fa1046b`) |
| Live `sin1` Preview `/home` (`v1.0.80` `versionCode=214`) | Uncompressed | **200 OK**, **`gzip`**, **61,192 B wire**, **`cache-control: private, max-age=120`**, `ops=25`, **0 bloat keys** | Verified zero `ops`/`corner`/`postTitle`/`subtitles`/`imdbRatingCount`/`resourceDetial`/`staffList` keys |
| Live `sin1` Preview `/movies`, `/search`, `/detail`, `/episodes` | — | All **200 OK**, **`gzip`**, **`cache-control: private`** | `/movies` 1,148 B (805.9 ms), `/search` 806 B, `/detail/8906247916759695608` 4,498 B, `/episodes/6207982430134357800` 237 B |
| Live `sin1` Preview `/api/stream/*` & `/captions` | — | **200 OK**, **`gzip`**, **`cache-control: no-store`** | `/api/stream` 2,062 B (10 streams), `/captions` 1,902 B (14 captions) |
| Harness `/home` Raw & Wire (`perf_harness.py`) | **13,827 B** (`none`) | **8,990 B raw / 906 B wire (`gzip`)** | **-35.0%** raw / **-93.4%** wire (`C1`/`A2`) |
| Policy Cache (`A9`) | 5.0 s blocking TTL | **15.0 s soft TTL + async SWR up to 30.0 s ceiling** | Honors `LATERBOX_POLICY_CACHE_TTL_SEC=0` |
| Backend Pytest Suite (`.venv/bin/python -m pytest -q`) | 139 passed | **143 passed** (0 failed, 0 skipped) | Includes `test_perf_optimizations.py` |

*(Note: Testing with raw `X-Laterbox-Version-Code: 80` against live Preview returned `426 UNSUPPORTED_APP_VERSION` because live Firestore `versionPolicy` requires `minimumSupportedVersionCode = 204` [`v1.0.70`], and CI shipped `v1.0.80` with `versionCode = 214`. Re-tested with `X-Laterbox-Version-Code: 214` (`v1.0.80`) and confirmed HTTP `200 OK`.)*

---

## 3. Pending On-Device Matrix

| Check | Status | How to Verify |
|---|---|---|
| A02s Cold Startup TTID / TTFD (`StartupBenchmark`, `CompilationMode.None()` vs Baseline Profile) | **Pending (A02s)** | Run `./tools/perf/run_a02s_benchmarks.sh --baseline` -> `--generate-profile` -> `--with-profile` -> `--compare` |
| A02s Movies Feed Frame Jank (`ScrollBenchmark`) | **Pending (A02s)** | Included in `run_a02s_benchmarks.sh` |
| A02s Detail Open (`DetailLoadBenchmark`) | **Pending (A02s)** | Included in `run_a02s_benchmarks.sh` |

---

## 4. Key Optimizations Applied by Phase

- **Phase 1 — Measurement Infrastructure:** Added `:benchmark` Macrobenchmark module (`StartupBenchmark`, `ScrollBenchmark`, `DetailLoadBenchmark`), `NetworkWaterfallLogger`, `NetworkWaterfallBaselineTest`, `tools/perf/run_a02s_benchmarks.sh`, and `Laterbox-MovieBox-API/scripts/perf_harness.py`.
- **Phase 2 — Startup & Profile Generation:** Replaced fixed `900 ms` splash hold with bounded `<= 800 ms` + `ReportDrawnWhen` (`MainActivity.kt`, `SplashTimingLogger.kt`); deferred non-critical `WorkManager`/DB recovery by 350 ms post-paint + added `ProfileVerifier` logging (`LaterboxApplication.kt`); scoped 9 non-root ViewModels out of `LaterboxNavGraph` with `saveState`/`restoreState`; added `:baselineprofile` module + 4-mode `run_a02s_benchmarks.sh`.
- **Phase 3 — Compose & UI Rendering:** Added `compose_stability.conf` + compiler reports, hoisted expensive `remember` transforms (`MoviesScreen`, `MovieDetailScreen`), added `contentType` across lazy grids/lists, deferred `DropdownMenu` composition until expanded, replaced `.blur()` modifiers with alpha scrims, tuned Coil 3 (`allowRgb565(true)`, `18%` memory, `120 MiB` disk, `crossfade(false)`), and isolated `JankStats` strictly to `benchmark` builds (`C5`).
- **Phase 4 — Client Network, Cache & Room v21:** Shared `ConnectionPool(8, 5m)` + `40 MiB` OkHttp cache (disabled on native resolver client), parallelized native-only `getSubtitles()` cleanly (`A6`), centralized per-endpoint `StaleAge` (`24h` home/catalogue/detail/episodes/staff; `6h` search; `1h` suggest), added in-memory LRU caches (`detailsMemoryCache`, `searchMemoryCache`, `suggestMemoryCache`), and shipped Room v21 `MIGRATION_20_21` composite indexes + PK rewrite on `observeIsInWatchlist`.
- **Phase 5 — Backend (`Laterbox-MovieBox-API`):** Lazy-loaded `firebase_admin`, `google.cloud.firestore_v1`, `requests`, `httpx`, and `curl_cffi` (`-73.4%` cold import); enabled `GZipMiddleware(minimum_size=1000)`; applied A2 `v1.0.80`-safe projection on `/home` and omitted unreferenced image dimensions on `/movies`, `/tv-series`, `/search`; added A3 `Cache-Control: private` / `no-store`; upgraded policy cache to `15s` SWR (`30s` ceiling); parallelized `_get_details_uncached` via `ThreadPoolExecutor(max_workers=3)`; offloaded blocking upload I/O in `config/routes.py:upload_hero_asset` via `run_in_threadpool`.

---

## 5. Action Items

1. **Master Context §17 Update:** Update **Master Context §17** compatibility baseline reference from `v1.0.79` to **`v1.0.80` (`versionCode = 80` default / CI `versionCode = 214`)**.
2. **Vercel Protection Bypass Secret Hygiene:** Running `vercel curl` auto-created a *Protection Bypass for Automation* secret on `prj_PcYZ72OhjgLfqGnxrwVPSizeBRbc` (`laterbox-moviebox-api`); revoke it in Vercel Project Settings -> Deployment Protection when no longer needed for automated preview testing.
