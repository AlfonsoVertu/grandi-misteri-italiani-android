package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.assistente.Media
import it.bibliotecadialessandria.ricerca.indagine.Archivio
import it.bibliotecadialessandria.ricerca.indagine.Legame
import it.bibliotecadialessandria.ricerca.indagine.NotaTeoria
import it.bibliotecadialessandria.ricerca.indagine.Teoria
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine.TOKEN
import org.json.JSONArray
import org.json.JSONObject

/**
 * `teorie` (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §6): le teorie raggruppano note (una nota puo' stare in piu'
 * teorie), segnalini, media e persone. Cancellare una teoria non cancella le sue note.
 */
object TeorieStrumento : Strumento() {

    override val nome = "teorie"
    override val descrizione = "Le teorie dell'indagine: ognuna raggruppa note (con i video ai minuti), segnalini della mappa, " +
        "media e persone. Puoi elencarle, leggerle, crearle, rinominarle, aggiungere o togliere note, collegare segnalini e media, cancellarle (con conferma)."

    private val ID = Parametro("id", "integer", "L'id della teoria (lo da' elenca).", true, esempio = 1)

    override val azioni = listOf(
        Azione("elenca", "Le teorie: id, titolo, quante note.", "", emptyList(), "guardo le teorie") { c, _ ->
            val i = Archivio.di(c); val t = i.teorie()
            riuscito().put("teorie", JSONArray().also { r -> t.forEach { r.put(JSONObject().put("id", it.id).put("titolo", it.titolo)
                .put("note", i.noteDellaTeoria(it.id).size).put("autore", it.autore)) } })
                .apply { if (t.isEmpty()) put("nota", "Nessuna teoria. Creane una con action=crea.") }
        },
        Azione("leggi", "Una teoria con le sue note, i video ai minuti, i segnalini, le persone e i media.", "",
            listOf(ID), "leggo una teoria") { c, a -> quale(c, a).let { (t, e) -> if (t == null) e!! else dettaglio(c, t) } },
        Azione("crea", "Crea una teoria (autore: assistente).", "Per raccogliere note e prove attorno a un'ipotesi.",
            listOf(Parametro("titolo", "string", "Il titolo.", true, esempio = "Ustica: la battaglia aerea"),
                Parametro("descrizione", "string", "L'ipotesi in breve, distinguendo fatti accertati e ipotesi.")), "creo una teoria",
            collegate = listOf("aggiungi_nota")) { c, a ->
            val id = Archivio.di(c).metti(Teoria(titolo = a.optString("titolo").trim(), descrizione = a.optString("descrizione").trim(), autore = "assistente"))
            Archivio.avvisa(); dettaglio(c, Archivio.di(c).teoria(id)!!).put("creata", true)
        },
        Azione("modifica", "Rinomina una teoria o ne cambia la descrizione.", "",
            listOf(ID, Parametro("titolo", "string", "Nuovo titolo."), Parametro("descrizione", "string", "Nuova descrizione.")), "modifico una teoria") { c, a ->
            val (t, e) = quale(c, a); if (t == null) return@Azione e!!
            Archivio.di(c).aggiorna(t.copy(titolo = a.optString("titolo", t.titolo), descrizione = a.optString("descrizione", t.descrizione), modificata = System.currentTimeMillis()))
            Archivio.avvisa(); dettaglio(c, Archivio.di(c).teoria(t.id)!!)
        },
        Azione("aggiungi_nota", "Mette una nota in una teoria.", "", listOf(ID, Parametro("nota_id", "integer", "L'id della nota.", true, esempio = 3)),
            "aggiungo una nota alla teoria") { c, a ->
            val (t, e) = quale(c, a); if (t == null) return@Azione e!!
            val n = Archivio.di(c).nota(a.optLong("nota_id")) ?: return@Azione AiutiIndagine.nonTrovato("la nota", a.optLong("nota_id"),
                Archivio.di(c).note().map { it.id to it.titolo }, "elenca").put("nota", "Le note si vedono con lo strumento note.")
            Archivio.di(c).metti(NotaTeoria(n.id, t.id)); Archivio.avvisa()
            riuscito().put("aggiunta", JSONObject().put("nota", n.titolo).put("teoria", t.titolo))
        },
        Azione("togli_nota", "Toglie una nota da una teoria (la nota resta).", "", listOf(ID, Parametro("nota_id", "integer", "L'id della nota.", true)),
            "tolgo una nota dalla teoria") { c, a ->
            val (t, e) = quale(c, a); if (t == null) return@Azione e!!
            Archivio.di(c).togliNotaDaTeoria(a.optLong("nota_id"), t.id); Archivio.avvisa(); riuscito().put("tolta", a.optLong("nota_id"))
        },
        Azione("collega", "Collega alla teoria un segnalino della mappa o un file dei Media.", "",
            listOf(ID, Parametro("segnalino_id", "integer", "L'id del segnalino."), Parametro("media_id", "integer", "L'id del file nei Media.")),
            "collego alla teoria") { c, a ->
            val (t, e) = quale(c, a); if (t == null) return@Azione e!!
            val i = Archivio.di(c)
            when {
                a.has("segnalino_id") -> { i.segnalino(a.optLong("segnalino_id")) ?: return@Azione AiutiIndagine.nonTrovato("il segnalino",
                    a.optLong("segnalino_id"), i.segnalini().map { it.id to it.titolo }, "elenca")
                    i.metti(Legame(da = "teoria", daId = t.id, verso = "segnalino", versoId = a.optLong("segnalino_id"))) }
                a.has("media_id") -> { Media.prendi(c, a.optLong("media_id")) ?: return@Azione Errore.nonEsiste("nei Media il file",
                    a.optString("media_id"), emptyList(), JSONObject().put("strumento", "media").put("action", "elenca"))
                    i.metti(Legame(da = "teoria", daId = t.id, verso = "media", versoId = a.optLong("media_id"))) }
                else -> return@Azione Errore.generico("Dimmi `segnalino_id` o `media_id`.", JSONObject().put("action", "collega").put("id", t.id).put("segnalino_id", 1), "mancano_parametri")
            }
            Archivio.avvisa(); dettaglio(c, t)
        },
        Azione("cancella", "Cancella una teoria (le note restano). A DUE PASSI con `conferma_token`, solo dopo il si' della persona.",
            "Solo se la persona lo chiede.", listOf(ID, TOKEN), "cancello una teoria") { c, a ->
            val (t, e) = quale(c, a); if (t == null) return@Azione e!!
            AiutiIndagine.conConferma(nome, "cancella", JSONObject().put("id", t.id), a,
                "Cancello la teoria ${t.id} «${t.titolo}» (${Archivio.di(c).noteDellaTeoria(t.id).size} note, che restano).",
                "La teoria sparisce; note, segnalini e persone restano.", "Non si torna indietro: andrebbe ricreata.") {
                Archivio.cancellaTeoria(c, t); Archivio.avvisa(); riuscito().put("cancellata", t.titolo)
            }
        },
    )

