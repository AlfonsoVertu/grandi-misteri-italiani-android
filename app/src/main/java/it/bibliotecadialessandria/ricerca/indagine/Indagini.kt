package it.bibliotecadialessandria.ricerca.indagine

import android.content.Context
import android.util.Log
import it.bibliotecadialessandria.ricerca.TrascrizioniYoutube
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine
import org.json.JSONArray
import org.json.JSONObject

/**
 * Le indagini precaricate (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §7.4): `assets/indagini/<caso>.json`, preparate
 * da Alfonso sul PC. All'avvio, UNA VOLTA SOLA per indagine, si importano nel database della persona: teoria, note con
 * i video al minuto, luoghi (segnalini), linee, persone con relazioni e cronologia, e la trascrizione del video
 * principale nella cache di [TrascrizioniYoutube] (cosi' e' gia' «in pancia»). Dopo, sono della persona: puo'
 * modificarle o cancellarle, e non tornano. Ogni riga ha `origine` = «<caso>:<id>», cosi' un import ripetuto non
 * duplica. I campi mancanti si tollerano.
 */
object Indagini {

    private const val TAG = "Assistente"
    private const val PREF = "indagini"

    /** Fuori dal filo dell'interfaccia. Torna i titoli delle indagini importate adesso. */
    fun importaSeServe(ctx: Context): List<String> {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val fatte = p.getStringSet("importate", emptySet()).orEmpty()
        val file = runCatching { app.assets.list("indagini") }.getOrNull().orEmpty().filter { it.endsWith(".json") }
        val nuove = ArrayList<String>()
        for (f in file) {
            val id = f.removeSuffix(".json")
            if (id in fatte) continue
            try {
                val o = JSONObject(app.assets.open("indagini/$f").bufferedReader().use { it.readText() })
                importa(app, o.optString("id").ifBlank { id }, o)
                p.edit().putStringSet("importate", p.getStringSet("importate", emptySet()).orEmpty() + id).apply()
                nuove.add(o.optString("titolo").ifBlank { id })
                Log.i(TAG, "indagine precaricata importata: $id")
            } catch (e: Exception) {
                Log.w(TAG, "indagine $id non importata: ${e.javaClass.simpleName} ${e.message?.take(120)}")
            }
        }
        if (nuove.isNotEmpty()) Archivio.avvisa()
        return nuove
    }

