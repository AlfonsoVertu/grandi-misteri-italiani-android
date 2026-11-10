package it.bibliotecadialessandria.ricerca.indagine

import android.content.Context
import it.bibliotecadialessandria.ricerca.Marca
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * La ricerca di un luogo con Nominatim di OpenStreetMap (11/11/2026, STUDIO_ASSISTENTE_AI.md §4.10). La policy
 * (operations.osmfoundation.org/policies/nominatim): al massimo 1 richiesta al secondo, un User-Agent che dica chi
 * siamo, niente richieste in blocco, risultati da tenere in cache. Qui: una richiesta alla volta (lucchetto), almeno
 * 1,1 s fra l'una e l'altra, solo su azione della persona o dell'assistente, e una cache in memoria per la sessione.
 */
object Luoghi {

    data class Luogo(val nome: String, val lat: Double, val lon: Double, val tipo: String)

    /** Il nome dell'app nell'User-Agent (lo chiede anche la policy delle tessere della mappa). */
    fun ua(ctx: Context): String {
        val versione = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
        return Marca.NOME.replace(" ", "") + "/" + versione + " (Android; " + Marca.SITO + ")"
    }

    private val lucchetto = Any()
    @Volatile private var ultima = 0L
    private val cache = HashMap<String, List<Luogo>>()

    /** Fuori dal filo dell'interfaccia. */
    fun cerca(ctx: Context, testo: String, quanti: Int = 5): List<Luogo> {
        val q = testo.trim()
        if (q.isEmpty()) return emptyList()
        synchronized(cache) { cache[q.lowercase()]?.let { return it } }
        synchronized(lucchetto) {
            val aspetta = 1100 - (System.currentTimeMillis() - ultima)
            if (aspetta > 0) Thread.sleep(aspetta)
            val u = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=${quanti.coerceIn(1, 10)}&accept-language=it&q=" +
                URLEncoder.encode(q, "UTF-8")
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000
                setRequestProperty("User-Agent", ua(ctx))
                setRequestProperty("Accept-Language", "it")
            }
            try {
                val code = c.responseCode
                ultima = System.currentTimeMillis()
                if (code == 429 || code == 403) throw RuntimeException("Nominatim chiede di andare piu' piano: riprova fra un minuto.")
                if (code !in 200..299) throw RuntimeException("Nominatim ha risposto HTTP $code.")
                val a = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                val fuori = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Luogo(it.optString("display_name"), it.optString("lat").toDouble(), it.optString("lon").toDouble(),
                        listOf(it.optString("category"), it.optString("type")).filter { s -> s.isNotBlank() }.joinToString("/"))
                }
                synchronized(cache) { cache[q.lowercase()] = fuori }
                return fuori
            } finally { c.disconnect() }
        }
    }
}
