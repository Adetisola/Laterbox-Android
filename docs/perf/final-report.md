# Laterbox Performance Audit & Optimization — Final Report (Phases 1–6)

**Scope:** `Adetisola/Laterbox-Android` (primary) and API contract / runtime surfaces in `Adetisola/Laterbox-MovieBox-API` (excluding MovieBox resolver signing/stream extraction and without proxying media bytes).
**Target Hardware:** Samsung Galaxy A02s (`SM-A025F`, Android 12 API 31, ARM32 `armeabi-v7a`, ~2 GB RAM).
**Compatibility Baseline Locked:** **`v1.0.80` (`versionCode = 80`)** (and backwards-compatible with `v1.0.79`).

---

## 1. Execution Summary & Boundary

- **Completed on VM (Android `main` + Backend `fa1046b`):** Phases **1, 2, 3, 4, 5, and 6** code changes, unit/Robolectric/pytest verification, Compose compiler stability reports, Room v21 query-plan verification, lint, and R8 `:app:assembleBenchmark` APK builds.
- **Pending Local Execution (User):**
  1. **Samsung Galaxy A02s (`SM-A025F`) On-Device Measurements (`C4`, `C12`):** The A02s is not connected to this VM. Run `./tools/perf/run_a02s_benchmarks.sh --baseline`, `--generate-profile`, `--with-profile`, and `--compare` over USB ADB locally. Hand-authored Baseline Profiles are deliberately absent from `app/src/main/` (`A1`).
  2. **Vercel Production Deploy Confirmation & Live `sin1` Probe (`C1`, `C3`, `C11`):** Commit `fa1046b` was pushed to `Adetisola/Laterbox-MovieBox-API` `main`. Because the VM lacks authenticated Vercel CLI credentials (`vercel` CLI not installed/logged in) and all >=1 KB API endpoints require Firebase App Check / API Auth, Vercel preview deployment (`v1.0.80` smoke check against preview URL), live `sin1` cold `/health` (< 1000 ms gate), and live-production `C3` re-verification remain **Pending (Vercel deploy verification)** until confirmed via your Vercel dashboard or local Vercel CLI (`vercel --prod`).
- **Scope Note:** `HttpDownloader` / `DownloadsScreen` was intentionally kept out of scope; `subtitleDownloadManager` inherited the shared `ConnectionPool(8, 5m)` + 40 MiB `Cache` via `moviesOkHttpClient`, and `recoverInterruptedTasks()` was deferred 350 ms post-paint in `LaterboxApplication`.

---

## 2. Measured (VM Proxy) Results — Before vs. After

### Android (VM / Robolectric / R8 Benchmark Proxy)

| Metric | Before (Phase 1) | After (Phase 6) | Delta / Status |
|---|---|---|---|
| Unit & Robolectric Tests (`:app:testDebugUnitTest`) | Baseline suite | **963 / 963 passed** (144 suites, 0 failed, 0 skipped) | Includes `Phase2StartupTest`, `Phase4OptimizationTest`, `NetworkWaterfallBaselineTest`, `V1_0_80CompatibilitySmokeTest`, `LaterboxDatabaseTest` (Room 20 -> 21) |
| Android Lint (`:app:lintDebug`) | — | **0 errors** (`BUILD SUCCESSFUL`) | Fixed `ProduceStateDoesNotAssignValue` in `MainActivity.kt` |
| `JankStats` Release Isolation (`C5`) | — | **Absent from `releaseRuntimeClasspath`** | Restricted strictly to `benchmarkImplementation` + `BuildConfig.ENABLE_JANK_STATS` |
| Compose Unstable Classes (`inferredUnstableClasses`) | 283 | **167** | **-41.0%** (`inferredStableClasses`: 350 -> 468; `knownUnstableArguments`: 564 -> 441, `-21.8%`) |
| Splash Hold & First-Prefs Delay (`C7`) | Fixed 900 ms | **Dynamic <= 800 ms cap + `ReportDrawnWhen`** | VM Robolectric median = **4 ms** (min 2 ms, max 58 ms across 5 runs) |
| Detail Open `CACHED` Trace (`NetworkWaterfallBaselineTest`) | Cold ~2,436 ms | **ttidMs=4, ttfdMs=54** (`MEMORY_HIT`) | Sub-frame L1 `detailsMemoryCache` hit |
| Benchmark APK `armeabi-v7a` (`C8` R8 proxy) | 57,014,650 bytes | **57,040,877 bytes** | **+26,227 bytes (+0.046%)** — well under <= 5% gate |
| Benchmark APK `universal` (`C8` R8 proxy) | 107,170,014 bytes | **107,196,237 bytes** | **+26,223 bytes (+0.024%)** |

