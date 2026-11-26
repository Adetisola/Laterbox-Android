# Phase 3 UI & Compose Report

**Status:** **Code-complete; on-device frame timing (`FrameTimingMetric`, `JankStats` p95) Pending (A02s)** via `./tools/perf/run_a02s_benchmarks.sh` (per §A5 & C12).
**Build Variant Verified:** `benchmark` (`:app:compileBenchmarkKotlin --rerun-tasks`) and `debug` Robolectric tests.

---

## 1. Compose Compiler Metrics Delta (`C6`, `:app:compileBenchmarkKotlin --rerun-tasks`)

| Metric | Before (`app/compose_stability.conf` absent) | After (`app/compose_stability.conf` active) | Delta |
|---|---:|---:|---|
| `inferredStableClasses` | `350` | `468` | **`+118` (`+33.7%`)** |
| `inferredUnstableClasses` | `283` | `167` | **`-116` (`-41.0%`)** |
| `effectivelyStableClasses` | `356` | `474` | **`+118`** |
| `knownStableArguments` | `34,139` | `34,254` | **`+115`** |
| `knownUnstableArguments` | `564` | `441` | **`-123` (`-21.8%`)** |
| `skippableComposables` | `945` (`1,974` restartable) | `945` (`1,974` restartable, `StrongSkipping=true`) | Preserved |

### Hot-Path Classes Transitioned `unstable -> stable`
Verified in `app/build/compose_compiler/app_benchmark-classes.txt`:
- `MovieTitleSummary`: `unstable -> stable`
- `BrowseSection`: `unstable -> stable`
- `MoviesHomeFeed`: `unstable -> stable`
- `MediaDetails`: `unstable -> stable`
- `SeriesProgressSummary`: `unstable -> stable`
- `MovieDetailUiState`: `unstable -> stable`
- `MoviesCatalogueUiState`: `unstable -> stable`
- `MoviesHomeUiState`: `unstable -> stable`
- `MoviesHomeRenderSlot`: `unstable -> stable`

*(Method: `app/compose_stability.conf` marks only Kotlin read-only collection interfaces `kotlin.collections.List`, `Map`, `Set`, `Collection` — zero blanket `@Immutable` over mutable classes per §A7.)*

---

## 2. Other Phase 3 Changes Applied & Verified

1. **JankStats (`C5` / `A10`)**:
   - Wired `androidx.metrics:metrics-performance:1.0.0` via `benchmarkImplementation` only (`ENABLE_JANK_STATS = true` in `benchmark`, `false` in `debug`/`release`).
   - Verified via `:app:dependencies`: present in `benchmarkRuntimeClasspath`, **absent from `releaseRuntimeClasspath`**.
   - Variant-isolated `JankStatsPlatformHook` attaches in `MainActivity` and tags active nav route (`LaterboxJank`).
2. **Recomposition & Lazy-List Hot Spots**:
   - Hoisted `remember(remoteConfig, recommendationsEnabled) { RemoteConfigLayoutHelper.buildMoviesHomeSlots(...) }` above `MoviesScreen` home `LazyColumn`.
   - Hoisted `remember(uiState.rankedRelatedTitles, details.relatedTitles, uiState.hiddenMediaKeys)` for `displayRelated` above `MovieDetailScreen` `LazyColumn`.
   - Added explicit `contentType` to `LazyColumn`/`LazyRow`/`LazyVerticalGrid` items in `MoviesScreen`, `MoviesCatalogueScreen`, `MoviesWatchlistScreen`, `MovieDetailScreen`, and `MoviesUiComponents`.
   - Deferred `DropdownMenu` composition with `if (menuExpanded)` / `if (showMoreActionsMenu)` in `MoviePosterCard`, `ContinueWatchingCard`, and `MovieDetailScreen`.
   - Replaced `.blur(20.dp)` / `.blur(10.dp)` in scrolling `BackdropArtworkBox` & `EpisodeRowCard` fallback tiles with alpha + dark scrim overlay.
3. **Coil Memory & Network Pooling**:
   - Exposed `AppContainer.imageOkHttpClient` (derived from `sharedOkHttpClient`) for `LaterboxApplication.newImageLoader()`, enabled `.allowRgb565(true)`, `18%` memory cache, `120 MiB` disk cache, and `.crossfade(false)`.
   - Created `LaterboxImageRequests` (bounded pixel sizes `360x540` posters, `720x405` backdrops, `160x160` avatars) and applied to hot scrolling `AsyncImage` call sites.
4. **Main-Thread JSON Parsing Audit (`C10`)**:
   - Verified `MovieBoxProvider.kt` wraps `/home` (`getHomeFeed`), `/movies`+`/tv-series` (`getCatalogue`), `/search` (`search`), `/search/suggest` (`getSuggestions`), `/detail/{id}` (`getDetails`), and `/episodes/{id}` (`getSeasons`) inside `withContext(Dispatchers.IO)` — zero `JSONObject` parsing executes on the Main thread.
