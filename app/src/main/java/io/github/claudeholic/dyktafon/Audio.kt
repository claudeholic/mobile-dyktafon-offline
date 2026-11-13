package io.github.claudeholic.dyktafon

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

const val SAMPLE_RATE = 16000

/** Rosnąca tablica próbek float (cała sesja trzymana w RAM: 10 min ~ 38 MB). */
class FloatBuf(initial: Int = SAMPLE_RATE * 30) {
    private var a = FloatArray(initial)
    @Volatile var size = 0
        private set

    @Synchronized
    fun add(x: FloatArray) {
        if (size + x.size > a.size) a = a.copyOf(maxOf(a.size * 2, size + x.size))
        System.arraycopy(x, 0, a, size, x.size)
        size += x.size
    }

    @Synchronized
    fun slice(from: Int, to: Int): FloatArray = a.copyOfRange(from.coerceIn(0, size), to.coerceIn(0, size))

    fun all(): FloatArray = slice(0, size)
}

/** Nagrywanie z mikrofonu, 16 kHz mono, paczki po 100 ms przekazywane do callbacku (w wątku nagrywania). */
class MicRecorder(private val onChunk: (FloatArray) -> Unit) {
    @Volatile private var running = false
    private var worker: Thread? = null

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf * 2, SAMPLE_RATE)
        )
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Nie udało się otworzyć mikrofonu" }
        running = true
        rec.startRecording()
        worker = thread(name = "mic") {
            val buf = ShortArray(SAMPLE_RATE / 10)
            try {
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) onChunk(FloatArray(n) { buf[it] / 32768f })
                }
            } finally {
                rec.stop()
                rec.release()
            }
        }
    }

    /** Zatrzymuje i czeka, aż ostatnia paczka trafi do callbacku. */
    fun stop() {
        running = false
        worker?.join()
        worker = null
    }
}

object Wav {
    fun write(file: File, samples: FloatArray) {
        file.parentFile?.mkdirs()
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) data.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.capacity()).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE)
            .putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.capacity())
        file.outputStream().use { it.write(h.array()); it.write(data.array()) }
    }

    /** Czyta WAV PCM 16-bit mono 16 kHz (tylko taki format jest obsługiwany). */
    fun read(file: File): FloatArray {
        RandomAccessFile(file, "r").use { f ->
            val bytes = ByteArray(f.length().toInt()); f.readFully(bytes)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "To nie jest plik WAV" }
            var pos = 12
            var channels = 0; var rate = 0; var bits = 0
            while (pos + 8 <= bytes.size) {
                val id = String(bytes, pos, 4); val len = b.getInt(pos + 4)
                if (id == "fmt ") {
                    channels = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt()
                } else if (id == "data") {
                    require(channels == 1 && rate == SAMPLE_RATE && bits == 16) {
                        "Wymagany WAV 16 kHz mono 16-bit (jest: $rate Hz, $channels kan., $bits bit)"
                    }
                    val n = minOf(len, bytes.size - pos - 8) / 2
                    return FloatArray(n) { b.getShort(pos + 8 + it * 2) / 32768f }
                }
                pos += 8 + len + (len and 1)
            }
            error("Brak danych audio w pliku WAV")
        }
    }
}
