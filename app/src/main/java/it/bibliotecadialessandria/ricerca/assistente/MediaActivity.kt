package it.bibliotecadialessandria.ricerca.assistente

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import it.bibliotecadialessandria.ricerca.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La schermata «Media» (11/11/2026, STUDIO_ASSISTENTE_AI.md §6): l'archivio dell'indagine. Elenco con le anteprime,
 * «＋ Aggiungi» dal telefono (foto, video, PDF, file), apri, condividi, cancella (con conferma), cerca per nome e per
 * testo (anche nelle descrizioni fatte dal modello e nelle trascrizioni). Gli allegati mandati in chat e i file
 * scaricati o scritti dall'assistente compaiono qui.
 */
class MediaActivity : Aspetto() {

    private lateinit var lista: LinearLayout
    private lateinit var cerca: EditText
    private val principale = Handler(Looper.getMainLooper())

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        val radice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colore(R.color.fondo)) }
        val testa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = sfondo(colore(R.color.card), colore(R.color.bordo), 0)
        }
        testa.addView(nota("‹").apply { textSize = 26f; setPadding(dp(4), 0, dp(12), 0); setOnClickListener { finish() }; contentDescription = "Indietro" })
        val nomi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nomi.addView(TextView(this).apply { text = "Media"; textSize = 18f
            setTypeface(Typeface.SERIF, Typeface.BOLD); setTextColor(colore(R.color.testo)) })
        nomi.addView(nota("Foto, video, PDF e file dell'indagine, solo sul telefono").apply { textSize = 12f })
        testa.addView(nomi, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        testa.addView(bottone("＋ Aggiungi", pieno = true) { scegli() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        radice.addView(testa, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        cerca = campo("Cerca per nome o testo…", "").apply {
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { disegna() }
            })
        }
        radice.addView(cerca, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(12), dp(10), dp(12), dp(4)) })
        val scorri = ScrollView(this)
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(16)) }
        scorri.addView(lista)
        radice.addView(scorri, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(radice)
    }

    override fun onResume() {
        super.onResume()
        Media.cambiato = { principale.post { disegna() } }
        disegna()
    }

    override fun onPause() {
        Media.cambiato = null
        super.onPause()
    }

    private fun scegli() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "application/pdf", "audio/*", "text/*", "application/json"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }, SCEGLI)
    }

    @Deprecated("Activity semplice, come il resto dell'app")
    override fun onActivityResult(codice: Int, esito: Int, dati: Intent?) {
        super.onActivityResult(codice, esito, dati)
        if (codice != SCEGLI || esito != Activity.RESULT_OK || dati == null) return
        val uri = urisDa(dati)
        importa(this, uri, "telefono") { _, errori -> errori.firstOrNull()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() } }
    }

    private fun disegna() {
        lista.removeAllViews()
        val q = cerca.text.toString().trim()
        val voci = if (q.isEmpty()) Media.tutte(this) else Media.cerca(this, q)
        if (voci.isEmpty()) {
            lista.addView(card().apply {
                addView(titoletto(if (q.isEmpty()) "L'archivio è vuoto" else "Niente con «$q»"), sotto(4))
                addView(nota(if (q.isEmpty()) "Aggiungi foto, video, PDF e file dal telefono con «＋ Aggiungi». Anche quello che " +
                    "mandi all'assistente in chat, e i file che l'assistente scarica o scrive per te, finiscono qui." else "Prova con un'altra parola."))
            }, sotto(10))
            return
        }
        lista.addView(nota("${voci.size} file").apply { setPadding(dp(4), 0, 0, dp(8)) })
        voci.forEach { v -> lista.addView(riga(v), sotto(10)) }
    }

    private val data = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY)

    private fun riga(v: Media.Voce): View {
        val c = card().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val alto = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        alto.addView(anteprima(this, v, 72), LinearLayout.LayoutParams(dp(72), dp(72)).apply { rightMargin = dp(12) })
        val testi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        testi.addView(titoletto(v.nome).apply { maxLines = 2 })
        val origine = when (v.origine) { "chat" -> "mandato in chat"; "url" -> "scaricato"; "assistente" -> "scritto dall'assistente"
            "vocale" -> "vocale"; else -> "dal telefono" }
        testi.addView(nota("${v.tipo} · ${Media.misura(v.dimensione)} · ${data.format(Date(v.aggiornato))} · $origine"))
        if (v.url.isNotBlank()) testi.addView(nota(v.url).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; textSize = 11f })
        if (v.testo.isNotBlank()) testi.addView(nota(v.testo.replace(Regex("\\s+"), " ").take(160)).apply { maxLines = 2; textSize = 12f })
        alto.addView(testi, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        alto.setOnClickListener { apri(this, v) }
        c.addView(alto)
        val bottoni = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        bottoni.addView(bottone("Apri") { apri(this, v) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        bottoni.addView(bottone("Condividi") { condividi(this, v) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
        bottoni.addView(bottone("Cancella") { chiediCancella(v) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        c.addView(bottoni, sopra(8))
        return c
    }

    private fun chiediCancella(v: Media.Voce) {
        AlertDialog.Builder(this).setTitle("Cancellare «${v.nome}»?")
            .setMessage("Il file sparisce da questo telefono. Non si torna indietro.")
            .setPositiveButton("Cancella") { _, _ -> Thread { Media.cancella(this, v); principale.post { disegna() } }.start() }
            .setNegativeButton("Annulla", null).show()
    }

    companion object {
        const val SCEGLI = 41

        private val miniature = object : LruCache<Long, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(k: Long, b: Bitmap) = b.byteCount
        }

        fun urisDa(dati: Intent): List<Uri> {
            val fuori = ArrayList<Uri>()
            dati.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i).uri?.let { fuori.add(it) } }
            if (fuori.isEmpty()) dati.data?.let { fuori.add(it) }
            return fuori
        }

        /** Copia nell'archivio dei contenuti del telefono, su un filo suo; [fatto] sul filo principale. */
        fun importa(a: Activity, uri: List<Uri>, origine: String, fatto: (List<Media.Voce>, List<String>) -> Unit) {
            val app = a.applicationContext
            Thread {
                val prese = ArrayList<Media.Voce>(); val errori = ArrayList<String>()
                uri.forEach { u ->
                    runCatching { Media.daTelefono(app, u, origine) }.onSuccess { prese.add(it) }
                        .onFailure { errori.add(it.message ?: "File non aggiunto.") }
                }
                Handler(Looper.getMainLooper()).post { fatto(prese, errori) }
            }.start()
        }

        fun miniaturaBitmap(ctx: android.content.Context, v: Media.Voce): Bitmap? {
            miniature.get(v.id)?.let { return it }
            val f = Media.miniaturaDi(ctx, v)
            if (!f.exists()) return null
            return BitmapFactory.decodeFile(f.absolutePath)?.also { miniature.put(v.id, it) }
        }

        /** L'anteprima di un file: la miniatura, o l'icona del tipo; per i video un ▶ sopra. */
        fun anteprima(a: Aspetto, v: Media.Voce, lato: Int): View {
            val box = FrameLayout(a).apply { background = a.sfondo(a.colore(R.color.fondo), a.colore(R.color.bordo), 10); clipToOutline = true }
            val b = miniaturaBitmap(a, v)
            if (b != null) box.addView(ImageView(a).apply { setImageBitmap(b); scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            else box.addView(TextView(a).apply { text = Media.icona(v.tipo); textSize = (lato / 2.6f); gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            if (v.tipo == "video") box.addView(TextView(a).apply { text = "▶"; textSize = lato / 4f; setTextColor(android.graphics.Color.WHITE)
                setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK); gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            box.contentDescription = v.nome
            return box
        }

        fun uriDi(a: Activity, v: Media.Voce): Uri = FileProvider.getUriForFile(a, a.packageName + ".file", Media.fileDi(a, v))

        /** Apre un file: i testi dentro l'app (un .md spesso non ha un'app per aprirlo), il resto con l'app giusta. */
        fun apri(a: Aspetto, v: Media.Voce) {
            val f = Media.fileDi(a, v)
            if (!f.exists()) { Toast.makeText(a, "Il file non c'è più.", Toast.LENGTH_LONG).show(); return }
            if (v.tipo == "testo") {
                val testo = runCatching { f.readText() }.getOrDefault("")
                val t = TextView(a).apply { text = testo; textSize = 15f; setTextColor(a.colore(R.color.testo)); setTextIsSelectable(true)
                    setPadding(a.dp(20), a.dp(12), a.dp(20), a.dp(12)) }
                AlertDialog.Builder(a).setTitle(v.nome).setView(ScrollView(a).apply { addView(t) })
                    .setPositiveButton("Chiudi", null).setNeutralButton("Condividi") { _, _ -> condividi(a, v) }.show()
                return
            }
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uriDi(a, v), v.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { a.startActivity(i) }.onFailure {
                runCatching { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uriDi(a, v), "*/*")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), v.nome)) }
                    .onFailure { Toast.makeText(a, "Nessuna app per aprire questo file.", Toast.LENGTH_LONG).show() }
            }
        }

        fun condividi(a: Activity, v: Media.Voce) {
            val u = uriDi(a, v)
            val i = Intent(Intent.ACTION_SEND).setType(v.mime).putExtra(Intent.EXTRA_STREAM, u)
                .putExtra(Intent.EXTRA_SUBJECT, v.nome).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            i.clipData = ClipData.newRawUri(v.nome, u)
            runCatching { a.startActivity(Intent.createChooser(i, "Condividi ${v.nome}")) }
                .onFailure { Toast.makeText(a, "Nessuna app per condividere.", Toast.LENGTH_LONG).show() }
        }
    }
}
