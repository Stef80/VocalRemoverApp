#!/usr/bin/env bash
# Lancia BackendBenchmarkTest su Firebase Test Lab (telefoni fisici) e scarica i report JSON.
#
# Uso:   scripts/run-testlab.sh <cpu|webgpu|qnn> [--dry-run]
# Env:   DEVICES="model:api model:api ..."  (sovrascrive l'elenco predefinito del flavor)
#        DURATION_SEC=20  MIN_SNR_DB=20  TIMEOUT=20m  SKIP_BUILD=1
#
# Piano Spark: 5 test fisici al giorno; ogni dispositivo conta come un test.
set -euo pipefail

FLAVOR="${1:-}"
DRY_RUN=0
[[ "${2:-}" == "--dry-run" ]] && DRY_RUN=1

case "$FLAVOR" in
  # webgpu: GPU di architetture diverse (Xclipse/AMD, Mali, Adreno) e fascia bassa MediaTek.
  webgpu) DEFAULT_DEVICES="e1s:36 tokay:35 a16x:35 dm1q:35 e1q:34" ;;
  # qnn: solo Snapdragon (HTP v73, v75, v79); su altri SoC il QNN EP non è disponibile.
  qnn)    DEFAULT_DEVICES="dm1q:35 e1q:34 pa1q:36" ;;
  # cpu: già misurata come riferimento in ogni run; qui solo fascia bassa e Tensor.
  cpu)    DEFAULT_DEVICES="a16x:35 tokay:35" ;;
  *) echo "Uso: $0 <cpu|webgpu|qnn> [--dry-run]" >&2; exit 2 ;;
esac

DEVICES="${DEVICES:-$DEFAULT_DEVICES}"
DURATION_SEC="${DURATION_SEC:-20}"
MIN_SNR_DB="${MIN_SNR_DB:-20}"
TIMEOUT="${TIMEOUT:-20m}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

CAP="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"
APP_ID="com.example.vocalremover.$FLAVOR"
APP_APK="ui/build/outputs/apk/$FLAVOR/debug/ui-$FLAVOR-debug.apk"
TEST_APK="ui/build/outputs/apk/androidTest/$FLAVOR/debug/ui-$FLAVOR-debug-androidTest.apk"
STAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="vr-benchmark-$FLAVOR-$STAMP"
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
  --environment-variables "durationSec=$DURATION_SEC,minSnrDb=$MIN_SNR_DB"
  --directories-to-pull "/sdcard/Android/data/$APP_ID/files"
  --results-dir "$RESULTS_DIR"
  --timeout "$TIMEOUT"
  --no-record-video
  --no-performance-metrics)

echo "Flavor: $FLAVOR  dispositivi: $DEVICES ($(wc -w <<< "$DEVICES") test)"
if (( DRY_RUN )); then
  printf '%q ' "${GCLOUD_CMD[@]}"; echo
  exit 0
fi

if [[ -f ui/google-services.json ]] && ! grep -q "\"$APP_ID\"" ui/google-services.json; then
  echo "ERRORE: ui/google-services.json non contiene il client $APP_ID e la build fallirebbe." >&2
  echo "Registra $APP_ID nella console Firebase e riscarica il file, oppure rimuovi il plugin google-services." >&2
  exit 1
fi

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  gradle -q ":ui:assemble${CAP}Debug" ":ui:assemble${CAP}DebugAndroidTest"
fi

mkdir -p "$OUT_DIR"
set +e
"${GCLOUD_CMD[@]}" 2>&1 | tee "$OUT_DIR/gcloud.log"
STATUS=${PIPESTATUS[0]}
set -e

BUCKET="$(grep -o 'test-lab-[a-z0-9_-]*' "$OUT_DIR/gcloud.log" | head -n1 || true)"
if [[ -z "$BUCKET" ]]; then
  echo "Bucket dei risultati non trovato in $OUT_DIR/gcloud.log" >&2
  exit "$(( STATUS == 0 ? 1 : STATUS ))"
fi

echo "Scarico i report da gs://$BUCKET/$RESULTS_DIR ..."
mapfile -t REPORTS < <(gcloud storage ls "gs://$BUCKET/$RESULTS_DIR/**/report-*.json" 2>/dev/null || true)
for url in "${REPORTS[@]}"; do
  device="${url#"gs://$BUCKET/$RESULTS_DIR/"}"
  device="${device%%/*}"
  gcloud storage cp --quiet "$url" "$OUT_DIR/$device.json" >/dev/null
  echo "== $device"; cat "$OUT_DIR/$device.json"
done
if (( ${#REPORTS[@]} == 0 )); then
  echo "Nessun report trovato (test non partito o crash prima della scrittura): vedi i log nella console."
fi

echo "Risultati in $OUT_DIR  (log e logcat: console Firebase > Test Lab)"
exit "$STATUS"
