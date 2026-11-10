package it.bibliotecadialessandria.ricerca.indagine

import android.app.AlertDialog
import android.graphics.BitmapFactory
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.assistente.Media
import it.bibliotecadialessandria.ricerca.assistente.MediaActivity
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine

/** La regola delle schede, scritta nell'interfaccia (STUDIO_ASSISTENTE_AI.md §7.3). */
const val REGOLA_PERSONE = "Riporta fatti e fonti, non accuse: ruolo neutro e ogni affermazione attribuita a chi la fa."

/** «Persone di interesse» (11/11/2026, fase 2b): l'elenco, la ricerca e «＋ Nuova». */
class PersoneActivity : Schermata() {
    override val titolo = "Persone di interesse"
    override val sottotitolo = REGOLA_PERSONE
    override val conRicerca = true
    override val suggerimentoRicerca = "Cerca per nome o ruolo…"
    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("＋ Nuova" to {
        chiedi("Nuova persona", listOf(Triple("Nome e cognome", "", 1), Triple("Ruolo nel caso (neutro: testimone, familiare…)", "", 1),
            Triple("Chi è (solo fatti, con la fonte)", "", 4)), spiega = REGOLA_PERSONE) { v ->
            if (v[0].isNotBlank()) vai(this, PersonaActivity::class.java, Archivio.di(this).metti(Persona(nome = v[0], ruolo = v[1], descrizione = v[2])))
        }
    })

    override fun disegna() {
        lista.removeAllViews()
        val q = testoCercato
        val p = if (q.isEmpty()) Archivio.di(this).persone() else Archivio.di(this).cercaPersone(AiutiIndagine.like(q))
        if (p.isEmpty()) { vuoto(if (q.isEmpty()) "Nessuna persona" else "Niente con «$q»", "Le schede raccolgono chi conta nel caso: ruolo, cronologia con le fonti, " +
            "video al minuto, luoghi, note e relazioni. $REGOLA_PERSONE"); return }
        p.forEach { x -> lista.addView(voce(x.nome, listOf(x.ruolo, x.descrizione.take(140)), x.autore) { vai(this, PersonaActivity::class.java, x.id) }, sotto(10)) }
    }
}

/** La scheda di una persona: ruolo, descrizione, foto, cronologia con le fonti, collegamenti. */
class PersonaActivity : Schermata() {
    private val id by lazy { intent.getLongExtra("id", 0) }
    override val titolo get() = "Persona di interesse"
    override val sottotitolo = REGOLA_PERSONE
    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("Modifica" to { modifica() })

    override fun disegna() {
        lista.removeAllViews()
        val i = Archivio.di(this)
        val p = i.persona(id) ?: run { finish(); return }
        lista.addView(card().apply {
            val alto = LinearLayout(this@PersonaActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            p.fotoId?.let { Media.prendi(this@PersonaActivity, it) }?.let { v ->
                val b = runCatching { BitmapFactory.decodeFile(Media.fileDi(this@PersonaActivity, v).absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 }) }.getOrNull()
                if (b != null) alto.addView(ImageView(this@PersonaActivity).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP
                    setOnClickListener { MediaActivity.apri(this@PersonaActivity, v) } }, LinearLayout.LayoutParams(dp(84), dp(84)).apply { rightMargin = dp(12) })
            }
            val testi = LinearLayout(this@PersonaActivity).apply { orientation = LinearLayout.VERTICAL }
            testi.addView(titoletto(p.nome).apply { textSize = 19f })
            if (p.ruolo.isNotBlank()) testi.addView(etichetta(p.ruolo), sopra(4))
            segnoAutore(p.autore)?.let { testi.addView(it.apply { setPadding(0, dp(4), 0, 0) }) }
            alto.addView(testi, LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(alto)
            if (p.descrizione.isNotBlank()) addView(nota(p.descrizione).apply { textSize = 15f; setTextColor(colore(R.color.testo)); setTextIsSelectable(true) }, sopra(10))
        }, sotto(10))

        lista.addView(sezione("Cronologia, con le fonti") {
            val c = i.cronologia(p.id)
            if (c.isEmpty()) addView(nota("Nessuna voce. Ogni voce dice quando, cosa ha fatto o detto, e la fonte."))
            c.forEach { v -> addView(rigaAzione("${v.quando}  —  ${v.cosa}\n     Fonte: ${v.fonte.ifBlank { "(manca)" }}", null) {
                conferma("Togliere questa voce?", v.cosa.take(200), "Togli") { i.togliVoce(v.id); disegna() } }) }
            addView(bottone("＋ Voce di cronologia") {
                chiedi("Nuova voce", listOf(Triple("Quando (es. 6 marzo 2013)", "", 1), Triple("Cosa ha fatto o detto (attribuito a chi lo dice)", "", 3),
                    Triple("Fonte: video e minuto, pagina, documento", "", 1)), spiega = REGOLA_PERSONE) { v ->
                    if (v[1].isBlank()) return@chiedi
                    if (v[2].isBlank()) { avvisa("Ogni voce vuole la sua fonte."); return@chiedi }
                    i.metti(VoceCronologia(personaId = p.id, quando = v[0], cosa = v[1], fonte = v[2], ordine = i.cronologia(p.id).size)); disegna()
                }
            }, sopra(6))
        }, sotto(10))

        lista.addView(sezione("Collegamenti") {
            val legami = i.legamiDa("persona", p.id)
            legami.forEach { l ->
                val et = if (l.etichetta.isNotBlank()) " (${l.etichetta})" else ""
                val (testo, apri) = when (l.verso) {
                    "video" -> testoVideo(l.youtubeId.orEmpty(), l.secondi, l.etichetta) to { apriVideo(l.youtubeId.orEmpty(), l.secondi) }
                    "nota" -> "✎  ${i.nota(l.versoId)?.titolo ?: "(cancellata)"}$et" to { vai(this@PersonaActivity, NotaActivity::class.java, l.versoId) }
                    "segnalino" -> "📍  ${i.segnalino(l.versoId)?.titolo ?: "(cancellato)"}$et" to { vai(this@PersonaActivity, MappaActivity::class.java, 0) { putExtra("segnalino", l.versoId) } }
                    "teoria" -> "◇  ${i.teoria(l.versoId)?.titolo ?: "(cancellata)"}$et" to { vai(this@PersonaActivity, TeoriaActivity::class.java, l.versoId) }
                    "media" -> (Media.prendi(this@PersonaActivity, l.versoId)?.let { Media.icona(it.tipo) + "  " + it.nome } ?: "(tolto dai Media)") + et to {
                        Media.prendi(this@PersonaActivity, l.versoId)?.let { MediaActivity.apri(this@PersonaActivity, it) }; Unit }
                    else -> "👤  ${l.etichetta.ifBlank { "collegato a" }} ${i.persona(l.versoId)?.nome ?: "(cancellata)"}" to { vai(this@PersonaActivity, PersonaActivity::class.java, l.versoId) }
                }
                addView(rigaAzione(testo, apri) { conferma("Togliere il collegamento?", "Si toglie solo il collegamento.", "Togli") { i.togliLegame(l.id); disegna() } })
            }
            i.legamiVerso("persona", p.id).filter { it.da == "persona" }.forEach { l ->
                addView(rigaAzione("👤  ${i.persona(l.daId)?.nome ?: ""} — ${l.etichetta}", { vai(this@PersonaActivity, PersonaActivity::class.java, l.daId) }))
            }
            if (legami.isEmpty()) addView(nota("Nessun collegamento."))
            addView(bottone("＋ Collega") { collega(p) }, sopra(6))
        }, sotto(10))

        lista.addView(bottone("Cancella la scheda") { conferma("Cancellare «${p.nome}»?", "La scheda sparisce con la sua cronologia e i collegamenti. Non si torna indietro.") {
            Archivio.cancellaPersona(this, p); finish() } }, sopra(4))
    }

