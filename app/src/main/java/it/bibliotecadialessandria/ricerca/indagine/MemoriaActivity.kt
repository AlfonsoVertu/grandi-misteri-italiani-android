package it.bibliotecadialessandria.ricerca.indagine

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine

/**
 * «Memoria» (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §7.2): tutte le memorie dell'assistente, cerca, crea, modifica,
 * cancella; ognuna dice il tipo, chi l'ha creata (utente o AI) e quando. Esporta e importa in markdown.
 */
class MemoriaActivity : Schermata() {
    override val titolo = "Memoria"
    override val sottotitolo = "Quello che l'assistente ricorda: istruzioni e preferenze valgono sempre"
    override val conRicerca = true
    override val suggerimentoRicerca = "Cerca nella memoria…"
    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("Markdown" to { markdown() }, "＋ Nuova" to { modifica(null) })

    override fun disegna() {
        lista.removeAllViews()
        val q = testoCercato
        val tutte = Archivio.di(this).memorie()
        val m = if (q.isEmpty()) tutte else AiutiIndagine.parole(q).let { p ->
            tutte.filter { x -> AiutiIndagine.punteggio(p, x.titolo + " " + x.testo + " " + x.tipo) > 0 } }
        if (m.isEmpty()) { vuoto(if (q.isEmpty()) "La memoria è vuota" else "Niente con «$q»",
            "Qui finisce quello che chiedi all'assistente di ricordare («d'ora in poi rispondi breve», «per un caso nuovo fai così…») e quello che " +
                "scrivi tu. Tipi: istruzione e preferenza (valgono sempre), flusso (una procedura a passi), fatto (cose apprese sull'indagine)."); return }
        Memorie.TIPI.forEach { tipo ->
            val di = m.filter { it.tipo == tipo }
            if (di.isEmpty()) return@forEach
            lista.addView(etichetta(when (tipo) { "istruzione" -> "Istruzioni"; "flusso" -> "Flussi"; "fatto" -> "Fatti"; else -> "Preferenze" } + " (${di.size})"), sotto(6))
            di.forEach { x ->
                lista.addView(voce(x.titolo, listOf(x.testo.take(220), "Creata da ${if (x.creataDa == "ai") "AI" else "utente"} il ${Memorie.quando(x.creata)}" +
                    if (x.modificata != x.creata) " · modificata il ${Memorie.quando(x.modificata)}" else ""), if (x.creataDa == "ai") "ai" else "") { scheda(x) }, sotto(10))
            }
        }
    }

    private fun scheda(m: Memoria) {
        AlertDialog.Builder(this).setTitle(m.titolo).setMessage("${m.tipo} · creata da ${if (m.creataDa == "ai") "AI" else "utente"} il ${Memorie.quando(m.creata)}\n\n${m.testo}")
            .setPositiveButton("Modifica") { _, _ -> modifica(m) }
            .setNeutralButton("Cancella") { _, _ -> conferma("Cancellare «${m.titolo}»?", "L'assistente non se la ricorderà più. Non si torna indietro.") {
                Archivio.di(this).togli(m); disegna() } }
            .setNegativeButton("Chiudi", null).show()
    }

    private fun modifica(m: Memoria?) {
        val tipi = Memorie.TIPI.toTypedArray()
        var scelto = (m?.tipo ?: "istruzione").let { Memorie.TIPI.indexOf(it).coerceAtLeast(0) }
        AlertDialog.Builder(this).setTitle("Tipo di memoria").setSingleChoiceItems(arrayOf("Istruzione: come vuoi che lavori", "Flusso: una procedura a passi",
            "Fatto: una cosa appresa", "Preferenza: un tuo gusto"), scelto) { d, k -> scelto = k; d.dismiss()
            chiedi(if (m == null) "Nuova memoria" else "Modifica memoria", listOf(Triple("Titolo", m?.titolo.orEmpty(), 1), Triple("Testo", m?.testo.orEmpty(), 5))) { v ->
                if (v[0].isBlank()) return@chiedi
                if (m == null) Archivio.di(this).metti(Memoria(tipo = tipi[scelto], titolo = v[0], testo = v[1], creataDa = "utente"))
                else Archivio.di(this).aggiorna(m.copy(tipo = tipi[scelto], titolo = v[0], testo = v[1], modificata = System.currentTimeMillis()))
                disegna()
            }
        }.setNegativeButton("Annulla", null).show()
    }

    private fun markdown() {
        AlertDialog.Builder(this).setTitle("Markdown").setItems(arrayOf("Esporta in un file .md", "Importa da un file .md")) { _, k ->
            if (k == 0) @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/markdown").putExtra(Intent.EXTRA_TITLE, "memoria_assistente.md"), ESPORTA)
            else @Suppress("DEPRECATION") startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/markdown", "text/plain", "text/x-markdown", "application/octet-stream")), IMPORTA)
        }.show()
    }

    @Deprecated("Activity semplice, come il resto dell'app")
    override fun onActivityResult(codice: Int, esito: Int, dati: Intent?) {
        super.onActivityResult(codice, esito, dati)
        val u = dati?.data ?: return
        if (esito != Activity.RESULT_OK) return
        runCatching {
            when (codice) {
                ESPORTA -> { contentResolver.openOutputStream(u, "wt")!!.use { it.write(Memorie.markdown(this).toByteArray()) }; avvisa("Memoria esportata.") }
                IMPORTA -> { val n = Memorie.importa(this, contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) })
                    avvisa(if (n == 0) "Nessuna memoria nuova nel file." else "Importate $n memorie."); disegna() }
            }
        }.onFailure { avvisa("Non ci sono riuscito: ${it.message}") }
    }

    companion object { private const val ESPORTA = 71; private const val IMPORTA = 72 }
}
