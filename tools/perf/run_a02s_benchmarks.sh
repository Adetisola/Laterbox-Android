#!/usr/bin/env bash
set -euo pipefail

# Runs Jetpack Macrobenchmark and Baseline Profile generation on a locally connected
# Samsung Galaxy A02s (SM-A025F, Android 12 API 31, ARM32) over USB ADB.
# Uses the non-debuggable R8-minified `benchmark` build variant (no root required).

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

BASELINE_JSON="$ROOT_DIR/docs/perf/a02s-baseline.json"
WITH_PROFILE_JSON="$ROOT_DIR/docs/perf/a02s-with-profile.json"
OUT_ROOT="$ROOT_DIR/build/perf-a02s"

print_help() {
  cat <<'EOF'
Usage: ./tools/perf/run_a02s_benchmarks.sh <MODE>

Modes (exactly one required):
  --baseline           Run StartupBenchmark, ScrollBenchmark, and DetailLoadBenchmark with
                       CompilationMode.None() on the connected A02s and write normalized
                       metrics to docs/perf/a02s-baseline.json.
  --generate-profile   Run :app:generateBenchmarkBaselineProfile (via androidx.baselineprofile
                       in benchmark build variant; no root required) and print the
                       generated baseline-prof.txt / startup-prof.txt paths to commit.
  --with-profile       Run StartupBenchmark, ScrollBenchmark, and DetailLoadBenchmark with
                       CompilationMode.Partial() on the connected A02s, collect logcat
                       perf tags (LaterboxSplash, LaterboxProfile, LaterboxJank,
                       LaterboxWaterfall), and write docs/perf/a02s-with-profile.json.
  --compare            Compare docs/perf/a02s-baseline.json against
                       docs/perf/a02s-with-profile.json and print a delta table.
  -h, --help           Show this help message and exit.

Environment:
  ADB_SERIAL           Optional ADB device serial (passed as 'adb -s $ADB_SERIAL').
EOF
}

if [[ $# -eq 0 ]]; then
  print_help >&2
  exit 1
fi

MODE=""
case "${1:-}" in
  --baseline|--generate-profile|--with-profile|--compare)
    MODE="$1"
    ;;
  -h|--help)
    print_help
    exit 0
    ;;
  *)
    echo "ERROR: Unknown option '$1'" >&2
    print_help >&2
    exit 1
    ;;
esac

DEVICE_SERIAL="${ADB_SERIAL:-}"
ADB_CMD=(adb)
if [[ -n "$DEVICE_SERIAL" ]]; then
  ADB_CMD=(adb -s "$DEVICE_SERIAL")
fi

