#!/usr/bin/env bash
# Builds the ONNX Runtime the app ships: the same version the sherpa-onnx AAR was built against,
# with only the operators Alyo's models use. The stock library carries every operator and is
# twice the size.
#
#   scripts/build-onnxruntime.sh                 build with scripts/onnxruntime-ops.config
#   scripts/build-onnxruntime.sh <model dir>     first add the operators of the .onnx files in
#                                                <model dir> to that config, then build
#
# Do that whenever a model is added to ModelCatalog.kt or bundled in the APK: put the new model's
# .onnx files in a directory and pass it. Operators are only ever added, so the models already
# listed keep working; to start over, empty the config and pass a directory with every model.
# A model that needs an operator left out fails to load, so test each one after a rebuild.
#
# Needs: ANDROID_HOME with an NDK, python3 with `pip install cmake ninja onnx onnxruntime`.
# Output: build/onnxruntime/libonnxruntime.so. To ship it, copy it to
# app/libs/onnxruntime/arm64-v8a/, test, then upload it to the `models` release under the name
# app/build.gradle.kts fetches, with the alyoN suffix raised by one.
set -euo pipefail
cd "$(dirname "$0")/.."

ORT_VERSION=1.28.2
SDK="${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
NDK="${ANDROID_NDK_HOME:-$(ls -d "$SDK"/ndk/* | sort -V | tail -n1)}"
OUT=build/onnxruntime
SRC="$OUT/src"
CONFIG="$PWD/scripts/onnxruntime-ops.config"

mkdir -p "$OUT"
[ -d "$SRC" ] || git clone --depth 1 -b "v$ORT_VERSION" https://github.com/microsoft/onnxruntime "$SRC"

if [ -n "${1:-}" ]; then
  MODELS="$(cd "$1" && pwd)"
  # Operators as the models are published...
  python3 "$SRC/tools/python/create_reduced_build_config.py" --format ONNX "$MODELS" "$OUT/raw.config"
  # ...and as they are once the runtime has optimized them, which swaps in fused operators.
  rm -rf "$OUT/ort" && mkdir -p "$OUT/ort"
  python3 -m onnxruntime.tools.convert_onnx_models_to_ort "$MODELS" --output_dir "$OUT/ort" --optimization_style Fixed
  python3 - "$OUT/raw.config" "$OUT/ort/required_operators.config" "$CONFIG" <<'PY'
import collections, sys
ops = collections.defaultdict(set)
# sys.argv[3] is the config as it stands: what is already there stays
for path in sys.argv[1:4]:
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#"):
            domain, opset, names = line.split(";")
            ops[(domain, int(opset))] |= set(names.split(","))
with open(sys.argv[3], "w") as out:
    out.write("# Operators the models Alyo ships or downloads need, as published and after\n")
    out.write("# ONNX Runtime has optimized them. Add a model's with\n")
    out.write("# scripts/build-onnxruntime.sh <dir holding its .onnx files>\n")
    out.write("# whenever a model is added to ModelCatalog.kt.\n")
    for domain, opset in sorted(ops):
        out.write(f"{domain};{opset};{','.join(sorted(ops[(domain, opset)]))}\n")
PY
fi

"$SRC/build.sh" --build_dir "$PWD/$OUT/build" --config MinSizeRel \
  --android --android_sdk_path "$SDK" --android_ndk_path "$NDK" --android_abi arm64-v8a --android_api 24 \
  --build_shared_lib --parallel --skip_tests --skip_submodule_sync --cmake_generator Ninja \
  --compile_no_warning_as_error --disable_ml_ops --include_ops_by_config "$CONFIG"

cp "$OUT/build/MinSizeRel/libonnxruntime.so" "$OUT/libonnxruntime.so"
ls -l "$OUT/libonnxruntime.so"
