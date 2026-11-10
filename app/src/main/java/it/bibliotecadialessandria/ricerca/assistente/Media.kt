package it.bibliotecadialessandria.ricerca.assistente

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer

/**
 * L'archivio MEDIA dell'indagine (11/11/2026, STUDIO_ASSISTENTE_AI.md §6): foto, video, PDF, audio e file di testo
 * che la persona aggiunge dal telefono, che manda in chat o che l'assistente scarica o scrive.
 *
 * Tutto nella cartella privata dell'app (`files/media/`): le altre app non la vedono, e per aprire o condividere un
 * file si passa dal FileProvider. L'indice e' un JSON (`files/media/indice.json`), scritto per intero a ogni cambio
 * con il file temporaneo e il rename (come la conversazione): poche centinaia di voci, niente Room per questo.
 *
 * Per ogni voce si tengono anche la miniatura (`m_<id>.jpg`, fatta una volta sola all'aggiunta) e il TESTO: quello
 * estratto da un file di testo, la descrizione fatta dal modello per le immagini, la trascrizione di un audio. Su
 * quel testo cerca la schermata Media e cerca l'assistente.
 */
object Media {

    private const val TAG = "Assistente"
    private const val INDICE = "indice.json"
    /** Oltre questa misura un file non entra nell'archivio (25 MB, come lo scaricamento da internet). */
    const val MASSIMO = 25L * 1024 * 1024

    class Voce(
        val id: Long,
        @Volatile var nome: String,
        val tipo: String,            // immagine | video | pdf | audio | testo | file
        val mime: String,
        val file: String,            // nome del file dentro files/media
        val origine: String,         // telefono | chat | vocale | url | assistente
        val url: String = "",
        val quando: Long = System.currentTimeMillis(),
        @Volatile var dimensione: Long = 0,
        @Volatile var testo: String = "",
        @Volatile var aggiornato: Long = quando,
    ) {
        fun json(): JSONObject = JSONObject().put("id", id).put("nome", nome).put("tipo", tipo).put("mime", mime)
            .put("file", file).put("origine", origine).put("url", url).put("quando", quando)
            .put("dimensione", dimensione).put("testo", testo).put("aggiornato", aggiornato)
    }

    private val voci = mutableListOf<Voce>()
    @Volatile private var caricato = false
    /** Chi guarda (la schermata Media) viene avvisato quando l'archivio cambia. Sul filo di chi cambia. */
    @Volatile var cambiato: (() -> Unit)? = null

    fun cartella(ctx: Context): File = File(ctx.applicationContext.filesDir, "media").apply { mkdirs() }
    fun fileDi(ctx: Context, v: Voce): File = File(cartella(ctx), v.file)
    fun miniaturaDi(ctx: Context, v: Voce): File = File(cartella(ctx), "m_${v.id}.jpg")

    private fun carica(ctx: Context) {
        if (caricato) return
        synchronized(voci) {
            if (caricato) return
            runCatching {
                val f = File(cartella(ctx), INDICE)
                if (f.exists()) {
                    val a = JSONArray(f.readText())
                    for (i in 0 until a.length()) a.getJSONObject(i).let { o ->
                        voci.add(Voce(o.getLong("id"), o.optString("nome"), o.optString("tipo"), o.optString("mime"),
                            o.optString("file"), o.optString("origine"), o.optString("url"), o.optLong("quando"),
                            o.optLong("dimensione"), o.optString("testo"), o.optLong("aggiornato", o.optLong("quando"))))
                    }
                }
            }.onFailure { Log.w(TAG, "indice dei media illeggibile") }
            caricato = true
        }
    }

    private fun salva(ctx: Context) {
        val a = JSONArray()
        synchronized(voci) { voci.forEach { a.put(it.json()) } }
        runCatching {
            val f = File(cartella(ctx), INDICE); val t = File(cartella(ctx), "$INDICE.tmp")
            t.writeText(a.toString()); if (!t.renameTo(f)) { f.delete(); t.renameTo(f) }
        }
        runCatching { cambiato?.invoke() }
    }

