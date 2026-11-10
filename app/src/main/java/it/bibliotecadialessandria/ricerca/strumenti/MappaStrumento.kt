package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.indagine.Archivio
import it.bibliotecadialessandria.ricerca.indagine.Legame
import it.bibliotecadialessandria.ricerca.indagine.Luoghi
import it.bibliotecadialessandria.ricerca.indagine.Segnalino
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine.TOKEN
import org.json.JSONArray
import org.json.JSONObject

/**
 * `mappa` (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §4.6 e §4.10): i segnalini e le linee della mappa dell'indagine
 * (OpenStreetMap). Un luogo per nome si cerca con Nominatim ([Luoghi]: 1 richiesta al secondo, User-Agent dell'app).
 */
object MappaStrumento : Strumento() {

    override val nome = "mappa"
    override val descrizione = "La mappa dell'indagine (OpenStreetMap): segnalini (luogo, titolo, testo, video al minuto, nota) e " +
        "linee che uniscono segnalini in ordine. Puoi cercare le coordinate di un luogo per nome, creare, leggere, modificare e " +
        "cancellare (con conferma) segnalini e linee."

    private val ID = Parametro("id", "integer", "L'id del segnalino (lo da' segnalini).", true, esempio = 1)
    private val IDL = Parametro("id", "integer", "L'id della linea (lo da' linee).", true, esempio = 1)
    private val PUNTI = Parametro("punti", "string", "Gli id dei segnalini in ordine, separati da virgole (almeno 2).", esempio = "1,2")

