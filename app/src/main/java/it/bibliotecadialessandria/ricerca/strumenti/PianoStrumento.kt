package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.indagine.Archivio
import it.bibliotecadialessandria.ricerca.indagine.Passo
import it.bibliotecadialessandria.ricerca.indagine.Piano
import org.json.JSONArray
import org.json.JSONObject

/**
 * `piano` (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §7.1): il piano a passi dei compiti lunghi. Sta su disco: se
 * l'app si chiude o finiscono i giri, al messaggio dopo si riprende dal primo passo non fatto. Un piano aperto alla volta;
 * la persona lo vede sopra la chat.
 */
object PianoStrumento : Strumento() {

    override val nome = "piano"
    override val descrizione = "Il piano a passi per un compito lungo (piu' ricerche, piu' video, piu' giri): lo crei all'inizio, " +
        "aggiorni lo stato di ogni passo mentre lavori, ci scrivi gli appunti (risultati intermedi, fonti) e lo chiudi alla fine. " +
        "La persona lo vede sopra la chat; se ti fermi, al messaggio dopo riprendi dal primo passo non fatto."

    val STATI = listOf("da_fare", "in_corso", "fatto", "bloccato")

    override val azioni = listOf(
        Azione("crea", "Crea il piano con i passi in ordine (chiude quello vecchio se ce n'e' uno aperto e lo dici).",
            "All'inizio di una richiesta che chiede piu' di 3-4 chiamate agli strumenti.",
            listOf(Parametro("titolo", "string", "Il compito in breve.", true, esempio = "Dossier sul caso David Rossi"),
                Parametro("passi", "string", "I passi in ordine: array JSON di testi oppure righe separate da «|» o a capo.", true,
                    esempio = "Cerca i video nel catalogo | Scarica le trascrizioni | Crea la teoria e le note | Scrivi il dossier"),
                Parametro("obiettivo", "string", "Cosa deve esserci alla fine.")), "preparo il piano", collegate = listOf("aggiorna_passo")) { c, a ->
            val passi = (runCatching { JSONArray(a.optString("passi")).let { j -> (0 until j.length()).map { j.optString(it) } } }.getOrNull()
                ?: a.optString("passi").split(Regex("\\s*\\|\\s*|\\n+"))).map { it.trim().removePrefix("-").trim().replace(Regex("^\\d+[.)]\\s*"), "") }
                .filter { it.isNotBlank() }
            if (passi.isEmpty()) return@Azione Errore.valoreSbagliato(nome, "crea", "Nessun passo in `passi`.", JSONObject().put("titolo", a.optString("titolo")).put("passi", "Passo uno | Passo due"))
            val i = Archivio.di(c)
            val vecchio = i.pianoAperto()
            var id = 0L
            Archivio.transazione(c) {
                vecchio?.let { i.aggiorna(it.copy(aperto = false, esito = "sostituito da un piano nuovo", modificato = System.currentTimeMillis())) }
                id = i.metti(Piano(titolo = a.optString("titolo").trim(), obiettivo = a.optString("obiettivo").trim()))
                passi.forEachIndexed { k, t -> i.metti(Passo(pianoId = id, ordine = k + 1, testo = t)) }
            }
            Archivio.avvisa()
            stato(c, i.piano(id)!!).put("creato", true).apply { if (vecchio != null) put("chiuso_il_vecchio", vecchio.titolo) }
                .put("prossimo", JSONObject().put("action", "aggiorna_passo").put("passo", 1).put("stato", "in_corso"))
        },
        Azione("aggiorna_passo", "Cambia lo stato di un passo (da_fare, in_corso, fatto, bloccato) e ne scrive l'esito (cosa hai trovato, con le fonti).",
            "Prima di cominciare un passo (in_corso) e appena l'hai finito (fatto + esito).",
            listOf(Parametro("passo", "integer", "Il numero del passo (da 1).", true, esempio = 1),
                Parametro("stato", "string", "Il nuovo stato.", true, STATI, "fatto"),
                Parametro("esito", "string", "Cosa e' venuto fuori, in breve, con link e minuti.")), "aggiorno il piano") { c, a ->
            val p = Archivio.di(c).pianoAperto() ?: return@Azione nessuno()
            val passi = Archivio.di(c).passi(p.id)
            val x = passi.firstOrNull { it.ordine == a.optInt("passo") } ?: return@Azione Errore.valoreSbagliato(nome, "aggiorna_passo",
                "Il piano ha i passi da 1 a ${passi.size}.", JSONObject().put("passo", passi.firstOrNull { it.stato != "fatto" }?.ordine ?: 1).put("stato", "fatto"))
            Archivio.di(c).aggiorna(x.copy(stato = a.optString("stato"), esito = a.optString("esito", x.esito)))
            Archivio.di(c).aggiorna(p.copy(modificato = System.currentTimeMillis()))
            Archivio.avvisa(); stato(c, p)
        },
        Azione("aggiungi_passo", "Aggiunge un passo in fondo al piano aperto.", "Quando scopri che serve altro.",
            listOf(Parametro("testo", "string", "Il passo.", true)), "aggiungo un passo") { c, a ->
            val p = Archivio.di(c).pianoAperto() ?: return@Azione nessuno()
            Archivio.di(c).metti(Passo(pianoId = p.id, ordine = Archivio.di(c).passi(p.id).size + 1, testo = a.optString("testo").trim()))
            Archivio.avvisa(); stato(c, p)
        },
        Azione("appunti", "Aggiunge una riga al taccuino del piano (risultati intermedi, fonti, idee): resta su disco anche se la conversazione si accorcia.", "",
            listOf(Parametro("testo", "string", "L'appunto.", true)), "scrivo un appunto") { c, a ->
            val p = Archivio.di(c).pianoAperto() ?: return@Azione nessuno()
            Archivio.di(c).aggiorna(p.copy(appunti = (p.appunti + "\n- " + a.optString("testo").trim()).trim().takeLast(12000), modificato = System.currentTimeMillis()))
            Archivio.avvisa(); stato(c, Archivio.di(c).piano(p.id)!!)
        },
        Azione("leggi", "Il piano aperto (o l'ultimo) con i passi, gli esiti e gli appunti.", "Per riprendere un compito: comincia dal primo passo non fatto.",
            emptyList(), "rileggo il piano") { c, _ ->
            val p = Archivio.di(c).pianoAperto() ?: Archivio.di(c).piani(1).firstOrNull() ?: return@Azione nessuno()
            stato(c, p)
        },
        Azione("chiudi", "Chiude il piano aperto con l'esito finale.", "Quando il compito e' finito (o la persona lo abbandona).",
            listOf(Parametro("esito", "string", "Com'e' finita, in breve.", true, esempio = "Dossier scritto nei Media: dossier_rossi.md")), "chiudo il piano") { c, a ->
            val p = Archivio.di(c).pianoAperto() ?: return@Azione nessuno()
            Archivio.di(c).aggiorna(p.copy(aperto = false, esito = a.optString("esito").trim(), modificato = System.currentTimeMillis()))
            Archivio.avvisa(); riuscito().put("chiuso", p.titolo)
        },
    )

