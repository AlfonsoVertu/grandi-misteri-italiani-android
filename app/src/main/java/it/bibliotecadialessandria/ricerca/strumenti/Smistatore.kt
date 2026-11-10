package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * Lo smistatore degli strumenti (10/11/2026): l'equivalente di `_execute_tool` + `_aiuto_sulla_chiamata` di
 * SoWay (`mcp_sse.py`). In ordine:
 *  1. `action` vuota o `documentazione|guida|help|doc` -> la guida dello strumento;
 *  2. `documentazione_<azione>` -> il dettaglio di quell'azione;
 *  3. azione che non c'e' -> `azione_ignota` con l'elenco delle vere e `forse_cercavi` (mai un vicolo cieco);
 *  4. argomenti: i mancanti TUTTI insieme, quelli inventati (`non_esistono` + `forse_intendevi`), i tipi, i valori;
 *  5. esecuzione;
 *  6. su ogni errore si aggiunge `come_si_chiama` (obbligatori, facoltativi, la forma della chiamata).
 * Lo schema per il modello e la guida si generano dalle stesse [Azione]: una sola fonte.
 */
object Smistatore {

    private const val TAG = "Assistente"

    /** Gli strumenti dell'assistente, nell'ordine in cui il modello li vede: la guida per prima. */
    val TUTTI: List<Strumento> by lazy {
        listOf(GuidaStrumento, CatalogoStrumento, TrascrizioniStrumento, WebStrumento, PaginaStrumento, MediaStrumento)
    }

    private val DOC = setOf("documentazione", "guida", "help", "doc", "docs", "aiuto")

