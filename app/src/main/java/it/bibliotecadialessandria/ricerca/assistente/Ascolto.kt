package it.bibliotecadialessandria.ricerca.assistente

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * L'agente ASCOLTO (11/11/2026, STUDIO_ASSISTENTE_AI.md §2.4, §4.2, §4.12): vocale → testo.
 *
 * REGISTRARE. Con AudioRecord, suono grezzo a 16.000 campioni al secondo, una voce sola, salvato in WAV: e' gia' la
 * forma che vuole il riconoscimento del telefono, e per il modello l'etichetta `wav` e' VERA (la trappola di Gemini
 * del bot WhatsApp — formato `wav`/`mp3` anche per byte Ogg — qui non si presenta: si manda sempre un WAV vero).
 * Un file audio allegato (mp3, m4a, ogg…) si decodifica e si riporta a WAV 16 kHz con [pcm] (da FroWay).
 *
 * TRASCRIVERE, in ordine:
 *  1. il modello per l'audio (`ConfigAi.modelli.audio`), blocco OpenAI `input_audio` con `format: wav`;
 *  2. il riconoscimento vocale del telefono sul file (solo Android 13+, `EXTRA_AUDIO_SOURCE`, codice di FroWay).
 * Regola del bot: se non c'e' ne' l'uno ne' l'altro, il microfono non compare ([disponibile]).
 * Nel registro solo durate e lunghezze, mai cosa si dice.
 */
object Ascolto {

    private const val TAG = "Assistente"
    const val FREQUENZA = 16000
    const val MASSIMO_MS = 2 * 60_000L

    /** C'e' un modo per trascrivere? (modello per l'audio scelto, oppure riconoscimento sul telefono da file) */
    fun disponibile(ctx: Context): Boolean = ConfigAi.modelli(ctx).audio.isNotBlank() || telefono(ctx)

