package io.github.claudeholic.dyktafon

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

const val TAG = "Dyktafon"

/** Model wgrywany przez adb do <external files>/models/<dir>/. */
data class ModelSpec(val label: String, val dir: String) {
    val files = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
}

/** Tryb czasu rzeczywistego: mały, szybki model (NeMo FastConformer, 10 języków w tym pl, 134 MB int8). */
val RT_MODEL = ModelSpec(
    "NeMo FastConformer transducer (int8, pl)",
    "sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8"
)

/** Tryb po nagraniu: duży, dokładniejszy model (Parakeet TDT 0.6B v3, 25 języków w tym pl). */
val OFFLINE_MODEL = ModelSpec(
    "NVIDIA Parakeet TDT 0.6B v3 (int8)",
    "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
)

class Engine(private val ctx: Context) {
    val modelsRoot: File = File(ctx.getExternalFilesDir(null), "models")

    init {
        // Katalogi tworzy sama aplikacja (właściciel = UID aplikacji). Katalogi utworzone przez
        // `adb shell mkdir` mają właściciela shell i aplikacja nie widzi ich zawartości.
        for (d in listOf(RT_MODEL.dir, OFFLINE_MODEL.dir)) File(modelsRoot, d).mkdirs()
        File(ctx.getExternalFilesDir(null), "test").mkdirs()
        File(ctx.getExternalFilesDir(null), "nagrania").mkdirs()
    }

    fun missingFiles(m: ModelSpec): List<String> =
        m.files.filter { !File(File(modelsRoot, m.dir), it).isFile }.map { "${m.dir}/$it" }

    fun load(m: ModelSpec, threads: Int): OfflineRecognizer {
        val d = File(modelsRoot, m.dir).absolutePath
        val cfg = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = "$d/encoder.int8.onnx",
                    decoder = "$d/decoder.int8.onnx",
                    joiner = "$d/joiner.int8.onnx",
                ),
                tokens = "$d/tokens.txt",
                modelType = "nemo_transducer",
                numThreads = threads,
                provider = "cpu",
            ),
            decodingMethod = "greedy_search",
        )
        return OfflineRecognizer(assetManager = null, config = cfg)
    }

    fun newVad(maxSpeechSec: Float): Vad = Vad(
        assetManager = ctx.assets,
        config = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = 0.35f,
                minSpeechDuration = 0.25f,
                windowSize = VAD_WINDOW,
                maxSpeechDuration = maxSpeechSec,
            ),
            sampleRate = SAMPLE_RATE,
            numThreads = 1,
        ),
    )

    companion object {
        const val VAD_WINDOW = 512

        /** Dodaje ciszę na początku (0,3 s) i końcu (0,5 s) – modele NeMo gubią inaczej skrajne słowa. */
        fun pad(a: FloatArray): FloatArray {
            val head = SAMPLE_RATE * 3 / 10
            val out = FloatArray(head + a.size + SAMPLE_RATE / 2)
            System.arraycopy(a, 0, out, head, a.size)
            return out
        }

        fun decode(r: OfflineRecognizer, samples: FloatArray): String {
            val s = r.createStream()
            try {
                s.acceptWaveform(pad(samples), SAMPLE_RATE)
                r.decode(s)
                return r.getResult(s).text.trim()
            } finally {
                s.release()
            }
        }
    }

    /**
     * Transkrypcja całego nagrania. Do 30 s – jedno przejście modelu na całości.
     * Dłuższe nagrania są cięte detektorem mowy (Silero VAD) na fragmenty do 25 s,
     * żeby nie wyczerpać pamięci (koszt uwagi rośnie kwadratowo z długością).
     */
    fun transcribeWhole(r: OfflineRecognizer, audio: FloatArray, onPartial: (String) -> Unit): String {
        if (audio.size <= SAMPLE_RATE * 30) return decode(r, audio)
        val vad = newVad(25f)
        val parts = ArrayList<String>()
        val ctxSamples = SAMPLE_RATE / 4
        fun drain() {
            while (!vad.empty()) {
                val seg = vad.front(); vad.pop()
                val st = (seg.start.toInt() - ctxSamples).coerceAtLeast(0)
                val en = (seg.start.toInt() + seg.samples.size + ctxSamples).coerceAtMost(audio.size)
                val t = decode(r, audio.copyOfRange(st, en))
                if (t.isNotBlank()) parts += t
                onPartial(parts.joinToString(" "))
            }
        }
        try {
            var i = 0
            while (i + VAD_WINDOW <= audio.size) {
                vad.acceptWaveform(audio.copyOfRange(i, i + VAD_WINDOW)); i += VAD_WINDOW
                drain()
            }
            vad.flush(); drain()
        } finally {
            vad.release()
        }
        return parts.joinToString(" ")
    }
}

