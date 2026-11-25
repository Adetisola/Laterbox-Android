#!/usr/bin/env bash
set -euo pipefail

# Runs Jetpack Macrobenchmark suites on a connected Samsung Galaxy A02s (SM-A025F)
# over USB ADB or Tailscale ADB, then summarizes median TTID/TTFD and p95 frame overrun.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

DEVICE_SERIAL="${ADB_SERIAL:-}"
ADB_CMD=(adb)
if [[ -n "$DEVICE_SERIAL" ]]; then
  ADB_CMD=(adb -s "$DEVICE_SERIAL")
fi

echo "==> Checking connected ADB devices..."
"${ADB_CMD[@]}" devices -l
DEVICE_MODEL="$("${ADB_CMD[@]}" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
DEVICE_ABI="$("${ADB_CMD[@]}" shell getprop ro.product.cpu.abi 2>/dev/null | tr -d '\r' || true)"
DEVICE_SDK="$("${ADB_CMD[@]}" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
echo "Target device: model=${DEVICE_MODEL:-unknown} abi=${DEVICE_ABI:-unknown} sdk=${DEVICE_SDK:-unknown}"

if [[ -z "$DEVICE_MODEL" ]]; then
  echo "ERROR: No Android device detected over ADB. Connect SM-A025F via USB or 'adb connect <tailscale-ip>:5555'."
  exit 1
fi

if [[ "${RUN_BASELINE_PROFILE:-0}" == "1" ]] && [[ -d "$ROOT_DIR/baselineprofile" ]]; then
  echo "==> Generating Baseline Profile on connected device..."
  ./gradlew :app:generateBenchmarkBaselineProfile
fi

echo "==> Running :benchmark:connectedBenchmarkAndroidTest (StartupBenchmark, ScrollBenchmark, DetailLoadBenchmark)..."
./gradlew :benchmark:connectedBenchmarkAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark

echo "==> Pulling benchmark JSON reports & waterfall logs..."
OUT_DIR="$ROOT_DIR/build/perf-a02s"
mkdir -p "$OUT_DIR"
find "$ROOT_DIR/benchmark/build/outputs/connected_android_test_additional_output" -name "*.json" -exec cp {} "$OUT_DIR/" \; 2>/dev/null || true
"${ADB_CMD[@]}" logcat -d -s LaterboxWaterfall:I LaterboxProfile:I LaterboxJank:I > "$OUT_DIR/logcat-perf.txt" || true

python3 - "$OUT_DIR" <<'PY'
import glob, json, os, sys

out_dir = sys.argv[1]
files = sorted(glob.glob(os.path.join(out_dir, "*.json")))
if not files:
    print(f"No benchmark JSON files found in {out_dir}")
    sys.exit(0)

for path in files:
    try:
        data = json.load(open(path))
    except Exception as exc:
        print(f"Skipping {path}: {exc}")
        continue
    print(f"\n=== {os.path.basename(path)} ===")
    for b in data.get("benchmarks", []):
        name = b.get("name")
        metrics = b.get("metrics", {})
        print(f"  {name}:")
        for m_name, m_vals in metrics.items():
            med = m_vals.get("median")
            p95 = m_vals.get("P95") or m_vals.get("p95")
            minimum = m_vals.get("minimum")
            maximum = m_vals.get("maximum")
            print(f"    {m_name}: median={med} p95={p95} min={minimum} max={maximum}")
PY
