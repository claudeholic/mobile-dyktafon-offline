# Licencje bibliotek i modeli

To repozytorium **nie zawiera** modeli ani bibliotek binarnych. Pobiera je skrypt `scripts/download.sh` z oficjalnych wydań projektu sherpa-onnx. Każdy składnik ma własną licencję, którą należy respektować przy dalszej dystrybucji, na przykład gdy dołączasz model do APK albo publikujesz zbudowaną aplikację.

Stan sprawdzony w listopadzie 2026 w podanych źródłach.

## Modele

| Składnik | Do czego | Licencja / warunki | Źródło |
|---|---|---|---|
| NVIDIA Parakeet TDT 0.6B v3 (eksport ONNX int8 przez sherpa-onnx) | tryb „Nagraj i przerób później” | **CC-BY-4.0** | https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 (sekcja „License/Terms of Use”) |
| NVIDIA NeMo `stt_multilingual_fastconformer_hybrid_large_pc` (be, de, en, es, fr, hr, it, pl, ru, uk; eksport ONNX int8 przez sherpa-onnx) | tryb „Przetwarzaj w czasie rzeczywistym” | **NGC Terms of Use**: karta modelu nie podaje innej licencji, a NGC stosuje wtedy swoje warunki korzystania | https://catalog.ngc.nvidia.com/orgs/nvidia/teams/nemo/models/stt_multilingual_fastconformer_hybrid_large_pc, warunki: https://ngc.nvidia.com/legal/terms |
| Silero VAD (`silero_vad.onnx`) | wykrywanie mowy | **MIT** | https://github.com/snakers4/silero-vad |

Gotowe paczki modeli w formacie sherpa-onnx: https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models

## Biblioteki

| Składnik | Licencja | Źródło |
|---|---|---|
| sherpa-onnx 2.0.1 (AAR: kod Kotlin + biblioteki natywne) | **Apache-2.0** | https://github.com/k2-fsa/sherpa-onnx |
| ONNX Runtime (biblioteka natywna `libonnxruntime.so` w AAR sherpa-onnx) | **MIT** | https://github.com/microsoft/onnxruntime |
| AndroidX (core, activity, Jetpack Compose, Material 3) | **Apache-2.0** | https://github.com/androidx/androidx |
| Kotlin (kompilator, biblioteka standardowa) | **Apache-2.0** | https://github.com/JetBrains/kotlin |
| Gradle Wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.jar`) | **Apache-2.0** | https://github.com/gradle/gradle |

## Kod tej aplikacji

Kod tego projektu (aplikacja w `app/`, pliki `*.gradle.kts` i `gradle/libs.versions.toml`, skrypty w `scripts/`; bez Gradle Wrapper `gradlew` i `gradle/wrapper/`, który jest na Apache-2.0) jest na licencji **MIT**, patrz plik [LICENSE](LICENSE). Licencja MIT dotyczy wyłącznie tego kodu. Modele i biblioteki wymienione wyżej mają własne licencje.
