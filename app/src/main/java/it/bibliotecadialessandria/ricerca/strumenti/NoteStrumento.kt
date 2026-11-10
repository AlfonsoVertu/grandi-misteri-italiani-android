package it.bibliotecadialessandria.ricerca.strumenti

import android.content.Context
import it.bibliotecadialessandria.ricerca.indagine.Archivio
import it.bibliotecadialessandria.ricerca.indagine.Nota
import it.bibliotecadialessandria.ricerca.indagine.NotaTeoria
import it.bibliotecadialessandria.ricerca.indagine.NotaVideo
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine.TOKEN
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * `note` (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §4.6 e §4.11): le note dell'indagine, ognuna collegata a uno o
 * piu' video al secondo, a teorie e a segnalini della mappa. Quelle scritte dall'assistente hanno autore «assistente».
 */
object NoteStrumento : Strumento() {

    override val nome = "note"
    override val descrizione = "Le note dell'indagine sul telefono: testo con la fonte, collegate a uno o piu' video al " +
        "minuto preciso, a teorie e alla mappa. Puoi elencarle, cercarle, leggerle, crearle, modificarle, collegare video e cancellarle (con conferma)."

    private val ID = Parametro("id", "integer", "L'id della nota (lo danno elenca e cerca).", true, esempio = 1)
    private val VIDEO = Parametro("video", "string", "I video da collegare: array JSON [{\"youtube_id\":\"…\",\"tempo\":\"12:30\"}] " +
        "oppure testo «id@12:30, id2@1:02:03» o link con &t=. Tempo vuoto = il video intero.", esempio = "oRFuohjWMpA@12:30")
    private val data = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

    override val azioni = listOf(
        Azione("elenca", "Le note, dalle piu' recenti: id, titolo, autore, quanti video.", "Per vedere cosa c'e' gia'.",
            listOf(Parametro("teoria_id", "integer", "Solo le note di questa teoria."),
                Parametro("quanti", "integer", "Quante (predefinito 20, massimo 50)."), Parametro("pagina", "integer", "La pagina (da 1).")),
            "guardo le note", collegate = listOf("cerca", "leggi")) { c, a -> elenca(c, a) },
        Azione("cerca", "Cerca nelle note (titolo, testo, fonte) o le note di un video.", "Per ritrovare una nota o sapere cosa si e' gia' annotato su un video.",
            listOf(Parametro("testo", "string", "Le parole da cercare.", esempio = "orologio"),
                Parametro("youtube_id", "string", "Solo le note collegate a questo video.")), "cerco nelle note",
            collegate = listOf("leggi")) { c, a -> cerca(c, a) },
        Azione("leggi", "Una nota intera: testo, fonte, video al minuto (con link), teorie, segnalini e persone collegate.", "",
            listOf(ID), "leggo una nota", errori = listOf("non_esiste: l'id non c'e' (ti dico quelli che ci sono)")) { c, a -> leggi(c, a) },
        Azione("crea", "Crea una nota (autore: assistente), con i video al minuto, la fonte e la teoria se le dai.",
            "Quando la persona chiede di annotare qualcosa o tu trovi un fatto da tenere. Metti SEMPRE la fonte (video+minuto o link).",
            listOf(Parametro("titolo", "string", "Il titolo breve.", true, esempio = "L'orologio caduto dopo"),
                Parametro("testo", "string", "Il testo della nota (fatti e chi li dice).", true, esempio = "Secondo Davide Vecchi (min 32:10)…"),
                Parametro("fonte", "string", "Da dove viene: «Pulp Podcast #71, min 32:10» o un URL.", esempio = "Pulp Podcast #71, min 32:10"),
                VIDEO, Parametro("teoria_id", "integer", "Mettila anche in questa teoria.")), "scrivo una nota",
            errori = listOf("mancano_parametri: titolo e testo", "video non capiti: ti dico quali"), collegate = listOf("collega_video", "teorie/aggiungi_nota", "mappa/crea_segnalino")) { c, a -> crea(c, a) },
        Azione("modifica", "Cambia titolo, testo o fonte di una nota (solo i campi passati); `aggiungi_in_fondo` aggiunge testo.", "",
            listOf(ID, Parametro("titolo", "string", "Nuovo titolo."), Parametro("testo", "string", "Nuovo testo (sostituisce)."),
                Parametro("fonte", "string", "Nuova fonte."), Parametro("aggiungi_in_fondo", "string", "Testo da aggiungere in fondo.")),
            "modifico una nota") { c, a -> modifica(c, a) },
        Azione("collega_video", "Collega un altro video (al minuto) a una nota.", "Una nota puo' avere piu' video.",
            listOf(ID, Parametro("youtube_id", "string", "L'id del video o il link.", true, esempio = "oRFuohjWMpA"),
                Parametro("tempo", "string", "Il minuto: «12:30», «1:02:03» o secondi. Vuoto = il video intero.", esempio = "12:30"),
                Parametro("etichetta", "string", "Cosa c'e' a quel minuto.")), "collego un video alla nota") { c, a -> collegaVideo(c, a) },
        Azione("scollega_video", "Toglie un video da una nota (il `collegamento_id` lo da' leggi).", "",
            listOf(ID, Parametro("collegamento_id", "integer", "L'id del collegamento (in leggi, video[].collegamento_id).", true)),
            "scollego un video") { c, a -> scollegaVideo(c, a) },
        Azione("cancella", "Cancella una nota. A DUE PASSI: la prima chiamata da' il riepilogo e il `conferma_token`; si richiama col " +
            "token SOLO dopo il si' della persona.", "Solo se la persona lo chiede.", listOf(ID, TOKEN), "cancello una nota") { c, a -> cancella(c, a) },
    )