    /** Il telefono sa scrivere un vocale gia' registrato (Android 13+)? */
    fun telefono(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        runCatching { SpeechRecognizer.isRecognitionAvailable(ctx) }.getOrDefault(false)

    // ── registrare ───────────────────────────────────────────────────

    /** Un vocale in registrazione: [ferma] restituisce il suono grezzo (PCM 16 bit, 16 kHz, mono). */
    class Registratore {
        private var rec: AudioRecord? = null
        private val dati = ByteArrayOutputStream()
        @Volatile private var vai = false
        private var filo: Thread? = null
        val inizio = System.currentTimeMillis()

        @SuppressLint("MissingPermission")
        fun parti(): Boolean {
            val min = AudioRecord.getMinBufferSize(FREQUENZA, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val r = runCatching { AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, FREQUENZA,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 2) }.getOrNull() ?: return false
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); return false }
            rec = r; vai = true
            r.startRecording()
            filo = Thread {
                val buf = ByteArray(4096)
                while (vai) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) synchronized(dati) { dati.write(buf, 0, n) }
                    if (System.currentTimeMillis() - inizio > MASSIMO_MS) vai = false
                }
            }.apply { isDaemon = true; start() }
            return true
        }

        fun ferma(): ByteArray {
            vai = false
            runCatching { filo?.join(1500) }
            runCatching { rec?.stop() }
            runCatching { rec?.release() }
            rec = null
            return synchronized(dati) { dati.toByteArray() }
        }

        val durataMs get() = System.currentTimeMillis() - inizio
    }

    /** Il suono grezzo in un file WAV (intestazione RIFF di 44 byte). */
    fun wav(pcm: ByteArray, frequenza: Int = FREQUENZA): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()); b.putInt(36 + pcm.size); b.put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()); b.putInt(16); b.putShort(1); b.putShort(1); b.putInt(frequenza)
        b.putInt(frequenza * 2); b.putShort(2); b.putShort(16)
        b.put("data".toByteArray()); b.putInt(pcm.size); b.put(pcm)
        return b.array()
    }

    /** Il suono grezzo di un WAV fatto da [wav] (o di qualunque audio: si decodifica). */
    fun pcmDi(f: File): ByteArray? {
        val testa = runCatching { f.inputStream().use { i -> ByteArray(44).also { i.read(it) } } }.getOrNull() ?: return null
        if (String(testa, 0, 4) == "RIFF" && String(testa, 8, 4) == "WAVE" && String(testa, 36, 4) == "data" &&
            ByteBuffer.wrap(testa, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int == FREQUENZA &&
            ByteBuffer.wrap(testa, 22, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() == 1)
            return f.readBytes().copyOfRange(44, f.length().toInt())
        return pcm(f.absolutePath)
    }

    fun durataMs(pcm: ByteArray) = pcm.size / 32L

    // ── trascrivere ──────────────────────────────────────────────────

    /** Il vocale (o l'audio allegato) [f] in testo. Vuoto se non ci si riesce. Fuori dal filo dell'interfaccia. */
    fun trascrivi(ctx: Context, f: File): String {
        val voce = pcmDi(f) ?: run { Log.w(TAG, "ascolto: audio non leggibile"); return "" }
        Log.i(TAG, "ascolto: ${durataMs(voce) / 1000.0} secondi da trascrivere")
        // (11/11/2026) solo silenzio o rumore di fondo: non si manda al modello, che altrimenti inventa una frase
        // (provato sul Tab8: 4 secondi di stanza trascritti «Inoltre dovresti»)
        if (silenzio(voce)) { Log.i(TAG, "ascolto: solo silenzio, niente da trascrivere"); return "" }
        val modello = ConfigAi.modelli(ctx).audio
        if (modello.isNotBlank()) {
            val t = runCatching { colModello(ctx, modello, voce) }
                .onFailure { Log.w(TAG, "ascolto: il modello audio non ha trascritto (${it.message?.take(120)})") }.getOrDefault("")
            if (t.isNotBlank()) { Log.i(TAG, "ascolto: trascritto col modello (${t.length} caratteri)"); return t }
        }
        if (telefono(ctx)) {
            val t = runCatching { colTelefono(ctx, voce) }.getOrDefault("")
            Log.i(TAG, "ascolto: trascritto sul telefono (${t.length} caratteri)")
            return t
        }
        return ""
    }

    private fun colModello(ctx: Context, modello: String, voce: ByteArray): String {
        val wav = wav(voce)
        val blocchi = JSONArray()
            .put(Vista.testo("Trascrivi parola per parola questo messaggio vocale, nella lingua in cui e' detto (di solito " +
                "italiano). Rispondi SOLO con la trascrizione, senza commenti, senza virgolette e senza tradurre. Se non si " +
                "sente nessuna parola rispondi esattamente: [silenzio]"))
            // la trappola di Gemini (bot WhatsApp, §2.4): il formato deve essere `wav` o `mp3`; qui i byte sono un WAV vero
            .put(JSONObject().put("type", "input_audio").put("input_audio", JSONObject()
                .put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav")))
        val t = Vista.chiama(ctx, modello, blocchi).trim().trim('"', '«', '»')
        return if (t.equals("[silenzio]", true)) "" else t
    }

    // ── il riconoscimento del telefono (da FroWay, Trascrizione.kt) ─────

    private fun colTelefono(ctx: Context, voce: ByteArray): String {
        if (Build.VERSION.SDK_INT < 33) return ""
        alza(voce)
        val pezzi = ArrayList<String>()
        for ((da, a) in parlati(voce)) riconosci(ctx, voce.copyOfRange(da, a)).trim().takeIf { it.isNotEmpty() }?.let { pezzi.add(it) }
        return pezzi.joinToString(" ")
    }

    /** Nessun tratto parlato, o un volume massimo troppo basso per una voce vicina al microfono. */
    fun silenzio(voce: ByteArray): Boolean {
        val c = ByteBuffer.wrap(voce).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var massimo = 0
        for (i in 0 until c.remaining()) massimo = maxOf(massimo, Math.abs(c.get(i).toInt()))
        return massimo < 1000 || parlati(voce).isEmpty()
    }

    private fun alza(voce: ByteArray) {
        val c = ByteBuffer.wrap(voce).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var massimo = 1
        for (i in 0 until c.remaining()) massimo = maxOf(massimo, Math.abs(c.get(i).toInt()))
        val quanto = minOf(8.0, 26000.0 / massimo)
        if (quanto <= 1.15) return
        for (i in 0 until c.remaining()) c.put(i, (c.get(i) * quanto).toInt().coerceIn(-32768, 32767).toShort())
    }

    private fun parlati(voce: ByteArray): List<Pair<Int, Int>> {
        val c = ByteBuffer.wrap(voce).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val passo = 320
        val finestre = c.remaining() / passo
        if (finestre < 5) return emptyList()
        val volume = DoubleArray(finestre) { i ->
            var somma = 0.0
            for (k in 0 until passo) { val v = c.get(i * passo + k).toDouble(); somma += v * v }
            Math.sqrt(somma / passo)
        }
        val fondo = volume.sorted()[finestre / 10]
        val soglia = maxOf(250.0, fondo * 3)
        val tratti = ArrayList<Pair<Int, Int>>()
        var inizio = -1; var silenzio = 0
        for (i in 0 until finestre) {
            if (volume[i] >= soglia) { if (inizio < 0) inizio = i; silenzio = 0 } else if (inizio >= 0) silenzio++
            val lungo = inizio >= 0 && i - inizio >= 700
            if (inizio >= 0 && (silenzio >= 28 || lungo || i == finestre - 1)) {
                val fine = i - silenzio
                if (fine - inizio >= 10) tratti.add(maxOf(0, inizio - 10) * passo * 2 to minOf(finestre, fine + 12) * passo * 2)
                inizio = -1; silenzio = 0
            }
        }
        return tratti
    }

    private fun riconosci(ctx: Context, voce: ByteArray): String {
        if (Build.VERSION.SDK_INT < 33 || voce.isEmpty()) return ""
        val tubo = ParcelFileDescriptor.createPipe()
        Thread { runCatching { ParcelFileDescriptor.AutoCloseOutputStream(tubo[1]).use { it.write(voce); it.write(ByteArray(16000 * 2)) } } }
            .apply { isDaemon = true; start() }
        val pezzi = StringBuilder()
        val finito = CountDownLatch(1)
        val mano = Handler(Looper.getMainLooper())
        var riconoscitore: SpeechRecognizer? = null
        fun aggiungi(b: Bundle?) {
            b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }
                ?.let { synchronized(pezzi) { if (pezzi.isNotEmpty()) pezzi.append(' '); pezzi.append(it) } }
        }
        val chiudi = Runnable { finito.countDown() }
        fun fraPoco() { mano.removeCallbacks(chiudi); mano.postDelayed(chiudi, 900) }
        mano.post {
            runCatching {
                riconoscitore = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(p: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(v: Float) {}
                        override fun onBufferReceived(b: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onPartialResults(b: Bundle?) {}
                        override fun onEvent(tipo: Int, b: Bundle?) {}
                        override fun onSegmentResults(b: Bundle) { aggiungi(b); fraPoco() }
                        override fun onEndOfSegmentedSession() = fraPoco()
                        override fun onResults(b: Bundle?) { aggiungi(b); fraPoco() }
                        override fun onError(errore: Int) = fraPoco()
                    })
                    startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "it-IT")
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, tubo[0])
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                        .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000))
                }
            }.onFailure { finito.countDown() }
        }
        finito.await(voce.size / 32L + 20_000L, TimeUnit.MILLISECONDS)
        mano.post { runCatching { riconoscitore?.destroy() } }
        runCatching { tubo[0].close() }
        Thread.sleep(300)
        return synchronized(pezzi) { pezzi.toString() }
    }

    /** Qualunque audio → suono grezzo a una voce, 16.000 al secondo (da FroWay, Trascrizione.pcm). */
    fun pcm(percorso: String): ByteArray? {
        val estrattore = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            estrattore.setDataSource(percorso)
            val traccia = (0 until estrattore.trackCount).firstOrNull {
                estrattore.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: return null
            val formato = estrattore.getTrackFormat(traccia)
            estrattore.selectTrack(traccia)
            codec = MediaCodec.createDecoderByType(formato.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(formato, null, null, 0)
            codec.start()
            val grezzo = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var finitoDentro = false
            var frequenza = formato.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var canali = formato.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var fermo = 0
            while (true) {
                if (!finitoDentro) {
                    val dentro = codec.dequeueInputBuffer(10_000)
                    if (dentro >= 0) {
                        val letti = estrattore.readSampleData(codec.getInputBuffer(dentro)!!, 0)
                        if (letti < 0 || estrattore.sampleTime / 1000 > 4 * 60_000L) {
                            codec.queueInputBuffer(dentro, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            finitoDentro = true
                        } else {
                            codec.queueInputBuffer(dentro, 0, letti, estrattore.sampleTime, 0)
                            estrattore.advance()
                        }
                    }
                }
                val fuori = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    fuori >= 0 -> {
                        fermo = 0
                        val pezzo = ByteArray(info.size)
                        codec.getOutputBuffer(fuori)!!.apply { position(info.offset); get(pezzo) }
                        grezzo.write(pezzo)
                        codec.releaseOutputBuffer(fuori, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                    fuori == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        frequenza = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        canali = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    else -> if (finitoDentro && ++fermo > 300) break
                }
            }
            return a16000(grezzo.toByteArray(), frequenza, canali.coerceAtLeast(1))
        } catch (e: Exception) {
            Log.w(TAG, "ascolto: audio non decodificato (${e.javaClass.simpleName})")
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { estrattore.release() }
        }
    }

    private fun a16000(byte: ByteArray, frequenza: Int, canali: Int): ByteArray {
        val tutti = ByteBuffer.wrap(byte).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val quanti = tutti.remaining() / canali
        if (quanti == 0 || frequenza <= 0) return ByteArray(0)
        val una = ShortArray(quanti) { i ->
            var somma = 0
            for (c in 0 until canali) somma += tutti.get(i * canali + c)
            (somma / canali).toShort()
        }
        val lunghi = (quanti.toLong() * 16000 / frequenza).toInt()
        val esce = ByteBuffer.allocate(lunghi * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until lunghi) {
            val dove = i.toDouble() * frequenza / 16000
            val prima = dove.toInt().coerceAtMost(quanti - 1)
            val dopo = (prima + 1).coerceAtMost(quanti - 1)
            val quota = dove - prima
            esce.putShort((una[prima] * (1 - quota) + una[dopo] * quota).toInt().toShort())
        }
        return esce.array()
    }
}