    private fun collega(p: Persona) {
        val i = Archivio.di(this)
        val voci = arrayOf("Un video al minuto", "Un segnalino della mappa", "Una nota", "Una teoria", "Un file dei Media", "Un'altra persona", "La foto (dai Media)")
        AlertDialog.Builder(this).setTitle("Collega ${p.nome} a…").setItems(voci) { _, k ->
            fun conEtichetta(verso: String, vid: Long) = chiedi("Etichetta (facoltativa)", listOf(Triple("Es. «testimone in», «moglie di»", "", 1)), "Collega") { v ->
                i.metti(Legame(da = "persona", daId = p.id, verso = verso, versoId = vid, etichetta = v[0])); disegna() }
            when (k) {
                0 -> chiediVideo { vid, s, et -> i.metti(Legame(da = "persona", daId = p.id, verso = "video", youtubeId = vid, secondi = s, etichetta = et)); disegna() }
                1 -> scegli("Quale segnalino?", i.segnalini().map { it.id to it.titolo }, "Non ci sono segnalini.") { conEtichetta("segnalino", it) }
                2 -> scegli("Quale nota?", i.note().map { it.id to it.titolo }, "Non ci sono note.") { conEtichetta("nota", it) }
                3 -> scegli("Quale teoria?", i.teorie().map { it.id to it.titolo }, "Non ci sono teorie.") { conEtichetta("teoria", it) }
                4 -> scegli("Quale file?", Media.tutte(this).map { it.id to it.nome }, "L'archivio Media è vuoto.") { conEtichetta("media", it) }
                5 -> scegli("Quale persona?", i.persone().filter { it.id != p.id }.map { it.id to it.nome }, "Non ci sono altre persone.") { conEtichetta("persona", it) }
                else -> scegli("Quale immagine?", Media.tutte(this).filter { it.tipo == "immagine" }.map { it.id to it.nome }, "Nei Media non ci sono immagini.") {
                    i.aggiorna(p.copy(fotoId = it, modificata = System.currentTimeMillis())); disegna() }
            }
        }.show()
    }

    private fun modifica() {
        val p = Archivio.di(this).persona(id) ?: return
        chiedi("Modifica la scheda", listOf(Triple("Nome", p.nome, 1), Triple("Ruolo (neutro)", p.ruolo, 1), Triple("Chi è (fatti, con la fonte)", p.descrizione, 5)),
            spiega = REGOLA_PERSONE) { v ->
            Archivio.di(this).aggiorna(p.copy(nome = v[0].ifBlank { p.nome }, ruolo = v[1], descrizione = v[2], modificata = System.currentTimeMillis())); disegna()
        }
    }
}
