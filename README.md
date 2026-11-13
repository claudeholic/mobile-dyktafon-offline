# Dyktafon STT: lokalna transkrypcja mowy na Androidzie (offline)

> **To jest POC (proof of concept), a nie docelowa aplikacja.** Powstała w 40 minut, żeby sprawdzić, jak lokalne modele rozpoznawania mowy radzą sobie na zwykłym telefonie ze średniej półki. Nie ma tu dopracowanego interfejsu, obsługi błędów na każdą okazję ani testów automatycznych.

Dyktafon na Androida, który zamienia polską mowę na tekst **w całości na telefonie**: bez internetu, bez chmury, bez abonamentu. Liczenie odbywa się wyłącznie na CPU telefonu.

## Dedykacja

Dla **Grzegorza** z LinkedIn, którego post o płatnej transkrypcji w słuchawkach kończył się pytaniem: płacić za AI w słuchawkach czy woleć aplikację na telefonie?

To pytanie zadziałało lepiej niż kawa: o pierwszej w nocy autor uznał, że najprościej będzie sprawdzić drugą opcję w praktyce. Oto wynik. Grzegorzu, dzięki za natchnienie. Za niewyspanie autor nie wystawia faktury.

## Co robi aplikacja

Na ekranie są dwa duże czerwone przyciski:

1. **„Nagraj i przerób później”**: pierwsze kliknięcie startuje nagrywanie, drugie je zatrzymuje. Potem model przetwarza całe nagranie, a aplikacja pokazuje tekst i pomiar, np. *„Nagranie 24,3 s, przetwarzanie 4,3 s, 0,18×”*. Stosunek to czas przetwarzania podzielony przez długość nagrania; wartość poniżej 1 oznacza, że przetwarzanie trwa krócej niż nagranie.
2. **„Przetwarzaj w czasie rzeczywistym”**: pierwsze kliknięcie startuje, drugie zatrzymuje. Tekst pojawia się na ekranie w trakcie mówienia. Bieżąca, jeszcze niezatwierdzona wypowiedź jest pokazana kursywą.

Każda sesja jest zapisywana jako WAV (16 kHz mono) w `Android/data/io.github.claudeholic.dyktafon/files/nagrania/`.

### Modele i biblioteka

| Tryb | Model | Rozmiar (int8) | Języki |
|---|---|---|---|
| Nagraj i przerób później | NVIDIA **Parakeet TDT 0.6B v3** | ~670 MB | 25 języków europejskich, w tym polski |
| Czas rzeczywisty | NVIDIA NeMo **FastConformer** hybrid large, wielojęzyczny | ~138 MB | be, de, en, es, fr, hr, it, **pl**, ru, uk |
| (oba) | **Silero VAD**, wykrywanie mowy | 0,6 MB | – |

