package it.bibliotecadialessandria.ricerca.indagine

import android.content.Context
import android.util.Log
import it.bibliotecadialessandria.ricerca.assistente.ConfigAi
import it.bibliotecadialessandria.ricerca.assistente.ModelliAi
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine
import it.bibliotecadialessandria.ricerca.strumenti.Smistatore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Il RAG locale, leggero (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §4.9). Tre fonti: la documentazione di ogni
 * azione degli strumenti (`assets/assistente/doc_strumenti.json`, nel formato dei doc_azioni di SoWay: cosa_fa, quando,
 * parametri, esempio, errori, collegate), le memorie e le note. Si cerca A PAROLE (senza accenti, radice corta); se la
 * persona ha scelto un modello di embedding e il suo fornitore li da' (`/embeddings`), si aggiunge la ricerca per
 * vettori e le due classifiche si fondono con RRF (1/(60+posizione)), come `ricerca_ibrida` di SoWay. Se gli embedding
 * falliscono si resta a parole, senza dirlo alla persona.
 *
 * UNA SOLA FONTE per la documentazione: [genera] la costruisce dalle [it.bibliotecadialessandria.ricerca.strumenti.Azione]
 * dello Smistatore. Il file negli assets e' quella stessa cosa scritta su disco (lo produce [scriviCopia]); se manca o
 * e' vecchio, vale quella generata.
 */
object Rag {

    private const val TAG = "Assistente"
    private const val PREF_EMBEDDING = "modello_embedding"

    // ── la documentazione delle azioni ───────────────────────────────

    /** La documentazione generata dalle definizioni: { strumento: { azione: {cosa_fa, quando, parametri, esempio, errori, collegate} } }. */
    fun genera(): JSONObject {
        val tutto = JSONObject()
        Smistatore.TUTTI.forEach { s ->
            val o = JSONObject()
            s.azioni.forEach { az ->
                o.put(az.nome, JSONObject().put("cosa_fa", az.cosaFa).put("quando", az.quando)
                    .put("parametri", JSONObject().also { p -> az.parametri.forEach { x ->
                        p.put(x.nome, (if (x.obbligatorio) "obbligatorio" else "facoltativo") + ", ${x.tipo}: ${x.cosE}" +
                            (x.valori?.let { " Valori: " + it.joinToString(", ") } ?: "")) } })
                    .put("esempio", az.esempio())
                    .put("errori", JSONArray(az.errori.ifEmpty { listOf("mancano_parametri: ti dico quali e la riga da copiare in fai_cosi") }))
                    .put("collegate", JSONArray(az.collegate)))
            }
            tutto.put(s.nome, o)
        }
        return tutto
    }

    @Volatile private var doc: JSONObject? = null

    fun documentazione(ctx: Context): JSONObject {
        doc?.let { return it }
        val generata = genera()
        val file = runCatching { JSONObject(ctx.assets.open("assistente/doc_strumenti.json").bufferedReader().use { it.readText() }) }.getOrNull()
        // il file vale se ha le stesse azioni; altrimenti e' vecchio e vale quella generata
        val buono = file != null && generata.keys().asSequence().all { s -> file.optJSONObject(s)?.let { f ->
            generata.getJSONObject(s).keys().asSequence().all { f.has(it) } } == true }
        return (if (buono) file!! else generata).also { doc = it }
    }

    /** Scrive la documentazione generata nella cartella dell'app sul telefono (Android/data/<pacchetto>/files), da copiare negli assets. */
    fun scriviCopia(ctx: Context) {
        runCatching { File(ctx.getExternalFilesDir(null) ?: return, "doc_strumenti.json").writeText(genera().toString(2)) }
    }

    private class Pezzo(val chiave: String, val testo: String, val dati: Any)