    fun piano(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[-_\\s]+"), "")

    fun strumento(nome: String): Strumento? = TUTTI.firstOrNull { piano(it.nome) == piano(nome) }

    /** La riga «sta usando: …» da mostrare mentre lo strumento lavora. */
    fun passo(nome: String, argomenti: JSONObject): String {
        val s = strumento(nome) ?: return "uso $nome"
        val a = argomenti.optString("action")
        if (a.isBlank() || piano(a) in DOC || piano(a).startsWith("documentazione")) return "leggo la guida di ${s.nome}"
        return s.azioni.firstOrNull { piano(it.nome) == piano(a) }?.passo?.ifBlank { null } ?: "uso ${s.nome}"
    }

    // ── lo schema per il modello ─────────────────────────────────────

    /** Gli strumenti in formato OpenAI (`tools`): action + l'unione dei parametri di tutte le azioni. */
    fun schema(): JSONArray = JSONArray().also { a -> TUTTI.forEach { a.put(schemaDi(it)) } }

    private fun schemaDi(s: Strumento): JSONObject {
        val props = JSONObject().put("action", JSONObject().put("type", "string").put("description",
            "Cosa fare: " + (listOf("documentazione", "documentazione_<azione>") + s.azioni.map { it.nome }).joinToString(" | ")))
        val visti = LinkedHashMap<String, Pair<Parametro, MutableList<String>>>()
        s.azioni.forEach { az -> az.parametri.forEach { p -> visti.getOrPut(p.nome) { p to mutableListOf() }.second.add(az.nome) } }
        visti.forEach { (nome, coppia) ->
            val (p, dove) = coppia
            val d = "(per: ${dove.joinToString(", ")}) ${p.cosE}" + (p.valori?.let { " Valori: ${it.take(25).joinToString(", ")}" + if (it.size > 25) "…" else "" } ?: "")
            props.put(nome, JSONObject().put("type", p.tipo).put("description", d.take(600)))
        }
        val descr = s.descrizione + " Azioni: " + s.azioni.joinToString("; ") { "${it.nome} = ${it.cosaFa}" } +
            ". Se non sai quali parametri vuole un'azione chiama con action=\"documentazione_<azione>\"."
        return JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", s.nome).put("description", descr.take(1500))
            .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray().put("action"))))
    }

    // ── guida e dettaglio ────────────────────────────────────────────

    /** La guida di uno strumento, scritta per un modello piccolo che copia gli esempi (forma di `_gpt_guida`). */
    fun guida(s: Strumento): JSONObject = JSONObject()
        .put("fai_cosi", JSONObject().put("action", "documentazione_${s.azioni.first().nome}"))
        .put("ok", true)
        .put("come_funziona", "Questo strumento fa cose diverse a seconda del parametro `action`. Scegli un'azione " +
            "dall'elenco qui sotto; se non sai cosa vuole, chiedi il suo dettaglio con action=\"documentazione_<azione>\".")
        .put("come_scoprire", JSONObject()
            .put("elenco delle azioni", JSONObject().put("action", "documentazione"))
            .put("cosa vuole una azione", JSONObject().put("action", "documentazione_<nome-azione>")))
        .put("strumento", s.nome)
        .put("a_cosa_serve", s.descrizione)
        .put("azioni", JSONArray().also { a -> s.azioni.forEach { az ->
            a.put(JSONObject().put("nome", az.nome).put("cosa_fa", az.cosaFa).put("esempio", az.esempio())) } })

    fun dettaglio(s: Strumento, az: Azione): JSONObject = JSONObject()
        .put("fai_cosi", az.esempio())
        .put("ok", true)
        .put("strumento", s.nome)
        .put("azione", JSONObject()
            .put("nome", az.nome).put("cosa_fa", az.cosaFa)
            .apply { if (az.quando.isNotBlank()) put("quando", az.quando) }
            .put("obbligatori", JSONArray(az.parametri.filter { it.obbligatorio }.map { it.nome }))
            .put("parametri", JSONArray().also { a -> az.parametri.forEach { p ->
                a.put(JSONObject().put("nome", p.nome).put("tipo", p.tipo).put("obbligatorio", p.obbligatorio).put("cos_e", p.cosE)
                    .apply { p.valori?.let { put("valori", JSONArray(it)) } }) } }))
        .put("come_scoprire", Errore.bussola(null))

    // ── esecuzione ───────────────────────────────────────────────────

    /** Esegue una chiamata del modello. Torna SEMPRE un JSON: mai un'eccezione, mai una risposta muta. */
    fun esegui(ctx: Context, nome: String, argomenti: JSONObject): JSONObject {
        val s = strumento(nome) ?: return Errore.nonEsiste("lo strumento", nome,
            vicini(nome, TUTTI.map { it.nome })).put("strumenti", JSONArray(TUTTI.map { it.nome }))
        val action = argomenti.optString("action").trim()
        val pa = piano(action)
        if (pa.isEmpty() || pa in DOC) return guida(s)
        if (pa.startsWith("documentazione")) {
            val chiesta = action.substringAfter("documentazione").trimStart('_', '-', ' ')
            val az = s.azioni.firstOrNull { piano(it.nome) == piano(chiesta) } ?: return azioneIgnota(s, chiesta)
            return dettaglio(s, az)
        }
        val az = s.azioni.firstOrNull { piano(it.nome) == pa } ?: return azioneIgnota(s, action)
        val (pulito, errore, avviso) = controlla(s, az, argomenti)
        if (errore != null) return conAiuto(errore, az)
        val esito = try {
            az.esegui(ctx, pulito!!)
        } catch (e: Exception) {
            // nel registro solo quale strumento e com'e' andata, mai il contenuto
            Log.i(TAG, "strumento ${s.nome}/${az.nome}: errore ${e.javaClass.simpleName}")
            Errore.generico("${s.nome}/${az.nome} non ci e' riuscito: ${e.message ?: e.javaClass.simpleName}",
                az.esempio(), "errore_esecuzione")
        }
        if (avviso != null) esito.put("avviso", avviso)
        return if (esito.optBoolean("ok", true) && !esito.has("errore")) esito else conAiuto(esito, az)
    }

    private fun azioneIgnota(s: Strumento, chiesta: String): JSONObject {
        val nomi = s.azioni.map { it.nome }
        return JSONObject()
            .put("fai_cosi", JSONObject().put("action", "documentazione"))
            .put("ok", false).put("codice", "azione_ignota")
            .put("errore", "In ${s.nome} non esiste nessuna azione «$chiesta». Qui sotto quelle che ci sono.")
            .put("forse_cercavi", JSONArray(vicini(chiesta, nomi)))
            .put("azioni", JSONArray().also { a -> s.azioni.forEach { a.put(JSONObject().put("nome", it.nome).put("cosa_fa", it.cosaFa)) } })
    }

    /** `come_si_chiama` come `_aiuto_sulla_chiamata`: su ogni errore, la forma giusta della chiamata. */
    private fun conAiuto(e: JSONObject, az: Azione): JSONObject {
        if (!e.has("come_si_chiama")) e.put("come_si_chiama", JSONObject()
            .put("forma", az.esempio())
            .put("obbligatori", JSONArray(az.parametri.filter { it.obbligatorio }.map { it.nome }))
            .put("facoltativi", JSONArray(az.parametri.filter { !it.obbligatorio }.map { it.nome })))
        return Errore.conBussola(e, az.nome)
    }

    private data class Controllo(val pulito: JSONObject?, val errore: JSONObject?, val avviso: JSONObject?)

    /** Controlla e converte gli argomenti. Mancanti tutti insieme; inventati segnalati con il nome vicino. */
    private fun controlla(s: Strumento, az: Azione, a: JSONObject): Controllo {
        val pulito = JSONObject().put("action", az.nome)
        val nomi = az.parametri.map { it.nome }
        val inventati = a.keys().asSequence().filter { it != "action" && az.parametri.none { p -> p.nome == it } }.toList()
        // un nome scritto quasi giusto («youtubeId» per «youtube_id») si accetta per quello vero
        val presi = HashMap<String, Any?>()
        val davvero = ArrayList<String>()
        for (k in inventati) {
            val vero = az.parametri.firstOrNull { piano(it.nome) == piano(k) }
            if (vero != null && !a.has(vero.nome)) presi[vero.nome] = a.opt(k) else davvero.add(k)
        }
        fun valore(p: Parametro): Any? = if (a.has(p.nome)) a.opt(p.nome) else presi[p.nome]
        val mancano = az.parametri.filter { p ->
            val v = valore(p)
            p.obbligatorio && (v == null || v == JSONObject.NULL || (v is String && v.isBlank()))
        }
        val avviso = if (davvero.isEmpty()) null else JSONObject()
            .put("non_esistono", JSONArray(davvero))
            .put("forse_intendevi", JSONArray(davvero.flatMap { vicini(it, nomi, 2) }.distinct().ifEmpty { nomi }))
            .put("nota", "Questi parametri non esistono per ${az.nome} e sono stati ignorati.")
        if (mancano.isNotEmpty()) {
            val esempio = JSONObject()
            az.parametri.filter { it.obbligatorio }.forEach { p -> esempio.put(p.nome, valore(p).takeIf { it != null && it != JSONObject.NULL && !(it is String && it.isBlank()) } ?: p.esempio ?: "<${p.nome}>") }
            val e = Errore.manca(s.nome, az.nome, mancano, esempio)
            if (avviso != null) { e.put("non_esistono", avviso.get("non_esistono")); e.put("forse_intendevi", avviso.get("forse_intendevi")) }
            return Controllo(null, e, null)
        }
        for (p in az.parametri) {
            val v = valore(p)
            if (v == null || v == JSONObject.NULL || (v is String && v.isBlank())) continue
            val convertito: Any? = when (p.tipo) {
                "integer" -> (v as? Number)?.toInt() ?: v.toString().trim().toDoubleOrNull()?.toInt()
                "number" -> (v as? Number)?.toDouble() ?: v.toString().trim().replace(',', '.').toDoubleOrNull()
                "boolean" -> (v as? Boolean) ?: when (v.toString().trim().lowercase()) { "true", "si", "sì", "1" -> true; "false", "no", "0" -> false; else -> null }
                else -> if (v is JSONObject || v is JSONArray) v.toString() else v.toString()
            }
            if (convertito == null) return Controllo(null, Errore.valoreSbagliato(s.nome, az.nome,
                "`${p.nome}` deve essere ${p.tipo}: «$v» non lo e'.", az.esempio()), null)
            val finale = p.valori?.let { ammessi ->
                ammessi.firstOrNull { piano(it) == piano(convertito.toString()) } ?: return Controllo(null,
                    Errore.valoreSbagliato(s.nome, az.nome, "`${p.nome}` = «$convertito» non e' fra i valori ammessi.",
                        az.esempio().put(p.nome, vicini(convertito.toString(), ammessi, 1).firstOrNull() ?: ammessi.first()), ammessi)
                        .put("forse_cercavi", JSONArray(vicini(convertito.toString(), ammessi))), null)
            } ?: convertito
            pulito.put(p.nome, finale)
        }
        return Controllo(pulito, null, avviso)
    }

    /** I nomi piu' vicini (distanza di modifica sui nomi «piani», e chi contiene l'altro). */
    fun vicini(chiesto: String, fra: List<String>, quanti: Int = 5): List<String> {
        val c = piano(chiesto)
        if (c.isEmpty()) return fra.take(quanti)
        return fra.map { it to (if (piano(it).contains(c) || c.contains(piano(it))) 0 else distanza(c, piano(it))) }
            .filter { it.second <= maxOf(3, c.length / 2) }
            .sortedBy { it.second }.take(quanti).map { it.first }
    }

    private fun distanza(a: String, b: String): Int {
        var prima = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val ora = IntArray(b.length + 1); ora[0] = i
            for (j in 1..b.length) ora[j] = minOf(prima[j] + 1, ora[j - 1] + 1, prima[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prima = ora
        }
        return prima[b.length]
    }
}