    override val azioni = listOf(
        Azione("segnalini", "I segnalini: id, titolo, lat, lon, video, nota. Filtri facoltativi.", "",
            listOf(Parametro("testo", "string", "Solo quelli con queste parole."), Parametro("teoria_id", "integer", "Solo quelli di questa teoria."),
                Parametro("nota_id", "integer", "Solo quelli di questa nota.")), "guardo la mappa") { c, a ->
            var s = if (a.has("teoria_id")) Archivio.segnaliniDellaTeoria(c, a.optLong("teoria_id")) else Archivio.di(c).segnalini()
            if (a.has("nota_id")) s = s.filter { it.notaId == a.optLong("nota_id") }
            if (a.optString("testo").isNotBlank()) { val q = AiutiIndagine.parole(a.optString("testo")); s = s.filter { AiutiIndagine.punteggio(q, it.titolo + " " + it.testo) > 0 } }
            riuscito().put("totale", s.size).put("segnalini", JSONArray().also { r -> s.take(50).forEach { r.put(riga(c, it)) } })
                .apply { if (s.isEmpty()) put("nota", "Nessun segnalino. Creane uno con action=crea_segnalino.") }
        },
        Azione("leggi_segnalino", "Un segnalino con la nota, il video al minuto, le persone e le linee collegate.", "", listOf(ID), "leggo un segnalino") { c, a ->
            quale(c, a).let { (s, e) -> if (s == null) e!! else dettaglio(c, s) }
        },
        Azione("cerca_luogo", "Cerca le coordinate di un luogo per nome con Nominatim (OpenStreetMap). Una richiesta per volta.",
            "Prima di creare un segnalino se non sai lat/lon, o per scegliere fra piu' luoghi con lo stesso nome.",
            listOf(Parametro("luogo", "string", "Il luogo: indirizzo, piazza, citta'.", true, esempio = "piazza Fontana, Milano")),
            "cerco il luogo sulla mappa", collegate = listOf("crea_segnalino")) { c, a ->
            val l = Luoghi.cerca(c, a.optString("luogo"))
            if (l.isEmpty()) Errore.nonDisponibile("Nominatim non trova «${a.optString("luogo")}». Prova con il nome della citta' o un indirizzo piu' semplice.",
                JSONObject().put("action", "cerca_luogo").put("luogo", "<luogo, citta'>"))
            else riuscito().put("luoghi", JSONArray().also { r -> l.forEach { r.put(JSONObject().put("nome", it.nome).put("lat", it.lat).put("lon", it.lon).put("tipo", it.tipo)) } })
                .put("fonte", "© OpenStreetMap contributors (Nominatim)")
        },
        Azione("crea_segnalino", "Crea un segnalino (autore: assistente): con `lat`+`lon` oppure con `luogo` (cerco io le coordinate col primo risultato di Nominatim).",
            "Per mettere sulla mappa un luogo del caso. Collega la nota o il video al minuto se ci sono.",
            listOf(Parametro("titolo", "string", "Il nome del punto.", true, esempio = "Piazza Fontana"),
                Parametro("lat", "number", "Latitudine (-90…90)."), Parametro("lon", "number", "Longitudine (-180…180)."),
                Parametro("luogo", "string", "In alternativa a lat/lon: il luogo da cercare.", esempio = "piazza Fontana, Milano"),
                Parametro("testo", "string", "Cosa e' successo li' (con la fonte)."),
                Parametro("youtube_id", "string", "Il video che ne parla (id o link)."), Parametro("tempo", "string", "Il minuto nel video."),
                Parametro("nota_id", "integer", "La nota collegata."), Parametro("teoria_id", "integer", "Collegalo anche a questa teoria.")),
            "metto un segnalino sulla mappa", errori = listOf("mancano lat/lon o luogo", "luogo non trovato da Nominatim"),
            collegate = listOf("cerca_luogo", "crea_linea")) { c, a -> creaSegnalino(c, a) },
        Azione("modifica_segnalino", "Cambia un segnalino (solo i campi passati).", "",
            listOf(ID, Parametro("titolo", "string", "Nuovo titolo."), Parametro("testo", "string", "Nuovo testo."),
                Parametro("lat", "number", "Nuova latitudine."), Parametro("lon", "number", "Nuova longitudine."),
                Parametro("youtube_id", "string", "Video (id o link)."), Parametro("tempo", "string", "Minuto."), Parametro("nota_id", "integer", "Nota collegata.")),
            "modifico un segnalino") { c, a ->
            val (s, e) = quale(c, a); if (s == null) return@Azione e!!
            val vid = if (a.has("youtube_id")) AiutiIndagine.idDi(a.optString("youtube_id")) else s.youtubeId
            Archivio.di(c).aggiorna(s.copy(titolo = a.optString("titolo", s.titolo), testo = a.optString("testo", s.testo),
                lat = if (a.has("lat")) a.optDouble("lat") else s.lat, lon = if (a.has("lon")) a.optDouble("lon") else s.lon,
                youtubeId = vid, secondi = if (a.has("tempo")) secondiDa(a.optString("tempo")) else s.secondi,
                notaId = if (a.has("nota_id")) a.optLong("nota_id").takeIf { Archivio.di(c).nota(it) != null } else s.notaId,
                modificato = System.currentTimeMillis()))
            Archivio.avvisa(); dettaglio(c, Archivio.di(c).segnalino(s.id)!!)
        },
        Azione("cancella_segnalino", "Cancella un segnalino (e le linee che restano con meno di 2 punti). A DUE PASSI con `conferma_token`.",
            "Solo se la persona lo chiede.", listOf(ID, TOKEN), "cancello un segnalino") { c, a ->
            val (s, e) = quale(c, a); if (s == null) return@Azione e!!
            AiutiIndagine.conConferma(nome, "cancella_segnalino", JSONObject().put("id", s.id), a, "Cancello dalla mappa il segnalino ${s.id} «${s.titolo}».",
                "Il segnalino sparisce, e con lui i suoi passaggi nelle linee.", "Non si torna indietro: andrebbe rimesso.") {
                Archivio.cancellaSegnalino(c, s); Archivio.avvisa(); riuscito().put("cancellato", s.titolo)
            }
        },
        Azione("crea_linea", "Unisce dei segnalini con una linea, nell'ordine dato (un percorso).", "",
            listOf(Parametro("titolo", "string", "Il nome della linea.", true, esempio = "Il percorso della fuga"), PUNTI.let {
                Parametro(it.nome, it.tipo, it.cosE, true, esempio = it.esempio) }, Parametro("colore", "string", "Colore #RRGGBB (predefinito rosso).")),
            "traccio una linea") { c, a ->
            val (punti, e) = puntiDa(c, a, "crea_linea"); if (punti == null) return@Azione e!!
            val id = Archivio.nuovaLinea(c, a.optString("titolo").trim(), punti, "assistente", colore = a.optString("colore").ifBlank { "#C62828" })
            Archivio.avvisa(); riuscito().put("linea", linea(c, id))
        },
        Azione("linee", "Le linee con i loro punti.", "", emptyList(), "guardo le linee") { c, _ ->
            riuscito().put("linee", JSONArray().also { r -> Archivio.di(c).linee().forEach { r.put(linea(c, it.id)) } })
        },
        Azione("modifica_linea", "Rinomina una linea o ne cambia i punti (in ordine).", "",
            listOf(IDL, Parametro("titolo", "string", "Nuovo titolo."), PUNTI), "modifico una linea") { c, a ->
            val l = Archivio.di(c).linea(a.optLong("id")) ?: return@Azione AiutiIndagine.nonTrovato("la linea", a.optLong("id"),
                Archivio.di(c).linee().map { it.id to it.titolo }, "linee")
            if (a.has("punti")) { val (p, e) = puntiDa(c, a, "modifica_linea"); if (p == null) return@Azione e!!; Archivio.mettiPunti(c, l.id, p) }
            if (a.has("titolo")) Archivio.di(c).aggiorna(l.copy(titolo = a.optString("titolo")))
            Archivio.avvisa(); riuscito().put("linea", linea(c, l.id))
        },
        Azione("cancella_linea", "Cancella una linea (i segnalini restano). A DUE PASSI con `conferma_token`.", "Solo se la persona lo chiede.",
            listOf(IDL, TOKEN), "cancello una linea") { c, a ->
            val l = Archivio.di(c).linea(a.optLong("id")) ?: return@Azione AiutiIndagine.nonTrovato("la linea", a.optLong("id"),
                Archivio.di(c).linee().map { it.id to it.titolo }, "linee")
            AiutiIndagine.conConferma(nome, "cancella_linea", JSONObject().put("id", l.id), a, "Cancello la linea ${l.id} «${l.titolo}».",
                "La linea sparisce; i segnalini restano.", "Andrebbe ritracciata.") {
                Archivio.di(c).togliLinea(l.id); Archivio.avvisa(); riuscito().put("cancellata", l.titolo)
            }
        },
    )

