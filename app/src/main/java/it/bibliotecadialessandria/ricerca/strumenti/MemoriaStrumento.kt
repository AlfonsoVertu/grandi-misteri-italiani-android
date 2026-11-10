package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.indagine.Archivio
import it.bibliotecadialessandria.ricerca.indagine.Memoria
import it.bibliotecadialessandria.ricerca.indagine.Memorie
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine.TOKEN
import org.json.JSONArray
import org.json.JSONObject

/**
 * `memoria` (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §7.2): come add_memory / list_memories / update_memory /
 * delete_memory / get_memory_markdown di SoWay, con i tipi istruzione, flusso, fatto, preferenza.
 */
object MemoriaStrumento : Strumento() {

    override val nome = "memoria"
    override val descrizione = "La tua memoria su questo telefono, che la persona vede e modifica nella schermata Memoria. Tipi: " +
        "istruzione (come la persona vuole che lavori: entra sempre nel prompt), preferenza (sempre nel prompt), flusso (una " +
        "procedura riusabile a passi: entra quando serve), fatto (cose apprese sull'indagine, con la fonte: entra quando serve)."

    private val ID = Parametro("id", "integer", "L'id della memoria (lo danno elenca e cerca).", true, esempio = 1)
    private val TIPO = Parametro("tipo", "string", "Il tipo.", valori = Memorie.TIPI)

    override val azioni = listOf(
        Azione("crea", "Ricorda una cosa (creata da: AI).", "Quando la persona dice «ricordati…», «d'ora in poi…», o descrive una procedura " +
            "da ripetere (flusso), o quando trovi un fatto da non perdere. Prima cerca se c'e' gia' (meglio aggiornare che duplicare).",
            listOf(TIPO.let { Parametro(it.nome, it.tipo, it.cosE, true, it.valori, "istruzione") },
                Parametro("titolo", "string", "Un titolo breve.", true, esempio = "Cita sempre il minuto"),
                Parametro("testo", "string", "Il contenuto. Per un flusso: i passi numerati.", true, esempio = "Quando parli di un video metti sempre il minuto.")),
            "mi segno una cosa in memoria", collegate = listOf("cerca", "aggiorna")) { c, a ->
            val gia = Archivio.di(c).memorie().firstOrNull { Smistatore.piano(it.titolo) == Smistatore.piano(a.optString("titolo")) }
            if (gia != null) return@Azione Errore.generico("C'e' gia' una memoria «${gia.titolo}» (id ${gia.id}): aggiornala invece di duplicarla.",
                JSONObject().put("action", "aggiorna").put("id", gia.id).put("testo", a.optString("testo")), "esiste_gia").put("memoria", riga(gia, true))
            val id = Archivio.di(c).metti(Memoria(tipo = a.optString("tipo"), titolo = a.optString("titolo").trim(), testo = a.optString("testo").trim(), creataDa = "ai"))
            Archivio.avvisa(); riuscito().put("creata", riga(Archivio.di(c).memoria(id)!!, true))
                .put("nota", "La persona la vede nella schermata Memoria e puo' modificarla o cancellarla.")
        },
        Azione("elenca", "Le memorie (titolo, tipo, chi l'ha creata, data), anche di un solo tipo.", "",
            listOf(TIPO), "guardo la memoria") { c, a ->
            val m = Archivio.di(c).memorie().filter { a.optString("tipo").isBlank() || it.tipo == a.optString("tipo") }
            riuscito().put("totale", m.size).put("memorie", JSONArray().also { r -> m.take(60).forEach { r.put(riga(it, false)) } })
        },
        Azione("cerca", "Cerca nelle memorie a parole (titolo e testo).", "", listOf(Parametro("testo", "string", "Le parole.", true, esempio = "caso nuovo")),
            "cerco nella memoria") { c, a ->
            val q = AiutiIndagine.parole(a.optString("testo"))
            val m = Archivio.di(c).memorie().map { it to AiutiIndagine.punteggio(q, it.titolo + " " + it.testo) }.filter { it.second > 0 }
                .sortedByDescending { it.second }.map { it.first }
            riuscito().put("trovate", m.size).put("memorie", JSONArray().also { r -> m.take(15).forEach { r.put(riga(it, true)) } })
        },
        Azione("leggi", "Una memoria intera.", "", listOf(ID), "leggo una memoria") { c, a ->
            quale(c, a).let { (m, e) -> if (m == null) e!! else riuscito().put("memoria", riga(m, true)) }
        },
        Azione("aggiorna", "Cambia titolo, testo o tipo di una memoria.", "",
            listOf(ID, TIPO, Parametro("titolo", "string", "Nuovo titolo."), Parametro("testo", "string", "Nuovo testo.")), "aggiorno la memoria") { c, a ->
            val (m, e) = quale(c, a); if (m == null) return@Azione e!!
            Archivio.di(c).aggiorna(m.copy(tipo = a.optString("tipo").ifBlank { m.tipo }, titolo = a.optString("titolo", m.titolo),
                testo = a.optString("testo", m.testo), modificata = System.currentTimeMillis()))
            Archivio.avvisa(); riuscito().put("aggiornata", riga(Archivio.di(c).memoria(m.id)!!, true))
        },
        Azione("cancella", "Cancella una memoria. A DUE PASSI con `conferma_token`, solo dopo il si' della persona.", "Solo se la persona lo chiede.",
            listOf(ID, TOKEN), "cancello una memoria") { c, a ->
            val (m, e) = quale(c, a); if (m == null) return@Azione e!!
            AiutiIndagine.conConferma(nome, "cancella", JSONObject().put("id", m.id), a, "Cancello la memoria ${m.id} «${m.titolo}» (${m.tipo}).",
                "Non me la ricordero' piu'.", "Andrebbe riscritta (o reimportata dal markdown esportato).") {
                Archivio.di(c).togli(m); Archivio.avvisa(); riuscito().put("cancellata", m.titolo)
            }
        },
        Azione("esporta", "Tutte le memorie in markdown (## titolo, tipo, chi, data, testo).", "Per un riepilogo o per salvarle in un file dei Media.",
            emptyList(), "esporto la memoria") { c, _ -> riuscito().put("markdown", Memorie.markdown(c)) },
    )

    private fun riga(m: Memoria, tutto: Boolean) = JSONObject().put("id", m.id).put("tipo", m.tipo).put("titolo", m.titolo)
        .put("creata_da", if (m.creataDa == "ai") "AI" else "utente").put("creata", Memorie.quando(m.creata))
        .put("testo", if (tutto) m.testo else m.testo.take(120))

    private fun quale(c: Context, a: JSONObject): Pair<Memoria?, JSONObject?> {
        val m = Archivio.di(c).memoria(a.optLong("id"))
        return if (m != null) m to null else null to AiutiIndagine.nonTrovato("la memoria", a.optLong("id"), Archivio.di(c).memorie().map { it.id to it.titolo }, "elenca")
    }
}
