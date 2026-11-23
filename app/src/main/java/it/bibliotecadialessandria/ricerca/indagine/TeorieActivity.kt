package it.bibliotecadialessandria.ricerca.indagine

import it.bibliotecadialessandria.ricerca.Marca
import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.assistente.Media
import it.bibliotecadialessandria.ricerca.assistente.MediaActivity

/** «Teorie» (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §6): l'elenco delle teorie e «＋ Nuova». */
class TeorieActivity : Schermata() {
    /** (13/11/2026) con l'extra «categoria» la schermata è la sezione di quella categoria (es. SiS · Segui i Soldi). */
    private val cat by lazy { CategorieTeorie.di(this, intent?.getStringExtra("categoria").orEmpty()) }
    override val titolo get() = cat?.nome ?: Marca.TEORIE
    override val sottotitolo get() = cat?.breve ?: Marca.SOTTO_ELENCO_TEORIE
    // (11/11/2026) «Importa»: una teoria ricevuta in ZIP (anche aprendo il file da un'altra app)
    override fun onCreate(stato: android.os.Bundle?) {
        super.onCreate(stato)
        intent?.data?.let { importaDa(it) }
    }

    private fun importaDa(u: android.net.Uri) {
        Thread {
            val r = runCatching { contentResolver.openInputStream(u)!!.use { Indagini.importaZip(this, it) } }
            runOnUiThread {
                r.onSuccess { avvisa("${Marca.TEORIA} importata: «$it». La trovi qui sotto."); disegna() }
                    .onFailure { avvisa("Non sono riuscito a importare la ${Marca.TEORIA.lowercase()}: ${it.message?.take(120)}") }
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
        chiedi("Nuova ${Marca.TEORIA.lowercase()}", listOf(Triple("Titolo", "", 1), Triple("L'ipotesi in breve", "", 4))) { v ->
            if (v[0].isNotBlank()) vai(this, TeoriaActivity::class.java, Archivio.di(this).metti(Teoria(titolo = v[0], descrizione = v[1], categoria = cat?.id.orEmpty())))
        }
    })

    override fun disegna() {
        lista.removeAllViews()
        val i = Archivio.di(this)
        val c = cat
        if (c != null) {
            // la sezione di una categoria: l'elemento comune e le regole da cui partono le sue teorie
            lista.addView(card().apply {
                addView(titoletto("${c.icona}  Si parte da: ${c.elemento}"), sotto(6))
                c.regole.forEach { r -> addView(nota("•  $r").apply { textSize = 14f }, sotto(4)) }
                addView(nota("Sono teorie: affiancano le altre teorie dei casi, non le sostituiscono.").apply { textSize = 12f }, sopra(4))
            }, sotto(10))
        } else {
            // le sezioni delle categorie, in cima all'elenco di tutte le teorie
            CategorieTeorie.tutte(this).forEach { k -> lista.addView(bottone("${k.icona}  ${k.nome}  (${i.teorieDi(k.id).size})", pieno = true) {
                vai(this, TeorieActivity::class.java, 0) { putExtra("categoria", k.id) } }, sotto(8)) }
        }
        val t = if (c != null) i.teorieDi(c.id) else i.teorie()
        if (t.isEmpty()) { vuoto("Nessuna ${Marca.TEORIA.lowercase()}", "Una ${Marca.TEORIA.lowercase()} raccoglie le note (con i loro video ai minuti), i media, le persone e i segnalini della mappa attorno a un'ipotesi. Creane una con «＋ Nuova»."); return }
        t.forEach { x ->
            val sigla = CategorieTeorie.di(this, x.categoria)?.let { if (c == null) "${it.icona} " else "" }.orEmpty()
            val passi = if (x.categoria.isNotBlank()) " · ${i.passiTeoria(x.id).size} passaggi" else ""
            lista.addView(voce(sigla + x.titolo, listOf(x.descrizione.substringBefore("\n\n").take(180), "${i.noteDellaTeoria(x.id).size} note · ${Archivio.segnaliniDellaTeoria(this, x.id).size} luoghi · " +
                "${Archivio.personeDellaTeoria(this, x.id).size} persone" + passi), x.autore) { vai(this, TeoriaActivity::class.java, x.id) }, sotto(10))
        }
    }
}

/** Una teoria: le sue note, i video ai minuti, i media, le persone, i segnalini e «Vedi sulla mappa». */
class TeoriaActivity : Schermata() {
    private val id by lazy { intent.getLongExtra("id", 0) }
    override val titolo get() = "${Marca.TEORIA}"
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
        val cat = CategorieTeorie.di(this, t.categoria)
        if (cat != null) {
            val passi = i.passiTeoria(t.id)
            lista.addView(sezione("${cat.icona}  ${cat.nome} · linea (${passi.size} passaggi)") {
                addView(nota("Teoria che parte da: ${cat.elemento}. Ogni passaggio ha la sua fonte; tocca per vederlo sulla mappa.").apply { textSize = 12f }, sotto(6))
                passi.forEachIndexed { n, p -> addView(rigaAzione(CategorieTeorie.descrivi(cat, p, n + 1), p.segnalinoId?.let { sid -> {
                    vai(this@TeoriaActivity, MappaActivity::class.java, 0) { putExtra("segnalino", sid); putExtra("teoria", t.id) } } }) {
                    i.togliPassoTeoria(p.id); disegna() }) }
                addView(bottone("＋ Passaggio") {
                    chiedi("Nuovo passaggio", cat.campi.map { Triple(it.nome, "", if (it.id == "cosa") 3 else 1) }) { v ->
                        if (v.all { it.isBlank() }) return@chiedi
                        val o = org.json.JSONObject(); cat.campi.forEachIndexed { k, f -> if (v[k].isNotBlank()) o.put(f.id, v[k]) }
                        i.metti(PassoTeoria(teoriaId = t.id, ordine = (passi.maxOfOrNull { it.ordine } ?: -1) + 1, campi = o.toString())); disegna()
                    }
                }, sopra(6))
            }, sotto(10))
        }
        lista.addView(bottone("📍  Vedi sulla mappa", pieno = true) { vai(this, MappaActivity::class.java, 0) { putExtra("teoria", t.id) } }, sotto(10))
        // (11/11/2026) la teoria in uno ZIP, da mandare alla redazione (info@workingwithweb.it) o a chi si vuole
        lista.addView(bottone("📤  Esporta e manda la ${Marca.TEORIA.lowercase()}") { EsportaTeoria.condividi(this, t.id) }, sotto(10))

