#!/usr/bin/env bash
# Buduje APK (debug), instaluje na podłączonym telefonie, nadaje uprawnienie do mikrofonu
# i wgrywa modele do katalogu aplikacji.
# Wymaga: ANDROID_HOME (Android SDK z platformą 36), JDK 17+, adb, wcześniej scripts/download.sh.
#
# Kolejność ma znaczenie: katalogi na modele musi utworzyć sama aplikacja (robi to przy starcie).
# Katalogi utworzone przez `adb shell mkdir` mają właściciela shell i aplikacja nie widzi plików.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PKG=io.github.claudeholic.dyktafon
EXT=/sdcard/Android/data/$PKG/files
MODELS=(
  sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8
  sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8
)

cd "$ROOT"
./gradlew --no-daemon assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk   # na telefonie może pojawić się okno Play Protect
adb shell pm grant $PKG android.permission.RECORD_AUDIO
adb shell am start -n $PKG/.MainActivity >/dev/null       # aplikacja tworzy models/, test/, nagrania/
until adb shell ls $EXT/test >/dev/null 2>&1; do sleep 1; done

for m in "${MODELS[@]}"; do
  for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
    adb push "models/$m/$f" "$EXT/models/$m/$f"
  done
done
adb shell am force-stop $PKG
adb shell am start -n $PKG/.MainActivity >/dev/null
echo "Gotowe. Wyniki i czasy: adb logcat -s Dyktafon"
