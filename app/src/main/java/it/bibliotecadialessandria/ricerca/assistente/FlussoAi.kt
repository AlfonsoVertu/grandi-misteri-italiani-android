package it.bibliotecadialessandria.ricerca.assistente

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * La risposta del modello mentre arriva, non quando ha finito. Copiato da FroWay (Assistenti.kt, che lo ha preso
 * dall'app SoWay) il 10/11/2026. Tolto solo il file di prova con richiesta e risposta grezze: qui la conversazione
 * non si scrive in giro. Aggiunta [completa] (in FroWay sta in Assistenti), per i fornitori senza streaming.
 *
 * Il ragionamento (che ogni fornitore manda a modo suo) si tiene fuori dalla risposta; le chiamate agli
 * strumenti arrivano a pezzi e si rimontano; certi modelli le scrivono nel testo, e si riconoscono.
 */
object FlussoAi {

    /** Chi vuole il ragionamento del modello (fra <think> o in reasoning_content) lo raccoglie qui, sul suo filo. */
    val pensiero = ThreadLocal<StringBuilder?>()

    class Esito(val messaggio: JSONObject)

    /** Il fornitore non sa (o non vuole) rispondere a pezzi: chi chiama rifa' la richiesta tutta in una volta. */
    class SenzaFlusso(messaggio: String) : RuntimeException(messaggio)

    fun chiedi(endpoint: String, chiave: String, modello: String, messaggi: JSONArray, attrezzi: JSONArray,
               scelta: String = "auto", annullato: () -> Boolean = { false }, pezzo: (String) -> Unit): Esito {
        val c = (URL(endpoint.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15000; readTimeout = 300000
            setRequestProperty("Authorization", "Bearer $chiave")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("User-Agent", ModelliAi.UA)
        }
        val corpo = JSONObject().put("model", modello).put("messages", messaggi).put("stream", true)
        if (attrezzi.length() > 0) corpo.put("tools", attrezzi).put("tool_choice", scelta)
        c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        if (code !in 200..299) {
            val errore = c.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            c.disconnect()
            throw RuntimeException(messaggioErrore(errore, code))
        }
        var dentroThink = false
        val testo = StringBuilder()
        val chiamate = HashMap<Int, JSONObject>()
        var chiuso = false
        var visto = false
        c.inputStream.bufferedReader().use { lettore ->
            while (true) {
                val riga = lettore.readLine() ?: break
                // IL DUPLEX: e' arrivato altro mentre la risposta arrivava a pezzi. Si chiude la connessione e basta
                // (SoWay aspetta la fine del giro; qui si risparmia il resto).
                if (annullato()) { c.disconnect(); throw Assistente.Annullata() }
                if (!riga.startsWith("data:")) continue
                val dato = riga.removePrefix("data:").trim()
                if (dato == "[DONE]") { chiuso = true; break }
                val j = runCatching { JSONObject(dato) }.getOrNull() ?: continue
                val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: continue
                visto = true
                // Il ragionamento, quando il fornitore lo manda a parte.
                (testoDi(delta, "reasoning_content") ?: testoDi(delta, "reasoning"))?.let { pensiero.get()?.append(it) }
                // testoDi, non optString: su un "content": null org.json da' la STRINGA "null", che finirebbe nella risposta.
                testoDi(delta, "content")?.let { grezzo ->
                    // Il pensiero scritto dentro il testo fra <think> e </think> non e' risposta.
                    val visibile = StringBuilder()
                    var i = 0
                    while (i < grezzo.length) {
                        if (!dentroThink) {
                            val apre = grezzo.indexOf("<think", i)
                            if (apre < 0) { visibile.append(grezzo.substring(i)); break }
                            visibile.append(grezzo, i, apre)
                            val fine = grezzo.indexOf('>', apre)
                            dentroThink = true
                            if (fine < 0) break
                            i = fine + 1
                        } else {
                            val chiude = grezzo.indexOf("</think", i)
                            if (chiude < 0) { pensiero.get()?.append(grezzo.substring(i)); break }
                            pensiero.get()?.append(grezzo, i, chiude)
                            val fine = grezzo.indexOf('>', chiude)
                            if (fine < 0) break
                            i = fine + 1; dentroThink = false
                        }
                    }
                    if (visibile.isNotEmpty()) { testo.append(visibile); pezzo(visibile.toString()) }
                }
                delta.optJSONArray("tool_calls")?.let { raccogli(chiamate, it) }
            }
        }
        c.disconnect()
        if (!visto) throw SenzaFlusso("Il fornitore non ha risposto a pezzi.")
        if (!chiuso && testo.isEmpty() && chiamate.isEmpty()) throw RuntimeException("La risposta si è interrotta senza dire niente.")

        // Certi modelli scrivono le chiamate nel testo invece di metterle nel campo apposta.
        var corpoTesto = testo.toString()
        if (chiamate.isEmpty()) {
            val (pulito, trovate) = chiamateNelTesto(corpoTesto)
            if (trovate.isNotEmpty()) { corpoTesto = pulito; trovate.forEachIndexed { i, ch -> chiamate[i] = ch } }
        }
        val msg = JSONObject().put("role", "assistant").put("content", corpoTesto)
        if (chiamate.isNotEmpty()) msg.put("tool_calls", JSONArray().also { a -> chiamate.toSortedMap().values.forEach { a.put(it) } })
        return Esito(msg)
    }

    private fun chiamateNelTesto(testo: String): Pair<String, List<JSONObject>> {
        if (!testo.contains("tool_call", true) && !testo.contains("[TOOL_CALLS]")) return testo to emptyList()
        val fuori = ArrayList<JSONObject>()
        var pulito = testo
        val forme = listOf(
            Regex("""<\|?tool_call\|?>\s*(?:call:)?\s*([A-Za-z0-9_\-]+)\s*\(([\s\S]*?)\)\s*<\/?tool_call\|?>""", RegexOption.IGNORE_CASE),
            Regex("""<tool_call>\s*([\s\S]*?)\s*</tool_call>""", RegexOption.IGNORE_CASE),
            Regex("""\[TOOL_CALLS\]\s*([A-Za-z0-9_\-]+)\s*\(([\s\S]*?)\)"""))
        for (r in forme) {
            for (m in r.findAll(pulito)) {
                val g = m.groupValues
                val (nome, grezzo) = when {
                    g.size >= 3 && g[2].isNotBlank() -> g[1] to g[2]
                    else -> {
                        val j = runCatching { JSONObject(sistemaJson(g[1])) }.getOrNull()
                        (j?.optString("name") ?: "") to (j?.opt("arguments")?.toString() ?: "")
                    }
                }
                if (nome.isBlank()) continue
                fuori.add(JSONObject().put("type", "function").put("id", "testo_" + fuori.size)
                    .put("function", JSONObject().put("name", nome).put("arguments", sistemaJson(grezzo))))
            }
            if (fuori.isNotEmpty()) { pulito = r.replace(pulito, "").trim(); break }
        }
        return pulito to fuori
    }

    /** Riporta a JSON quello che il modello ha scritto credendo di scriverlo (grassetti, chiavi senza virgolette, graffe mancanti). */
    private fun sistemaJson(grezzo: String): String {
        var t = grezzo.trim()
        if (t.isEmpty()) return "{}"
        if (runCatching { JSONObject(t) }.isSuccess) return t
        t = t.substringBefore("<tool_").substringBefore("<|").trim()
        t = t.replace("**", "").replace("""\_""", "_")
        val aperte = t.count { it == '{' } - t.count { it == '}' }
        if (aperte > 0) t += "}".repeat(aperte)
        if (runCatching { JSONObject(t) }.isSuccess) return t
        t = Regex("""([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)(\s*:)""")
            .replace(t) { m -> m.groupValues[1] + "\"" + m.groupValues[2] + "\"" + m.groupValues[3] }
        return if (runCatching { JSONObject(t) }.isSuccess) t else "{}"
    }

    private fun testoDi(o: JSONObject, chiave: String): String? = (o.opt(chiave) as? String)?.takeIf { it.isNotEmpty() }

    /** Rimonta le chiamate agli strumenti: il nome arriva una volta, gli argomenti a spizzichi. */
    private fun raccogli(dove: HashMap<Int, JSONObject>, pezzi: JSONArray) {
        for (i in 0 until pezzi.length()) {
            val p = pezzi.optJSONObject(i) ?: continue
            val acc = dove.getOrPut(p.optInt("index", i)) {
                JSONObject().put("type", "function").put("id", "").put("function", JSONObject().put("name", "").put("arguments", ""))
            }
            (p.opt("id") as? String)?.takeIf { it.isNotEmpty() }?.let { acc.put("id", it) }
            val fn = p.optJSONObject("function") ?: continue
            val accFn = acc.getJSONObject("function")
            (fn.opt("name") as? String)?.takeIf { it.isNotEmpty() }?.let { accFn.put("name", it) }
            (fn.opt("arguments") as? String)?.takeIf { it.isNotEmpty() }?.let { accFn.put("arguments", accFn.optString("arguments") + it) }
        }
    }

    private fun messaggioErrore(corpo: String, code: Int): String {
        val j = runCatching { JSONObject(corpo) }.getOrNull()
            ?: return "Il modello ha risposto male (HTTP $code): ${corpo.take(200)}"
        val detto = j.optJSONObject("error")?.optString("message") ?: j.optString("error").takeIf { it.isNotBlank() } ?: corpo.take(200)
        return if (code == 401 || code == 403) "La chiave del fornitore non è valida o manca ($detto)" else detto
    }

    /** La chiamata tutta in una volta, per i fornitori che la risposta a pezzi non la danno (da FroWay, Assistenti.completa). */
    fun completa(endpoint: String, chiave: String, modello: String, messaggi: JSONArray, attrezzi: JSONArray, scelta: String = "auto"): JSONObject {
        val c = (URL(endpoint.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true
            connectTimeout = 15000; readTimeout = 180000
            setRequestProperty("Authorization", "Bearer $chiave")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", ModelliAi.UA)
        }
        val corpo = JSONObject().put("model", modello).put("messages", messaggi)
        if (attrezzi.length() > 0) corpo.put("tools", attrezzi).put("tool_choice", scelta)
        c.outputStream.use { it.write(corpo.toString().toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val testo = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw RuntimeException(messaggioErrore(testo, code))
        val j = runCatching { JSONObject(testo) }.getOrNull()
            ?: throw RuntimeException("Il modello ha risposto male (HTTP $code): ${testo.take(200)}")
        j.optJSONObject("error")?.let { throw RuntimeException(it.optString("message").ifBlank { "Il modello ha rifiutato la richiesta." }) }
        val m = j.getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        // org.json da' la stringa "null" per un content null: qui si rimette vuoto
        if (m.isNull("content")) m.put("content", "")
        return m
    }
}
