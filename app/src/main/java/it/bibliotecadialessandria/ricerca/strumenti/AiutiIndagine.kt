package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.TrascrizioniYoutube
import it.bibliotecadialessandria.ricerca.idYoutube
import org.json.JSONArray
import org.json.JSONObject

/**
 * I pezzi comuni agli strumenti dell'indagine (11/11/2026, fase 2b): note, teorie, mappa, persone, memoria, piano.
 */
object AiutiIndagine {

    /** Un video al secondo, letto da quello che scrive il modello. */
    data class VideoAl(val youtubeId: String, val secondi: Int?, val etichetta: String = "")

    private val ID = Regex("^[A-Za-z0-9_-]{11}$")

    /** Un id YouTube da un id o da un link; null se non lo e'. */
    fun idDi(t: String): String? = t.trim().let { s -> if (ID.matches(s)) s else idYoutube(s) }

    /**
     * I video da un parametro: un array JSON (`[{"youtube_id":"…","tempo":"12:30"}]`, anche con `id`/`secondi`), oppure
     * un testo «id@12:30, https://youtu.be/…?t=754». Torna i video e le parti che non ha capito.
     */
    fun videoDa(grezzo: String): Pair<List<VideoAl>, List<String>> {
        val t = grezzo.trim()
        if (t.isEmpty()) return emptyList<VideoAl>() to emptyList()
        val fuori = ArrayList<VideoAl>(); val male = ArrayList<String>()
        val arr = runCatching { JSONArray(t) }.getOrNull() ?: runCatching { JSONArray().put(JSONObject(t)) }.getOrNull()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i)
                if (o == null) { arr.optString(i).let { s -> unoDa(s)?.let { fuori.add(it) } ?: male.add(s) }; continue }
                val id = idDi(o.optString("youtube_id").ifBlank { o.optString("id").ifBlank { o.optString("video").ifBlank { o.optString("url") } } })
                if (id == null) { male.add(o.toString()); continue }
                val sec = secondiDa(o.opt("tempo")?.takeIf { it != JSONObject.NULL } ?: o.opt("secondi")?.takeIf { it != JSONObject.NULL })
                    ?: Regex("[?&]t=(\\d+)").find(o.optString("url"))?.groupValues?.get(1)?.toIntOrNull()
                fuori.add(VideoAl(id, sec, o.optString("etichetta")))
            }
            return fuori to male
        }
        t.split(Regex("[,;\\n]+|\\s+(?=https?://)")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { s ->
            unoDa(s)?.let { fuori.add(it) } ?: male.add(s)
        }
        return fuori to male
    }

    private fun unoDa(s: String): VideoAl? {
        if (s.startsWith("http")) {
            val id = idYoutube(s) ?: return null
            val sec = Regex("[?&#]t=(\\d+)").find(s)?.groupValues?.get(1)?.toIntOrNull()
            return VideoAl(id, sec)
        }
        val parti = s.split('@', ' ', limit = 2)
        val id = idDi(parti[0]) ?: return null
        return VideoAl(id, parti.getOrNull(1)?.let { secondiDa(it.trim()) })
    }

    /** Il titolo di un video, se lo conosciamo (trascrizione sul telefono o titoli ricordati). */
    fun titolo(ctx: Context, youtubeId: String): String =
        ctx.getSharedPreferences("titoli_video", Context.MODE_PRIVATE).getString(youtubeId, null)
            ?: runCatching { TrascrizioniYoutube.salvata(ctx, youtubeId)?.titolo }.getOrNull().orEmpty()

    fun ricordaTitolo(ctx: Context, youtubeId: String, titolo: String) {
        if (titolo.isNotBlank()) ctx.getSharedPreferences("titoli_video", Context.MODE_PRIVATE).edit().putString(youtubeId, titolo).apply()
    }

    /** Un video in uscita: id, secondi, mm:ss, link al secondo e titolo se c'e'. */
    fun video(ctx: Context, youtubeId: String, secondi: Int?, extra: JSONObject = JSONObject()): JSONObject = extra
        .put("youtube_id", youtubeId)
        .apply { if (secondi != null) put("secondi", secondi).put("minuto", mmss(secondi)) }
        .put("link", linkAlSecondo(youtubeId, secondi))
        .apply { titolo(ctx, youtubeId).takeIf { it.isNotBlank() }?.let { put("titolo", it) } }

    /**
     * La cancellazione a due passi, uguale per tutti: senza token -> il riepilogo e il token; con il token buono -> [fai].
     */
    fun conConferma(strumento: String, azione: String, chiave: JSONObject, a: JSONObject, riepilogo: String,
                    cosaSuccede: String, indietro: String, fai: () -> JSONObject): JSONObject {
        val token = a.optString("conferma_token")
        if (token.isBlank()) return Conferma.richiedi(strumento, azione, chiave, riepilogo, cosaSuccede, indietro)
        Conferma.valida(token, strumento, azione, chiave)?.let { perche ->
            return Errore.generico(perche, JSONObject(chiave.toString()).put("action", azione), "conferma_non_valida")
                .put("nota", "Richiama senza token per averne uno nuovo, e chiedi di nuovo alla persona.")
        }
        return fai()
    }

    val TOKEN = Parametro("conferma_token", "string", "Il token avuto dalla prima chiamata, SOLO dopo il si' della persona.")

    /** Qualcosa con quell'id non c'e': si dicono quelli che ci sono (mai un vicolo cieco). */
    fun nonTrovato(cosa: String, id: Long, ci: List<Pair<Long, String>>, elenca: String): JSONObject =
        Errore.nonEsiste(cosa, id.toString(), emptyList(), JSONObject().put("action", elenca))
            .put("ci_sono", JSONArray().also { a -> ci.take(15).forEach { (i, t) -> a.put(JSONObject().put("id", i).put("titolo", t)) } })
            .apply { if (ci.isEmpty()) put("nota", "Non ce n'e' ancora nessuno.") }

    /** Il testo di una ricerca LIKE di SQLite. */
    fun like(t: String) = "%" + t.trim().replace("%", "").replace("_", " ") + "%"

    /** Parole senza accenti, per cercare «a parole». */
    fun parole(t: String): List<String> =
        java.text.Normalizer.normalize(t.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in VUOTE }.distinct()

    private val VUOTE = setOf("che", "chi", "per", "con", "del", "della", "delle", "dei", "degli", "nel", "nella", "una", "uno",
        "gli", "le", "dal", "dalla", "sul", "sulla", "come", "cosa", "sono", "the", "and", "and", "non", "piu", "anche", "questo", "questa")

    /** Quante parole di [q] ci sono in [testo] (gia' «piano»). */
    fun punteggio(q: List<String>, testo: String): Int {
        val t = " " + java.text.Normalizer.normalize(testo.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return q.count { p -> t.contains(" " + p.take(maxOf(4, p.length - 2))) || t.contains(p) }
    }
}
