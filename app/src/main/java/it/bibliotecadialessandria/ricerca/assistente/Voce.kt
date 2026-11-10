package it.bibliotecadialessandria.ricerca.assistente

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * L'agente VOCE (11/11/2026, STUDIO_ASSISTENTE_AI.md §2.5, §4.2, §4.12): la risposta letta ad alta voce con il
 * TextToSpeech del telefono (gratis, senza rete se la voce e' scaricata), in italiano. Come `_voce_finale` del bot:
 * UNA lettura sola, alla fine, sul testo intero; si legge da sola solo se la domanda era a voce, e ogni risposta
 * ha il pulsante ▶/■ per riascoltarla. Tutto sul filo principale (come Voce.kt dell'app SoWay).
 */
object Voce {

    private const val TAG = "Assistente"
    private var tts: TextToSpeech? = null
    private var pronta = false
    private var inAttesa: (() -> Unit)? = null
    private val principale = Handler(Looper.getMainLooper())

    /** L'id del messaggio che si sta leggendo (null = zitta). */
    @Volatile var leggendo: Long? = null
        private set
    /** Chi guarda (la chat) ridisegna il pulsante ▶/■. */
    @Volatile var cambiato: (() -> Unit)? = null

    private fun dillo() = principale.post { cambiato?.invoke() }

    private fun prepara(ctx: Context, poi: () -> Unit) {
        if (tts != null && pronta) { poi(); return }
        inAttesa = poi
        if (tts != null) return
        tts = TextToSpeech(ctx.applicationContext) { stato ->
            principale.post {
                if (stato != TextToSpeech.SUCCESS) { Log.w(TAG, "voce: TextToSpeech non disponibile ($stato)"); tts = null; inAttesa = null; return@post }
                val r = tts?.setLanguage(Locale.ITALIAN) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) Log.w(TAG, "voce: manca la voce italiana ($r)")
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { if (id?.endsWith("#fine") == true) { leggendo = null; dillo() } }
                    @Deprecated("") override fun onError(id: String?) { leggendo = null; dillo() }
                    override fun onStop(id: String?, interrotto: Boolean) { leggendo = null; dillo() }
                })
                pronta = true
                inAttesa?.invoke(); inAttesa = null
            }
        }
    }

    /** Toglie dal testo quello che a voce non si dice: link, simboli del markdown, emoji dei bottoni. */
    fun daDire(t: String): String = t
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("\\*\\*|__|`|^#{1,6}\\s+", RegexOption.MULTILINE), "")
        .replace(Regex("(?m)^\\s*[*\\-•]\\s+"), "")
        .replace(Regex("(?<![\\w])\\*(?!\\s)|(?<!\\s)\\*(?![\\w])"), "")
        .replace(Regex("[\\[\\]()<>]"), " ")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Legge il messaggio [id]. Chiamabile da qualunque filo. */
    fun leggi(ctx: Context, id: Long, testo: String) {
        val pulito = daDire(testo)
        if (pulito.isBlank()) return
        principale.post {
            prepara(ctx) {
                val t = tts ?: return@prepara
                t.stop()
                val massimo = (runCatching { TextToSpeech.getMaxSpeechInputLength() }.getOrDefault(4000) - 100).coerceAtLeast(500)
                val pezzi = ArrayList<String>()
                pulito.split(Regex("\\n+")).forEach { par ->
                    var resto = par.trim()
                    while (resto.length > massimo) {
                        val taglio = resto.lastIndexOf(". ", massimo).takeIf { it > 100 } ?: massimo
                        pezzi.add(resto.substring(0, taglio + 1)); resto = resto.substring(taglio + 1).trim()
                    }
                    if (resto.isNotEmpty()) pezzi.add(resto)
                }
                leggendo = id
                pezzi.forEachIndexed { i, p ->
                    t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null,
                        if (i == pezzi.size - 1) "$id#fine" else "$id#$i")
                }
                Log.i(TAG, "voce: leggo una risposta (${pulito.length} caratteri, ${pezzi.size} pezzi)")
                dillo()
            }
        }
    }

    fun ferma() {
        principale.post { runCatching { tts?.stop() }; leggendo = null; dillo() }
    }
}
