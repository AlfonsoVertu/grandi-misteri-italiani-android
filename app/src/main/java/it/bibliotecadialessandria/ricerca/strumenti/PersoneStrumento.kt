package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.assistente.Media
import it.bibliotecadialessandria.ricerca.indagine.Archivio
import it.bibliotecadialessandria.ricerca.indagine.Legame
import it.bibliotecadialessandria.ricerca.indagine.Persona
import it.bibliotecadialessandria.ricerca.indagine.VoceCronologia
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine.TOKEN
import org.json.JSONArray
import org.json.JSONObject

/**
 * `persone` (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §7.3): le persone di interesse. REGOLA (diffamazione): fatti
 * e fonti, mai accuse; ruolo neutro; ogni voce della cronologia ha la sua fonte (obbligatoria anche qui nello schema).
 */
object PersoneStrumento : Strumento() {

    override val nome = "persone"
    const val REGOLA = "Riporta fatti e fonti, non accuse: ruolo neutro (vittima, familiare, magistrato, testimone, giornalista, " +
        "avvocato; «indagato» solo se risulta da atti pubblici) e ogni affermazione attribuita a chi la fa («secondo X, min 12:30»)."
    override val descrizione = "Le persone di interesse dell'indagine: scheda (nome, ruolo, descrizione, foto dai Media), cronologia " +
        "di cosa ha fatto o detto con la fonte di ogni voce, collegamenti a video al minuto, segnalini, note, teorie, media e altre " +
        "persone (con un'etichetta). $REGOLA"

    private val ID = Parametro("id", "integer", "L'id della persona (lo danno elenca e cerca).", true, esempio = 1)
    private val TIPI = listOf("video", "segnalino", "nota", "teoria", "media", "persona")

