package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import org.json.JSONObject

/**
 * Lo standard dei tool come in SoWay (10/11/2026, STUDIO_ASSISTENTE_AI.md §3): UN strumento per argomento, con il
 * parametro `action` che sceglie cosa fare; `documentazione` e `documentazione_<azione>` le gestisce lo
 * [Smistatore] per tutti, in un posto solo. Ogni [Azione] dichiara i suoi parametri UNA volta: da li' si generano
 * lo schema per il modello, la guida, il dettaglio di un'azione e il controllo degli argomenti (una sola fonte).
 */

/** Un parametro di un'azione. [tipo] e' quello di JSON Schema: string, integer, number, boolean. */
class Parametro(
    val nome: String,
    val tipo: String,
    val cosE: String,
    val obbligatorio: Boolean = false,
    /** I valori ammessi, se sono pochi e fissi (il controllo li confronta senza maiuscole e accenti). */
    val valori: List<String>? = null,
    /** Il valore d'esempio da mettere in `fai_cosi`. */
    val esempio: Any? = null,
)

/** Una cosa che uno strumento sa fare. */
class Azione(
    val nome: String,
    val cosaFa: String,
    /** Quando usarla (aiuta il modello a scegliere fra azioni vicine). */
    val quando: String = "",
    val parametri: List<Parametro> = emptyList(),
    /** La riga che si vede nella chat mentre lavora: «cerco nel catalogo…». */
    val passo: String = "",
    /** (11/11/2026, fase 2b) per la documentazione del RAG (doc_strumenti.json): gli errori tipici e le azioni vicine. */
    val errori: List<String> = emptyList(),
    val collegate: List<String> = emptyList(),
    /** Riceve argomenti gia' controllati e convertiti ai tipi giusti; torna la risposta in JSON. Fuori dal filo dell'interfaccia. */
    val esegui: (ctx: Context, a: JSONObject) -> JSONObject,
) {
    /** La chiamata d'esempio da copiare: action + gli obbligatori (o tutti se non ce ne sono). */
    fun esempio(): JSONObject = JSONObject().put("action", nome).also { o ->
        val quali = parametri.filter { it.obbligatorio }.ifEmpty { parametri.take(1) }
        quali.forEach { p -> o.put(p.nome, p.esempio ?: "<${p.tipo}>") }
    }
}

/** Uno strumento: un nome parlante, cosa fa, le sue azioni. */
abstract class Strumento {
    abstract val nome: String
    abstract val descrizione: String
    abstract val azioni: List<Azione>
}

/** Una risposta riuscita: `ok: true` per primo, poi il resto. */
fun riuscito(): JSONObject = JSONObject().put("ok", true)

/** Il link di YouTube a un secondo preciso: lo stesso in tutti gli strumenti, l'app lo apre al punto giusto. */
fun linkAlSecondo(youtubeId: String, secondi: Int?): String =
    "https://www.youtube.com/watch?v=$youtubeId" + (if (secondi != null && secondi > 0) "&t=${secondi}s" else "")

/** 754 -> "12:34", 3723 -> "1:02:03". */
fun mmss(secondi: Int): String {
    val s = secondi.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Accetta "1:02:03", "12:34", 754 e "754s"; null se non e' un tempo. */
fun secondiDa(v: Any?): Int? = when (v) {
    null -> null
    is Number -> v.toInt()
    else -> v.toString().trim().removeSuffix("s").let { t ->
        if (':' in t) t.split(':').map { it.toIntOrNull() ?: return null }.fold(0) { acc, n -> acc * 60 + n }
        else t.toDoubleOrNull()?.toInt()
    }
}
