package it.bibliotecadialessandria.ricerca.indagine

import android.content.Context
import org.json.JSONObject

/**
 * Le categorie di teorie (13/11/2026, Alfonso: «i SiS sono teorie basate sul denaro: non sovrascrivono le altre ma ne
 * stabiliscono l'approccio di base; nel motore la sezione è generica: sono sempre teorie, in una categorizzazione che parte
 * da un elemento in comune»).
 *
 * `assets/categorie_teorie.json` (facoltativo, per app): [{"id","nome","breve","elemento","colore","icona","regole":[…],
 * "campi":[{"id","nome"}]}]. Una teoria con `categoria` = id ha una «linea» di passaggi ([PassoTeoria]) con quei campi.
 * Senza il file l'app non mostra nessuna sezione e le teorie restano come prima.
 */
object CategorieTeorie {

    data class Campo(val id: String, val nome: String)
    data class Categoria(val id: String, val nome: String, val breve: String, val elemento: String, val colore: String,
                         val icona: String, val regole: List<String>, val campi: List<Campo>)

    @Volatile private var cache: List<Categoria>? = null

    fun tutte(ctx: Context): List<Categoria> = cache ?: runCatching {
        val a = org.json.JSONArray(ctx.applicationContext.assets.open("categorie_teorie.json").bufferedReader().use { it.readText() })
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            val r = o.optJSONArray("regole"); val c = o.optJSONArray("campi")
            Categoria(o.optString("id"), o.optString("nome"), o.optString("breve"), o.optString("elemento"),
                o.optString("colore", "#B8860B"), o.optString("icona", "◆"),
                (0 until (r?.length() ?: 0)).map { r!!.optString(it) },
                (0 until (c?.length() ?: 0)).mapNotNull { c!!.optJSONObject(it) }.map { Campo(it.optString("id"), it.optString("nome")) })
        }.filter { it.id.isNotBlank() }
    }.getOrDefault(emptyList()).also { cache = it }

    fun di(ctx: Context, id: String): Categoria? = if (id.isBlank()) null else tutte(ctx).firstOrNull { it.id == id }

    fun campi(p: PassoTeoria): JSONObject = runCatching { JSONObject(p.campi) }.getOrDefault(JSONObject())

    /** Il passaggio in due righe: i primi campi in fila, poi gli altri «nome: valore». */
    fun descrivi(cat: Categoria?, p: PassoTeoria, numero: Int): String {
        val o = campi(p)
        val nomi = cat?.campi ?: o.keys().asSequence().map { Campo(it, it) }.toList()
        val testa = nomi.take(3).mapNotNull { c -> o.optString(c.id).takeIf { it.isNotBlank() } }.joinToString(" · ")
        val resto = nomi.drop(3).mapNotNull { c -> o.optString(c.id).takeIf { it.isNotBlank() }?.let { "${c.nome}: $it" } }
        return "$numero. $testa" + if (resto.isEmpty()) "" else "\n     " + resto.joinToString("\n     ")
    }
}