    private fun pezziDoc(ctx: Context): List<Pezzo> {
        val d = documentazione(ctx)
        return d.keys().asSequence().flatMap { s -> d.getJSONObject(s).let { o -> o.keys().asSequence().map { az ->
            val x = o.getJSONObject(az)
            // il pezzo «chiave» (nome + cosa_fa + quando) cerca meglio della voce intera (prova di SoWay con Qwen)
            Pezzo("doc:$s/$az", "$s $az ${az.replace('_', ' ')} ${x.optString("cosa_fa")} ${x.optString("quando")}",
                JSONObject().put("strumento", s).put("azione", az).put("cosa_fa", x.optString("cosa_fa"))
                    .put("esempio", x.optJSONObject("esempio") ?: JSONObject().put("action", az)))
        } } }.toList()
    }

    /** Le azioni pertinenti a una richiesta, la migliore prima. */
    fun cercaDoc(ctx: Context, testo: String, quanti: Int): List<JSONObject> =
        classifica(ctx, testo, pezziDoc(ctx), quanti).map { it.dati as JSONObject }

    // ── la ricerca: parole (+ vettori se ci sono) ────────────────────

    private fun classifica(ctx: Context, testo: String, pezzi: List<Pezzo>, quanti: Int, minimo: Int = 1): List<Pezzo> {
        val q = AiutiIndagine.parole(testo)
        if (q.isEmpty() || pezzi.isEmpty()) return emptyList()
        val aParole = pezzi.map { it to AiutiIndagine.punteggio(q, it.testo) }.filter { it.second >= minimo }
            .sortedByDescending { it.second }.map { it.first }
        val vettori = runCatching { perVettori(ctx, testo, pezzi) }.onFailure { Log.i(TAG, "rag: embedding non disponibili (${it.message?.take(80)})") }.getOrNull()
        if (vettori == null) return aParole.take(quanti)
        val punti = HashMap<String, Double>()
        aParole.forEachIndexed { k, p -> punti[p.chiave] = (punti[p.chiave] ?: 0.0) + 1.0 / (60 + k) }
        vettori.take(quanti * 2).forEachIndexed { k, p -> punti[p.chiave] = (punti[p.chiave] ?: 0.0) + 1.0 / (60 + k) }
        val perChiave = pezzi.associateBy { it.chiave }
        return punti.entries.sortedByDescending { it.value }.take(quanti).mapNotNull { perChiave[it.key] }
    }

    fun modelloEmbedding(ctx: Context): String =
        ctx.getSharedPreferences("assistente_ai", Context.MODE_PRIVATE).getString(PREF_EMBEDDING, "").orEmpty()

    fun salvaModelloEmbedding(ctx: Context, m: String) =
        ctx.getSharedPreferences("assistente_ai", Context.MODE_PRIVATE).edit().putString(PREF_EMBEDDING, m.trim()).apply()

    private val vettoriInMemoria = HashMap<String, FloatArray>()
    @Volatile private var falliti = 0L

    /** null se non c'e' un modello di embedding (o il fornitore non li da'): allora solo parole. */
    private fun perVettori(ctx: Context, testo: String, pezzi: List<Pezzo>): List<Pezzo>? {
        val modello = modelloEmbedding(ctx).ifBlank { return null }
        if (System.currentTimeMillis() - falliti < 10 * 60_000) return null
        val cfg = ConfigAi.leggi(ctx)
        if (!cfg.pronta) return null
        val chiave = { t: String -> modello + ":" + MessageDigest.getInstance("SHA-1").digest(t.toByteArray()).joinToString("") { "%02x".format(it) } }
        val mancano = pezzi.map { it.testo }.distinct().filter { synchronized(vettoriInMemoria) { chiave(it) !in vettoriInMemoria } }
        try {
            mancano.chunked(64).forEach { gruppo ->
                embedding(cfg.endpoint, cfg.chiave, modello, gruppo).forEachIndexed { k, v -> synchronized(vettoriInMemoria) { vettoriInMemoria[chiave(gruppo[k])] = v } }
            }
            val qv = embedding(cfg.endpoint, cfg.chiave, modello, listOf(testo)).first()
            return pezzi.map { p -> p to (synchronized(vettoriInMemoria) { vettoriInMemoria[chiave(p.testo)] }?.let { dot(qv, it) } ?: -1f) }
                .sortedByDescending { it.second }.map { it.first }
        } catch (e: Exception) { falliti = System.currentTimeMillis(); throw e }
    }

