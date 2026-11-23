package it.bibliotecadialessandria.ricerca

/**
 * I tipi comuni a tutte le app dei canali (10/11/2026): ogni app ha il suo [Catalogo]
 * (le chiamate al sito, come la Biblioteca, oppure un JSON dentro l'app, come Entropy for Life)
 * ma tutte parlano con le schermate attraverso questi tipi.
 */

/** Una casella di un filtro: `campo` e' l'identificativo da rimandare al catalogo. */
data class Opzione(val campo: String, val etichetta: String, val predefinita: Boolean)

data class Gruppo(val titolo: String, val opzioni: List<Opzione>)

/** Un campo di testo: `campo` e' il nome da rimandare, `valore` quello proposto. */
data class Testo(val campo: String, val etichetta: String, val valore: String)

class Modulo(
    val gruppi: List<Gruppo>,
    val testi: List<Testo>,
    val attributi: List<Testo>,
    /** Quello che serve al catalogo per cercare (sessione, campi del sito...): le schermate non lo guardano. */
    internal val interno: Any? = null,
    internal val preso: Long = System.currentTimeMillis(),
)

data class Video(
    val youtubeId: String,
    val titolo: String,
    val durata: String,
    val data: String,
    val tipo: String,
    val categoria: String,
    val serie: String,
    val periodo: String,
    val zona: String,
    /** (12/11/2026) una voce che NON e' un video YouTube (un episodio di podcast): la pagina da aprire. Vuoto = YouTube. */
    val esterno: String = "",
    /** (12/11/2026) il video YouTube da cui prendere miniatura e sottotitoli quando la voce e' esterna (l'«Originale»). */
    val sottotitoliDa: String = "",
    /** (12/11/2026) un video del catalogo che e' anche un episodio di podcast: la pagina dell'episodio («Ascolta»). */
    val podcast: String = "",
    /** (12/11/2026) la miniatura di una voce esterna senza video YouTube (la copertina del podcast). */
    val copertina: String = "",
) {
    val link: String get() = esterno.ifBlank { "https://www.youtube.com/watch?v=$youtubeId" }
    val anteprima: String get() = when {
        esterno.isBlank() -> "https://i.ytimg.com/vi/$youtubeId/mqdefault.jpg"
        sottotitoliDa.isNotBlank() -> "https://i.ytimg.com/vi/$sottotitoliDa/mqdefault.jpg"
        else -> copertina
    }
    /** Il video YouTube a cui legare note e minuti: lo stesso, o l'«Originale» di una voce esterna (vuoto se non c'e'). */
    val idYoutubeVero: String get() = if (esterno.isBlank()) youtubeId else sottotitoliDa

    /** «11' · Aperistoria · Storia · Europa»: solo cio' che c'e'. */
    fun dettagli(): String = listOf(durata, tipo.lowercase().replaceFirstChar { it.uppercase() },
        categoria, serie, periodo, zona)
        .map { it.trim() }
        .filter { it.isNotEmpty() && it != "-" }
        .joinToString(" · ")
}

data class Esito(val video: List<Video>, val totale: Int)

/** Un link del canale: mattonella in home e bottone nei crediti. */
class Collegamento(val icona: Int, val nome: String, val sotto: String, val url: String)

/** L'id di un video YouTube da un link watch, youtu.be, embed o shorts. */
fun idYoutube(url: String): String? =
    Regex("""(?:v=|youtu\.be/|/embed/|/shorts/)([A-Za-z0-9_-]{11})""").find(url)?.groupValues?.get(1)
