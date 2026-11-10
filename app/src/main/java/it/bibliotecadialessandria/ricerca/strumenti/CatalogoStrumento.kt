package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.Canale
import it.bibliotecadialessandria.ricerca.Catalogo
import it.bibliotecadialessandria.ricerca.Marca
import it.bibliotecadialessandria.ricerca.Video
import it.bibliotecadialessandria.ricerca.idYoutube
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * `catalogo`: cerca nei video dell'app (10/11/2026). La ricerca e' quella della schermata Cerca ([Catalogo.cerca]
 * del flavor: punteggio titolo 4, tag 2, descrizione 1); il dettaglio di un video si legge da `assets/catalogo.json`
 * e da `files/aggiunti.json` (i video arrivati dopo, dal feed dei canali). Tutto sul telefono, senza rete.
 */
object CatalogoStrumento : Strumento() {

    override val nome = "catalogo"
    override val descrizione = "Cerca fra i video dell'app (catalogo sul telefono): per parole nel titolo, nella " +
        "descrizione e nei tag, e per caso, tema, canale e tipo. Da' anche il dettaglio di un video (descrizione, " +
        "casi, durata) e i valori ammessi per i filtri."

    private val PAROLE = Parametro("testo", "string", "Le parole da cercare (tutte devono comparire). Vuoto = nessuna parola.", esempio = "Ustica")
    private val CASO = Parametro("caso", "string", "Solo i video di questo caso (i nomi veri con action=filtri).")
    private val TEMA = Parametro("tema", "string", "Solo i video di questo tema (i nomi veri con action=filtri).")
    private val CANALE = Parametro("canale", "string", "Solo i video di questo canale.")
    private val TIPO = Parametro("tipo", "string", "Video, Short o Diretta.", valori = listOf("Video", "Short", "Diretta"))
    private val DOVE = Parametro("dove", "string", "Dove cercare le parole: titolo, descrizione, tag o tutto (predefinito).",
        valori = listOf("titolo", "descrizione", "tag", "tutto"))
    private val QUANTI = Parametro("quanti", "integer", "Quanti risultati (1-25, predefinito 10).", esempio = 10)
    private val PAGINA = Parametro("pagina", "integer", "La pagina dei risultati, da 1 (predefinito 1).")
    private val ID = Parametro("youtube_id", "string", "L'id YouTube del video (11 caratteri) o il suo link.", true, esempio = "oRFuohjWMpA")

    override val azioni = listOf(
        Azione("cerca", "Cerca i video per parole e filtri; torna titolo, canale, data, durata, casi, temi e link.",
            "Per trovare i video che parlano di un caso, di una persona o di un luogo.",
            listOf(PAROLE, CASO, TEMA, CANALE, TIPO, DOVE, QUANTI, PAGINA), "cerco nel catalogo dei video") { _, a -> cerca(a) },
        Azione("dettaglio", "Tutto quello che il catalogo sa di un video: titolo, canale, data, durata, casi, temi, tag, descrizione.",
            "Dopo cerca, per leggere la descrizione di un video.", listOf(ID), "leggo la scheda del video") { c, a -> dettaglio(c, a) },
        Azione("filtri", "I valori ammessi per caso, tema, canale e tipo, con quanti video ha ognuno.",
            "Prima di cercare per caso o tema, per usare i nomi esatti.", emptyList(), "guardo i filtri del catalogo") { _, _ -> filtri() },
        Azione("recenti", "Gli ultimi video arrivati nel catalogo.", "Per «cosa c'e' di nuovo».",
            listOf(TIPO, QUANTI), "guardo gli ultimi video") { _, a -> recenti(a) },
    )

    // ── i valori dei filtri, dal modulo della schermata Cerca ─────────

    private fun opzioni(): Map<String, List<String>> = runBlocking {
        Catalogo.modulo().gruppi.flatMap { it.opzioni }.groupBy({ it.campo.substringBefore(':') }, { it.campo.substringAfter(':') })
    }

    private fun video(v: Video): JSONObject = JSONObject()
        .put("youtube_id", v.youtubeId).put("titolo", v.titolo)
        .apply {
            if (Marca.MULTI) put("canale", v.categoria).put("casi", v.serie).put("temi", v.periodo)
            else { if (v.categoria.isNotBlank()) put("categoria", v.categoria); if (v.serie.isNotBlank()) put("serie", v.serie) }
        }
        .put("tipo", v.tipo).put("data", v.data).put("durata", v.durata).put("link", v.link)