    private fun dot(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in 0 until minOf(a.size, b.size)) s += a[i] * b[i]; return s }

    private fun embedding(endpoint: String, chiave: String, modello: String, testi: List<String>): List<FloatArray> {
        val c = (URL(endpoint.trimEnd('/') + "/embeddings").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 10_000; readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $chiave"); setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", ModelliAi.UA)
        }
        c.outputStream.use { it.write(JSONObject().put("model", modello).put("input", JSONArray(testi.map { it.take(2000) })).toString().toByteArray()) }
        try {
            if (c.responseCode !in 200..299) throw RuntimeException("HTTP ${c.responseCode}")
            val d = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("data")
            return (0 until d.length()).map { d.getJSONObject(it) }.sortedBy { it.optInt("index") }.map { o ->
                val e = o.getJSONArray("embedding"); val v = FloatArray(e.length()) { e.getDouble(it).toFloat() }
                val n = kotlin.math.sqrt(v.fold(0f) { s, x -> s + x * x }).takeIf { it > 0 } ?: 1f
                for (i in v.indices) v[i] /= n; v
            }
        } finally { c.disconnect() }
    }

    // ── il contesto per il prompt ────────────────────────────────────

    /**
     * Le parti pertinenti da mettere nel prompt per questa [domanda]: TUTTE le istruzioni e preferenze della memoria, i
     * flussi e i fatti pertinenti, le azioni degli strumenti pertinenti, le note pertinenti. "" se non c'e' niente.
     */
    fun contesto(ctx: Context, domanda: String): String {
        val i = Archivio.di(ctx)
        val sb = StringBuilder()
        val memorie = i.memorie()
        val sempre = memorie.filter { it.tipo == "istruzione" || it.tipo == "preferenza" }
        if (sempre.isNotEmpty()) {
            sb.append("MEMORIA — ISTRUZIONI E PREFERENZE DELLA PERSONA (valgono sempre, salvo le regole di sicurezza):\n")
            sempre.take(30).forEach { sb.append("- [${it.tipo}] ${it.titolo}: ${it.testo.take(600)}\n") }
            sb.append('\n')
        }
        val altre = memorie.filter { it.tipo == "flusso" || it.tipo == "fatto" }
            .map { Pezzo("mem:${it.id}", it.titolo + " " + it.testo, it) }
        val pertinenti = classifica(ctx, domanda, altre, 4).map { it.dati as Memoria }
        if (pertinenti.isNotEmpty()) {
            sb.append("MEMORIA — FLUSSI E FATTI PERTINENTI (se un flusso descrive come fare questa richiesta, seguilo):\n")
            pertinenti.forEach { sb.append("- [${it.tipo} #${it.id}] ${it.titolo}: ${it.testo.take(1200)}\n") }
            sb.append('\n')
        }
        val note = classifica(ctx, domanda, i.note().map { Pezzo("nota:${it.id}", it.titolo + " " + it.testo + " " + it.fonte, it) }, 3, minimo = 2)
            .map { it.dati as Nota }
        if (note.isNotEmpty()) {
            sb.append("NOTE DELL'INDAGINE PERTINENTI (sono della persona o di indagini precaricate; per tutto il testo: note action=leggi):\n")
            note.forEach { sb.append("- nota ${it.id} «${it.titolo}»: ${it.testo.take(300)}${if (it.fonte.isNotBlank()) " (fonte: ${it.fonte})" else ""}\n") }
            sb.append('\n')
        }
        val doc = cercaDoc(ctx, domanda, 4)
        if (doc.isNotEmpty()) {
            sb.append("AZIONI DEGLI STRUMENTI CHE POTREBBERO SERVIRE (esempi da copiare):\n")
            doc.forEach { sb.append("- ${it.optString("strumento")}: ${it.optJSONObject("esempio")} — ${it.optString("cosa_fa").take(160)}\n") }
        }
        return sb.toString().trim()
    }
}
