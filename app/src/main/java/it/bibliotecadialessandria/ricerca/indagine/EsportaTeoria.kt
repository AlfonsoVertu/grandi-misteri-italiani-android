package it.bibliotecadialessandria.ricerca.indagine

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import it.bibliotecadialessandria.ricerca.Marca
import it.bibliotecadialessandria.ricerca.assistente.Media
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * (11/11/2026, Alfonso: «una funzione di esportazione della teoria in zip e condivisione via email»; «alla fine del video
 * invitiamo le persone a fare le loro teorie e mandarcele per creare i video».)
 *
 * Lo ZIP contiene:
 *  - `teoria.json`: la teoria nello STESSO formato delle indagini precaricate (assets/indagini, Indagini.importa,
 *    strumenti/indagine.py): note con i video al minuto, luoghi, persone con relazioni e cronologia. Così una teoria
 *    ricevuta si importa e, se è buona, si pubblica come indagine;
 *  - `teoria.md`: la stessa cosa da leggere;
 *  - `media/…`: i file dell'archivio Media collegati alla teoria e le foto delle persone.
 * Si condivide con la scheda di Android, con l'email della redazione già scritta (si può scegliere anche un'altra app).
 */
object EsportaTeoria {

    const val EMAIL = "info@workingwithweb.it"

    private fun slug(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "teoria" }

    private fun video(lista: List<Pair<String, Int?>>) = JSONArray().apply {
        lista.forEach { (y, s) -> put(JSONObject().put("id", y).apply { if (s != null) put("secondi", s) }) }
    }

