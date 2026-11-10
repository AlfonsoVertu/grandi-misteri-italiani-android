package it.bibliotecadialessandria.ricerca

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.text.Normalizer

/**
 * Il catalogo di un'app per ARGOMENTO (10/11/2026, «Grandi misteri italiani»): i video di piu' canali che parlano
 * di almeno un caso, in `assets/catalogo.json` (costruito da `strumenti/catalogo_argomento.py`), con canale, casi
 * (i tag), temi, descrizione. La ricerca la fa il telefono, senza rete e senza chiamare i nostri server.
 * I video nuovi dei canali li prende dal feed pubblico di YouTube e li tiene solo se parlano di un caso, con le
 * stesse regole del catalogo (`regole.casi`): li salva in `aggiunti.json`.
 */
object Catalogo {
    private const val FILE = "catalogo.json"
    private const val AGGIUNTI = "aggiunti.json"
    private const val OGNI = 3600_000L
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"

    private class Voce(val v: Video, val tipo: String, val canale: String, val casi: List<String>, val temi: List<String>,
                       val titolo: String, val descrizione: String, val tag: String)
    private class Dati(val filtri: JSONObject, val casi: JSONObject, val temiDeiCasi: JSONObject, val nomi: Map<String, String>, val voci: List<Voce>)

    @Volatile private var dati: Dati? = null
    private lateinit var app: Context

    fun prepara(c: Context) {
        app = c.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { runCatching { carica(); integra(c) } }
    }

    private fun carica(): Dati {
        dati?.let { return it }
        val o = JSONObject(app.assets.open(FILE).bufferedReader().use { it.readText() })
        val aggiunti = File(app.filesDir, AGGIUNTI).takeIf { it.exists() }
            ?.let { f -> runCatching { JSONArray(f.readText()) }.getOrNull() } ?: JSONArray()
        return leggi(o, aggiunti).also { dati = it }
    }