- Inferencję wykonuje [**sherpa-onnx**](https://github.com/k2-fsa/sherpa-onnx) 2.0.1 (ONNX Runtime), na CPU, 2 wątki.
- Oba modele same rozpoznają język, więc nie trzeba go ustawiać.
- Tryb czasu rzeczywistego to **symulowany streaming**. Strumieniowego modelu z językiem polskim w sherpa-onnx nie ma, więc Silero VAD wykrywa wypowiedzi, a bieżący fragment jest dekodowany od nowa co ok. 0,2–0,3 s. Po pauzie fragment zostaje zatwierdzony.

## Wydajność: krótkie testy, nie benchmark

Telefon: **Nokia XR20**, Snapdragon 480 (SM4350: 2× Cortex-A76 + 6× Cortex-A55), 6 GB RAM, Android 14. Liczenie wyłącznie na CPU. Poniższe liczby pochodzą z kilku krótkich prób. To orientacyjny obraz, a nie rzetelny pomiar.

**Dyktowanie na żywo przez autora (mikrofon telefonu):**

| Tryb | Nagranie | Wynik |
|---|---|---|
| Nagraj i przerób później (Parakeet v3) | 24,3 s | przetwarzanie **4,34 s → 0,18×**; ocena autora: transkrypcja idealna, szybciej niż Whisper na jego komputerze stacjonarnym (subiektywne porównanie) |
| Czas rzeczywisty (FastConformer) | 22,8 s | tekst odświeżany średnio co 177 ms (maks. 318 ms); ocena autora: lekkie błędy, ale akceptowalne |

**Testy na plikach WAV** (polska mowa z syntezatora piper TTS plus jedno krótkie nagranie prawdziwej mowy):

| Test | Długość | Czas przetwarzania | Stosunek |
|---|---|---|---|
| Parakeet v3, 2 wątki | 25,1 s | 4,7–5,0 s (pierwsze uruchomienie po załadowaniu: 5,5 s) | 0,19–0,22× |
| Parakeet v3, 2 wątki, nagranie cięte VAD | 44,1 s | 9,8 s | 0,22× |
| Parakeet v3, 4 wątki, mowa prawdziwa | 2,8 s | 0,64 s | 0,23× |
| Parakeet v3, 8 wątków | 25,1 s | 7,0 s | 0,28× |

- Dwa wątki, czyli dwa szybkie rdzenie A76, okazały się najszybsze. Więcej wątków nie pomaga, a 8 wyraźnie spowalnia.
- Czas rzeczywisty na plikach WAV podawanych w tempie nagrania: podgląd dekodowany średnio w 290–320 ms (maks. 0,6–0,7 s). Sesja kończy się poniżej 1 s po końcu mowy.
- Ładowanie modeli po starcie aplikacji: FastConformer ok. 2,5–2,7 s, Parakeet v3 ok. 5–6,4 s.
- Pamięć RAM z oboma modelami: ok. 1,45 GB.

## Jak zbudować i zainstalować

Wymagania:
- Linux lub macOS;
- JDK 17 lub nowszy;
- Android SDK z platformą 36 (zmienna `ANDROID_HOME` albo plik `local.properties` z `sdk.dir=...`);
- `adb`, telefon z Androidem 8+ (arm64) i włączonym debugowaniem USB;
- ok. 1 GB miejsca na modele.

```bash
# 1. Biblioteka sherpa-onnx (AAR), Silero VAD i oba modele STT (~650 MB do pobrania, ~860 MB po rozpakowaniu)
scripts/download.sh

# 2. Budowa, instalacja, uprawnienie do mikrofonu, wgranie modeli na telefon
export ANDROID_HOME=$HOME/Android/Sdk
scripts/install_and_push.sh
```

Ręcznie:

```bash
scripts/download.sh                 # albo: scripts/download.sh --build-only (bez modeli STT)
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant io.github.claudeholic.dyktafon android.permission.RECORD_AUDIO
adb shell am start -n io.github.claudeholic.dyktafon/.MainActivity   # najpierw uruchom: aplikacja tworzy swoje katalogi

EXT=/sdcard/Android/data/io.github.claudeholic.dyktafon/files
for m in sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8 \
         sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8; do
  for f in encoder.int8.onnx decoder.int8.onnx joiner.int8.onnx tokens.txt; do
    adb push models/$m/$f $EXT/models/$m/$f
  done
done
adb shell am force-stop io.github.claudeholic.dyktafon && adb shell am start -n io.github.claudeholic.dyktafon/.MainActivity
```

Wyniki i czasy trafiają też do logcat: `adb logcat -s Dyktafon`.

**Test bez mówienia:** wgraj plik WAV (16 kHz, mono, 16-bit) do `$EXT/test/` i uruchom:

```bash
adb shell am start -n io.github.claudeholic.dyktafon/.MainActivity --es test_wav plik.wav --es test_mode offline   # albo realtime
```

W trybie `realtime` plik jest podawany w tempie nagrania. Opcjonalnie `--ei threads N` zmienia liczbę wątków i przeładowuje modele.

## Znane ograniczenia

- **Symulowany streaming** w trybie czasu rzeczywistego:
  - roboczy tekst może się zmieniać, dopóki trwa wypowiedź;
  - przy mówieniu bez pauz fragment jest cięty co 8 s, co może przeciąć słowo;
  - FastConformer jest szybki, ale mniej dokładny od Parakeet.
- **Okno Play Protect przy instalacji:** debug APK spoza sklepu wywołuje pytanie „Wysłać aplikację do sprawdzenia?”. Instalacja przechodzi po wybraniu „Nie wysyłaj” (albo „Wyślij tym razem”).
- Nagrania dłuższe niż 30 s są w trybie po nagraniu cięte detektorem mowy na fragmenty do 25 s, żeby ograniczyć zużycie pamięci.
- Modele leżą w katalogu aplikacji (`Android/data/...`), więc **odinstalowanie aplikacji je usuwa**. Aktualizacja przez `adb install -r` je zachowuje.
- Katalogi na modele musi utworzyć sama aplikacja przy pierwszym uruchomieniu. Pliki wgrane do katalogów utworzonych przez `adb shell mkdir` są dla aplikacji niewidoczne.
- Ok. 1,45 GB RAM i ok. 8 s ładowania modeli po starcie.
- Tylko arm64-v8a, tylko orientacja pionowa, brak nagrywania w tle (brak usługi pierwszoplanowej) i brak kopiowania tekstu.
- Główna aktywność przyjmuje parametry testowe z intencji (`test_wav`, `test_mode`, `threads`). To wygodne w POC, ale niepożądane w aplikacji produkcyjnej.
- Nie sprawdzono: akceleracji GPU/NPU, długich nagrań (kilka minut i więcej), zużycia baterii i nagrzewania, pracy w hałasie.

## Licencje

Kod tego projektu (aplikacja w `app/`, pliki `*.gradle.kts` i `gradle/libs.versions.toml`, skrypty w `scripts/`; bez Gradle Wrapper `gradlew` i `gradle/wrapper/`, który jest na Apache-2.0) jest udostępniony na licencji **MIT**, patrz plik [LICENSE](LICENSE).

Modele i biblioteki zewnętrzne mają własne licencje, opisane w [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md):
- Parakeet TDT 0.6B v3: CC-BY-4.0;
- FastConformer wielojęzyczny: NGC Terms of Use;
- sherpa-onnx: Apache-2.0;
- ONNX Runtime i Silero VAD: MIT.

Repozytorium nie zawiera modeli ani bibliotek binarnych.