    fun riga(c: Context, n: Nota): JSONObject = JSONObject().put("id", n.id).put("titolo", n.titolo).put("autore", n.autore)
        .put("video", Archivio.di(c).videoDi(n.id).size).put("modificata", data.format(Date(n.modificata)))
        .apply { if (n.fonte.isNotBlank()) put("fonte", n.fonte) }

    private fun quale(c: Context, a: JSONObject): Pair<Nota?, JSONObject?> {
        val id = a.optLong("id")
        val n = Archivio.di(c).nota(id) ?: return null to AiutiIndagine.nonTrovato("la nota", id,
            Archivio.di(c).note().map { it.id to it.titolo }, "elenca")
        return n to null
    }

    private fun elenca(c: Context, a: JSONObject): JSONObject {
        val tutte = if (a.has("teoria_id")) Archivio.di(c).noteDellaTeoria(a.optLong("teoria_id")) else Archivio.di(c).note()
        val quanti = a.optInt("quanti", 20).coerceIn(1, 50); val pagina = a.optInt("pagina", 1).coerceAtLeast(1)
        val o = riuscito().put("totale", tutte.size).put("note", JSONArray().also { r ->
            tutte.drop((pagina - 1) * quanti).take(quanti).forEach { r.put(riga(c, it)) } })
        if (tutte.size > pagina * quanti) o.put("continua", JSONObject().put("action", "elenca").put("pagina", pagina + 1))
        if (tutte.isEmpty()) o.put("nota", "Nessuna nota. Creane una con action=crea.")
        return o
    }