/**
 * Tryb „czas rzeczywisty” (symulowany streaming): Silero VAD wykrywa początek i koniec wypowiedzi,
 * a w trakcie mówienia bieżący fragment jest dekodowany od nowa tak często, jak pozwala CPU
 * (nie częściej niż co 250 ms). Po końcu wypowiedzi fragment jest dekodowany ostatecznie i zatwierdzany.
 */
class RealtimeSession(
    private val engine: Engine,
    private val rec: OfflineRecognizer,
    private val onUpdate: (committed: String, partial: String, stats: String) -> Unit,
    private val onDone: (committed: String, stats: String) -> Unit,
) {
    private val queue = LinkedBlockingQueue<FloatArray>()
    val audio = FloatBuf()
    private val committed = ArrayList<String>()
    private var nPartial = 0; private var sumPartialMs = 0L; private var maxPartialMs = 0L
    private var lastPartialMs = 0L; private var lastPartialAudio = 0f
    private var nFinal = 0; private var sumFinalMs = 0L

    private val worker = thread(name = "realtime") { loop() }

    fun feed(chunk: FloatArray) = queue.put(chunk)
    fun finish() = queue.put(FloatArray(0))

    private fun stats(): String {
        val avg = if (nPartial > 0) sumPartialMs / nPartial else 0
        val avgF = if (nFinal > 0) sumFinalMs / nFinal else 0
        return "Podgląd: $nPartial dekodowań, śr. $avg ms, maks. $maxPartialMs ms " +
            "(ostatnie ${lastPartialMs} ms dla ${"%.1f".format(lastPartialAudio)} s mowy)\n" +
            "Zatwierdzone wypowiedzi: $nFinal, śr. $avgF ms"
    }

    private fun loop() {
        val vad = engine.newVad(8f)
        var vadOffset = 0
        var speechStart = -1
        var lastDecode = 0L
        var partial = ""
        val ctxSamples = SAMPLE_RATE / 4
        try {
            var ending = false
            while (!ending) {
                val chunks = ArrayList<FloatArray>()
                chunks += queue.take(); queue.drainTo(chunks)
                for (c in chunks) if (c.isEmpty()) ending = true else audio.add(c)

                while (vadOffset + Engine.VAD_WINDOW <= audio.size) {
                    vad.acceptWaveform(audio.slice(vadOffset, vadOffset + Engine.VAD_WINDOW))
                    vadOffset += Engine.VAD_WINDOW
                    if (speechStart < 0 && vad.isSpeechDetected()) {
                        speechStart = (vadOffset - SAMPLE_RATE / 2).coerceAtLeast(0)
                    }
                }
                if (ending) vad.flush()

                // Zakończone wypowiedzi -> dekodowanie ostateczne.
                while (!vad.empty()) {
                    val seg = vad.front(); vad.pop()
                    val segEnd = seg.start.toInt() + seg.samples.size
                    val st = (seg.start.toInt() - ctxSamples).coerceAtLeast(0)
                    val en = (segEnd + ctxSamples).coerceAtMost(audio.size)
                    val t0 = System.nanoTime()
                    val text = Engine.decode(rec, audio.slice(st, en))
                    sumFinalMs += (System.nanoTime() - t0) / 1_000_000; nFinal++
                    if (text.isNotBlank()) committed += text
                    partial = ""
                    speechStart = if (vad.isSpeechDetected()) segEnd else -1
                }

                // Podgląd bieżącej wypowiedzi (tylko gdy nie ma zaległego audio w kolejce).
                val now = System.nanoTime()
                if (!ending && speechStart >= 0 && queue.isEmpty() && now - lastDecode > 250_000_000L) {
                    val seg = audio.slice(speechStart, audio.size)
                    val t0 = System.nanoTime()
                    partial = Engine.decode(rec, seg)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    nPartial++; sumPartialMs += ms; maxPartialMs = maxOf(maxPartialMs, ms)
                    lastPartialMs = ms; lastPartialAudio = seg.size / SAMPLE_RATE.toFloat()
                    lastDecode = System.nanoTime()
                }
                onUpdate(committed.joinToString(" "), partial, stats())
            }
        } catch (e: Throwable) {
            Log.e(TAG, "realtime loop", e)
            committed += "[błąd: ${e.message}]"
        } finally {
            vad.release()
        }
        Log.i(TAG, "RT_DONE audio=${"%.1f".format(audio.size / SAMPLE_RATE.toFloat())}s ${stats().replace('\n', ' ')}")
        onDone(committed.joinToString(" "), stats())
    }
}