    private fun lista(a: JSONArray?): List<JSONObject> = a?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } }.orEmpty()
    private fun stringhe(a: JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it.isNotBlank() } }.orEmpty()

    /** I video di un elemento: [{"id","secondi"}] (anche youtube_id/tempo). */
    private fun video(a: JSONArray?): List<AiutiIndagine.VideoAl> = if (a == null) emptyList() else AiutiIndagine.videoDa(a.toString()).first

    fun importa(ctx: Context, caso: String, o: JSONObject) {
        val i = Archivio.di(ctx)
        Archivio.transazione(ctx) {
            // la teoria del caso
            val t = o.optJSONObject("teoria")
            val teoriaId = (i.teoriaDa("$caso:teoria") ?: if (t != null || o.has("titolo")) Teoria(
                titolo = t?.optString("titolo")?.ifBlank { null } ?: o.optString("titolo", caso),
                descrizione = listOfNotNull(t?.optString("descrizione")?.ifBlank { null }, o.optString("descrizione").ifBlank { null }).joinToString("\n\n"),
                autore = "indagine", origine = "$caso:teoria").let { Teoria(id = i.metti(it), titolo = it.titolo) } else null)?.id

            // le note
            val note = HashMap<String, Long>()
            // (10/11/2026) all'incontrario: gli elenchi mostrano prima le piu' recenti, cosi' la prima nota del caso
            // (che e' anche la prima della puntata) finisce in cima
            lista(o.optJSONArray("note")).withIndex().reversed().forEach { (k, n) ->
                val chiave = "$caso:" + n.optString("id").ifBlank { "n$k" }
                val gia = i.notaDa(chiave)
                val id = gia?.id ?: i.metti(Nota(titolo = n.optString("titolo").ifBlank { "Nota ${k + 1}" }, testo = n.optString("testo"),
                    fonte = n.optString("fonte"), autore = "indagine", origine = chiave)).also { nid ->
                    video(n.optJSONArray("video")).forEach { v -> i.metti(NotaVideo(notaId = nid, youtubeId = v.youtubeId, secondi = v.secondi, etichetta = v.etichetta)) }
                }
                note[n.optString("id").ifBlank { "n$k" }] = id
                if (teoriaId != null) i.metti(NotaTeoria(id, teoriaId))
            }

            // i luoghi
            val luoghi = HashMap<String, Long>()
            lista(o.optJSONArray("luoghi")).forEachIndexed { k, l ->
                if (!l.has("lat") || !l.has("lon")) return@forEachIndexed
                val chiave = "$caso:" + l.optString("id").ifBlank { "l$k" }
                val v = video(l.optJSONArray("video")).firstOrNull()
                val sueNote = stringhe(l.optJSONArray("note")).mapNotNull { note[it] }
                val id = i.segnalinoDa(chiave)?.id ?: i.metti(Segnalino(lat = l.optDouble("lat"), lon = l.optDouble("lon"),
                    titolo = l.optString("nome").ifBlank { l.optString("titolo", "Luogo ${k + 1}") }, testo = l.optString("descrizione"),
                    youtubeId = v?.youtubeId, secondi = v?.secondi, notaId = sueNote.firstOrNull(), autore = "indagine", origine = chiave)).also { sid ->
                    if (teoriaId != null) i.metti(Legame(da = "teoria", daId = teoriaId, verso = "segnalino", versoId = sid))
                }
                luoghi[l.optString("id").ifBlank { "l$k" }] = id
            }

            // le linee
            lista(o.optJSONArray("linee")).forEachIndexed { k, l ->
                val chiave = "$caso:linea$k"
                val punti = stringhe(l.optJSONArray("luoghi")).mapNotNull { luoghi[it] }
                if (punti.size >= 2 && i.lineaDa(chiave) == null)
                    Archivio.nuovaLinea(ctx, l.optString("titolo").ifBlank { "Linea ${k + 1}" }, punti, "indagine", chiave)
            }

            // le persone (prima tutte, poi le relazioni fra loro)
            val persone = HashMap<String, Long>()
            val nuove = HashSet<Long>()
            lista(o.optJSONArray("persone")).forEachIndexed { k, p ->
                val chiave = "$caso:" + p.optString("id").ifBlank { "p$k" }
                val gia = i.personaDa(chiave)
                val id = gia?.id ?: i.metti(Persona(nome = p.optString("nome").ifBlank { "Persona ${k + 1}" }, ruolo = p.optString("ruolo"),
                    descrizione = p.optString("descrizione"), autore = "indagine", origine = chiave)).also { nuove.add(it) }
                persone[p.optString("id").ifBlank { "p$k" }] = id
            }
            lista(o.optJSONArray("persone")).forEachIndexed { k, p ->
                val id = persone[p.optString("id").ifBlank { "p$k" }] ?: return@forEachIndexed
                if (id !in nuove) return@forEachIndexed
                if (teoriaId != null) i.metti(Legame(da = "persona", daId = id, verso = "teoria", versoId = teoriaId))
                video(p.optJSONArray("video")).forEach { v -> i.metti(Legame(da = "persona", daId = id, verso = "video", youtubeId = v.youtubeId, secondi = v.secondi, etichetta = v.etichetta)) }
                stringhe(p.optJSONArray("note")).mapNotNull { note[it] }.forEach { n -> i.metti(Legame(da = "persona", daId = id, verso = "nota", versoId = n)) }
                stringhe(p.optJSONArray("luoghi")).mapNotNull { luoghi[it] }.forEach { s -> i.metti(Legame(da = "persona", daId = id, verso = "segnalino", versoId = s)) }
                lista(p.optJSONArray("relazioni")).forEach { r -> persone[r.optString("persona")]?.takeIf { it != id }?.let { altra ->
                    i.metti(Legame(da = "persona", daId = id, verso = "persona", versoId = altra, etichetta = r.optString("etichetta"))) } }
                lista(p.optJSONArray("cronologia")).forEachIndexed { n, c ->
                    i.metti(VoceCronologia(personaId = id, quando = c.optString("quando"), cosa = c.optString("cosa"), fonte = c.optString("fonte"), ordine = n))
                }
            }
        }

        // la trascrizione del video principale «in pancia» (se non c'e' gia' una scaricata)
        o.optJSONObject("trascrizione")?.let { t ->
            val vid = t.optString("video").ifBlank { o.optString("video_principale") }
            val righe = lista(t.optJSONArray("righe")).mapNotNull { r ->
                val testo = r.optString("testo").trim(); if (testo.isEmpty()) null
                else TrascrizioniYoutube.Riga(r.optInt("ms"), r.optInt("durata_ms"), testo)
            }
            if (AiutiIndagine.idDi(vid) != null && righe.isNotEmpty() && TrascrizioniYoutube.salvata(ctx, vid) == null) {
                val durata = righe.last().let { (it.inizioMs + it.durataMs) / 1000 }
                TrascrizioniYoutube.mettiInCache(ctx, TrascrizioniYoutube.Trascrizione(vid, t.optString("titolo").ifBlank { o.optString("titolo") },
                    t.optString("canale"), durata, t.optString("lingua", "it"), t.optBoolean("automatica", true), false, System.currentTimeMillis(), righe))
            }
            t.optString("titolo").takeIf { it.isNotBlank() }?.let { AiutiIndagine.ricordaTitolo(ctx, vid, it) }
        }
    }
}
