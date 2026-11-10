package it.bibliotecadialessandria.ricerca.indagine

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.LinearLayout
import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine
import it.bibliotecadialessandria.ricerca.strumenti.mmss
import it.bibliotecadialessandria.ricerca.strumenti.secondiDa

/**
 * «Note» (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §4.11): elenco (recenti prima, «✦ AI» per quelle
 * dell'assistente, «◆ precaricata» per le indagini), ricerca, «＋ Nuova».
 */
class NoteActivity : Schermata() {
    override val titolo = "Note"
    override val sottotitolo = "Appunti dell'indagine, con la fonte e i video al minuto"
    override val conRicerca = true
    override val suggerimentoRicerca = "Cerca nelle note…"
    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("＋ Nuova" to { nuova(this) })

    override fun disegna() {
        lista.removeAllViews()
        val i = Archivio.di(this)
        val q = testoCercato
        val note = if (q.isEmpty()) i.note() else i.cercaNote(AiutiIndagine.like(q))
        if (note.isEmpty()) { vuoto(if (q.isEmpty()) "Nessuna nota" else "Niente con «$q»",
            "Crea una nota con «＋ Nuova», oppure dalla riga di un video nei risultati con «＋ Nota» (chiede il minuto). Anche l'assistente può scriverne."); return }
        lista.addView(nota("${note.size} note"), sotto(8))
        note.forEach { n ->
            val v = i.videoDi(n.id)
            lista.addView(voce(n.titolo, listOf(n.testo.replace(Regex("\\s+"), " ").take(160),
                listOfNotNull(n.fonte.takeIf { it.isNotBlank() }?.let { "Fonte: $it" }, if (v.isNotEmpty()) "${v.size} video" else null).joinToString(" · ")),
                n.autore) { vai(this, NotaActivity::class.java, n.id) }, sotto(10))
        }
    }

    companion object {
        /** Una nota nuova (chiede il titolo) e apre l'editor. [video] e [secondi] la collegano subito a un video. */
        fun nuova(a: Schermata, video: String? = null, secondi: Int? = null) {
            a.chiedi("Nuova nota", listOf(Triple("Titolo", "", 1), Triple("Testo", "", 4), Triple("Fonte (video e minuto, link…)", "", 1))) { v ->
                if (v[0].isBlank() && v[1].isBlank()) return@chiedi
                val id = Archivio.di(a).metti(Nota(titolo = v[0].ifBlank { v[1].take(40) }, testo = v[1], fonte = v[2]))
                if (video != null) Archivio.di(a).metti(NotaVideo(notaId = id, youtubeId = video, secondi = secondi))
                vai(a, NotaActivity::class.java, id)
            }
        }

        /**
         * «＋ Nota» dalla riga di un video (RicercaActivity): chiede il minuto e se metterlo in una nota nuova o in una
         * che c'e' gia'. Funziona da qualsiasi Activity.
         */
        fun daVideo(a: android.app.Activity, youtubeId: String, titolo: String) {
            AiutiIndagine.ricordaTitolo(a, youtubeId, titolo)
            val dp = { x: Int -> (x * a.resources.displayMetrics.density).toInt() }
            val minuto = android.widget.EditText(a).apply { hint = "Minuto, es. 12:30 (vuoto = tutto il video)"; isSingleLine = true }
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0); addView(minuto) }
            AlertDialog.Builder(a).setTitle("＋ Nota: $titolo").setView(box)
                .setPositiveButton("Nota nuova") { _, _ -> conMinuto(a, minuto.text.toString()) { s -> nuovaDa(a, youtubeId, titolo, s) } }
                .setNeutralButton("In una nota esistente") { _, _ -> conMinuto(a, minuto.text.toString()) { s ->
                    val note = Archivio.di(a).note()
                    if (note.isEmpty()) { nuovaDa(a, youtubeId, titolo, s); return@conMinuto }
                    AlertDialog.Builder(a).setTitle("In quale nota?").setItems(note.map { it.titolo }.toTypedArray()) { _, k ->
                        Archivio.di(a).metti(NotaVideo(notaId = note[k].id, youtubeId = youtubeId, secondi = s))
                        Archivio.di(a).aggiorna(note[k].copy(modificata = System.currentTimeMillis()))
                        android.widget.Toast.makeText(a, "Collegato a «${note[k].titolo}»" + (s?.let { " al minuto ${mmss(it)}" } ?: ""), android.widget.Toast.LENGTH_LONG).show()
                    }.setNegativeButton("Annulla", null).show()
                } }
                .setNegativeButton("Annulla", null).show()
        }

        private fun conMinuto(a: Context, t: String, fatto: (Int?) -> Unit) {
            if (t.isBlank()) { fatto(null); return }
            val s = secondiDa(t) ?: run { android.widget.Toast.makeText(a, "Il minuto si scrive come 12:30.", android.widget.Toast.LENGTH_LONG).show(); return }
            fatto(s)
        }

        private fun nuovaDa(a: Context, youtubeId: String, titolo: String, s: Int?) {
            val id = Archivio.di(a).metti(Nota(titolo = titolo.take(80), testo = "", fonte = titolo + (s?.let { ", min ${mmss(it)}" } ?: "")))
            Archivio.di(a).metti(NotaVideo(notaId = id, youtubeId = youtubeId, secondi = s))
            vai(a, NotaActivity::class.java, id)
        }
    }
}