    override val azioni = listOf(
        Azione("elenca", "Le persone: id, nome, ruolo.", "", emptyList(), "guardo le persone di interesse") { c, _ ->
            val p = Archivio.di(c).persone()
            riuscito().put("persone", JSONArray().also { r -> p.forEach { r.put(riga(it)) } })
                .apply { if (p.isEmpty()) put("nota", "Nessuna persona. Creane una con action=crea.") }
        },
        Azione("cerca", "Cerca per nome, ruolo o descrizione.", "", listOf(Parametro("testo", "string", "Le parole.", true, esempio = "Rossi")),
            "cerco fra le persone") { c, a ->
            val p = Archivio.di(c).cercaPersone(AiutiIndagine.like(a.optString("testo")))
            riuscito().put("trovate", p.size).put("persone", JSONArray().also { r -> p.forEach { r.put(riga(it)) } })
        },
        Azione("leggi", "La scheda intera: ruolo, descrizione, cronologia con le fonti, tutti i collegamenti.", "", listOf(ID), "leggo una scheda") { c, a ->
            quale(c, a).let { (p, e) -> if (p == null) e!! else dettaglio(c, p) }
        },
        Azione("crea", "Crea una scheda (autore: assistente). $REGOLA", "Quando una persona conta nel caso. Prima cerca se esiste gia'.",
            listOf(Parametro("nome", "string", "Nome e cognome.", true, esempio = "Antonella Tognazzi"),
                Parametro("ruolo", "string", "Ruolo NEUTRO nel caso.", true, esempio = "familiare (vedova)"),
                Parametro("descrizione", "string", "Chi e', solo fatti, con la fonte.", esempio = "Vedova di David Rossi; ospite di Pulp Podcast #71."),
                Parametro("fonte", "string", "La fonte della descrizione (video+minuto o URL).", true, esempio = "Pulp Podcast #71, min 0:40")),
            "creo una scheda persona", errori = listOf("mancano_parametri: nome, ruolo, fonte"), collegate = listOf("aggiungi_cronologia", "collega")) { c, a ->
            val gia = Archivio.di(c).persone().firstOrNull { Smistatore.piano(it.nome) == Smistatore.piano(a.optString("nome")) }
            if (gia != null) return@Azione Errore.generico("C'e' gia' una scheda per «${gia.nome}» (id ${gia.id}).",
                JSONObject().put("action", "aggiorna").put("id", gia.id), "esiste_gia").put("persona", riga(gia))
            val fonte = a.optString("fonte").trim()
            val desc = a.optString("descrizione").trim().let { d -> if (d.isBlank() || d.contains(fonte)) d else "$d (fonte: $fonte)" }
            val id = Archivio.di(c).metti(Persona(nome = a.optString("nome").trim(), ruolo = a.optString("ruolo").trim(), descrizione = desc, autore = "assistente"))
            Archivio.avvisa(); dettaglio(c, Archivio.di(c).persona(id)!!).put("creata", true)
        },
        Azione("aggiorna", "Cambia nome, ruolo, descrizione o foto (solo i campi passati).", "",
            listOf(ID, Parametro("nome", "string", "Nome."), Parametro("ruolo", "string", "Ruolo neutro."), Parametro("descrizione", "string", "Descrizione (fatti con fonte)."),
                Parametro("foto_media_id", "integer", "Un'immagine dell'archivio Media da usare come foto.")), "aggiorno una scheda") { c, a ->
            val (p, e) = quale(c, a); if (p == null) return@Azione e!!
            val foto = if (a.has("foto_media_id")) Media.prendi(c, a.optLong("foto_media_id"))?.takeIf { it.tipo == "immagine" }?.id
                ?: return@Azione Errore.nonEsiste("nei Media l'immagine", a.optString("foto_media_id"), emptyList(), JSONObject().put("strumento", "media").put("action", "elenca").put("tipo", "immagine"))
                else p.fotoId
            Archivio.di(c).aggiorna(p.copy(nome = a.optString("nome", p.nome), ruolo = a.optString("ruolo", p.ruolo),
                descrizione = a.optString("descrizione", p.descrizione), fotoId = foto, modificata = System.currentTimeMillis()))
            Archivio.avvisa(); dettaglio(c, Archivio.di(c).persona(p.id)!!)
        },
        Azione("aggiungi_cronologia", "Aggiunge una voce alla cronologia: quando, cosa ha fatto o detto, e la FONTE (obbligatoria).", "",
            listOf(ID, Parametro("quando", "string", "La data o il momento.", true, esempio = "6 marzo 2013"),
                Parametro("cosa", "string", "Il fatto, attribuito («secondo X…» se e' una ricostruzione).", true, esempio = "Secondo la vedova (min 5:10), quel giorno…"),
                Parametro("fonte", "string", "Video+minuto, pagina web o documento.", true, esempio = "Pulp Podcast #71, min 5:10")),
            "aggiungo alla cronologia") { c, a ->
            val (p, e) = quale(c, a); if (p == null) return@Azione e!!
            val n = Archivio.di(c).cronologia(p.id).size
            Archivio.di(c).metti(VoceCronologia(personaId = p.id, quando = a.optString("quando").trim(), cosa = a.optString("cosa").trim(), fonte = a.optString("fonte").trim(), ordine = n))
            Archivio.avvisa(); dettaglio(c, p)
        },
        Azione("togli_cronologia", "Toglie una voce dalla cronologia (`voce_id` da leggi).", "",
            listOf(ID, Parametro("voce_id", "integer", "L'id della voce.", true)), "tolgo una voce") { c, a ->
            val (p, e) = quale(c, a); if (p == null) return@Azione e!!
            Archivio.di(c).togliVoce(a.optLong("voce_id")); Archivio.avvisa(); dettaglio(c, p)
        },
        Azione("collega", "Collega la persona a: un video al minuto, un segnalino, una nota, una teoria, un file dei Media o un'altra persona (con etichetta, es. «moglie di»).", "",
            listOf(ID, Parametro("a", "string", "Cosa collegare.", true, TIPI, esempio = "video"),
                Parametro("a_id", "integer", "L'id della cosa (non serve per un video)."),
                Parametro("youtube_id", "string", "Per a=video: id o link."), Parametro("tempo", "string", "Per a=video: il minuto."),
                Parametro("etichetta", "string", "Il legame, neutro: «moglie di», «parla al minuto», «testimone in».")),
            "collego la persona") { c, a -> collega(c, a) },
        Azione("scollega", "Toglie un collegamento (`legame_id` da leggi).", "", listOf(ID, Parametro("legame_id", "integer", "L'id del collegamento.", true)),
            "tolgo un collegamento") { c, a ->
            val (p, e) = quale(c, a); if (p == null) return@Azione e!!
            Archivio.di(c).togliLegame(a.optLong("legame_id")); Archivio.avvisa(); dettaglio(c, p)
        },
        Azione("cancella", "Cancella una scheda. A DUE PASSI con `conferma_token`, solo dopo il si' della persona.", "Solo se la persona lo chiede.",
            listOf(ID, TOKEN), "cancello una scheda") { c, a ->
            val (p, e) = quale(c, a); if (p == null) return@Azione e!!
            AiutiIndagine.conConferma(nome, "cancella", JSONObject().put("id", p.id), a, "Cancello la scheda ${p.id} «${p.nome}» con la sua cronologia e i collegamenti.",
                "La scheda sparisce (note, video e segnalini restano).", "Non si torna indietro.") {
                Archivio.cancellaPersona(c, p); Archivio.avvisa(); riuscito().put("cancellata", p.nome)
            }
        },
    )