    // minuscolo, senza accenti e senza punteggiatura, con uno spazio davanti: cosi' una parola si cerca solo
    // all'INIZIO delle parole («ustica» non trova «rustica» ne' «acustica»)
    private fun piano(s: String) = " " + Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^a-z0-9]+"), " ")
    private fun ha(testo: String, parola: String) = testo.contains(" $parola")
    private fun lista(a: JSONArray?) = a?.let { t -> (0 until t.length()).map { t.getString(it) } }.orEmpty()

    private fun voce(x: JSONObject): Voce {
        val casi = lista(x.optJSONArray("casi")); val temi = lista(x.optJSONArray("temi")); val tag = lista(x.optJSONArray("tag"))
        val sec = x.optInt("durata"); val canale = x.optString("canale")
        val data = x.optString("data").split("-").takeIf { it.size == 3 }?.let { (y, m, d) -> "$d/$m/$y" }.orEmpty()
        // nella riga del video: durata · tipo · canale · casi · temi
        val v = Video(x.getString("id"), x.optString("titolo"), if (sec > 0) "${maxOf(1, sec / 60)}'" else "",
            data, x.optString("tipo"), canale, casi.joinToString(", "), temi.joinToString(", "), "")
        return Voce(v, x.optString("tipo"), canale, casi, temi, piano(v.titolo), piano(x.optString("descrizione")),
            piano((tag + casi + temi + canale).joinToString(" ")))
    }

    private fun chiaveData(v: Video) = v.data.split("/").reversed().joinToString("")

    private fun leggi(o: JSONObject, aggiunti: JSONArray): Dati {
        val a = o.getJSONArray("video")
        val base = (0 until a.length()).map { voce(a.getJSONObject(it)) }
        val ids = base.mapTo(HashSet()) { it.v.youtubeId }
        val nuovi = (0 until aggiunti.length()).map { voce(aggiunti.getJSONObject(it)) }.filter { it.v.youtubeId !in ids }
        val filtri = JSONObject()
        o.getJSONArray("filtri").let { f -> for (i in 0 until f.length()) f.getJSONObject(i).let { filtri.put(it.getString("id"), it) } }
        val regole = o.optJSONObject("regole") ?: JSONObject()
        val nomi = HashMap<String, String>()
        o.optJSONArray("canali")?.let { c -> for (i in 0 until c.length()) c.getJSONObject(i).let { nomi[it.optString("id")] = it.optString("nome") } }
        val tutte = (nuovi + base).sortedByDescending { chiaveData(it.v) }
        return Dati(filtri, regole.optJSONObject("casi") ?: JSONObject(), regole.optJSONObject("temi") ?: JSONObject(), nomi, tutte)
    }

    /** I casi di un titolo secondo le regole del catalogo (le stesse di strumenti/catalogo_argomento.py). */
    private fun casiDi(d: Dati, titolo: String): List<String> {
        val t = " " + titolo.lowercase().replace(Regex("\\s+"), " ") + " "
        return d.casi.keys().asSequence().filter { k -> lista(d.casi.optJSONArray(k)).any { it in t } }.toList()
    }

    /** Le parole dell'argomento: gli avvisi dei canali di casa arrivano solo per i titoli che ne contengono una. */
    fun paroleCasa(c: Context): List<String> {
        if (!::app.isInitialized) app = c.applicationContext
        val d = runCatching { carica() }.getOrNull() ?: return emptyList()
        return d.casi.keys().asSequence().flatMap { lista(d.casi.optJSONArray(it)).asSequence() }.distinct().sorted().toList()
    }

    /** «Novità» di un'app per argomento: gli ultimi video sui casi, di tutti i canali, dal catalogo. */
    suspend fun recenti(tipo: String): List<Video> = withContext(Dispatchers.IO) {
        val nome = when (tipo) { "Live" -> "Diretta"; else -> tipo }
        carica().voci.filter { it.tipo == nome }.take(30).map { it.v }
    }

    /** I video nuovi dei canali dal feed pubblico di YouTube, solo se parlano di un caso; al massimo una volta l'ora. */
    suspend fun integra(c: Context, forza: Boolean = false) = withContext(Dispatchers.IO) {
        if (!::app.isInitialized) app = c.applicationContext
        val pref = app.getSharedPreferences("catalogo", Context.MODE_PRIVATE)
        if (!forza && System.currentTimeMillis() - pref.getLong("integrato", 0L) < OGNI) return@withContext
        val d = carica()
        val presenti = d.voci.mapTo(HashSet()) { it.v.youtubeId }
        val file = File(app.filesDir, AGGIUNTI)
        val aggiunti = file.takeIf { it.exists() }?.let { runCatching { JSONArray(it.readText()) }.getOrNull() } ?: JSONArray()
        var nuovi = 0
        for ((uc, nome) in Marca.CASA) for ((tipo, pl) in listOf("Video" to "UULF", "Short" to "UUSH", "Diretta" to "UULV")) {
            val doc = runCatching {
                Jsoup.connect("https://www.youtube.com/feeds/videos.xml?playlist_id=$pl${uc.removePrefix("UC")}")
                    .userAgent(UA).timeout(15_000).ignoreContentType(true).parser(Parser.xmlParser()).get()
            }.getOrNull() ?: continue
            for (e in doc.getElementsByTag("entry")) {
                val id = e.getElementsByTag("yt:videoId").firstOrNull()?.text() ?: continue
                if (id in presenti) continue
                val titolo = e.getElementsByTag("title").firstOrNull()?.text().orEmpty()
                val casi = casiDi(d, titolo)
                presenti.add(id)
                if (casi.isEmpty()) continue            // fuori argomento: non entra
                aggiunti.put(JSONObject().put("id", id).put("titolo", titolo).put("tipo", tipo).put("canale", d.nomi[uc] ?: nome)
                    .put("data", e.getElementsByTag("published").firstOrNull()?.text().orEmpty().take(10))
                    .put("casi", JSONArray(casi)).put("temi", JSONArray(casi.mapNotNull { d.temiDeiCasi.optString(it).ifBlank { null } }.distinct()))
                    .put("tag", JSONArray()).put("descrizione", e.getElementsByTag("media:description").firstOrNull()?.text().orEmpty().take(1500)))
                nuovi++
            }
        }
        pref.edit().putLong("integrato", System.currentTimeMillis()).apply()
        if (nuovi > 0) { file.writeText(aggiunti.toString()); dati = null; carica() }
    }

    suspend fun modulo(forza: Boolean = false): Modulo = withContext(Dispatchers.IO) {
        val d = carica()
        fun gruppo(id: String, titolo: String, acceso: Boolean): Gruppo? {
            val f = d.filtri.optJSONObject(id) ?: return null
            val op = f.getJSONArray("opzioni")
            return Gruppo(titolo, (0 until op.length()).map { Opzione("$id:${op.getString(it)}", op.getString(it), acceso) })
        }
        Modulo(
            listOfNotNull(
                Gruppo("Cerca le parole nel", listOf(Opzione("dove:titolo", "Titolo", true),
                    Opzione("dove:descrizione", "Descrizione", true), Opzione("dove:tag", "Tag, casi e canale", true),
                    Opzione("dove:trascrizione", "Trascrizione (dei video già scaricati)", false))),
                gruppo("caso", "Caso", false), gruppo("tema", "Tema", false), gruppo("canale", "Canale", false), gruppo("tipo", "Tipo", true)),
            listOf(Testo("Descrizione", "Parole da cercare", ""), Testo("MaxRisultati", "Quanti risultati al massimo", "100")),
            emptyList(),
        )
    }

    /** Come in Entropy: parole in titolo/descrizione/tag a scelta; caso, tema, canale e tipo come filtri. */
    suspend fun cerca(accese: Set<String>, valori: Map<String, String>): Esito = withContext(Dispatchers.IO) {
        val d = carica()
        fun scelti(id: String) = accese.filter { it.startsWith("$id:") }.map { it.substringAfter(':') }.toSet()
        val tipi = scelti("tipo"); val temi = scelti("tema"); val casi = scelti("caso"); val canali = scelti("canale")
        val dove = scelti("dove").ifEmpty { setOf("titolo") }
        val parole = piano(valori["Descrizione"].orEmpty()).split(Regex("\\s+")).filter { it.length > 1 }
        // (10/11/2026) le trascrizioni: solo quelle gia' sul telefono (pulsante «Scarica trascrizione» o assistente)
        val trascr: Map<String, String> = if ("trascrizione" in dove && parole.isNotEmpty())
            TrascrizioniYoutube.salvate(app).mapNotNull { id ->
                TrascrizioniYoutube.salvata(app, id)?.let { id to piano(TrascrizioniYoutube.testo(it)) }
            }.toMap() else emptyMap()
        fun nellaTrascr(x: Voce, p: String) = trascr[x.v.youtubeId]?.let { ha(it, p) } == true
        val trovati = d.voci.filter { x ->
            (tipi.isEmpty() || x.tipo in tipi) && (canali.isEmpty() || x.canale in canali) &&
                (temi.isEmpty() || x.temi.any { it in temi }) && (casi.isEmpty() || x.casi.any { it in casi }) &&
                parole.all { p -> ("titolo" in dove && ha(x.titolo, p)) || ("descrizione" in dove && ha(x.descrizione, p)) ||
                    ("tag" in dove && ha(x.tag, p)) || ("trascrizione" in dove && nellaTrascr(x, p)) }
        }
        fun punti(x: Voce) = parole.sumOf { p -> (if (ha(x.titolo, p)) 4 else 0) + (if (ha(x.tag, p)) 2 else 0) +
            (if (ha(x.descrizione, p)) 1 else 0) + (if (nellaTrascr(x, p)) 1 else 0) }
        val ordinati = if (parole.isEmpty()) trovati else trovati.sortedByDescending { punti(it) }
        val max = valori["MaxRisultati"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        Esito(ordinati.take(max).map { it.v }, trovati.size)
    }
}
