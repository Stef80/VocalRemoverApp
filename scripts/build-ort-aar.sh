#!/usr/bin/env bash
# Builds the custom ONNX Runtime 1.24.3 AAR (CPU + WebGPU + QNN) and copies it into third_party/maven.
# Every step is skipped when already done, so re-running only repeats what is missing.
#
# Usage: scripts/build-ort-aar.sh
# Env:   ORT_WORK_DIR=$HOME/ort-build  QAIRT_DIR=$HOME/qairt
#        ANDROID_SDK (default: sdk.dir from local.properties)  PARALLEL=4 (overrides the recipe)
set -euo pipefail

ORT_VERSION="1.24.3"
QAIRT_VERSION="2.42.0.251225"
QAIRT_URL="https://softwarecenter.qualcomm.com/api/download/software/sdks/Qualcomm_AI_Runtime_Community/All/$QAIRT_VERSION/v$QAIRT_VERSION.zip"
NDK_VERSION="28.0.13004108"
JDK_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
OUT_COORD_GROUP="com.stef.vocalremover.thirdparty"
OUT_ARTIFACT="onnxruntime-android-vr"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ORT_WORK_DIR="${ORT_WORK_DIR:-$HOME/ort-build}"
QAIRT_DIR="${QAIRT_DIR:-$HOME/qairt}"
PARALLEL="${PARALLEL:-4}"
if [[ -z "${ANDROID_SDK:-}" && -f local.properties ]]; then
  ANDROID_SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | head -n1)"
fi
: "${ANDROID_SDK:?ANDROID_SDK not set and sdk.dir not found in local.properties}"

SETTINGS="$ROOT/scripts/ort/vr_aar_build_settings.json"
QNN_HOME="$QAIRT_DIR/extract/qairt/$QAIRT_VERSION"
NDK_DIR="$ANDROID_SDK/ndk/$NDK_VERSION"
JDK_DIR="$ORT_WORK_DIR/jdk17"
VENV="$ORT_WORK_DIR/venv"
SRC="$ORT_WORK_DIR/onnxruntime"
BUILD_DIR="$ORT_WORK_DIR/out"
DEST="$ROOT/third_party/maven/${OUT_COORD_GROUP//.//}/$OUT_ARTIFACT/$ORT_VERSION"

mkdir -p "$ORT_WORK_DIR" "$QAIRT_DIR"

# 1. QAIRT SDK (HTTP/1.1: HTTP/2 downloads dropped once; the server answers 403 to HEAD, GET works).
QAIRT_ZIP="$QAIRT_DIR/v$QAIRT_VERSION.zip"
if [[ ! -d "$QNN_HOME" ]]; then
  if ! unzip -tq "$QAIRT_ZIP" >/dev/null 2>&1; then
    echo "==> Downloading QAIRT $QAIRT_VERSION"
    curl -L --http1.1 --retry 5 --retry-all-errors -C - -o "$QAIRT_ZIP" "$QAIRT_URL"
    unzip -tq "$QAIRT_ZIP" >/dev/null
  fi
  echo "==> Extracting QAIRT"
  unzip -q -o "$QAIRT_ZIP" -d "$QAIRT_DIR/extract"
fi
grep -q '^version: 2\.42\.0' "$QNN_HOME/sdk.yaml" \
  || { echo "QAIRT at $QNN_HOME is not version 2.42.0" >&2; exit 1; }

# 2. Android NDK.
if [[ ! -d "$NDK_DIR" ]]; then
  echo "==> Installing NDK $NDK_VERSION"
  SDKMANAGER="$(command -v sdkmanager || echo "$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager")"
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK" "ndk;$NDK_VERSION"
fi

# 3. JDK 17 (AGP 7.4.2 inside ORT's java/ build fails with newer JDKs).
if [[ ! -x "$JDK_DIR/bin/java" ]]; then
  echo "==> Downloading Temurin 17"
  mkdir -p "$JDK_DIR"
  curl -L --http1.1 --retry 5 --retry-all-errors "$JDK_URL" | tar -xz -C "$JDK_DIR" --strip-components=1
fi