    private fun riga(p: Persona) = JSONObject().put("id", p.id).put("nome", p.nome).put("ruolo", p.ruolo)

    private fun quale(c: Context, a: JSONObject): Pair<Persona?, JSONObject?> {
        val p = Archivio.di(c).persona(a.optLong("id"))
        return if (p != null) p to null else null to AiutiIndagine.nonTrovato("la persona", a.optLong("id"), Archivio.di(c).persone().map { it.id to it.nome }, "elenca")
    }

    fun dettaglio(c: Context, p: Persona): JSONObject {
        val i = Archivio.di(c)
        val legami = JSONArray()
        i.legamiDa("persona", p.id).forEach { l ->
            val o = JSONObject().put("legame_id", l.id).put("tipo", l.verso).apply { if (l.etichetta.isNotBlank()) put("etichetta", l.etichetta) }
            when (l.verso) {
                "video" -> l.youtubeId?.let { AiutiIndagine.video(c, it, l.secondi, o) }
                "nota" -> o.put("id", l.versoId).put("titolo", i.nota(l.versoId)?.titolo ?: "(cancellata)")
                "segnalino" -> o.put("id", l.versoId).put("titolo", i.segnalino(l.versoId)?.titolo ?: "(cancellato)")
                "teoria" -> o.put("id", l.versoId).put("titolo", i.teoria(l.versoId)?.titolo ?: "(cancellata)")
                "media" -> o.put("id", l.versoId).put("nome", Media.prendi(c, l.versoId)?.nome ?: "(tolto)")
                "persona" -> o.put("id", l.versoId).put("nome", i.persona(l.versoId)?.nome ?: "(cancellata)")
            }
            legami.put(o)
        }
        i.legamiVerso("persona", p.id).filter { it.da == "persona" }.forEach { l ->
            legami.put(JSONObject().put("legame_id", l.id).put("tipo", "persona").put("id", l.daId).put("nome", i.persona(l.daId)?.nome ?: "")
                .put("etichetta", "(lui/lei e') oggetto di: " + l.etichetta))
        }
        return riuscito().put("persona", riga(p).put("descrizione", p.descrizione).put("autore", p.autore).apply { p.fotoId?.let { put("foto_media_id", it) } })
            .put("cronologia", JSONArray().also { r -> i.cronologia(p.id).forEach { v ->
                r.put(JSONObject().put("voce_id", v.id).put("quando", v.quando).put("cosa", v.cosa).put("fonte", v.fonte)) } })
            .put("collegamenti", legami)
            .put("regola", REGOLA)
    }

    private fun collega(c: Context, a: JSONObject): JSONObject {
        val (p, e) = quale(c, a); if (p == null) return e!!
        val i = Archivio.di(c)
        val tipo = a.optString("a")
        val et = a.optString("etichetta").trim()
        if (tipo == "video") {
            val vid = AiutiIndagine.idDi(a.optString("youtube_id")) ?: return Errore.valoreSbagliato(nome, "collega", "Per a=video serve `youtube_id` (id o link).",
                JSONObject().put("id", p.id).put("a", "video").put("youtube_id", "oRFuohjWMpA").put("tempo", "12:30"))
            i.metti(Legame(da = "persona", daId = p.id, verso = "video", youtubeId = vid, secondi = secondiDa(a.optString("tempo").ifBlank { null }), etichetta = et))
        } else {
            val id = a.optLong("a_id")
            val c1 = when (tipo) {
                "nota" -> i.nota(id)?.titolo; "segnalino" -> i.segnalino(id)?.titolo; "teoria" -> i.teoria(id)?.titolo
                "media" -> Media.prendi(c, id)?.nome; else -> i.persona(id)?.takeIf { it.id != p.id }?.nome
            } ?: return Errore.nonEsiste("$tipo con id", id.toString(), emptyList(), JSONObject().put("action", "collega").put("id", p.id).put("a", tipo).put("a_id", "<id>"))
                .put("come_trovarlo", when (tipo) { "nota" -> "note action=elenca"; "segnalino" -> "mappa action=segnalini"; "teoria" -> "teorie action=elenca"
                    "media" -> "media action=elenca"; else -> "persone action=elenca" })
            i.metti(Legame(da = "persona", daId = p.id, verso = tipo, versoId = id, etichetta = et)).also { c1.length }
        }
        Archivio.avvisa()
        return dettaglio(c, p).put("collegato", tipo)
    }
}
