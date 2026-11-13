#!/usr/bin/env bash
# Pobiera bibliotekę sherpa-onnx (AAR), model VAD oraz dwa modele STT z GitHub Releases projektu sherpa-onnx.
#   app/libs/sherpa-onnx-2.0.1.aar          ~49 MB  (potrzebne do zbudowania)
#   app/src/main/assets/silero_vad.onnx     ~0,6 MB (potrzebne do zbudowania, trafia do APK)
#   models/<FastConformer>/                 ~138 MB (wgrywane na telefon przez adb)
#   models/<Parakeet TDT 0.6B v3>/          ~670 MB (wgrywane na telefon przez adb)
# Użycie: scripts/download.sh            (wszystko)
#         scripts/download.sh --build-only (tylko AAR i VAD, bez modeli STT)
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REL=https://github.com/k2-fsa/sherpa-onnx/releases/download
SHERPA_VERSION=2.0.1
MODELS=(
  sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8
  sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
)

mkdir -p "$ROOT/app/libs" "$ROOT/app/src/main/assets" "$ROOT/models"

aar="$ROOT/app/libs/sherpa-onnx-$SHERPA_VERSION.aar"
[ -s "$aar" ] || curl -fL -o "$aar" "$REL/v$SHERPA_VERSION/sherpa-onnx-$SHERPA_VERSION.aar"

vad="$ROOT/app/src/main/assets/silero_vad.onnx"
[ -s "$vad" ] || curl -fL -o "$vad" "$REL/asr-models/silero_vad.onnx"

[ "${1:-}" = "--build-only" ] && exit 0

for m in "${MODELS[@]}"; do
  if [ -s "$ROOT/models/$m/encoder.int8.onnx" ]; then echo "jest już: $m"; continue; fi
  echo "pobieram: $m"
  curl -fL "$REL/asr-models/$m.tar.bz2" | tar xj -C "$ROOT/models"
done
du -sh "$ROOT"/models/*
