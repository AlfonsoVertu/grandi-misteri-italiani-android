package it.bibliotecadialessandria.ricerca.indagine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import it.bibliotecadialessandria.ricerca.Marca
import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.TrascrizioniYoutube
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    // ---- (10/11/2026) le indagini nuove da mondo, una al giorno, senza reinstallare l'app ----
    // Alfonso: «ogni giorno un nuovo video e un nuovo approfondimento aggiornato nell'app, come quello di David Rossi».
    // `indagini/indice.json` accanto a versione.json: [{"id","titolo","file","sha256","dal"}]. Si importa solo dal
    // giorno «dal» (AAAA-MM-GG, ora italiana del telefono), solo se l'impronta coincide, una volta sola come gli assets.

    private const val CARTELLA = "https://mondo.workingwithweb.eu/${Marca.MONDO}/indagini/"
    private const val OGNI = 30 * 60_000L
    private const val AVVISI = "indagini_nuove"

    private fun leggi(url: String): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15000; readTimeout = 30000; useCaches = false }
        return try { if (c.responseCode == 200) c.inputStream.use { it.readBytes() } else null } finally { c.disconnect() }
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** Fuori dal filo dell'interfaccia. `forza` salta l'intervallo. Torna i titoli importati adesso. */
    fun scaricaNuove(ctx: Context, forza: Boolean = false): List<String> {
        if (!Marca.ASSISTENTE) return emptyList()
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val ora = System.currentTimeMillis()
        if (!forza && ora - p.getLong("guardato", 0L) < OGNI) return emptyList()
        p.edit().putLong("guardato", ora).apply()
        val indice = runCatching { leggi(CARTELLA + "indice.json?t=" + ora / 60_000) }.getOrNull() ?: return emptyList()
        val oggi = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(ora))
        val nuove = ArrayList<String>()
        for (e in lista(runCatching { JSONArray(String(indice)) }.getOrNull())) {
            val id = e.optString("id").takeIf { it.matches(Regex("[a-z0-9_]{2,60}")) } ?: continue
            if (id in p.getStringSet("importate", emptySet()).orEmpty()) continue
            if (e.optString("dal").isNotBlank() && e.optString("dal") > oggi) continue
            val file = e.optString("file").ifBlank { "$id.json" }.takeIf { it.matches(Regex("[A-Za-z0-9_.-]+")) } ?: continue
            try {
                val b = leggi(CARTELLA + file + "?s=" + e.optString("sha256").take(12)) ?: continue
                if (e.optString("sha256").isNotBlank() && !sha256(b).equals(e.optString("sha256"), true)) {
                    Log.w(TAG, "indagine $id: impronta diversa, non importata"); continue
                }
                val o = JSONObject(String(b, Charsets.UTF_8))
                importa(app, o.optString("id").ifBlank { id }, o)
                p.edit().putStringSet("importate", p.getStringSet("importate", emptySet()).orEmpty() + id).apply()
                nuove.add(o.optString("titolo").ifBlank { e.optString("titolo", id) })
                Log.i(TAG, "indagine scaricata e importata: $id")
            } catch (x: Exception) {
                Log.w(TAG, "indagine $id non scaricata: ${x.javaClass.simpleName} ${x.message?.take(120)}")
            }
        }
        if (nuove.isNotEmpty()) Archivio.avvisa()
        return nuove
    }

    /** Dal controllo periodico: scarica e avvisa con una notifica. */
    fun scaricaEAvvisa(ctx: Context) {
        val nuove = scaricaNuove(ctx)
        if (nuove.isEmpty()) return
        val c = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 26) c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(AVVISI, "Indagini nuove", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Un avviso quando nell'app arriva un approfondimento nuovo su un caso" })
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        val apri = c.packageManager.getLaunchIntentForPackage(c.packageName)?.let {
            PendingIntent.getActivity(c, 7412, it, PendingIntent.FLAG_IMMUTABLE) }
        val testo = nuove.joinToString(", ") + ": note, mappa e persone del caso, con i video al minuto."
        val n = NotificationCompat.Builder(c, AVVISI).setSmallIcon(R.drawable.ic_notifica)
            .setContentTitle(if (nuove.size == 1) "Nuova indagine nell'app" else "Nuove indagini nell'app")
            .setContentText(testo).setStyle(NotificationCompat.BigTextStyle().bigText(testo))
            .setContentIntent(apri).setAutoCancel(true).build()
        try { nm.notify(7412, n) } catch (_: SecurityException) {}
    }
}