    private fun cerca(c: Context, a: JSONObject): JSONObject {
        val i = Archivio.di(c)
        val testo = a.optString("testo"); val vid = a.optString("youtube_id").let { AiutiIndagine.idDi(it) ?: it }
        if (testo.isBlank() && vid.isBlank()) return Errore.generico("Dimmi `testo` o `youtube_id`.",
            JSONObject().put("action", "cerca").put("testo", "<parole>"), "mancano_parametri")
        var trovate = if (testo.isNotBlank()) {
            val q = AiutiIndagine.parole(testo)
            i.note().map { it to AiutiIndagine.punteggio(q, it.titolo + " " + it.testo + " " + it.fonte) }
                .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
                .ifEmpty { i.cercaNote(AiutiIndagine.like(testo)) }
        } else i.note()
        if (vid.isNotBlank()) { val sue = i.noteDelVideo(vid).map { it.notaId }.toSet(); trovate = trovate.filter { it.id in sue } }
        return riuscito().put("trovate", trovate.size).put("note", JSONArray().also { r -> trovate.take(20).forEach { r.put(riga(c, it)) } })
            .apply { if (trovate.isEmpty()) put("nota", "Nessuna nota trovata. Prova con meno parole o action=elenca.") }
    }

    fun dettaglio(c: Context, n: Nota): JSONObject {
        val i = Archivio.di(c)
        return riuscito().put("nota", JSONObject().put("id", n.id).put("titolo", n.titolo).put("testo", n.testo).put("fonte", n.fonte)
            .put("autore", n.autore).put("creata", data.format(Date(n.creata))).put("modificata", data.format(Date(n.modificata))))
            .put("video", JSONArray().also { r -> i.videoDi(n.id).forEach { v ->
                r.put(AiutiIndagine.video(c, v.youtubeId, v.secondi, JSONObject().put("collegamento_id", v.id)
                    .apply { if (v.etichetta.isNotBlank()) put("etichetta", v.etichetta) })) } })
            .put("teorie", JSONArray().also { r -> i.teorieDellaNota(n.id).forEach { r.put(JSONObject().put("id", it.id).put("titolo", it.titolo)) } })
            .put("segnalini", JSONArray().also { r -> i.segnaliniDellaNota(n.id).forEach { r.put(JSONObject().put("id", it.id).put("titolo", it.titolo)) } })
            .put("persone", JSONArray().also { r -> i.legamiVerso("nota", n.id).filter { it.da == "persona" }
                .mapNotNull { i.persona(it.daId) }.forEach { r.put(JSONObject().put("id", it.id).put("nome", it.nome).put("ruolo", it.ruolo)) } })
    }

    private fun leggi(c: Context, a: JSONObject): JSONObject { val (n, e) = quale(c, a); return if (n == null) e!! else dettaglio(c, n) }

    private fun crea(c: Context, a: JSONObject): JSONObject {
        val (video, male) = AiutiIndagine.videoDa(a.optString("video"))
        if (male.isNotEmpty() && video.isEmpty()) return Errore.valoreSbagliato(nome, "crea", "Non ho capito i video: ${male.joinToString(", ")}.",
            JSONObject().put("titolo", a.optString("titolo")).put("testo", "…").put("video", "oRFuohjWMpA@12:30"))
        val teoria = if (a.has("teoria_id")) Archivio.di(c).teoria(a.optLong("teoria_id")) ?: return AiutiIndagine.nonTrovato("la teoria",
            a.optLong("teoria_id"), Archivio.di(c).teorie().map { it.id to it.titolo }, "elenca").put("nota", "Le teorie si vedono con lo strumento teorie, action=elenca.") else null
        var id = 0L
        Archivio.transazione(c) {
            val i = Archivio.di(c)
            id = i.metti(Nota(titolo = a.optString("titolo").trim(), testo = a.optString("testo").trim(), fonte = a.optString("fonte").trim(), autore = "assistente"))
            video.forEach { v -> i.metti(NotaVideo(notaId = id, youtubeId = v.youtubeId, secondi = v.secondi, etichetta = v.etichetta)) }
            if (teoria != null) i.metti(NotaTeoria(id, teoria.id))
        }
        Archivio.avvisa()
        return dettaglio(c, Archivio.di(c).nota(id)!!).put("creata", true)
            .apply { if (male.isNotEmpty()) put("avviso", "Questi video non li ho capiti e non li ho collegati: ${male.joinToString(", ")}") }
            .put("nota_per_te", "La nota e' nella schermata Note. Per metterla sulla mappa: mappa action=crea_segnalino con nota_id=$id.")
    }