*(Note: `:app:assembleRelease` signing dry-run fails without `KEYSTORE_PATH` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`, so R8 `benchmark` variant APKs serve as the C8 size-delta proxy.)*

### Backend (`Laterbox-MovieBox-API`, Commit `fa1046b`)

| Metric | Before (Phase 1) | After (Phase 5) | Delta / Status |
|---|---|---|---|
| `import api` Cold Import Median (`C1`, 5 runs) | **2,771.34 ms** | **737.42 ms** (min 726.27 ms, max 788.32 ms) | **-73.4%** — **< 800 ms `C1` gate PASSED** (`firebase_admin`, `firestore_v1`, `requests`, `httpx`, `curl_cffi` deferred cleanly) |
| `/home` Payload Raw Size (`perf_harness.py`) | **13,827 B** | **8,990 B** | **-35.0%** (A2 `v1.0.80`-safe field projection) |
| `/home` Compressed Wire Size (`Content-Encoding: gzip`) | 13,827 B (`none`) | **906 B** (`gzip`) | **-93.4%** wire reduction |
| `/detail/10001` Wire Size | 8,092 B (`none`) | **1,114 B** (`gzip`) | **-86.2%** wire reduction (all A2 / `test_details_and_staff` fields preserved) |
| `Cache-Control` Header (`A3`) | `""` (empty) | **`private, max-age=120, stale-while-revalidate=300`** (`/home`, `/movies`, `/tv-series`, `/detail`, `/staff`), **`private, max-age=60, stale-while-revalidate=120`** (`/episodes`, `/search`), **`no-store`** (`/api/stream/*`, `/episodes/*/sizes`) | Zero `public` headers on App-Check-gated endpoints |
| Policy Cache (`A9`) | 5.0 s blocking TTL | **15.0 s soft TTL + async SWR up to 30.0 s ceiling** | Instant non-blocking stale return under 30s; honors `LATERBOX_POLICY_CACHE_TTL_SEC=0` |
| Backend Pytest Suite (`.venv/bin/python -m pytest -q`) | 139 passed | **143 passed** (0 failed, 0 skipped) | Includes `test_perf_optimizations.py` |

---

## 3. Pending (A02s & Production) Verification Matrix

| Check | Status | How to Verify |
|---|---|---|
| A02s Cold Startup TTID / TTFD (`StartupBenchmark`, `CompilationMode.None()` vs Baseline Profile) | **Pending (A02s)** | Run `./tools/perf/run_a02s_benchmarks.sh --baseline` -> `--generate-profile` -> `--with-profile` -> `--compare` |
| A02s Movies Feed Frame Jank (`ScrollBenchmark`) | **Pending (A02s)** | Included in `run_a02s_benchmarks.sh` |
| A02s Detail Open (`DetailLoadBenchmark`) | **Pending (A02s)** | Included in `run_a02s_benchmarks.sh` |
| Live `sin1` Vercel Preview `v1.0.80` Check (`C11`), Cold `/health` < 1000 ms (`C1`), & Post-Deploy `C3` | **Pending (Vercel deploy verification)** | Verify auto-deploy of `Laterbox-MovieBox-API` `fa1046b` in Vercel or run `vercel --prod`, then test `/health` cold/warm latency and re-run `NetworkWaterfallBaselineTest` against production |

---

## 4. Key Optimizations Applied by Phase

- **Phase 1 — Measurement Infrastructure:** Added `:benchmark` Macrobenchmark module (`StartupBenchmark`, `ScrollBenchmark`, `DetailLoadBenchmark`), `NetworkWaterfallLogger`, `NetworkWaterfallBaselineTest`, `tools/perf/run_a02s_benchmarks.sh`, and `Laterbox-MovieBox-API/scripts/perf_harness.py`.
- **Phase 2 — Startup & Profile Generation:** Replaced fixed `900 ms` splash hold with bounded `<= 800 ms` + `ReportDrawnWhen` (`MainActivity.kt`, `SplashTimingLogger.kt`); deferred non-critical `WorkManager`/DB recovery by 350 ms post-paint + added `ProfileVerifier` logging (`LaterboxApplication.kt`); scoped 9 non-root ViewModels out of `LaterboxNavGraph` with `saveState`/`restoreState`; added `:baselineprofile` module + 4-mode `run_a02s_benchmarks.sh`.
- **Phase 3 — Compose & UI Rendering:** Added `compose_stability.conf` + compiler reports, hoisted expensive `remember` transforms (`MoviesScreen`, `MovieDetailScreen`), added `contentType` across lazy grids/lists, deferred `DropdownMenu` composition until expanded, replaced expensive `.blur()` modifiers with alpha scrims, tuned Coil 3 (`allowRgb565(true)`, `18%` memory, `120 MiB` disk, `crossfade(false)`), and isolated `JankStats` strictly to `benchmark` builds (`C5`).
- **Phase 4 — Client Network, Cache & Room v21:** Shared `ConnectionPool(8, 5m)` + `40 MiB` OkHttp cache (disabled on native resolver client), parallelized native-only `getSubtitles()` cleanly (`A6`), centralized per-endpoint `StaleAge` (`24h` home/catalogue/detail/episodes/staff; `6h` search; `1h` suggest), added in-memory LRU caches (`detailsMemoryCache`, `searchMemoryCache`, `suggestMemoryCache`) for sub-frame hits, and shipped Room v21 `MIGRATION_20_21` composite indexes + PK rewrite on `observeIsInWatchlist`.
- **Phase 5 — Backend (`Laterbox-MovieBox-API`):** Lazy-loaded `firebase_admin`, `google.cloud.firestore_v1`, `requests`, `httpx`, and `curl_cffi` via PEP 562 `__getattr__` / cached getters (`-73.4%` cold import); enabled `GZipMiddleware(minimum_size=1000)`; applied A2 `v1.0.80`-safe projection on `/home` (`-35%` raw, `-93.4%` gzip wire) and omitted unreferenced image dimensions on `/movies`, `/tv-series`, `/search`; added A3 `Cache-Control: private` / `no-store`; upgraded policy cache to `15s` SWR (`30s` ceiling); parallelized `_get_details_uncached` via `ThreadPoolExecutor(max_workers=3)`; offloaded blocking upload I/O in `config/routes.py:upload_hero_asset` via `run_in_threadpool`.

---

## 5. Action Item — Master Context Maintenance

> **Reminder:** Update **Master Context §17** minimum compatibility baseline reference from `v1.0.79` to **`v1.0.80` (`versionCode = 80`)**.