# 4. Python venv with cmake, ninja and pkgconf (vcpkg needs pkg-config on PATH).
if [[ ! -x "$VENV/bin/pkg-config" ]]; then
  echo "==> Creating venv"
  [[ -x "$VENV/bin/pip" ]] || python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet cmake ninja pkgconf
  ln -sf pkgconf "$VENV/bin/pkg-config"
fi

# 5. ORT sources.
if [[ ! -d "$SRC/.git" ]]; then
  echo "==> Cloning onnxruntime v$ORT_VERSION"
  git clone --depth 1 --branch "v$ORT_VERSION" https://github.com/microsoft/onnxruntime.git "$SRC"
fi

# 6. Build (vcpkg reads only ANDROID_NDK_HOME).
export JAVA_HOME="$JDK_DIR"
export ANDROID_NDK_HOME="$NDK_DIR"
export ANDROID_HOME="$ANDROID_SDK"
export PATH="$JAVA_HOME/bin:$VENV/bin:$PATH"

# PARALLEL overrides the recipe's "--parallel N" without editing the checked-in file.
RECIPE="$ORT_WORK_DIR/vr_aar_build_settings.json"
python3 - "$SETTINGS" "$RECIPE" "$PARALLEL" <<'PY'
import json, sys
src, dst, parallel = sys.argv[1:4]
cfg = json.load(open(src))
params = cfg["build_params"]
params[params.index("--parallel") + 1] = parallel
json.dump(cfg, open(dst, "w"), indent=4)
PY

echo "==> Building the AAR (this is long on a clean tree)"
python3 "$SRC/tools/ci_build/github/android/build_aar_package.py" \
  --build_dir "$BUILD_DIR" \
  --android_sdk_path "$ANDROID_SDK" \
  --android_ndk_path "$NDK_DIR" \
  --qnn_path "$QNN_HOME" \
  "$RECIPE"

# 7. Copy into the repo, rewriting only the project's own coordinates in the POM.
BUILT="$(find "$BUILD_DIR/aar_out" -type d -path "*/com/microsoft/onnxruntime/onnxruntime-android-qnn/$ORT_VERSION" | head -n1)"
[[ -n "$BUILT" ]] || { echo "Built AAR not found under $BUILD_DIR/aar_out" >&2; exit 1; }
mkdir -p "$DEST"
cp "$BUILT/onnxruntime-android-qnn-$ORT_VERSION.aar" "$DEST/$OUT_ARTIFACT-$ORT_VERSION.aar"
python3 - "$BUILT/onnxruntime-android-qnn-$ORT_VERSION.pom" "$DEST/$OUT_ARTIFACT-$ORT_VERSION.pom" \
  "$OUT_COORD_GROUP" "$OUT_ARTIFACT" <<'PY'
import sys
src, dst, group, artifact = sys.argv[1:5]
text = open(src).read()
old_g = "<groupId>com.microsoft.onnxruntime</groupId>"
old_a = "<artifactId>onnxruntime-android-qnn</artifactId>"
assert text.count(old_g) == 1 and text.count(old_a) == 1, "unexpected POM layout"
text = text.replace(old_g, f"<groupId>{group}</groupId>").replace(old_a, f"<artifactId>{artifact}</artifactId>")
open(dst, "w").write(text)
PY

# 8. Verification.
AAR="$DEST/$OUT_ARTIFACT-$ORT_VERSION.aar"
POM="$DEST/$OUT_ARTIFACT-$ORT_VERSION.pom"
echo "==> Verification"
echo "-- execution providers in libonnxruntime.so:"
unzip -p "$AAR" jni/arm64-v8a/libonnxruntime.so | strings | grep -E "^(WebGpu|QNN)ExecutionProvider$" | sort -u
echo "-- LOAD segment alignment (expected 0x4000):"
TMP_SO="$(mktemp)"; trap 'rm -f "$TMP_SO"' EXIT
unzip -p "$AAR" jni/arm64-v8a/libonnxruntime.so > "$TMP_SO"
"$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf" -lW "$TMP_SO" | awk '$1 == "LOAD" {print $NF}'
echo "-- qnn-runtime dependencies in POM: $(grep -c qnn-runtime "$POM")"
grep "<artifactId>$OUT_ARTIFACT</artifactId>" "$POM"
echo "Artifact: $DEST"