        val note = i.noteDellaTeoria(t.id)
        lista.addView(sezione("Note (${note.size})") {
            if (note.isEmpty()) addView(nota("Nessuna nota: aggiungine una qui sotto, o dalla nota con «Assegna a ${Marca.TEORIE.lowercase()}»."))
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
            if (video.isEmpty()) addView(nota("I video arrivano dalle note della ${Marca.TEORIA.lowercase()}."))
            video.forEach { (n, v) -> addView(rigaAzione(testoVideo(v.youtubeId, v.secondi, v.etichetta.ifBlank { n.titolo }), { apriVideo(v.youtubeId, v.secondi) })) }
        }, sotto(10))

        val persone = Archivio.personeDellaTeoria(this, t.id)
        lista.addView(sezione("Persone (${persone.size})") {
            if (persone.isEmpty()) addView(nota("Nessuna persona collegata (si collegano dalla scheda della persona)."))
            persone.forEach { p -> addView(rigaAzione("👤  ${p.nome}" + if (p.ruolo.isNotBlank()) " · ${p.ruolo}" else "", { vai(this@TeoriaActivity, PersonaActivity::class.java, p.id) })) }
        }, sotto(10))

        val luoghi = Archivio.segnaliniDellaTeoria(this, t.id)
        lista.addView(sezione("Luoghi sulla mappa (${luoghi.size})") {
            if (luoghi.isEmpty()) addView(nota("Nessun segnalino: arrivano dalle note della ${Marca.TEORIA.lowercase()} o si collegano qui."))
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

        lista.addView(bottone("Cancella la ${Marca.TEORIA.lowercase()}") { conferma("Cancellare «${t.titolo}»?", "La ${Marca.TEORIA.lowercase()} sparisce; le note, i segnalini e le persone restano. Non si torna indietro.") {
            Archivio.cancellaTeoria(this, t); finish() } }, sopra(4))
    }

    private fun modifica() {
        val t = Archivio.di(this).teoria(id) ?: return
        chiedi("Modifica la ${Marca.TEORIA.lowercase()}", listOf(Triple("Titolo", t.titolo, 1), Triple("L'ipotesi in breve", t.descrizione, 5))) { v ->
            Archivio.di(this).aggiorna(t.copy(titolo = v[0].ifBlank { t.titolo }, descrizione = v[1], modificata = System.currentTimeMillis())); disegna()
        }
    }
}