/** Una nota: testo, fonte, video al minuto, teorie, mappa, persone collegate. */
class NotaActivity : Schermata() {
    private val id by lazy { intent.getLongExtra("id", 0) }
    override val titolo get() = "Nota"
    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("Modifica" to { modifica() })

    override fun onCreate(stato: Bundle?) { super.onCreate(stato) }

    override fun disegna() {
        lista.removeAllViews()
        val i = Archivio.di(this)
        val n = i.nota(id) ?: run { finish(); return }
        lista.addView(card().apply {
            segnoAutore(n.autore)?.let { addView(it) }
            addView(titoletto(n.titolo).apply { textSize = 18f }, sotto(6))
            addView(nota(n.testo.ifBlank { "(nessun testo: tocca «Modifica»)" }).apply { textSize = 15f; setTextColor(colore(R.color.testo)); setTextIsSelectable(true) })
            if (n.fonte.isNotBlank()) addView(nota("Fonte: ${n.fonte}").apply { setTextIsSelectable(true) }, sopra(8))
            addView(nota("Creata il ${Memorie.quando(n.creata)}" + if (n.modificata != n.creata) " · modificata il ${Memorie.quando(n.modificata)}" else "").apply { textSize = 11f }, sopra(6))
        }, sotto(10))

        lista.addView(sezione("Video al minuto") {
            val v = i.videoDi(n.id)
            if (v.isEmpty()) addView(nota("Nessun video collegato."))
            v.forEach { x -> addView(rigaAzione(testoVideo(x.youtubeId, x.secondi, x.etichetta), { apriVideo(x.youtubeId, x.secondi) }) {
                conferma("Scollegare il video?", "La nota resta, si toglie solo il collegamento.", "Scollega") { i.togliVideo(x.id); disegna() } }) }
            addView(bottone("＋ Collega un video") { chiediVideo { vid, s, et -> i.metti(NotaVideo(notaId = n.id, youtubeId = vid, secondi = s, etichetta = et)); disegna() } }, sopra(6))
        }, sotto(10))

        lista.addView(sezione("Teorie") {
            val t = i.teorieDellaNota(n.id)
            if (t.isEmpty()) addView(nota("In nessuna teoria."))
            t.forEach { x -> addView(rigaAzione("◇  ${x.titolo}", { vai(this@NotaActivity, TeoriaActivity::class.java, x.id) }) {
                i.togliNotaDaTeoria(n.id, x.id); disegna() }) }
            addView(bottone("Assegna a teorie") { assegna() }, sopra(6))
        }, sotto(10))

        lista.addView(sezione("Sulla mappa") {
            val s = i.segnaliniDellaNota(n.id)
            if (s.isEmpty()) addView(nota("Nessun segnalino."))
            s.forEach { x -> addView(rigaAzione("📍  ${x.titolo}", { vai(this@NotaActivity, MappaActivity::class.java, 0) { putExtra("segnalino", x.id) } })) }
            addView(bottone("Metti sulla mappa") { vai(this@NotaActivity, MappaActivity::class.java, 0) { putExtra("nota", n.id) } }, sopra(6))
        }, sotto(10))

        val persone = i.legamiVerso("nota", n.id).filter { it.da == "persona" }.mapNotNull { i.persona(it.daId) }
        if (persone.isNotEmpty()) lista.addView(sezione("Persone") {
            persone.forEach { p -> addView(rigaAzione("👤  ${p.nome}" + if (p.ruolo.isNotBlank()) " · ${p.ruolo}" else "", { vai(this@NotaActivity, PersonaActivity::class.java, p.id) })) }
        }, sotto(10))

        lista.addView(bottone("Cancella la nota") { conferma("Cancellare «${n.titolo}»?", "La nota sparisce da questo telefono (i segnalini restano senza nota). Non si torna indietro.") {
            Archivio.cancellaNota(this, n); finish() } }, sopra(4))
    }

    private fun modifica() {
        val n = Archivio.di(this).nota(id) ?: return
        chiedi("Modifica la nota", listOf(Triple("Titolo", n.titolo, 1), Triple("Testo", n.testo, 6), Triple("Fonte", n.fonte, 1))) { v ->
            Archivio.di(this).aggiorna(n.copy(titolo = v[0].ifBlank { n.titolo }, testo = v[1], fonte = v[2], modificata = System.currentTimeMillis())); disegna()
        }
    }

    private fun assegna() {
        val i = Archivio.di(this)
        val tutte = i.teorie()
        if (tutte.isEmpty()) { avvisa("Non ci sono teorie: creale nella schermata Teorie."); return }
        val dentro = i.teorieDellaNota(id).map { it.id }.toSet()
        val scelte = tutte.map { it.id in dentro }.toBooleanArray()
        AlertDialog.Builder(this).setTitle("Teorie di questa nota")
            .setMultiChoiceItems(tutte.map { it.titolo }.toTypedArray(), scelte) { _, k, si -> scelte[k] = si }
            .setPositiveButton("Salva") { _, _ ->
                tutte.forEachIndexed { k, t -> if (scelte[k]) i.metti(NotaTeoria(id, t.id)) else i.togliNotaDaTeoria(id, t.id) }; disegna()
            }.setNegativeButton("Annulla", null).show()
    }
}
