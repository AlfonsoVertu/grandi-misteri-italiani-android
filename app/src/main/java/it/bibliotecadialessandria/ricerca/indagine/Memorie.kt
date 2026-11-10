package it.bibliotecadialessandria.ricerca.indagine

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La memoria dell'assistente (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §7.2), sulla falsa riga di
 * `odoo_gpt.memory` di SoWay (titolo + testo, `get_memory_markdown` = «## titolo», metadati, testo, «---»), con in piu'
 * il TIPO e chi l'ha creata:
 *  - istruzione: come la persona vuole che lavori → entra SEMPRE nel prompt;
 *  - preferenza: gusti della persona → sempre nel prompt anche questa (sono poche righe);
 *  - flusso: una procedura riusabile → nel prompt se pertinente (ricerca a parole, vedi [Rag]);
 *  - fatto: cose apprese sull'indagine → nel prompt se pertinente.
 */
object Memorie {

    val TIPI = listOf("istruzione", "flusso", "fatto", "preferenza")
    private val data = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

    fun quando(t: Long): String = data.format(Date(t))

    /** Tutte le memorie in markdown (per esportarle o condividerle). */
    fun markdown(ctx: Context): String {
        val sb = StringBuilder("# Memoria dell'assistente\n\n")
        Archivio.di(ctx).memorie().sortedWith(compareBy({ TIPI.indexOf(it.tipo) }, { it.creata })).forEach { m ->
            sb.append("## ").append(m.titolo.replace("\n", " ")).append('\n')
            sb.append("**Tipo**: ").append(m.tipo).append(" · **Creata da**: ").append(if (m.creataDa == "ai") "AI" else "utente")
                .append(" · **Data**: ").append(quando(m.creata)).append("\n\n")
            sb.append(m.testo.trim()).append("\n\n---\n\n")
        }
        return sb.toString()
    }

    /**
     * Importa un markdown nel formato di [markdown] (o quello di SoWay: «## titolo», «**Meta**: …», testo, «---»).
     * Le memorie con lo stesso titolo e testo non si duplicano. Torna quante ne ha aggiunte.
     */
    fun importa(ctx: Context, md: String): Int {
        val i = Archivio.di(ctx)
        val gia = i.memorie().map { it.titolo.trim() + "\u0000" + it.testo.trim() }.toSet()
        var n = 0
        md.split(Regex("(?m)^## ")).drop(1).forEach { blocco ->
            val righe = blocco.lines()
            val titolo = righe.first().trim()
            var tipo = "fatto"; var da = "utente"
            val corpo = StringBuilder()
            righe.drop(1).forEach { r ->
                val t = r.trim()
                when {
                    t.startsWith("**Tipo**") -> {
                        Regex("\\*\\*Tipo\\*\\*:\\s*(\\w+)").find(t)?.groupValues?.get(1)?.lowercase()?.takeIf { it in TIPI }?.let { tipo = it }
                        if (Regex("Creata da\\*\\*:\\s*AI", RegexOption.IGNORE_CASE).containsMatchIn(t)) da = "ai"
                    }
                    t.startsWith("**Meta**") -> corpo.append(t.removePrefix("**Meta**:").trim()).append('\n')
                    t == "---" -> {}
                    else -> corpo.append(r).append('\n')
                }
            }
            val testo = corpo.toString().trim()
            if (titolo.isNotBlank() && (titolo + "\u0000" + testo) !in gia) {
                i.metti(Memoria(tipo = tipo, titolo = titolo, testo = testo, creataDa = da)); n++
            }
        }
        if (n > 0) Archivio.avvisa()
        return n
    }
}