require_device() {
  echo "==> Checking connected ADB devices..."
  "${ADB_CMD[@]}" devices -l
  local model abi sdk
  model="$("${ADB_CMD[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
  abi="$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
  sdk="$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
  echo "Target device: model=${model:-unknown} abi=${abi:-unknown} sdk=${sdk:-unknown}"
  if [[ -z "$model" ]]; then
    echo "ERROR: No Android device detected over ADB. Connect SM-A025F via USB ADB." >&2
    exit 1
  fi
}

export_and_summarize() {
  local run_dir="$1"
  local dest_json="$2"
  local mode_label="$3"
  mkdir -p "$run_dir" "$(dirname "$dest_json")"
  find "$ROOT_DIR/benchmark/build/outputs/connected_android_test_additional_output" \
    -name "*.json" -exec cp {} "$run_dir/" \; 2>/dev/null || true
  "${ADB_CMD[@]}" logcat -d -s \
    LaterboxSplash:D LaterboxProfile:I LaterboxJank:I LaterboxWaterfall:I \
    > "$run_dir/logcat-perf.txt" || true

  python3 - "$run_dir" "$dest_json" "$mode_label" <<'PY'
import glob, json, os, sys

run_dir, dest_json, mode_label = sys.argv[1:4]
files = sorted(glob.glob(os.path.join(run_dir, "*.json")))
normalized = {"mode": mode_label, "benchmarks": {}}

for path in files:
    try:
        with open(path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except Exception as exc:
        print(f"Skipping {path}: {exc}")
        continue
    for b in data.get("benchmarks", []):
        name = b.get("name", "unknown")
        metrics_out = {}
        for m_name, m_vals in b.get("metrics", {}).items():
            metrics_out[m_name] = {
                "median": m_vals.get("median"),
                "p95": m_vals.get("P95") or m_vals.get("p95"),
                "minimum": m_vals.get("minimum"),
                "maximum": m_vals.get("maximum"),
            }
        normalized["benchmarks"][name] = metrics_out

with open(dest_json, "w", encoding="utf-8") as fh:
    json.dump(normalized, fh, indent=2, sort_keys=True)
print(f"\n==> Wrote normalized report: {dest_json}")
for b_name, m_map in normalized["benchmarks"].items():
    print(f"  {b_name}:")
    for m_name, stats in m_map.items():
        print(f"    {m_name}: median={stats['median']} p95={stats['p95']} min={stats['minimum']} max={stats['maximum']}")
PY
}

compare_reports() {
  if [[ ! -f "$BASELINE_JSON" ]] || [[ ! -f "$WITH_PROFILE_JSON" ]]; then
    echo "ERROR: Both $BASELINE_JSON and $WITH_PROFILE_JSON must exist before running --compare." >&2
    exit 1
  fi
  python3 - "$BASELINE_JSON" "$WITH_PROFILE_JSON" <<'PY'
import json, sys

base = json.load(open(sys.argv[1], encoding="utf-8"))
prof = json.load(open(sys.argv[2], encoding="utf-8"))

def pick_metrics(data, preferred_names):
    for name in preferred_names:
        if name in data.get("benchmarks", {}):
            return name, data["benchmarks"][name]
    return None, {}

pairs = [
    ("Cold Startup", ["coldStartupNoCompilation"], ["coldStartupBaselineProfile"]),
    ("Movies Home Scroll", ["scrollMoviesHomeFeedNoCompilation", "scrollMoviesHomeFeed"], ["scrollMoviesHomeFeed"]),
    ("Search -> Detail", ["navigateSearchToDetailNoCompilation", "navigateSearchToDetail"], ["navigateSearchToDetail"]),
]

print("| Suite | Benchmark | Metric | Baseline (median) | With Profile (median) | Delta | % Change |")
print("|---|---|---|---|---|---|---|")
for label, b_keys, p_keys in pairs:
    b_name, b_metrics = pick_metrics(base, b_keys)
    p_name, p_metrics = pick_metrics(prof, p_keys)
    all_metrics = sorted(set(b_metrics.keys()) | set(p_metrics.keys()))
    for m in all_metrics:
        bv = (b_metrics.get(m) or {}).get("median")
        pv = (p_metrics.get(m) or {}).get("median")
        if isinstance(bv, (int, float)) and isinstance(pv, (int, float)) and bv != 0:
            delta = pv - bv
            pct = (delta / bv) * 100.0
            print(f"| {label} | `{m}` | median | {bv:.2f} | {pv:.2f} | {delta:+.2f} | {pct:+.1f}% |")
        else:
            print(f"| {label} | `{m}` | median | {bv} | {pv} | - | - |")
PY
}

case "$MODE" in
  --baseline)
    require_device
    "${ADB_CMD[@]}" logcat -c || true
    echo "==> Running Macrobenchmarks with CompilationMode.None()..."
    ./gradlew :benchmark:connectedBenchmarkAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
      -Pandroid.testInstrumentationRunnerArguments.class=com.laterbox.benchmark.StartupBenchmark#coldStartupNoCompilation,com.laterbox.benchmark.ScrollBenchmark#scrollMoviesHomeFeedNoCompilation,com.laterbox.benchmark.DetailLoadBenchmark#navigateSearchToDetailNoCompilation
    export_and_summarize "$OUT_ROOT/baseline" "$BASELINE_JSON" "baseline"
    ;;
  --generate-profile)
    require_device
    if [[ ! -d "$ROOT_DIR/baselineprofile" ]]; then
      echo "ERROR: :baselineprofile module not found." >&2
      exit 1
    fi
    echo "==> Generating Baseline Profile on connected A02s (benchmark build variant)..."
    ./gradlew :app:generateBenchmarkBaselineProfile
    echo "==> Generated profile artifact path(s):"
    find "$ROOT_DIR/app/src" "$ROOT_DIR/app/build/outputs" \
      \( -name "baseline-prof.txt" -o -name "startup-prof.txt" \) 2>/dev/null || true
    echo "Review the generated profile(s) above and commit them cleanly to git."
    ;;
  --with-profile)
    require_device
    "${ADB_CMD[@]}" logcat -c || true
    echo "==> Running Macrobenchmarks with Baseline Profile (CompilationMode.Partial)..."
    ./gradlew :benchmark:connectedBenchmarkAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark \
      -Pandroid.testInstrumentationRunnerArguments.class=com.laterbox.benchmark.StartupBenchmark#coldStartupBaselineProfile,com.laterbox.benchmark.ScrollBenchmark#scrollMoviesHomeFeed,com.laterbox.benchmark.DetailLoadBenchmark#navigateSearchToDetail
    export_and_summarize "$OUT_ROOT/with-profile" "$WITH_PROFILE_JSON" "with-profile"
    ;;
  --compare)
    compare_reports
    ;;
esac
