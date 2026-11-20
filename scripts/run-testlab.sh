#!/usr/bin/env bash
# Runs BackendBenchmarkTest on Firebase Test Lab (physical phones) and downloads the JSON reports.
#
# Usage: scripts/run-testlab.sh <cpu|webgpu|qnn|auto> [--dry-run]
# Env:   DEVICES="model:api model:api ..."  (overrides the backend's default list)
#        DURATION_SEC=20  MIN_SNR_DB=20  TIMEOUT=20m  SKIP_BUILD=1
#
# Spark plan: 5 physical tests per day; each device counts as one test.
set -euo pipefail

BACKEND="${1:-}"
DRY_RUN=0
[[ "${2:-}" == "--dry-run" ]] && DRY_RUN=1

WEBGPU_DEVICES="e1s:36 tokay:35 a16x:35 dm1q:35 e1q:34"
QNN_DEVICES="dm1q:35 e1q:34 pa1q:36"

case "$BACKEND" in
  # webgpu: GPUs of different architectures (Xclipse/AMD, Mali, Adreno) and low-end MediaTek.
  webgpu) DEFAULT_DEVICES="$WEBGPU_DEVICES" ;;
  # qnn: Snapdragon only (HTP v73, v75, v79); on other SoCs the QNN EP is not available.
  qnn)    DEFAULT_DEVICES="$QNN_DEVICES" ;;
  # cpu: already measured as the reference in every run; here only low-end and Tensor.
  cpu)    DEFAULT_DEVICES="a16x:35 tokay:35" ;;
  # auto: the app picks the backend per device. At most 5 devices (Spark daily limit):
  # two HTP generations (v73, v79) and three GPU families (Xclipse/AMD, Mali, low-end MediaTek).
  auto)   DEFAULT_DEVICES="dm1q:35 pa1q:36 e1s:36 tokay:35 a16x:35" ;;
  *) echo "Usage: $0 <cpu|webgpu|qnn|auto> [--dry-run]" >&2; exit 2 ;;
esac

DEVICES="${DEVICES:-$DEFAULT_DEVICES}"
DURATION_SEC="${DURATION_SEC:-20}"
MIN_SNR_DB="${MIN_SNR_DB:-20}"
TIMEOUT="${TIMEOUT:-20m}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

APP_ID="com.stef.vocalremover"
APP_APK="ui/build/outputs/apk/debug/ui-debug.apk"
TEST_APK="ui/build/outputs/apk/androidTest/debug/ui-debug-androidTest.apk"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="vr-benchmark-$BACKEND-$STAMP"
OUT_DIR="build/testlab/$RESULTS_DIR"

DEVICE_ARGS=()
for d in $DEVICES; do
  DEVICE_ARGS+=(--device "model=${d%%:*},version=${d##*:}")
done

GCLOUD_CMD=(gcloud firebase test android run
  --type instrumentation
  --app "$APP_APK"
  --test "$TEST_APK"
  "${DEVICE_ARGS[@]}"
  --environment-variables "backend=$BACKEND,durationSec=$DURATION_SEC,minSnrDb=$MIN_SNR_DB"
  --directories-to-pull "/sdcard/Android/data/$APP_ID/files"
  --results-dir "$RESULTS_DIR"
  --timeout "$TIMEOUT"
  --no-record-video
  --no-performance-metrics)

echo "Backend: $BACKEND  devices: $DEVICES ($(wc -w <<< "$DEVICES") tests)"
if (( DRY_RUN )); then
  printf '%q ' "${GCLOUD_CMD[@]}"; echo
  exit 0
fi

if [[ -f ui/google-services.json ]] && ! grep -q "\"$APP_ID\"" ui/google-services.json; then
  echo "ERROR: ui/google-services.json does not contain the client $APP_ID and the build would fail." >&2
  echo "Register $APP_ID in the Firebase console and download the file again, or remove the google-services plugin." >&2
  exit 1
fi

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  # The system gradle may be older than AGP needs: use the version pinned by the wrapper.
  GRADLE_VERSION="$(sed -n 's/^distributionUrl=.*gradle-\(.*\)-bin\.zip$/\1/p' gradle/wrapper/gradle-wrapper.properties)"
  GRADLE_BIN="$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-"$GRADLE_VERSION"-bin/*/gradle-"$GRADLE_VERSION"/bin/gradle 2>/dev/null | head -n1 || true)"
  if [[ -z "$GRADLE_BIN" ]]; then
    echo "ERROR: Gradle $GRADLE_VERSION not found under ~/.gradle/wrapper/dists (pinned in gradle/wrapper/gradle-wrapper.properties)." >&2
    exit 1
  fi
  "$GRADLE_BIN" -q :ui:assembleDebug :ui:assembleDebugAndroidTest
fi

mkdir -p "$OUT_DIR"
set +e
"${GCLOUD_CMD[@]}" 2>&1 | tee "$OUT_DIR/gcloud.log"
STATUS=${PIPESTATUS[0]}
set -e

BUCKET="$(grep -o 'test-lab-[a-z0-9_-]*' "$OUT_DIR/gcloud.log" | head -n1 || true)"
if [[ -z "$BUCKET" ]]; then
  echo "Results bucket not found in $OUT_DIR/gcloud.log" >&2
  exit "$(( STATUS == 0 ? 1 : STATUS ))"
fi

echo "Downloading the reports from gs://$BUCKET/$RESULTS_DIR ..."
mapfile -t REPORTS < <(gcloud storage ls "gs://$BUCKET/$RESULTS_DIR/**/report-*.json" 2>/dev/null || true)
for url in "${REPORTS[@]}"; do
  device="${url#"gs://$BUCKET/$RESULTS_DIR/"}"
  device="${device%%/*}"
  gcloud storage cp --quiet "$url" "$OUT_DIR/$device.json" >/dev/null
  echo "== $device"; cat "$OUT_DIR/$device.json"
done
if (( ${#REPORTS[@]} == 0 )); then
  echo "No report found (test did not start or crashed before writing): see the logs in the console."
fi

echo "Results in $OUT_DIR  (logs and logcat: Firebase console > Test Lab)"
exit "$STATUS"