    private fun cerca(a: JSONObject): JSONObject {
        val op = opzioni()
        val accese = HashSet<String>()
        // dove cercare le parole
        when (a.optString("dove", "tutto")) {
            "titolo" -> accese.add("dove:titolo"); "descrizione" -> accese.add("dove:descrizione"); "tag" -> accese.add("dove:tag")
            else -> accese.addAll(listOf("dove:titolo", "dove:descrizione", "dove:tag"))
        }
        for (filtro in listOf("caso", "tema", "canale", "tipo")) {
            val chiesto = a.optString(filtro).trim()
            if (chiesto.isEmpty()) continue
            val ammessi = op[filtro].orEmpty()
            val vero = ammessi.firstOrNull { Smistatore.piano(it) == Smistatore.piano(chiesto) }
                ?: ammessi.firstOrNull { Smistatore.piano(it).contains(Smistatore.piano(chiesto)) }
                ?: return Errore.nonEsiste("il $filtro", chiesto, Smistatore.vicini(chiesto, ammessi),
                    JSONObject().put("action", "filtri")).put("nota", "I $filtro veri si vedono con action=filtri.")
            accese.add("$filtro:$vero")
        }
        val quanti = a.optInt("quanti", 10).coerceIn(1, 25)
        val pagina = a.optInt("pagina", 1).coerceAtLeast(1)
        val esito = runBlocking { Catalogo.cerca(accese, mapOf("Descrizione" to a.optString("testo"), "MaxRisultati" to "1000")) }
        val pezzo = esito.video.drop((pagina - 1) * quanti).take(quanti)
        val o = riuscito().put("trovati", esito.totale).put("pagina", pagina)
            .put("video", JSONArray().also { arr -> pezzo.forEach { arr.put(video(it)) } })
        if (pagina * quanti < esito.video.size) o.put("continua", JSONObject().put("action", "cerca").put("pagina", pagina + 1))
        if (esito.totale == 0) o.put("suggerimento", "Nessun video: prova meno parole, una parola diversa o togli i filtri.")
        return o
    }

    // ── il dettaglio, letto dal JSON del catalogo ─────────────────────

    @Volatile private var schede: Map<String, JSONObject>? = null

    private fun schede(ctx: Context): Map<String, JSONObject> {
        schede?.let { return it }
        val m = HashMap<String, JSONObject>()
        runCatching {
            val o = JSONObject(ctx.assets.open("catalogo.json").bufferedReader().use { it.readText() })
            val v = o.getJSONArray("video"); for (i in 0 until v.length()) v.getJSONObject(i).let { m[it.optString("id")] = it }
        }
        runCatching {
            val v = JSONArray(File(ctx.filesDir, "aggiunti.json").readText())
            for (i in 0 until v.length()) v.getJSONObject(i).let { m.putIfAbsent(it.optString("id"), it) }
        }
        return m.also { schede = it }
    }

    private fun dettaglio(ctx: Context, a: JSONObject): JSONObject {
        val chiesto = a.optString("youtube_id").trim()
        val id = idYoutube(chiesto) ?: chiesto
        val x = schede(ctx)[id] ?: return Errore.nonEsiste("nel catalogo il video", id, emptyList(),
            JSONObject().put("action", "cerca").put("testo", "<parole del titolo>"))
            .put("nota", "Non e' nel catalogo dell'app: puoi comunque scaricarne la trascrizione con lo strumento trascrizioni.")
        val o = riuscito().put("youtube_id", id)
        listOf("titolo", "canale", "tipo", "data").forEach { k -> x.optString(k).takeIf { it.isNotBlank() }?.let { o.put(k, it) } }
        x.optInt("durata").takeIf { it > 0 }?.let { o.put("durata_secondi", it).put("durata", mmss(it)) }
        listOf("casi", "temi", "tag").forEach { k -> x.optJSONArray(k)?.takeIf { it.length() > 0 }?.let { o.put(k, it) } }
        x.optString("descrizione").takeIf { it.isNotBlank() }?.let { o.put("descrizione", it.take(3000)) }
        return o.put("link", linkAlSecondo(id, null))
    }

    private fun filtri(): JSONObject {
        val op = opzioni()
        val o = riuscito()
        listOf("caso" to "casi", "tema" to "temi", "canale" to "canali", "tipo" to "tipi").forEach { (k, plur) ->
            op[k]?.let { o.put(plur, JSONArray(it)) }
        }
        return o.put("nota", "Usa questi nomi esatti nei parametri caso, tema, canale e tipo di action=cerca.")
    }

    private fun recenti(a: JSONObject): JSONObject {
        val quanti = a.optInt("quanti", 10).coerceIn(1, 25)
        val tipo = a.optString("tipo", "Video").ifBlank { "Video" }
        val lista = runBlocking {
            if (Marca.MULTI) Catalogo.recenti(tipo) else {
                val t = when (tipo) { "Short" -> Canale.Tipo.SHORT; "Diretta" -> Canale.Tipo.LIVE; else -> Canale.Tipo.VIDEO }
                Canale.ultimi(t)
            }
        }
        return riuscito().put("tipo", tipo).put("video", JSONArray().also { arr -> lista.take(quanti).forEach { arr.put(video(it)) } })
    }
}
