package io.github.claudeholic.dyktafon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

enum class Mode { IDLE, REC_OFFLINE, PROCESSING, REC_REALTIME, FINISHING_REALTIME }

private val PL = Locale.forLanguageTag("pl-PL")
fun fmt1(x: Double) = String.format(PL, "%.1f", x)
fun fmt2(x: Double) = String.format(PL, "%.2f", x)

/** Stan aplikacji (przeżywa odtworzenie Activity). */
object Ctl {
    private val main = Handler(Looper.getMainLooper())
    private fun ui(f: () -> Unit) = main.post(f)

    lateinit var engine: Engine
        private set
    @Volatile private var rtRec: OfflineRecognizer? = null
    @Volatile private var offRec: OfflineRecognizer? = null
    private var loadedThreads = -1
    @Volatile private var loading = false

    // 2 wątki = dwa szybkie rdzenie A76; zmierzone na Nokii XR20 jako najszybsze (4/6/8 wolniej).
    var threads by mutableStateOf(2)
    var status by mutableStateOf("Ładowanie modeli…")
    var rtReady by mutableStateOf(false)
    var offReady by mutableStateOf(false)
    var mode by mutableStateOf(Mode.IDLE)
    var resultTitle by mutableStateOf("")
    var transcript by mutableStateOf("")
    var partial by mutableStateOf("")
    var stats by mutableStateOf("")
    var recStartMs by mutableStateOf(0L)

    private var mic: MicRecorder? = null
    private var offlineBuf: FloatBuf? = null
    private var rtSession: RealtimeSession? = null
    private var rtIsTest = false
    private var pendingTest: (() -> Unit)? = null

    fun init(ctx: Context, wantThreads: Int) {
        if (!::engine.isInitialized) engine = Engine(ctx.applicationContext)
        if (loading || (loadedThreads == wantThreads && rtRec != null && offRec != null)) return
        if (mode != Mode.IDLE) return
        loading = true
        threads = wantThreads
        rtReady = false; offReady = false
        val missing = engine.missingFiles(RT_MODEL) + engine.missingFiles(OFFLINE_MODEL)
        if (missing.isNotEmpty()) {
            status = "Brak plików modeli w ${engine.modelsRoot.absolutePath}:\n" +
                missing.joinToString("\n") + "\nWgraj je przez adb (patrz README.md)."
            loading = false
            return
        }
        status = "Ładowanie modeli (wątki CPU: $wantThreads)…"
        thread(name = "load") {
            try {
                rtRec?.release(); offRec?.release(); rtRec = null; offRec = null
                var t0 = System.nanoTime()
                val rt = engine.load(RT_MODEL, wantThreads)
                val rtMs = (System.nanoTime() - t0) / 1_000_000
                rtRec = rt
                Log.i(TAG, "MODEL_LOADED rt threads=$wantThreads ms=$rtMs")
                ui { rtReady = true; status = "FastConformer gotowy (${rtMs} ms). Ładowanie Parakeet v3…" }
                t0 = System.nanoTime()
                val off = engine.load(OFFLINE_MODEL, wantThreads)
                val offMs = (System.nanoTime() - t0) / 1_000_000
                offRec = off
                loadedThreads = wantThreads
                Log.i(TAG, "MODEL_LOADED offline threads=$wantThreads ms=$offMs")
                ui {
                    offReady = true
                    status = "Modele gotowe (wątki CPU: $wantThreads). Ładowanie: FastConformer " +
                        "${fmt1(rtMs / 1000.0)} s, Parakeet v3 ${fmt1(offMs / 1000.0)} s"
                    loading = false
                    pendingTest?.let { pendingTest = null; it() }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "MODEL_LOAD_FAILED", e)
                ui { status = "Błąd ładowania modeli: ${e.message}"; loading = false }
            }
        }
    }

    // ---------- Tryb 1: nagraj i przerób później ----------

    fun startOffline() {
        if (mode != Mode.IDLE || offRec == null) return
        val buf = FloatBuf()
        offlineBuf = buf
        mic = MicRecorder { buf.add(it) }.also { it.start() }
        resultTitle = "Nagrywanie (przetwarzanie po zatrzymaniu)…"
        transcript = ""; partial = ""; stats = ""
        recStartMs = System.currentTimeMillis()
        mode = Mode.REC_OFFLINE
    }

    fun stopOffline() {
        if (mode != Mode.REC_OFFLINE) return
        mic?.stop(); mic = null
        val audio = offlineBuf!!.all(); offlineBuf = null
        saveRecording("po_nagraniu", audio)
        processOffline(audio, "nagranie")
    }

