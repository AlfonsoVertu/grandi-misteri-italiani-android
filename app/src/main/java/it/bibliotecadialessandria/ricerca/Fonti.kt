package it.bibliotecadialessandria.ricerca

import java.util.concurrent.ConcurrentHashMap

/**
 * (12/11/2026) Le voci del catalogo che NON sono video YouTube (gli episodi di un podcast, id «pod_...»): da dove
 * prendere i sottotitoli. Un link YouTube (l'«Originale» citato nelle note) SOVRASCRIVE la fonte esterna, che non ha
 * sottotitoli suoi. Lo riempie il Catalogo dell'app quando carica; nelle app senza podcast resta vuoto.
 */
object Fonti {
    private val sottotitoli = ConcurrentHashMap<String, String>()

    fun registra(id: String, youtube: String) { if (youtube.isNotBlank()) sottotitoli[id] = youtube }

    /** Una voce esterna: per ora gli episodi di podcast. */
    fun esterna(id: String): Boolean = id.startsWith("pod_")

    /** Il video YouTube da cui prendere i sottotitoli di [id]: lo stesso se e' un video, l'«Originale» se e' esterna, null se non c'e'. */
    fun perSottotitoli(id: String): String? = sottotitoli[id] ?: if (esterna(id)) null else id
}
