# Phase 1 Performance Baseline Measurements

**Target Hardware:** Samsung Galaxy A02s (`SM-A025F`), Android 12 (API 31), ARM32 (`armeabi-v7a`), ~2 GB RAM.
**Build Variant:** `benchmark` (`isMinifyEnabled = true`, `isShrinkResources = true`, `isDebuggable = false`, debug-signed, `<profileable android:shell="true" />`).

> [!IMPORTANT]
> **Measurement Provenance (A5 Compliance)**
> This cloud VM has no attached Galaxy A02s (`adb devices` empty) and no `/dev/kvm`. On-device A02s metrics are explicitly marked **Pending (A02s)** until `./tools/perf/run_a02s_benchmarks.sh` executes on hardware. All VM proxy measurements below were recorded directly from real builds, unit tests, SQLite `EXPLAIN QUERY PLAN`, FastAPI ASGI `TestClient`, and live `curl -w` probes.

---

## 1. Summary Baseline Table ("Before" State)

| Metric | Measurement Source | Before (Baseline) | Target |
|---|---|---|---|
| Cold start TTID (median, 5 iters) | On-device A02s `StartupBenchmark` | **Pending (A02s)** *(artificial 900ms splash hold + 14 eager root VMs present in code)* | `-25%+` TTID |
| Cold start TTFD (median, 5 iters) | On-device A02s `StartupBenchmark` | **Pending (A02s)** | — |
| Home scroll frame overrun (p95) | On-device A02s `ScrollBenchmark` | **Pending (A02s)** | `< 16 ms` |
| Home scroll jank % | On-device A02s `ScrollBenchmark` | **Pending (A02s)** | `< 5%` |
| Detail TTID (uncached, A02s) | On-device A02s `DetailLoadBenchmark` | **Pending (A02s)** | — |
| Detail TTID (cached, A02s) | On-device A02s `DetailLoadBenchmark` | **Pending (A02s)** | `< 300 ms` |
| Detail waterfall TTID / TTFD (cold, 60ms/hop MockWebServer) | **Measured (VM Proxy: `NetworkWaterfallBaselineTest`)** | `ttidMs = 2510 ms`, `ttfdMs = 2706 ms` | No chain `> 2` hops |
| Detail waterfall TTID / TTFD (Room-cached re-open) | **Measured (VM Proxy: `NetworkWaterfallBaselineTest`)** | `ttidMs = 1 ms`, `ttfdMs = 68 ms` | `< 300 ms` |
| `app-armeabi-v7a-benchmark.apk` size (**C8 Proxy Denominator**) | **Measured (VM R8 build)** | `57,014,650 bytes` (`54.37 MiB`) | `<= +5%` growth |
| `app-arm64-v8a-benchmark.apk` size | **Measured (VM R8 build)** | `63,668,378 bytes` (`60.72 MiB`) | `<= +5%` growth |
| `app-universal-benchmark.apk` size (**C8 Proxy Denominator**) | **Measured (VM R8 build)** | `107,170,014 bytes` (`102.21 MiB`) | `<= +5%` growth |
| `app-release.apk` size | `:app:assembleRelease --dry-run` | Blocked in VM (`KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` unset); `benchmark` build (`initWith(release)`, `isMinifyEnabled = true`, `isShrinkResources = true`) is used as the R8 size denominator | `<= +5%` growth |
| Backend cold import (`import api`, fresh Python 3.12) | **Measured (VM: `scripts/perf_harness.py`)** | `2,771.34 ms` median (`2,379.65–3,375.18 ms`) | `< 800 ms` |
| Backend live Vercel `sin1` `/health` cold / warm | **Measured (VM `curl -w` live probe)** | Cold `1,933.15 ms` (`ttfb 1.933s`) / Warm `411.24 ms` | Cold `< 1000 ms` |
| Backend `/home` response size / encoding | **Measured (VM ASGI: `scripts/perf_harness.py`)** | `13,827 bytes` (`encoding=none`, `cache-control=""`, `8.887 ms`) | GZip + pruned |
| Backend `/detail/10001` response size / encoding | **Measured (VM ASGI: `scripts/perf_harness.py`)** | `8,092 bytes` (`encoding=none`, `cache-control=""`, `7.180 ms`) | GZip + `private` cache |
| Backend `/episodes/10001` response size / encoding | **Measured (VM ASGI: `scripts/perf_harness.py`)** | `127 bytes` (`encoding=none`, `cache-control=""`, `6.668 ms`) | `private, max-age=60` |

---

## 2. Detail-Page Open Network Waterfall (VM Proxy Capture)

Captured via `NetworkWaterfallBaselineTest` (`MockWebServer` with realistic 40–60ms per-hop delays, Robolectric API 31):

```text
PHASE1_WATERFALL_COLD:
  trace=1 ttidMs=2510 ttfdMs=2706 spans=[
    repository.getDetails @+6ms   dur=2500ms src=NETWORK blockPaint=true  early=false;
    detail                @+2341ms dur=58ms  src=NETWORK bytes=551 blockPaint=true  early=false;
    episodes              @+2548ms dur=5ms   src=NETWORK bytes=105 blockPaint=false early=false;
    stream_fallback       @+2579ms dur=5ms   src=NETWORK bytes=117 blockPaint=false early=false;
    episode_metadata      @+2612ms dur=1ms   src=NETWORK bytes=87  blockPaint=false early=false;
    captions              @+2650ms dur=1ms   src=NETWORK bytes=95  blockPaint=false early=false
  ]

PHASE1_WATERFALL_CACHED:
  trace=2 ttidMs=1 ttfdMs=68 spans=[
    repository.getDetails @+1ms    dur=51ms  src=NETWORK blockPaint=false early=false
  ]
```

**Observed Waterfall Bottlenecks:**
1. **Cold Room DB open + `mediaResolver.resolveConcreteTarget` latency (`+6ms` to `+2341ms`)** delayed the first `GET /detail/10001` network request on cold start, blocking first paint (`ttidMs = 2510ms`).
2. **Serial chains on cold detail open**: `getDetails` must complete before `loadEpisodes` (`+2548ms`) and `checkPlayback` (`+2579ms`) start when `cachedSummary == null`; inside `getEpisodes`, `episode_metadata` (`+2612ms`) runs serially after `/episodes`; inside `resolvePlaybackSourcesUncached`, `getSubtitles` (`+2650ms`) runs serially after stream resolution (can be parallelized on the native resolver path per A6!).
3. **Cache hit on immediate re-open** (`ttidMs = 1ms`, `ttfdMs = 68ms`) works well within `CACHE_TTL_MS`, though expired entries (`> CACHE_TTL_MS`) currently block on network instead of bounded stale-while-revalidate (addressed in Phase 4 per A4).

---

## 3. Baseline Test Suites Verified

- **Laterbox-Android**:
  - `NetworkWaterfallBaselineTest`: PASSED
  - `MoviesRepositoryTest`: PASSED (all tests)
  - `MovieBoxNativeStreamResolverTest`: PASSED (all tests)
  - `:app:assembleBenchmark` & `:benchmark:assembleBenchmark`: BUILD SUCCESSFUL (`57.0 MB` ARM32 APK)
- **Laterbox-MovieBox-API**:
  - `.venv/bin/python -m pytest -q`: `139 passed` in `15.26s`