    private fun processOffline(audio: FloatArray, what: String) {
        val r = offRec ?: return
        mode = Mode.PROCESSING
        val dur = audio.size / SAMPLE_RATE.toDouble()
        resultTitle = "Przetwarzanie modelem Parakeet v3…"
        stats = "Długość: ${fmt1(dur)} s"
        thread(name = "offline") {
            val t0 = System.nanoTime()
            val text = try {
                engine.transcribeWhole(r, audio) { p -> ui { transcript = p } }
            } catch (e: Throwable) {
                Log.e(TAG, "offline decode", e); "[błąd: ${e.message}]"
            }
            val proc = (System.nanoTime() - t0) / 1e9
            val ratio = if (dur > 0) proc / dur else 0.0
            val line = "Nagranie ${fmt1(dur)} s, przetwarzanie ${fmt1(proc)} s, ${fmt2(ratio)}×"
            Log.i(TAG, "OFFLINE_RESULT $what threads=$threads dur=${"%.2f".format(dur)} " +
                "proc=${"%.2f".format(proc)} ratio=${"%.3f".format(ratio)} text=$text")
            ui {
                resultTitle = "Wynik (po nagraniu, Parakeet v3)"
                transcript = text.ifBlank { "(nie rozpoznano mowy)" }
                stats = "$line\n(stosunek = czas przetwarzania / długość nagrania; <1 = szybciej niż nagranie)"
                mode = Mode.IDLE
            }
        }
    }

    // ---------- Tryb 2: czas rzeczywisty ----------

    fun startRealtime() = startRealtimeInternal(null)

    private fun startRealtimeInternal(testAudio: FloatArray?) {
        val r = rtRec ?: return
        if (mode != Mode.IDLE) return
        resultTitle = "Na żywo (FastConformer)…"
        transcript = ""; partial = ""; stats = ""
        val s = RealtimeSession(engine, r,
            onUpdate = { c, p, st -> ui { transcript = c; partial = p; stats = st } },
            onDone = { c, st -> ui {
                transcript = c.ifBlank { "(nie rozpoznano mowy)" }; partial = ""
                stats = st; resultTitle = "Wynik (czas rzeczywisty, FastConformer)"
                mode = Mode.IDLE
            } })
        rtSession = s
        rtIsTest = testAudio != null
        recStartMs = System.currentTimeMillis()
        mode = Mode.REC_REALTIME
        if (testAudio == null) {
            mic = MicRecorder { s.feed(it) }.also { it.start() }
        } else {
            // Test: plik WAV podawany w tempie nagrania (paczki 100 ms co 100 ms).
            thread(name = "wavfeed") {
                val step = SAMPLE_RATE / 10
                val t0 = System.nanoTime()
                var i = 0; var k = 0
                while (i < testAudio.size) {
                    s.feed(testAudio.copyOfRange(i, minOf(i + step, testAudio.size)))
                    i += step; k++
                    val wait = t0 + k * 100_000_000L - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
                ui { stopRealtime() }
            }
        }
    }

    fun stopRealtime() {
        if (mode != Mode.REC_REALTIME) return
        mode = Mode.FINISHING_REALTIME
        val s = rtSession ?: return
        thread(name = "rtstop") {
            mic?.stop(); mic = null
            s.finish()
            if (!rtIsTest) saveRecording("na_zywo", s.audio.all())
        }
    }

    // ---------- Testy z pliku (adb) ----------

    fun runTest(file: File, testMode: String) {
        val job = {
            try {
                val audio = Wav.read(file)
                Log.i(TAG, "TEST_START mode=$testMode file=${file.name} dur=${audio.size / SAMPLE_RATE.toDouble()}")
                if (testMode == "realtime") startRealtimeInternal(audio)
                else processOffline(audio, "plik ${file.name}")
            } catch (e: Throwable) {
                Log.e(TAG, "TEST_FAILED", e); status = "Test: ${e.message}"
            }
        }
        if (mode == Mode.IDLE && rtRec != null && offRec != null && !loading) job() else pendingTest = job
    }

    private fun saveRecording(prefix: String, audio: FloatArray) {
        if (audio.isEmpty()) return
        try {
            val name = prefix + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
            Wav.write(File(engine.modelsRoot.parentFile, "nagrania/$name"), audio)
        } catch (e: Throwable) {
            Log.w(TAG, "save wav", e)
        }
    }
}