    fun tutte(ctx: Context): List<Voce> { carica(ctx); return synchronized(voci) { voci.sortedByDescending { it.aggiornato } } }

    fun prendi(ctx: Context, id: Long): Voce? { carica(ctx); return synchronized(voci) { voci.firstOrNull { it.id == id } } }

    fun perNome(ctx: Context, nome: String): Voce? { carica(ctx); return synchronized(voci) { voci.firstOrNull { it.nome.equals(nome, true) } } }

    fun piano(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Cerca per nome, tipo, origine/URL e testo; tutte le parole devono esserci. */
    fun cerca(ctx: Context, testo: String, tipo: String? = null): List<Voce> {
        val parole = piano(testo).split(Regex("\\s+")).filter { it.isNotBlank() }
        return tutte(ctx).filter { v ->
            (tipo.isNullOrBlank() || v.tipo == tipo) && parole.all { p ->
                piano(v.nome).contains(p) || piano(v.testo).contains(p) || v.tipo == p || piano(v.url).contains(p)
            }
        }
    }

    // ── tipi ─────────────────────────────────────────────────────────

    fun tipoDa(mime: String, nome: String): String {
        val m = mime.lowercase(); val n = nome.lowercase()
        return when {
            m.startsWith("image/") -> "immagine"
            m.startsWith("video/") -> "video"
            m.startsWith("audio/") -> "audio"
            m == "application/pdf" || n.endsWith(".pdf") -> "pdf"
            m.startsWith("text/") || m == "application/json" || Regex("\\.(txt|md|json|csv)$").containsMatchIn(n) -> "testo"
            else -> "file"
        }
    }

    fun mimeDa(nome: String): String {
        val est = nome.substringAfterLast('.', "").lowercase()
        return when (est) {
            "md" -> "text/markdown"; "txt" -> "text/plain"; "json" -> "application/json"; "csv" -> "text/csv"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(est) ?: "application/octet-stream"
        }
    }

    fun icona(tipo: String) = when (tipo) {
        "immagine" -> "🖼"; "video" -> "🎬"; "pdf" -> "📄"; "audio" -> "🎵"; "testo" -> "📝"; else -> "📎"
    }

    /** Un nome di file senza caratteri pericolosi e senza cartelle. */
    fun nomePulito(nome: String): String = nome.substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[^\\p{L}\\p{N} ._()\\-]"), "_").trim().trimStart('.').take(90).ifBlank { "file" }

    /** Id piccoli (1, 2, 3…): il modello li deve copiare, e lo smistatore li legge come numeri interi. */
    private fun nuovoId(): Long = synchronized(voci) { (voci.maxOfOrNull { it.id } ?: 0) + 1 }

    // ── aggiungere ───────────────────────────────────────────────────

    /** Il nome e la misura di un contenuto del telefono (galleria, file). */
    fun infoDi(ctx: Context, uri: Uri): Pair<String, Long> {
        var nome = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        var misura = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { nome = it }
                    if (!c.isNull(1)) misura = c.getLong(1)
                }
            }
        }
        return nome to misura
    }

    /**
     * Copia nell'archivio un contenuto del telefono. Le foto si salvano gia' ridimensionate (lato lungo 1600) e
     * ruotate per il verso giusto: e' anche la forma che va al modello. Fuori dal filo dell'interfaccia.
     */
    fun daTelefono(ctx: Context, uri: Uri, origine: String): Voce {
        carica(ctx)
        val (nomeOrig, misura) = infoDi(ctx, uri)
        if (misura > MASSIMO) throw IllegalArgumentException("«$nomeOrig» è troppo grande (${misura / 1048576} MB, massimo 25).")
        val mime = ctx.contentResolver.getType(uri) ?: mimeDa(nomeOrig)
        var nome = nomePulito(nomeOrig)
        if (!nome.contains('.')) MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { nome += ".$it" }
        val tipo = tipoDa(mime, nome)
        val id = nuovoId()
        val dest = posto(ctx, id, nome)
        if (tipo == "immagine" && !mime.contains("gif")) {
            val b = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val pronta = ridimensiona(b, 1600) ?: b
            dest.writeBytes(pronta)
        } else {
            ctx.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var tot = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; tot += n
                    if (tot > MASSIMO) { o.close(); dest.delete(); throw IllegalArgumentException("«$nome» è troppo grande (massimo 25 MB).") }
                    o.write(buf, 0, n) }
            } }
        }
        val mimeFinale = if (tipo == "immagine" && !mime.contains("gif")) "image/jpeg" else mime
        return registra(ctx, Voce(id, nome, tipo, mimeFinale, relativo(ctx, dest), origine, dimensione = dest.length(),
            testo = if (tipo == "testo") runCatching { dest.readText().take(20000) }.getOrDefault("") else ""))
    }

    /** Mette nell'archivio dei byte gia' pronti (scaricati da internet, scritti dall'assistente). */
    fun daByte(ctx: Context, nome: String, mime: String, byte: ByteArray, origine: String, url: String = ""): Voce {
        carica(ctx)
        val n = nomePulito(nome)
        val tipo = tipoDa(mime, n)
        val id = nuovoId()
        val dest = posto(ctx, id, n)
        dest.writeBytes(byte)
        return registra(ctx, Voce(id, n, tipo, mime, relativo(ctx, dest), origine, url, dimensione = dest.length(),
            testo = if (tipo == "testo") String(byte, Charsets.UTF_8).take(20000) else ""))
    }

    /** Ogni file nella sua cartellina (`f<id>/<nome>`): cosi' si apre e si condivide con il suo nome vero. */
    private fun posto(ctx: Context, id: Long, nome: String): File = File(File(cartella(ctx), "f$id").apply { mkdirs() }, nome)

    private fun relativo(ctx: Context, f: File): String = f.relativeTo(cartella(ctx)).path.replace(File.separatorChar, '/')

    private fun registra(ctx: Context, v: Voce): Voce {
        runCatching { miniatura(ctx, v) }.onFailure { Log.i(TAG, "miniatura non fatta: ${it.javaClass.simpleName}") }
        synchronized(voci) { voci.add(v) }
        salva(ctx)
        Log.i(TAG, "media: aggiunto ${v.tipo} (${v.dimensione} byte, ${v.origine})")
        return v
    }

    /** Scrive (o riscrive) un file di testo dell'archivio: nome uguale = stessa voce, aggiornata. */
    fun scriviTesto(ctx: Context, nome: String, testo: String, inFondo: Boolean): Pair<Voce, Boolean> {
        carica(ctx)
        var n = nomePulito(nome)
        if (!Regex("\\.(md|txt|json|csv)$", RegexOption.IGNORE_CASE).containsMatchIn(n)) n += ".md"
        val esiste = synchronized(voci) { voci.firstOrNull { it.nome.equals(n, true) && it.tipo == "testo" } }
        if (esiste != null) {
            val f = fileDi(ctx, esiste)
            val nuovo = if (inFondo && f.exists()) f.readText() + (if (f.readText().endsWith("\n")) "" else "\n") + testo else testo
            f.writeText(nuovo)
            esiste.dimensione = f.length(); esiste.testo = nuovo.take(20000); esiste.aggiornato = System.currentTimeMillis()
            salva(ctx)
            return esiste to false
        }
        return daByte(ctx, n, mimeDa(n), testo.toByteArray(Charsets.UTF_8), "assistente") to true
    }

    fun ricordaTesto(ctx: Context, v: Voce, testo: String) {
        v.testo = testo.take(20000)
        salva(ctx)
    }

    fun rinomina(ctx: Context, v: Voce, nome: String) { v.nome = nomePulito(nome); salva(ctx) }

    fun cancella(ctx: Context, v: Voce) {
        carica(ctx)
        runCatching { val f = fileDi(ctx, v); f.delete(); if (f.parentFile != cartella(ctx)) f.parentFile?.delete() }
        runCatching { miniaturaDi(ctx, v).delete() }
        synchronized(voci) { voci.removeAll { it.id == v.id } }
        salva(ctx)
        Log.i(TAG, "media: cancellato un ${v.tipo}")
    }

    // ── immagini e miniature ─────────────────────────────────────────

    /** JPEG con il lato lungo al massimo [lato], ruotato secondo l'EXIF. null se non e' un'immagine leggibile. */
    fun ridimensiona(byte: ByteArray, lato: Int, qualita: Int = 85): ByteArray? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(byte, 0, byte.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var campione = 1
        while (maxOf(o.outWidth, o.outHeight) / (campione * 2) >= lato) campione *= 2
        val b = BitmapFactory.decodeByteArray(byte, 0, byte.size, BitmapFactory.Options().apply { inSampleSize = campione }) ?: return null
        val gradi = runCatching {
            when (ExifInterface(byte.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
            }
        }.getOrDefault(0)
        return comprimi(scala(b, lato, gradi), qualita)
    }

    fun scala(b: Bitmap, lato: Int, gradi: Int = 0): Bitmap {
        val f = minOf(1f, lato.toFloat() / maxOf(b.width, b.height))
        if (f >= 1f && gradi == 0) return b
        val m = Matrix().apply { postScale(f, f); if (gradi != 0) postRotate(gradi.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    fun comprimi(b: Bitmap, qualita: Int = 85): ByteArray {
        // il PNG trasparente diventerebbe nero: si mette su fondo bianco
        val piena = if (b.hasAlpha()) Bitmap.createBitmap(b.width, b.height, Bitmap.Config.RGB_565).also {
            Canvas(it).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) } } else b
        return ByteArrayOutputStream().also { piena.compress(Bitmap.CompressFormat.JPEG, qualita, it) }.toByteArray()
    }

    /** Un fotogramma di un video al millisecondo [ms] (o il primo buono). */
    fun fotogramma(f: File, ms: Long): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.getFrameAtTime(ms * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) { null } finally { runCatching { r.release() } }
    }

    fun durataMs(f: File): Long {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) { 0L } finally { runCatching { r.release() } }
    }

    /** Le pagine di un PDF come immagini (per il ripiego quando il modello il PDF non lo prende). */
    fun pagineDelPdf(f: File, quante: Int, lato: Int = 1400): List<Bitmap> {
        val fuori = ArrayList<Bitmap>()
        runCatching {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { pdf ->
                    for (i in 0 until minOf(quante, pdf.pageCount)) pdf.openPage(i).use { p ->
                        val s = lato.toFloat() / maxOf(p.width, p.height)
                        val b = Bitmap.createBitmap((p.width * s).toInt().coerceAtLeast(1), (p.height * s).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        b.eraseColor(Color.WHITE)
                        p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        fuori.add(b)
                    }
                }
            }
        }
        return fuori
    }

    fun pagineDi(f: File): Int = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
    }.getOrDefault(0)

    /** La miniatura (lato 320), fatta una volta sola: foto, primo fotogramma del video, prima pagina del PDF. */
    fun miniatura(ctx: Context, v: Voce) {
        val f = fileDi(ctx, v)
        val b: Bitmap? = when (v.tipo) {
            "immagine" -> BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
            "video" -> fotogramma(f, 1000) ?: fotogramma(f, 0)
            "pdf" -> pagineDelPdf(f, 1, 480).firstOrNull()
            else -> null
        }
        if (b != null) miniaturaDi(ctx, v).writeBytes(comprimi(scala(b, 320), 80))
    }

    fun misura(byte: Long): String = when {
        byte >= 1048576 -> "%.1f MB".format(byte / 1048576.0)
        byte >= 1024 -> "${byte / 1024} KB"
        else -> "$byte byte"
    }
}