    private fun quale(c: Context, a: JSONObject): Pair<Teoria?, JSONObject?> {
        val t = Archivio.di(c).teoria(a.optLong("id"))
        return if (t != null) t to null else null to AiutiIndagine.nonTrovato("la teoria", a.optLong("id"), Archivio.di(c).teorie().map { it.id to it.titolo }, "elenca")
    }

    fun dettaglio(c: Context, t: Teoria): JSONObject {
        val i = Archivio.di(c)
        val note = i.noteDellaTeoria(t.id)
        return riuscito().put("teoria", JSONObject().put("id", t.id).put("titolo", t.titolo).put("descrizione", t.descrizione).put("autore", t.autore))
            .put("note", JSONArray().also { r -> note.forEach { n -> r.put(JSONObject().put("id", n.id).put("titolo", n.titolo).put("fonte", n.fonte)) } })
            .put("video", JSONArray().also { r -> note.flatMap { n -> i.videoDi(n.id).map { n to it } }.take(30).forEach { (n, v) ->
                r.put(AiutiIndagine.video(c, v.youtubeId, v.secondi, JSONObject().put("nota", n.titolo))) } })
            .put("segnalini", JSONArray().also { r -> Archivio.segnaliniDellaTeoria(c, t.id).forEach { r.put(JSONObject().put("id", it.id).put("titolo", it.titolo).put("lat", it.lat).put("lon", it.lon)) } })
            .put("persone", JSONArray().also { r -> Archivio.personeDellaTeoria(c, t.id).forEach { r.put(JSONObject().put("id", it.id).put("nome", it.nome).put("ruolo", it.ruolo)) } })
            .put("media", JSONArray().also { r -> i.legamiDa("teoria", t.id).filter { it.verso == "media" }.mapNotNull { Media.prendi(c, it.versoId) }
                .forEach { r.put(JSONObject().put("id", it.id).put("nome", it.nome).put("tipo", it.tipo)) } })
    }
}
