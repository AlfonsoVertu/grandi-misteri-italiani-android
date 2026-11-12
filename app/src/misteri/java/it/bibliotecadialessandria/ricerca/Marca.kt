package it.bibliotecadialessandria.ricerca

/**
 * «Grandi misteri italiani» (10/11/2026): la prima app per ARGOMENTO. Dentro ci sono piu' canali YouTube scelti da
 * Alfonso; nel catalogo entrano solo i loro video che parlano di un caso (Mostro di Firenze, Loggia P2, Sindona...),
 * e gli avvisi dei canali di casa arrivano solo per quei video.
 */
object Marca {
    const val NOME = "Grandi misteri italiani"
    /** Piu' canali in un'app (app per argomento): niente scheda Post, Novita' dal catalogo. */
    const val MULTI = true
    const val DOPPIO_TEMA = true
    /** (10/11/2026) L'assistente AI (chat con la chiave dell'utente) e il pulsante «Scarica trascrizione»: per ora solo Misteri. */
    const val ASSISTENTE = true
    const val MOTTO = "Delitti, stragi, logge e misteri d'Italia, raccontati dai migliori canali YouTube"
    /** I canali dell'app: id «UC...» e nome. Gli stessi del catalogo (strumenti/argomenti/misteri.json). */
    val CASA = listOf(
        "UCkW9jjNhT7isEY1PTpbPkvQ" to "Vox Investigazioni",
        "UCBahpZUV9wGy0ZdiVL2rqCA" to "Starting Finance",
        "UCRCWJCFoZUvkkWzIqzfBy6g" to "Nova Lectio",
        "UCAlNOYv6A-WMZ799N1iLMFA" to "Il Punto Economico",
        "UCY99TnBJ8xyat2lpeN_hcEA" to "Pulp Podcast",
        "UCtgIlU49ppoBvEgDboD34Pg" to "Roberto Saviano",
        "UCWJhBqnyVdHAuxexgjghNFw" to "Fanpage.it",
        "UC_OSSIRAJ1QhAqDO113JVYg" to "Vanilla Magazine",
    )
    /** Per le parti dell'app pensate per un canale solo (Post, «Apri il canale»): qui non si usano. */
    const val CANALE = "https://www.youtube.com/@VoxInvestigazioni"
    const val CANALE_ID = "kW9jjNhT7isEY1PTpbPkvQ"
    const val SITO = "https://mondo.workingwithweb.eu/grandi-misteri-italiani-unofficial-app/"
    /** Il codice pubblico dell'app (licenza: tutti i diritti riservati). */
    const val CODICE = "https://github.com/AlfonsoVertu/grandi-misteri-italiani-android"
    /** La cartella su mondo.workingwithweb.eu con l'APK e versione.json. */
    const val MONDO = "grandi-misteri-italiani"
    const val CERCA_SOTTO = "Per caso, tema e canale"
    const val CERCO = "Cerco fra i video..."
    /** Il suggerimento nella casella di ricerca. */
    const val SUGGERIMENTO = "Cerca un video: Moro, Ustica, Orlandi..."
    const val NON_RISPONDE = "Non riesco a leggere il catalogo dei video. Riprova."
    const val NOTA_CERCA = "Ricerca fra i video sui misteri italiani dei canali dell'app · app non ufficiale"
    const val PIEDE = "App non ufficiale · i video sono dei rispettivi canali YouTube"
    const val CREDITI = "Grandi misteri italiani è a cura di Alfonso Vertucci e Havva Vertucci, di Working With Web " +
        "(workingwithweb.it) e Libertà e Azione (libertaeazione.it). " +
        "I video sono dei canali Vox Investigazioni, Starting Finance, Nova Lectio, Il Punto Economico, " +
        "Pulp Podcast, Roberto Saviano, Fanpage.it e Vanilla Magazine (che ha dato il suo permesso). Questa app li raccoglie per argomento e li rende comodi da " +
        "cercare; non è affiliata a nessuno dei canali. I casi e i temi sono assegnati in automatico dai titoli e " +
        "possono sbagliare."
    const val AVVISI_NOTA = "Un avviso quando uno dei canali dell'app pubblica un video su un mistero italiano"

    /** I link dei canali, in home (dopo le sezioni dell'app) e nei crediti. */
    val LINK = listOf(
        Collegamento(R.drawable.ic_canale, "Vanilla Magazine", "Storia e misteri · fonte che ha aderito", "https://www.youtube.com/@Vanilla_Magazine"),
        Collegamento(R.drawable.ic_canale, "Vox Investigazioni", "Cronaca e casi", "https://www.youtube.com/@VoxInvestigazioni"),
        Collegamento(R.drawable.ic_canale, "Pulp Podcast", "Il podcast", "https://www.youtube.com/@Pulp_podcast"),
        Collegamento(R.drawable.ic_canale, "Roberto Saviano", "Mafie e camorra", "https://www.youtube.com/@robertosavianoofficial569"),
        Collegamento(R.drawable.ic_canale, "Nova Lectio", "Storia e geopolitica", "https://www.youtube.com/@NovaLectio"),
        Collegamento(R.drawable.ic_canale, "Il Punto Economico", "Scandali e finanza", "https://www.youtube.com/@ilpuntoeconomico"),
        Collegamento(R.drawable.ic_canale, "Starting Finance", "Economia", "https://www.youtube.com/@StartingFinance"),
        Collegamento(R.drawable.ic_canale, "Fanpage.it", "Notizie e inchieste", "https://www.youtube.com/@FanpageIt"),
        Collegamento(R.drawable.ic_sito, "La pagina", "Cerca anche dal browser", SITO),
    )
}