    private fun minuto(s: Int?) = if (s == null) "" else if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)

    /** Costruisce lo ZIP nella cache e torna il file. Fuori dal filo dell'interfaccia. */
    fun zip(a: Activity, teoriaId: Long): File? {
        val i = Archivio.di(a)
        val t = i.teoria(teoriaId) ?: return null
        val note = i.noteDellaTeoria(t.id)
        val luoghi = Archivio.segnaliniDellaTeoria(a, t.id)
        val persone = Archivio.personeDellaTeoria(a, t.id)
        val idNota = note.withIndex().associate { (k, n) -> n.id to "n${k + 1}" }
        val idLuogo = luoghi.withIndex().associate { (k, s) -> s.id to "l${k + 1}" }
        val idPersona = persone.withIndex().associate { (k, p) -> p.id to "p${k + 1}" }
        val media = LinkedHashMap<Long, Media.Voce>()
        i.legamiDa("teoria", t.id).filter { it.verso == "media" }.forEach { l -> Media.prendi(a, l.versoId)?.let { media[it.id] = it } }

        val md = StringBuilder("# ${t.titolo}\n\n${t.descrizione}\n\n")
        val jNote = JSONArray()
        md.append("## Note (${note.size})\n\n")
        note.forEach { n ->
            val v = i.videoDi(n.id)
            jNote.put(JSONObject().put("id", idNota[n.id]).put("titolo", n.titolo).put("testo", n.testo).put("fonte", n.fonte)
                .put("video", video(v.map { it.youtubeId to it.secondi })))
            md.append("### ${n.titolo}\n\n${n.testo}\n\n")
            if (n.fonte.isNotBlank()) md.append("Fonte: ${n.fonte}\n\n")
            v.forEach { x -> md.append("- https://www.youtube.com/watch?v=${x.youtubeId}" + (x.secondi?.let { "&t=${it}s (min ${minuto(it)})" } ?: "") + "\n") }
            md.append("\n")
        }
        val jLuoghi = JSONArray()
        if (luoghi.isNotEmpty()) md.append("## Luoghi (${luoghi.size})\n\n")
        luoghi.forEach { s ->
            jLuoghi.put(JSONObject().put("id", idLuogo[s.id]).put("nome", s.titolo).put("lat", s.lat).put("lon", s.lon).put("descrizione", s.testo)
                .put("video", video(listOfNotNull(s.youtubeId?.let { it to s.secondi })))
                .put("note", JSONArray(listOfNotNull(s.notaId?.let { idNota[it] }))))
            md.append("- **${s.titolo}** (%.5f, %.5f)".format(s.lat, s.lon) + (if (s.testo.isNotBlank()) ": ${s.testo}" else "") + "\n")
        }
        if (luoghi.isNotEmpty()) md.append("\n")
        val jPersone = JSONArray()
        if (persone.isNotEmpty()) md.append("## Persone (${persone.size})\n\n")
        persone.forEach { p ->
            val leg = i.legamiDa("persona", p.id)
            val cron = i.cronologia(p.id)
            p.fotoId?.let { f -> Media.prendi(a, f)?.let { media[it.id] = it } }
            jPersone.put(JSONObject().put("id", idPersona[p.id]).put("nome", p.nome).put("ruolo", p.ruolo).put("descrizione", p.descrizione)
                .put("video", video(leg.filter { it.verso == "video" && it.youtubeId != null }.map { it.youtubeId!! to it.secondi }))
                .put("note", JSONArray(leg.filter { it.verso == "nota" }.mapNotNull { idNota[it.versoId] }))
                .put("luoghi", JSONArray(leg.filter { it.verso == "segnalino" }.mapNotNull { idLuogo[it.versoId] }))
                .put("relazioni", JSONArray().apply { leg.filter { it.verso == "persona" }.forEach { r -> idPersona[r.versoId]?.let { put(JSONObject().put("persona", it).put("etichetta", r.etichetta)) } } })
                .put("cronologia", JSONArray().apply { cron.forEach { c -> put(JSONObject().put("quando", c.quando).put("cosa", c.cosa).put("fonte", c.fonte)) } }))
            md.append("### ${p.nome}" + (if (p.ruolo.isNotBlank()) " · ${p.ruolo}" else "") + "\n\n${p.descrizione}\n\n")
            cron.forEach { c -> md.append("- ${c.quando}: ${c.cosa}" + (if (c.fonte.isNotBlank()) " (${c.fonte})" else "") + "\n") }
            md.append("\n")
        }
        if (media.isNotEmpty()) { md.append("## Media\n\n"); media.values.forEach { md.append("- media/${it.file} · ${it.nome}\n") } }
        md.append("\n---\nTeoria creata con l'app ${Marca.NOME} (non ufficiale). I video citati sono dei rispettivi canali.\n")

        val caso = "teoria_" + slug(t.titolo)
        val j = JSONObject().put("id", caso).put("titolo", t.titolo).put("descrizione", t.descrizione)
            .put("teoria", JSONObject().put("titolo", t.titolo).put("descrizione", t.descrizione))
            .put("video_principale", note.firstNotNullOfOrNull { n -> i.videoDi(n.id).firstOrNull()?.youtubeId } ?: "")
            .put("note", jNote).put("luoghi", jLuoghi).put("linee", JSONArray()).put("persone", jPersone)
            .put("app", Marca.NOME).put("esportata", System.currentTimeMillis())

        val cart = File(a.cacheDir, "teorie").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val out = File(cart, "$caso.zip")
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.putNextEntry(ZipEntry("teoria.json")); z.write(j.toString(1).toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("teoria.md")); z.write(md.toString().toByteArray()); z.closeEntry()
            val dir = Media.cartella(a)
            media.values.forEach { m -> val f = File(dir, m.file); if (f.isFile) { z.putNextEntry(ZipEntry("media/" + m.file)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
        }
        return out
    }

    /** Dal pulsante della teoria: prepara lo ZIP e apre la condivisione con l'email della redazione già scritta. */
    fun condividi(a: Activity, teoriaId: Long) {
        val titolo = Archivio.di(a).teoria(teoriaId)?.titolo ?: return
        Toast.makeText(a, "Preparo lo ZIP della teoria…", Toast.LENGTH_SHORT).show()
        Thread {
            val f = runCatching { zip(a, teoriaId) }.getOrNull()
            a.runOnUiThread {
                if (f == null || !f.isFile) { Toast.makeText(a, "Non sono riuscito a preparare lo ZIP.", Toast.LENGTH_LONG).show(); return@runOnUiThread }
                val u = FileProvider.getUriForFile(a, "${a.packageName}.file", f)
                val manda = Intent(Intent.ACTION_SEND).setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, u)
                    .putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
                    .putExtra(Intent.EXTRA_SUBJECT, "La mia teoria: $titolo")
                    .putExtra(Intent.EXTRA_TEXT, "Ciao, vi mando la mia teoria «$titolo», fatta con l'app ${Marca.NOME}.\n\n" +
                        "Dentro lo ZIP ci sono le note con i video al minuto, i luoghi, le persone e i file collegati.\n\n" +
                        "Il mio nome da citare nel video (facoltativo): ")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { a.startActivity(Intent.createChooser(manda, "Manda la teoria a $EMAIL")) }
                    .onFailure { Toast.makeText(a, "Nessuna app per condividere: lo ZIP è pronto, scrivi a $EMAIL.", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }
}
