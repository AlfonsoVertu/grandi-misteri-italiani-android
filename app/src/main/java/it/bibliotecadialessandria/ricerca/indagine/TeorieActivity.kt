package it.bibliotecadialessandria.ricerca.indagine

import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.assistente.Media
import it.bibliotecadialessandria.ricerca.assistente.MediaActivity

/** «Teorie» (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §6): l'elenco delle teorie e «＋ Nuova». */
class TeorieActivity : Schermata() {
    override val titolo = "Teorie"
    override val sottotitolo = "Note, video, media, persone e luoghi raccolti per ipotesi"
    // (11/11/2026) «Importa»: una teoria ricevuta in ZIP (anche aprendo il file da un'altra app)
    override fun onCreate(stato: android.os.Bundle?) {
        super.onCreate(stato)
        intent?.data?.let { importaDa(it) }
    }

    private fun importaDa(u: android.net.Uri) {
        Thread {
            val r = runCatching { contentResolver.openInputStream(u)!!.use { Indagini.importaZip(this, it) } }
            runOnUiThread {
                r.onSuccess { avvisa("Teoria importata: «$it». La trovi qui sotto."); disegna() }
                    .onFailure { avvisa("Non sono riuscito a importare la teoria: ${it.message?.take(120)}") }
            }
        }.start()
    }

    @Deprecated("API") override fun onActivityResult(codice: Int, esito: Int, dati: android.content.Intent?) {
        super.onActivityResult(codice, esito, dati)
        if (codice == 73 && esito == RESULT_OK) dati?.data?.let { importaDa(it) }
    }

    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("Importa" to {
        runCatching { startActivityForResult(android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("application/zip"), 73) }
            .onFailure { avvisa("Nessuna app per scegliere il file.") }
    }, "＋ Nuova" to {
        chiedi("Nuova teoria", listOf(Triple("Titolo", "", 1), Triple("L'ipotesi in breve", "", 4))) { v ->
            if (v[0].isNotBlank()) vai(this, TeoriaActivity::class.java, Archivio.di(this).metti(Teoria(titolo = v[0], descrizione = v[1])))
        }
    })

    override fun disegna() {
        lista.removeAllViews()
        val i = Archivio.di(this)
        val t = i.teorie()
        if (t.isEmpty()) { vuoto("Nessuna teoria", "Una teoria raccoglie le note (con i loro video ai minuti), i media, le persone e i segnalini della mappa attorno a un'ipotesi. Creane una con «＋ Nuova»."); return }
        t.forEach { x ->
            lista.addView(voce(x.titolo, listOf(x.descrizione.take(180), "${i.noteDellaTeoria(x.id).size} note · ${Archivio.segnaliniDellaTeoria(this, x.id).size} luoghi · " +
                "${Archivio.personeDellaTeoria(this, x.id).size} persone"), x.autore) { vai(this, TeoriaActivity::class.java, x.id) }, sotto(10))
        }
    }
}

/** Una teoria: le sue note, i video ai minuti, i media, le persone, i segnalini e «Vedi sulla mappa». */
class TeoriaActivity : Schermata() {
    private val id by lazy { intent.getLongExtra("id", 0) }
    override val titolo get() = "Teoria"
    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("Modifica" to { modifica() })