    private fun modifica(c: Context, a: JSONObject): JSONObject {
        val (n, e) = quale(c, a); if (n == null) return e!!
        if (listOf("titolo", "testo", "fonte", "aggiungi_in_fondo").none { a.has(it) }) return Errore.generico("Non mi hai detto cosa cambiare.",
            JSONObject().put("action", "modifica").put("id", n.id).put("testo", "<nuovo testo>"), "mancano_parametri")
        val testo = (if (a.has("testo")) a.optString("testo") else n.testo) + (if (a.has("aggiungi_in_fondo")) "\n\n" + a.optString("aggiungi_in_fondo") else "")
        Archivio.di(c).aggiorna(n.copy(titolo = a.optString("titolo", n.titolo), testo = testo.trim(), fonte = a.optString("fonte", n.fonte),
            modificata = System.currentTimeMillis()))
        Archivio.avvisa()
        return dettaglio(c, Archivio.di(c).nota(n.id)!!).put("modificata", true)
    }

    private fun collegaVideo(c: Context, a: JSONObject): JSONObject {
        val (n, e) = quale(c, a); if (n == null) return e!!
        val vid = AiutiIndagine.idDi(a.optString("youtube_id")) ?: return Errore.valoreSbagliato(nome, "collega_video",
            "«${a.optString("youtube_id")}» non e' un id di YouTube (11 caratteri) ne' un link.", JSONObject().put("id", n.id).put("youtube_id", "oRFuohjWMpA").put("tempo", "12:30"))
        val sec = if (a.has("tempo")) secondiDa(a.optString("tempo")) ?: return Errore.valoreSbagliato(nome, "collega_video",
            "`tempo` «${a.optString("tempo")}» non e' un tempo: scrivi «12:30», «1:02:03» o i secondi.", JSONObject().put("id", n.id).put("youtube_id", vid).put("tempo", "12:30")) else
            Regex("[?&]t=(\\d+)").find(a.optString("youtube_id"))?.groupValues?.get(1)?.toIntOrNull()
        Archivio.di(c).metti(NotaVideo(notaId = n.id, youtubeId = vid, secondi = sec, etichetta = a.optString("etichetta")))
        Archivio.di(c).aggiorna(n.copy(modificata = System.currentTimeMillis()))
        Archivio.avvisa()
        return dettaglio(c, n).put("collegato", AiutiIndagine.video(c, vid, sec))
    }

    private fun scollegaVideo(c: Context, a: JSONObject): JSONObject {
        val (n, e) = quale(c, a); if (n == null) return e!!
        val cid = a.optLong("collegamento_id")
        if (Archivio.di(c).videoDi(n.id).none { it.id == cid }) return Errore.nonEsiste("nella nota ${n.id} il collegamento", cid.toString(),
            emptyList(), JSONObject().put("action", "leggi").put("id", n.id)).put("nota", "I collegamento_id sono in leggi, dentro video[].")
        Archivio.di(c).togliVideo(cid); Archivio.avvisa()
        return dettaglio(c, n).put("scollegato", cid)
    }

    private fun cancella(c: Context, a: JSONObject): JSONObject {
        val (n, e) = quale(c, a); if (n == null) return e!!
        val v = Archivio.di(c).videoDi(n.id).size
        return AiutiIndagine.conConferma(nome, "cancella", JSONObject().put("id", n.id), a,
            "Cancello la nota ${n.id} «${n.titolo}» con $v video collegati.", "La nota sparisce; i segnalini restano senza nota.",
            "Non si torna indietro: andrebbe riscritta.") {
            Archivio.cancellaNota(c, n); Archivio.avvisa()
            riuscito().put("cancellata", JSONObject().put("id", n.id).put("titolo", n.titolo))
        }
    }
}