    private fun nessuno() = Errore.generico("Non c'e' un piano aperto.", JSONObject().put("action", "crea").put("titolo", "<compito>").put("passi", "Passo uno | Passo due"), "nessun_piano")

    fun stato(c: Context, p: Piano): JSONObject {
        val passi = Archivio.di(c).passi(p.id)
        val primo = passi.firstOrNull { it.stato != "fatto" }
        return riuscito().put("piano", JSONObject().put("id", p.id).put("titolo", p.titolo).put("obiettivo", p.obiettivo).put("aperto", p.aperto)
            .put("passi", JSONArray().also { r -> passi.forEach { x -> r.put(JSONObject().put("passo", x.ordine).put("testo", x.testo).put("stato", x.stato)
                .apply { if (x.esito.isNotBlank()) put("esito", x.esito) }) } })
            .apply { if (p.appunti.isNotBlank()) put("appunti", p.appunti) }
            .apply { if (p.esito.isNotBlank()) put("esito", p.esito) })
            .put("fatti", "${passi.count { it.stato == "fatto" }} su ${passi.size}")
            .apply { if (primo != null && p.aperto) put("riprendi_da", JSONObject().put("passo", primo.ordine).put("testo", primo.testo)) }
    }

    /** Il piano aperto in poche righe, per il prompt (il punto di ripresa). null se non ce n'e'. */
    fun perIlPrompt(c: Context): String? {
        val p = Archivio.di(c).pianoAperto() ?: return null
        val passi = Archivio.di(c).passi(p.id)
        val primo = passi.firstOrNull { it.stato != "fatto" }
        return buildString {
            append("PIANO APERTO «${p.titolo}»").append(if (p.obiettivo.isNotBlank()) " (obiettivo: ${p.obiettivo})" else "").append(":\n")
            passi.forEach { x -> append("  ${x.ordine}. [${x.stato}] ${x.testo}").append(if (x.esito.isNotBlank()) " → ${x.esito.take(300)}" else "").append('\n') }
            if (p.appunti.isNotBlank()) append("Appunti del taccuino:\n").append(p.appunti.takeLast(2500)).append('\n')
            if (primo != null) append("Punto di ripresa: il passo ${primo.ordine} («${primo.testo}»). Se la persona dice «continua» (o non cambia " +
                "argomento) riprendi da li'; se cambia argomento chiedi se chiudere il piano.")
            else append("Tutti i passi sono fatti: rispondi con il risultato e chiudi il piano (action=chiudi).")
        }
    }
}