    override fun disegna() {
        lista.removeAllViews()
        val i = Archivio.di(this)
        val t = i.teoria(id) ?: run { finish(); return }
        lista.addView(card().apply {
            segnoAutore(t.autore)?.let { addView(it) }
            addView(titoletto(t.titolo).apply { textSize = 18f }, sotto(6))
            if (t.descrizione.isNotBlank()) addView(nota(t.descrizione).apply { textSize = 15f; setTextColor(colore(R.color.testo)); setTextIsSelectable(true) })
        }, sotto(10))
        lista.addView(bottone("📍  Vedi sulla mappa", pieno = true) { vai(this, MappaActivity::class.java, 0) { putExtra("teoria", t.id) } }, sotto(10))
        // (11/11/2026) la teoria in uno ZIP, da mandare alla redazione (info@workingwithweb.it) o a chi si vuole
        lista.addView(bottone("📤  Esporta e manda la teoria") { EsportaTeoria.condividi(this, t.id) }, sotto(10))

        val note = i.noteDellaTeoria(t.id)
        lista.addView(sezione("Note (${note.size})") {
            if (note.isEmpty()) addView(nota("Nessuna nota: aggiungine una qui sotto, o dalla nota con «Assegna a teorie»."))
            note.forEach { n -> addView(rigaAzione("✎  ${n.titolo}" + if (n.fonte.isNotBlank()) "\n     ${n.fonte}" else "", { vai(this@TeoriaActivity, NotaActivity::class.java, n.id) }) {
                i.togliNotaDaTeoria(n.id, t.id); disegna() }) }
            addView(fila("＋ Nota esistente" to {
                val fuori = i.note().filter { n -> note.none { it.id == n.id } }
                scegli("Quale nota?", fuori.map { it.id to it.titolo }, "Non ci sono altre note.") { nid -> i.metti(NotaTeoria(nid, t.id)); disegna() }
            }, "＋ Nota nuova" to {
                chiedi("Nuova nota", listOf(Triple("Titolo", "", 1), Triple("Testo", "", 4), Triple("Fonte", "", 1))) { v ->
                    if (v[0].isBlank() && v[1].isBlank()) return@chiedi
                    val nid = i.metti(Nota(titolo = v[0].ifBlank { v[1].take(40) }, testo = v[1], fonte = v[2])); i.metti(NotaTeoria(nid, t.id)); disegna()
                }
            }), sopra(6))
        }, sotto(10))

        val video = note.flatMap { n -> i.videoDi(n.id).map { n to it } }
        lista.addView(sezione("Video ai minuti (${video.size})") {
            if (video.isEmpty()) addView(nota("I video arrivano dalle note della teoria."))
            video.forEach { (n, v) -> addView(rigaAzione(testoVideo(v.youtubeId, v.secondi, v.etichetta.ifBlank { n.titolo }), { apriVideo(v.youtubeId, v.secondi) })) }
        }, sotto(10))

        val persone = Archivio.personeDellaTeoria(this, t.id)
        lista.addView(sezione("Persone (${persone.size})") {
            if (persone.isEmpty()) addView(nota("Nessuna persona collegata (si collegano dalla scheda della persona)."))
            persone.forEach { p -> addView(rigaAzione("👤  ${p.nome}" + if (p.ruolo.isNotBlank()) " · ${p.ruolo}" else "", { vai(this@TeoriaActivity, PersonaActivity::class.java, p.id) })) }
        }, sotto(10))

        val luoghi = Archivio.segnaliniDellaTeoria(this, t.id)
        lista.addView(sezione("Luoghi sulla mappa (${luoghi.size})") {
            if (luoghi.isEmpty()) addView(nota("Nessun segnalino: arrivano dalle note della teoria o si collegano qui."))
            luoghi.forEach { s -> addView(rigaAzione("📍  ${s.titolo}", { vai(this@TeoriaActivity, MappaActivity::class.java, 0) { putExtra("segnalino", s.id); putExtra("teoria", t.id) } })) }
            addView(bottone("＋ Collega un segnalino") {
                scegli("Quale segnalino?", i.segnalini().filter { s -> luoghi.none { it.id == s.id } }.map { it.id to it.titolo }, "Non ci sono altri segnalini.") { sid ->
                    i.metti(Legame(da = "teoria", daId = t.id, verso = "segnalino", versoId = sid)); disegna() }
            }, sopra(6))
        }, sotto(10))

        val media = i.legamiDa("teoria", t.id).filter { it.verso == "media" }
        lista.addView(sezione("Media (${media.size})") {
            media.forEach { l -> val v = Media.prendi(this@TeoriaActivity, l.versoId)
                addView(rigaAzione((v?.let { Media.icona(it.tipo) + "  " + it.nome } ?: "(tolto dai Media)"), v?.let { x -> { MediaActivity.apri(this@TeoriaActivity, x) } }) {
                    i.togliLegame(l.id); disegna() }) }
            addView(bottone("＋ Collega un file dei Media") {
                scegli("Quale file?", Media.tutte(this@TeoriaActivity).map { it.id to it.nome }, "L'archivio Media è vuoto.") { mid ->
                    i.metti(Legame(da = "teoria", daId = t.id, verso = "media", versoId = mid)); disegna() }
            }, sopra(6))
        }, sotto(10))

        lista.addView(bottone("Cancella la teoria") { conferma("Cancellare «${t.titolo}»?", "La teoria sparisce; le note, i segnalini e le persone restano. Non si torna indietro.") {
            Archivio.cancellaTeoria(this, t); finish() } }, sopra(4))
    }

    private fun modifica() {
        val t = Archivio.di(this).teoria(id) ?: return
        chiedi("Modifica la teoria", listOf(Triple("Titolo", t.titolo, 1), Triple("L'ipotesi in breve", t.descrizione, 5))) { v ->
            Archivio.di(this).aggiorna(t.copy(titolo = v[0].ifBlank { t.titolo }, descrizione = v[1], modificata = System.currentTimeMillis())); disegna()
        }
    }
}