    private fun quale(c: Context, a: JSONObject): Pair<Segnalino?, JSONObject?> {
        val s = Archivio.di(c).segnalino(a.optLong("id"))
        return if (s != null) s to null else null to AiutiIndagine.nonTrovato("il segnalino", a.optLong("id"),
            Archivio.di(c).segnalini().map { it.id to it.titolo }, "segnalini")
    }

    fun riga(c: Context, s: Segnalino): JSONObject = JSONObject().put("id", s.id).put("titolo", s.titolo).put("lat", s.lat).put("lon", s.lon)
        .apply { if (s.youtubeId != null) put("video", AiutiIndagine.video(c, s.youtubeId, s.secondi)) }
        .apply { if (s.notaId != null) put("nota_id", s.notaId) }

    fun dettaglio(c: Context, s: Segnalino): JSONObject {
        val i = Archivio.di(c)
        return riuscito().put("segnalino", riga(c, s).put("testo", s.testo).put("autore", s.autore))
            .apply { s.notaId?.let { i.nota(it) }?.let { put("nota", JSONObject().put("id", it.id).put("titolo", it.titolo).put("fonte", it.fonte)) } }
            .put("persone", JSONArray().also { r -> i.legamiVerso("segnalino", s.id).filter { it.da == "persona" }.mapNotNull { i.persona(it.daId) }
                .forEach { r.put(JSONObject().put("id", it.id).put("nome", it.nome)) } })
            .put("linee", JSONArray().also { r -> i.linee().filter { l -> i.punti(l.id).any { it.segnalinoId == s.id } }.forEach { r.put(JSONObject().put("id", it.id).put("titolo", it.titolo)) } })
    }

