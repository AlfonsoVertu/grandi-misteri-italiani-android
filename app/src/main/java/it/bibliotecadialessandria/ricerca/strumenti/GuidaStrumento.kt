package it.bibliotecadialessandria.ricerca.strumenti

import it.bibliotecadialessandria.ricerca.Marca
import org.json.JSONArray
import org.json.JSONObject

/**
 * `guida`: la bussola dell'assistente (10/11/2026). Cosa fa l'app, cosa sa fare l'assistente, quali strumenti ha e
 * come si chiamano. Sempre per prima nell'elenco, mai filtrata.
 */
object GuidaStrumento : Strumento() {

    override val nome = "guida"
    override val descrizione = "La guida: cosa fa l'app ${Marca.NOME}, cosa sa fare l'assistente, quali strumenti ha " +
        "e come si usano. Usala quando non sai quale strumento serve."

    override val azioni = listOf(
        Azione("app", "Cosa fa l'app e cosa puo' fare la persona da sola (senza assistente).",
            "Quando chiedono come funziona l'app o dove trovare qualcosa.", emptyList(), "leggo la guida dell'app") { _, _ -> app() },
        Azione("strumenti", "Gli strumenti dell'assistente con le loro azioni e un esempio da copiare per ognuna.",
            "Quando non sai quale strumento usare.", emptyList(), "guardo i miei strumenti") { _, _ -> strumenti() },
        Azione("come-si-usa", "La regola per chiamare gli strumenti: strumento + action + parametri, e i tre passi.",
            "", emptyList(), "rileggo come si usano gli strumenti") { _, _ -> comeSiUsa() },
    )

    private fun app(): JSONObject = riuscito()
        .put("app", Marca.NOME).put("motto", Marca.MOTTO)
        .put("canali", JSONArray(Marca.CASA.map { it.second }))
        .put("cosa_si_fa_nell_app", JSONArray(listOf(
            "Cerca: i video per parole, caso, tema, canale e tipo (il catalogo e' sul telefono).",
            "Novita': gli ultimi video, short e dirette dei canali.",
            "Preferiti e Playlist: salvati solo sul telefono.",
            "Scarica trascrizione: accanto a ogni video nei risultati, salva i sottotitoli con i tempi in un file .txt (senza AI).",
            "I miei canali: avvisi da altri canali YouTube.",
            "Assistente AI: questa chat; usa la chiave e il fornitore scelti dalla persona nelle Impostazioni AI. Con ＋ si " +
                "allegano foto, video, PDF e file; tenendo premuto il microfono si manda un vocale (la risposta si sente anche a voce).",
            "Media: l'archivio dell'indagine (foto, video, PDF, audio, testi) sul telefono: aggiungi, apri, condividi, cancella, cerca.")))
        .put("assistente", "Risponde usando il catalogo dei video, le trascrizioni dei video, il web (DuckDuckGo e Wikipedia) " +
            "le pagine web e l'archivio Media (strumento `media`). Quello che la persona scrive lo legge il fornitore del modello che ha scelto. Nessun server dell'app.")
        .put("crediti", Marca.CREDITI)

    private fun strumenti(): JSONObject = riuscito().put("strumenti", JSONArray().also { arr ->
        Smistatore.TUTTI.forEach { s -> arr.put(JSONObject().put("strumento", s.nome).put("a_cosa_serve", s.descrizione)
            .put("azioni", JSONArray().also { az -> s.azioni.forEach { az.put(JSONObject().put("nome", it.nome).put("cosa_fa", it.cosaFa).put("esempio", it.esempio())) } })) }
    })

    private fun comeSiUsa(): JSONObject = riuscito()
        .put("regola", "Ogni strumento vuole il parametro `action` (cosa fare) e i parametri di quell'azione.")
        .put("tre_passi", JSONArray(listOf(
            "1. Non sai cosa vuole uno strumento? Chiamalo con action=\"documentazione\": hai l'elenco delle azioni.",
            "2. Non sai cosa vuole un'azione? action=\"documentazione_<azione>\": hai i parametri e un esempio da copiare.",
            "3. Se sbagli, l'errore ti dice cosa manca e ha `fai_cosi`: copia quello e riprova.")))
        .put("esempio", JSONObject().put("strumento", "catalogo").put("argomenti", JSONObject().put("action", "cerca").put("testo", "Ustica")))
        .put("citare", "Quando riferisci un video cita titolo e minuto con il link https://www.youtube.com/watch?v=<id>&t=<secondi>s.")
}