    private fun linea(c: Context, id: Long): JSONObject {
        val i = Archivio.di(c); val l = i.linea(id)!!
        return JSONObject().put("id", l.id).put("titolo", l.titolo).put("colore", l.colore)
            .put("punti", JSONArray().also { r -> i.punti(id).mapNotNull { i.segnalino(it.segnalinoId) }.forEach { r.put(JSONObject().put("id", it.id).put("titolo", it.titolo)) } })
    }

    private fun puntiDa(c: Context, a: JSONObject, azione: String): Pair<List<Long>?, JSONObject?> {
        val ids = a.optString("punti").split(Regex("[^0-9]+")).mapNotNull { it.toLongOrNull() }
        val ci = Archivio.di(c).segnalini()
        val mancano = ids.filter { id -> ci.none { it.id == id } }
        if (ids.size < 2 || mancano.isNotEmpty()) return null to Errore.valoreSbagliato(nome, azione,
            if (ids.size < 2) "Servono almeno 2 segnalini in `punti`." else "Questi segnalini non ci sono: ${mancano.joinToString(", ")}.",
            JSONObject().put("titolo", a.optString("titolo", "…")).put("punti", ci.take(2).joinToString(",") { it.id.toString() }.ifBlank { "1,2" }))
            .put("ci_sono", JSONArray().also { r -> ci.take(20).forEach { r.put(JSONObject().put("id", it.id).put("titolo", it.titolo)) } })
        return ids to null
    }

    private fun creaSegnalino(c: Context, a: JSONObject): JSONObject {
        var lat = if (a.has("lat")) a.optDouble("lat") else null
        var lon = if (a.has("lon")) a.optDouble("lon") else null
        var trovato: String? = null
        if (lat == null || lon == null) {
            val luogo = a.optString("luogo")
            if (luogo.isBlank()) return Errore.generico("Mancano `lat` e `lon` (oppure `luogo`).",
                JSONObject().put("action", "crea_segnalino").put("titolo", a.optString("titolo")).put("luogo", "piazza Fontana, Milano"), "mancano_parametri")
                .put("forse_intendevi", JSONArray(listOf("lat", "lon", "luogo")))
            val l = Luoghi.cerca(c, luogo).firstOrNull() ?: return Errore.nonDisponibile("Nominatim non trova «$luogo». Prova con un nome piu' semplice o dai lat/lon.",
                JSONObject().put("action", "cerca_luogo").put("luogo", luogo))
            lat = l.lat; lon = l.lon; trovato = l.nome
        }
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return Errore.valoreSbagliato(nome, "crea_segnalino", "Coordinate fuori scala: lat $lat, lon $lon.",
            JSONObject().put("titolo", a.optString("titolo")).put("lat", 45.4669).put("lon", 9.1934))
        val vid = AiutiIndagine.idDi(a.optString("youtube_id"))
        val notaId = if (a.has("nota_id")) a.optLong("nota_id").takeIf { Archivio.di(c).nota(it) != null } ?: return AiutiIndagine.nonTrovato("la nota",
            a.optLong("nota_id"), Archivio.di(c).note().map { it.id to it.titolo }, "elenca") else null
        val id = Archivio.di(c).metti(Segnalino(lat = lat, lon = lon, titolo = a.optString("titolo").trim(), testo = a.optString("testo").trim(),
            youtubeId = vid, secondi = if (vid != null) secondiDa(a.optString("tempo").ifBlank { null }) else null, notaId = notaId, autore = "assistente"))
        if (a.has("teoria_id") && Archivio.di(c).teoria(a.optLong("teoria_id")) != null)
            Archivio.di(c).metti(Legame(da = "teoria", daId = a.optLong("teoria_id"), verso = "segnalino", versoId = id))
        Archivio.avvisa()
        return dettaglio(c, Archivio.di(c).segnalino(id)!!).put("creato", true)
            .apply { if (trovato != null) put("luogo_trovato", trovato).put("fonte_coordinate", "© OpenStreetMap contributors (Nominatim)") }
            .put("nota_per_te", "Il segnalino e' nella schermata Mappa.")
    }
}
