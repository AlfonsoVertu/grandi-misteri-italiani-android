package it.bibliotecadialessandria.ricerca.assistente

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.provider.MediaStore
import android.view.MotionEvent
import androidx.core.content.FileProvider
import java.io.File
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.StyleSpan
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import it.bibliotecadialessandria.ricerca.Catalogo
import it.bibliotecadialessandria.ricerca.Marca
import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.strumenti.mmss

/**
 * La chat con l'assistente AI (10/11/2026, STUDIO_ASSISTENTE_AI.md §4.4): bolle, risposta che arriva a pezzi,
 * la riga «sta usando: …» mentre chiama uno strumento, i link ai video che aprono YouTube al secondo giusto,
 * «Svuota conversazione». Senza chiave: l'invito ad aprire le impostazioni (l'app non si rompe).
 */
class AssistenteActivity : Aspetto() {

    private lateinit var lista: LinearLayout
    private lateinit var scorri: ScrollView
    private lateinit var campo: EditText
    private lateinit var striscia: LinearLayout
    private lateinit var registra: TextView
    private var inFondo = true
    /** Gli allegati scelti e non ancora mandati (id nell'archivio Media). */
    private val inAttesa = mutableListOf<Long>()
    private var scatto: File? = null
    private var registratore: Ascolto.Registratore? = null
    private var annullaVocale = false
    private var lettore: MediaPlayer? = null
    private var suona: Long? = null
    private val orologio = android.os.Handler(android.os.Looper.getMainLooper())

    private val LINK_VIDEO = Regex("""https?://(?:www\.|m\.)?(?:youtube\.com/watch\?v=|youtu\.be/)([A-Za-z0-9_-]{11})(?:[&?]t=(\d+)s?)?""")

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        // (11/11/2026) mentre e' aperta la fotocamera Android puo' chiudere l'app (provato sul Tab8): lo scatto in
        // arrivo e gli allegati gia' scelti si ritrovano dallo stato salvato
        stato?.getString("scatto")?.let { scatto = File(it) }
        stato?.getLongArray("in_attesa")?.let { inAttesa.addAll(it.toList()) }
        ConfigAi.importaSeCe(this)?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() }
        Catalogo.prepara(this)
        val radice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colore(R.color.fondo)) }

        // testata: indietro, nome, impostazioni, svuota
        val testa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = sfondo(colore(R.color.card), colore(R.color.bordo), 0)
        }
        testa.addView(nota("‹").apply { textSize = 26f; setPadding(dp(4), 0, dp(12), 0); setOnClickListener { finish() }; contentDescription = "Indietro" })
        val nomi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nomi.addView(TextView(this).apply { text = "Assistente AI"; textSize = 18f
            setTypeface(Typeface.SERIF, Typeface.BOLD); setTextColor(colore(R.color.testo)) })
        nomi.addView(nota(Marca.NOME).apply { textSize = 12f })
        testa.addView(nomi, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        testa.addView(nota("Media").apply { textSize = 14f; setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { startActivity(Intent(this@AssistenteActivity, MediaActivity::class.java)) } })
        testa.addView(nota("Svuota").apply { textSize = 14f; setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { chiediSvuota() } })
        testa.addView(nota("Impostazioni").apply { textSize = 14f; setTextColor(colore(R.color.oro)); setPadding(dp(10), dp(8), dp(4), dp(8))
            setOnClickListener { startActivity(Intent(this@AssistenteActivity, ImpostazioniAiActivity::class.java)) } })
        radice.addView(testa, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        // (11/11/2026, fase 2b, §7.1) il piano del compito lungo, sopra la chat (tocca per aprirlo o chiuderlo)
        pianoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), dp(8))
            background = sfondo(colore(R.color.card), colore(R.color.oro), 0); visibility = View.GONE
            setOnClickListener { pianoAperto = !pianoAperto; disegnaPiano() } }
        radice.addView(pianoBox, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scorri = ScrollView(this).apply { isFillViewport = true }
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(12), dp(12), dp(12)) }
        scorri.addView(lista, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        scorri.viewTreeObserver.addOnScrollChangedListener {
            inFondo = scorri.scrollY + scorri.height >= lista.height - dp(48)
        }
        radice.addView(scorri, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // la casella per scrivere
        val sotto = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = sfondo(colore(R.color.card), colore(R.color.bordo), 0)
        }
        campo = campo("Scrivi una domanda…", "", righe = 1).apply {
            isSingleLine = false; maxLines = 5
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        // (11/11/2026) + allegati: foto, fotocamera, video, PDF/file
        sotto.addView(tondo("＋", "Allega foto, video o file") { menuAllegati() }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(6) })
        sotto.addView(campo, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        // (11/11/2026) il microfono: tieni premuto = registra, rilascia = invia, scorri a sinistra = annulla.
        // Regola del bot: se non c'e' ne' il modello per l'audio ne' il riconoscimento del telefono, non compare.
        if (Ascolto.disponibile(this)) sotto.addView(tondo("🎤", "Tieni premuto per un vocale") {}.apply { setOnTouchListener { v, e -> microfono(v, e) } },
            LinearLayout.LayoutParams(dp(46), dp(46)).apply { rightMargin = dp(6) })
        sotto.addView(bottone("Invia", pieno = true) { manda() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        // gli allegati in attesa e la riga «sto registrando», sopra la casella
        striscia = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), 0) }
        radice.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(striscia); visibility = View.GONE
            setBackgroundColor(colore(R.color.card)) }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        registra = TextView(this).apply { textSize = 14f; setTextColor(colore(R.color.oro)); setPadding(dp(16), dp(8), dp(16), dp(4))
            setBackgroundColor(colore(R.color.card)); visibility = View.GONE }
        radice.addView(registra, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        radice.addView(sotto, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(radice)
        if (inAttesa.isNotEmpty()) disegnaStriscia()
    }

    override fun onSaveInstanceState(fuori: Bundle) {
        super.onSaveInstanceState(fuori)
        scatto?.let { fuori.putString("scatto", it.absolutePath) }
        fuori.putLongArray("in_attesa", inAttesa.toLongArray())
    }

    override fun onResume() {
        super.onResume()
        Conversazione.ascolta = { disegna() }
        Voce.cambiato = { disegna() }
        it.bibliotecadialessandria.ricerca.indagine.Archivio.cambiato = { orologio.post { if (::pianoBox.isInitialized) disegnaPiano() } }
        disegna(); scorri.post { scorri.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onPause() {
        Conversazione.ascolta = null
        Voce.cambiato = null
        it.bibliotecadialessandria.ricerca.indagine.Archivio.cambiato = null
        registratore?.let { it.ferma(); registratore = null; registra.visibility = View.GONE }
        fermaLettore()
        super.onPause()
    }

    private fun manda() {
        val t = campo.text.toString().trim()
        if (t.isEmpty() && inAttesa.isEmpty()) return
        val cfg = ConfigAi.leggi(this)
        if (!cfg.pronta) { invito(cfg.manca()); return }
        campo.setText("")
        inFondo = true
        Conversazione.scrivi(this, t, inAttesa.toList())
        inAttesa.clear(); disegnaStriscia()
        // la tastiera che si chiude cambia l'altezza: si torna in fondo dopo, quando la chat si e' ridisegnata
        scorri.postDelayed({ inFondo = true; scorri.fullScroll(View.FOCUS_DOWN) }, 450)
    }

    /** Un bottone tondo con un simbolo (+, microfono). */
    private fun tondo(simbolo: String, descrizione: String, vai: () -> Unit) = TextView(this).apply {
        text = simbolo; textSize = 20f; gravity = Gravity.CENTER; setTextColor(colore(R.color.oro))
        background = sfondo(colore(R.color.fondo), colore(R.color.oro), 23)
        contentDescription = descrizione
        setOnClickListener { vai() }
    }

    // ── allegati ─────────────────────────────────────────────────────

    private fun menuAllegati() {
        val voci = arrayOf("Foto dalla galleria", "Scatta una foto", "Video", "PDF o altro file (anche audio)")
        AlertDialog.Builder(this).setTitle("Allega").setItems(voci) { _, quale ->
            when (quale) {
                0 -> scegliDa(Intent(Intent.ACTION_GET_CONTENT).setType("image/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true))
                1 -> fotocamera()
                2 -> scegliDa(Intent(Intent.ACTION_GET_CONTENT).setType("video/*"))
                else -> scegliDa(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "audio/*", "text/*", "application/json", "image/*", "video/*")))
            }
        }.show()
    }

    private fun scegliDa(i: Intent) {
        runCatching { startActivityForResult(i.addCategory(Intent.CATEGORY_OPENABLE), ALLEGA) }
            .onFailure { Toast.makeText(this, "Nessuna app per scegliere il file.", Toast.LENGTH_LONG).show() }
    }

    /** La fotocamera del telefono scrive in un file nostro (FileProvider): niente permesso CAMERA per l'app. */
    private fun fotocamera() {
        val dir = File(cacheDir, "scatti").apply { mkdirs() }
        val f = File(dir, "foto_${System.currentTimeMillis()}.jpg")
        scatto = f
        val u = FileProvider.getUriForFile(this, "$packageName.file", f)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, u)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("foto", u)
        runCatching { startActivityForResult(i, SCATTO) }
            .onFailure { Toast.makeText(this, "Nessuna fotocamera disponibile.", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("Activity semplice, come il resto dell'app")
    override fun onActivityResult(codice: Int, esito: Int, dati: Intent?) {
        super.onActivityResult(codice, esito, dati)
        if (esito != Activity.RESULT_OK) return
        val uri = when (codice) {
            ALLEGA -> dati?.let { MediaActivity.urisDa(it) }.orEmpty()
            SCATTO -> scatto?.takeIf { it.exists() && it.length() > 0 }?.let { listOf(FileProvider.getUriForFile(this, "$packageName.file", it)) }.orEmpty()
            else -> return
        }
        if (uri.isEmpty()) return
        Toast.makeText(this, "Preparo l'allegato…", Toast.LENGTH_SHORT).show()
        MediaActivity.importa(this, uri, "chat") { prese, errori ->
            if (codice == SCATTO) scatto?.delete()
            errori.firstOrNull()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            inAttesa.addAll(prese.map { it.id }); disegnaStriscia()
        }
    }

    private fun disegnaStriscia() {
        striscia.removeAllViews()
        (striscia.parent as? View)?.visibility = if (inAttesa.isEmpty()) View.GONE else View.VISIBLE
        inAttesa.toList().forEach { id ->
            val v = Media.prendi(this, id) ?: return@forEach
            val box = android.widget.FrameLayout(this)
            box.addView(MediaActivity.anteprima(this, v, 64), android.widget.FrameLayout.LayoutParams(dp(64), dp(64)))
            box.addView(TextView(this).apply { text = "✕"; textSize = 13f; gravity = Gravity.CENTER; setTextColor(colore(R.color.su_oro))
                background = sfondo(colore(R.color.oro), colore(R.color.oro), 11); contentDescription = "Togli ${v.nome}"
                setOnClickListener { inAttesa.remove(id); disegnaStriscia()
                    // mai mandato: non resta nemmeno nell'archivio
                    if (v.origine == "chat") Thread { Media.cancella(applicationContext, v) }.start() } },
                android.widget.FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            striscia.addView(box, LinearLayout.LayoutParams(dp(70), dp(70)).apply { rightMargin = dp(8) })
        }
    }

    // ── vocali ───────────────────────────────────────────────────────

    private fun microfono(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), PERMESSO_MICROFONO); return true
                }
                val cfg = ConfigAi.leggi(this)
                if (!cfg.pronta) { invito(cfg.manca()); return true }
                fermaLettore(); Voce.ferma()
                val r = Ascolto.Registratore()
                if (!r.parti()) { Toast.makeText(this, "Il microfono non si apre.", Toast.LENGTH_LONG).show(); return true }
                registratore = r; annullaVocale = false
                v.alpha = 0.5f
                registra.visibility = View.VISIBLE
                aggiornaRegistra()
            }
            MotionEvent.ACTION_MOVE -> if (registratore != null) {
                val via = e.x < -dp(90)
                if (via != annullaVocale) { annullaVocale = via; aggiornaRegistra() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val r = registratore ?: return true
                registratore = null; v.alpha = 1f
                registra.visibility = View.GONE
                val pcm = r.ferma()
                val ms = Ascolto.durataMs(pcm)
                if (annullaVocale || e.actionMasked == MotionEvent.ACTION_CANCEL) { Toast.makeText(this, "Vocale annullato.", Toast.LENGTH_SHORT).show(); return true }
                if (ms < 700) { Toast.makeText(this, "Tieni premuto il microfono mentre parli.", Toast.LENGTH_SHORT).show(); return true }
                mandaVocale(pcm, ms)
            }
        }
        return true
    }

    private fun aggiornaRegistra() {
        val r = registratore ?: return
        registra.text = if (annullaVocale) "✕ Rilascia per annullare" else
            "● Registro… ${mmss((r.durataMs / 1000).toInt())}  ·  rilascia per mandare, scorri a sinistra per annullare"
        orologio.removeCallbacksAndMessages(null)
        orologio.postDelayed({ aggiornaRegistra() }, 300)
    }

    private fun mandaVocale(pcm: ByteArray, ms: Long) {
        val f = File(Conversazione.cartellaVocali(this), "vocale_${System.currentTimeMillis()}.wav")
        runCatching { f.writeBytes(Ascolto.wav(pcm)) }.onFailure { Toast.makeText(this, "Vocale non salvato.", Toast.LENGTH_LONG).show(); return }
        inFondo = true
        val t = campo.text.toString().trim()
        campo.setText("")
        Conversazione.scrivi(this, t, inAttesa.toList(), f, ms)
        inAttesa.clear(); disegnaStriscia()
        // la tastiera che si chiude cambia l'altezza: si torna in fondo dopo, quando la chat si e' ridisegnata
        scorri.postDelayed({ inFondo = true; scorri.fullScroll(View.FOCUS_DOWN) }, 450)
    }

    override fun onRequestPermissionsResult(codice: Int, permessi: Array<out String>, esiti: IntArray) {
        super.onRequestPermissionsResult(codice, permessi, esiti)
        if (codice != PERMESSO_MICROFONO) return
        Toast.makeText(this, if (esiti.firstOrNull() == PackageManager.PERMISSION_GRANTED) "Ora tieni premuto il microfono e parla."
            else "Senza il permesso del microfono non posso registrare i vocali.", Toast.LENGTH_LONG).show()
    }

    private fun suonaVocale(m: Conversazione.Messaggio) {
        if (suona == m.id) { fermaLettore(); disegna(); return }
        fermaLettore(); Voce.ferma()
        runCatching {
            lettore = MediaPlayer().apply {
                setDataSource(m.vocale); setOnCompletionListener { fermaLettore(); disegna() }
                prepare(); start()
            }
            suona = m.id
        }.onFailure { Toast.makeText(this, "Il vocale non si riproduce.", Toast.LENGTH_SHORT).show() }
        disegna()
    }

    private fun fermaLettore() {
        runCatching { lettore?.stop() }; runCatching { lettore?.release() }
        lettore = null; suona = null
    }

    /** (11/11/2026) la chiave come procedura guidata in tre passi: per chi non sa cos'è una chiave API */
    private fun invito(perche: String?) {
        AlertDialog.Builder(this).setTitle("Attiva l'assistente in 3 passi")
            .setMessage((perche?.let { "$it\n\n" } ?: "") +
                "L'assistente usa un modello di intelligenza artificiale con una chiave tua, gratuita per iniziare.\n\n" +
                "1. Tocca «Apri OpenRouter» e accedi (anche con Google).\n" +
                "2. Crea una chiave («Create key») e copiala.\n" +
                "3. Torna qui, tocca «Incolla la chiave» e incollala nel campo Chiave.\n\n" +
                "La chiave resta solo su questo telefono, cifrata. Quello che chiedi lo legge il fornitore del modello.")
            .setPositiveButton("Incolla la chiave") { _, _ -> startActivity(Intent(this, ImpostazioniAiActivity::class.java)) }
            .setNeutralButton("Apri OpenRouter") { _, _ -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://openrouter.ai/keys"))) } }
            .setNegativeButton("Non ora", null).show()
    }

    private fun chiediSvuota() {
        AlertDialog.Builder(this).setTitle("Svuotare la conversazione?")
            .setMessage("I messaggi con l'assistente saranno cancellati da questo telefono.")
            .setPositiveButton("Svuota") { _, _ -> Conversazione.svuota(this) }
            .setNegativeButton("Annulla", null).show()
    }

    // ── disegno ──────────────────────────────────────────────────────

    private lateinit var pianoBox: LinearLayout
    private var pianoAperto = true

    /** Il piano aperto (strumento `piano`): titolo, quanti passi fatti, e i passi con il loro stato. */
    private fun disegnaPiano() {
        val i = runCatching { it.bibliotecadialessandria.ricerca.indagine.Archivio.di(this) }.getOrNull() ?: return
        val p = if (Marca.ASSISTENTE) i.pianoAperto() else null
        pianoBox.removeAllViews()
        if (p == null) { pianoBox.visibility = View.GONE; return }
        pianoBox.visibility = View.VISIBLE
        val passi = i.passi(p.id)
        pianoBox.addView(TextView(this).apply { textSize = 13.5f; setTextColor(colore(R.color.oro)); setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            text = (if (pianoAperto) "▾ " else "▸ ") + "Piano: ${p.titolo} · ${passi.count { it.stato == "fatto" }} su ${passi.size}" })
        if (!pianoAperto) return
        passi.forEach { x ->
            val segno = when (x.stato) { "fatto" -> "✓"; "in_corso" -> "▶"; "bloccato" -> "✕"; else -> "○" }
            pianoBox.addView(nota("$segno  ${x.ordine}. ${x.testo}" + if (x.esito.isNotBlank() && x.stato == "fatto") " — ${x.esito.take(90)}" else "").apply {
                textSize = 12.5f; maxLines = 2
                if (x.stato == "in_corso") setTextColor(colore(R.color.testo)) })
        }
    }

    private fun disegna() {
        if (::pianoBox.isInitialized) disegnaPiano()
        val messaggi = Conversazione.messaggi(this)
        lista.removeAllViews()
        val cfg = ConfigAi.leggi(this)
        if (!cfg.pronta) lista.addView(cartaInvito(cfg.manca()), sotto(12))
        if (messaggi.isEmpty()) lista.addView(cartaBenvenuto(cfg.pronta), sotto(12))
        messaggi.forEach { m -> lista.addView(bolla(m), sotto(10)) }
        if (Conversazione.occupata() && messaggi.none { it.inCorso }) lista.addView(nota("…").apply { textSize = 18f })
        if (inFondo) scorri.post { scorri.fullScroll(View.FOCUS_DOWN) }
    }

    private fun cartaInvito(perche: String?): View = card().apply {
        background = sfondo(colore(R.color.card), colore(R.color.oro), 14)
        addView(titoletto("Per usare l'assistente serve un modello AI"), sotto(4))
        addView(nota((perche ?: "") + " L'assistente usa un fornitore di modelli scelto da te (OpenRouter, OpenAI, Anthropic, " +
            "Google Gemini, Groq…) con la tua chiave API. Quello che scrivi all'assistente lo legge il fornitore del modello."), sotto(10))
        addView(bottone("Apri le impostazioni dell'assistente", pieno = true) {
            startActivity(Intent(this@AssistenteActivity, ImpostazioniAiActivity::class.java)) })
    }

    private fun cartaBenvenuto(pronta: Boolean): View = card().apply {
        addView(titoletto("Chiedimi dei casi e dei video"), sotto(4))
        addView(nota("Cerco nel catalogo dei video, leggo le trascrizioni e ti dico in quale minuto se ne parla; posso " +
            "anche cercare sul web e su Wikipedia. Cito sempre la fonte."), sotto(8))
        val esempi = runCatching { assets.open("assistente/esempi.txt").bufferedReader().readLines() }.getOrDefault(emptyList())
            .map { it.trim() }.filter { it.isNotEmpty() }
        if (esempi.isNotEmpty() && pronta) {
            val fila = LinearLayout(this@AssistenteActivity).apply { orientation = LinearLayout.HORIZONTAL }
            esempi.forEach { e ->
                fila.addView(TextView(this@AssistenteActivity).apply {
                    text = e; textSize = 14f; setPadding(dp(14), dp(8), dp(14), dp(8)); setTextColor(colore(R.color.testo))
                    background = sfondo(colore(R.color.fondo), colore(R.color.bordo), 18)
                    setOnClickListener { campo.setText(e); campo.setSelection(e.length) }
                }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(8) })
            }
            addView(HorizontalScrollView(this@AssistenteActivity).apply { isHorizontalScrollBarEnabled = false; addView(fila) })
        }
    }

    private fun bolla(m: Conversazione.Messaggio): View {
        val mia = m.ruolo == "user"
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = if (mia) Gravity.END else Gravity.START }
        val testo = TextView(this).apply {
            textSize = 15.5f; setLineSpacing(0f, 1.15f)
            setTextColor(colore(if (mia) R.color.su_oro else R.color.testo))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = if (mia) sfondo(colore(R.color.oro), colore(R.color.oro), 16)
                else sfondo(colore(R.color.card), colore(if (m.errore) R.color.oro else R.color.bordo), 16)
            val corpo = when {
                m.testo.isNotBlank() -> m.testo
                m.inCorso -> "…"
                else -> ""
            }
            text = if (mia) corpo else formatta(corpo)
            if (!mia) {
                Linkify.addLinks(this, Linkify.WEB_URLS)
                movementMethod = LinkMovementMethod.getInstance()
                setLinkTextColor(colore(R.color.oro))
            }
            setTextIsSelectable(false)
            setOnLongClickListener { copia(m.testo); true }
        }
        if (mia) {
            // gli allegati (anteprime che si aprono) e il vocale, sopra il testo
            if (m.allegati.isNotEmpty()) {
                val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
                m.allegati.forEach { id ->
                    val v = Media.prendi(this, id)
                    if (v != null) fila.addView(MediaActivity.anteprima(this, v, 120).apply { setOnClickListener { MediaActivity.apri(this@AssistenteActivity, v) } },
                        LinearLayout.LayoutParams(dp(120), dp(120)).apply { leftMargin = dp(6) })
                    else fila.addView(nota("📎 (tolto dai Media)").apply { setPadding(dp(6), 0, 0, 0) })
                }
                box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fila) },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
            }
            if (m.vocale.isNotBlank()) box.addView(bottone((if (suona == m.id) "■  " else "▶  ") + "🎤 Vocale " + mmss((m.durataVocale / 1000).toInt()), pieno = true) {
                suonaVocale(m) }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(4) })
            if (m.testo.isNotBlank()) box.addView(testo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(48) })
            // l'agente Ascolto: la trascrizione subito sotto, come «📝 Trascrizione» su WhatsApp
            val ascolto = when {
                m.trascrizione.isNotBlank() -> "📝 " + m.trascrizione
                m.aVoce && m.stato.isNotBlank() && (m.preparando || m.errore) -> "📝 " + m.stato
                else -> null
            }
            if (ascolto != null) box.addView(nota(ascolto).apply { textSize = 14f; setTextColor(colore(R.color.testo)); setPadding(dp(14), dp(8), dp(14), dp(8))
                background = sfondo(colore(R.color.card), colore(R.color.oro), 14); setOnLongClickListener { copia(m.trascrizione); true } },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(48); topMargin = dp(4) })
            // l'agente Vista: cosa ha visto (corto; tieni premuto per copiarlo tutto)
            m.visto.toList().forEach { r ->
                val corto = r.replaceFirst(Regex("^\\[[^\\]]*\\]\\s*"), "")
                box.addView(nota("👁 " + corto.take(280) + (if (corto.length > 280) "…" else "")).apply {
                    textSize = 12f; setPadding(dp(6), dp(4), 0, 0); setOnLongClickListener { copia(r); true } },
                    LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(48) })
            }
            if (m.preparando && !m.aVoce && m.stato.isNotBlank()) box.addView(nota(m.stato).apply { textSize = 12f; setPadding(dp(6), dp(4), 0, 0) })
        } else box.addView(testo, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(24) })
        if (!mia) {
            // cosa sta usando (mentre lavora) o cosa ha usato (dopo)
            if (m.passi.isNotEmpty()) {
                val riga = if (m.inCorso) "sta usando: ${m.passi.last()}…" else "ha usato: " + m.passi.distinct().joinToString(" · ")
                box.addView(nota(riga).apply { textSize = 12f; setPadding(dp(6), dp(4), 0, 0) })
            } else if (m.inCorso) box.addView(nota("sta scrivendo…").apply { textSize = 12f; setPadding(dp(6), dp(4), 0, 0) })
            // i video citati: un bottone che apre YouTube al secondo giusto
            // l'agente Voce: ▶ per ascoltare la risposta (letta da sola se la domanda era a voce), ■ per fermarla
            if (!m.inCorso && !m.errore && m.testo.isNotBlank()) box.addView(nota(if (Voce.leggendo == m.id) "■  Ferma la lettura" else "▶  Ascolta").apply {
                textSize = 13f; setTextColor(colore(R.color.oro)); setPadding(dp(6), dp(6), dp(12), dp(2))
                setOnClickListener { if (Voce.leggendo == m.id) Voce.ferma() else { fermaLettore(); Voce.leggi(this@AssistenteActivity, m.id, m.testo) } }
            })
            if (!m.inCorso) LINK_VIDEO.findAll(m.testo).map { it.groupValues[1] to it.groupValues[2].toIntOrNull() }
                .distinct().take(8).forEach { (id, s) ->
                    box.addView(bottone("▶  " + (nomeDelVideo(m.testo, id, s) ?: "Apri il video") + (s?.let { " · ${mmss(it)}" } ?: "")) {
                        apri("https://www.youtube.com/watch?v=$id" + (s?.let { "&t=${it}s" } ?: ""))
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
                }
        }
        return box
    }

    /**
     * Il nome da mettere sul bottone di un video: la prima riga del punto dell'elenco che contiene il link,
     * senza simboli del markdown, accorciata. null se non si trova (allora «Apri il video»).
     */
    private fun nomeDelVideo(testo: String, id: String, secondi: Int?): String? {
        val blocchi = testo.split(Regex("\\n(?=\\s*([*\\-•]|\\d+\\.)\\s)|\\n\\s*\\n"))
        // (11/11/2026) lo stesso video a minuti diversi: il blocco giusto e' quello con l'id E il tempo di QUEL link
        // (prima i bottoni prendevano tutti il titolo del primo punto)
        val conId = blocchi.filter { it.contains(id) }
        val conTempo = Regex(Regex.escape(id) + "[^\\s)\\]]*?[?&]t=(\\d+)")
        fun tempiDi(b: String) = conTempo.findAll(b).map { it.groupValues[1].toInt() }.toList()
        val b = conId.firstOrNull { if (secondi == null) tempiDi(it).isEmpty() else secondi in tempiDi(it) }
            ?: conId.firstOrNull() ?: return null
        // la prima riga senza link; se titolo e link stanno sulla stessa riga, quella riga senza il link
        val righe = b.lines().map { it.trim() }.filter { it.isNotBlank() }
        val riga = righe.firstOrNull { !it.contains("http") }
            ?: righe.firstOrNull()?.replace(Regex("\\(?https?://\\S+\\)?"), "") ?: return null
        var pulita = riga.replace(Regex("^([*\\-•]|\\d+\\.)\\s+"), "").replace("**", "").replace("*", "")
            .replace(Regex("\\s*\\(durata[^)]*\\)"), "").trim().trimEnd(':', '.', ' ', ',', '-', '—')
        // il titolo fra virgolette, se c'e', e' il nome migliore
        Regex("«([^»]{3,})»|\"([^\"]{3,})\"").find(pulita)?.let { m -> pulita = m.groupValues[1].ifEmpty { m.groupValues[2] } }
        if (pulita.length < 3) return null
        return if (pulita.length > 60) pulita.take(57).trimEnd() + "…" else pulita
    }

    /** Un markdown minimo: elenchi con «•», **grassetto**, *corsivo* e i titoli con # senza cancelletti. */
    private fun formatta(t: String): CharSequence {
        val pulito = t.lines().joinToString("\n") {
            it.replace(Regex("^#{1,6}\\s+"), "").replace(Regex("^(\\s*)[*\\-]\\s+"), "$1•  ")
                // [testo](link) del markdown: il testo e poi il link nudo, che Linkify rende cliccabile
                .replace(Regex("\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)"), "$1 $2")
        }
        val sb = SpannableStringBuilder()
        var i = 0
        val r = Regex("\\*\\*(.+?)\\*\\*|(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])")
        r.findAll(pulito).forEach { m ->
            sb.append(pulito, i, m.range.first)
            val inizio = sb.length
            val grassetto = m.groupValues[1].isNotEmpty()
            sb.append(if (grassetto) m.groupValues[1] else m.groupValues[2])
            sb.setSpan(StyleSpan(if (grassetto) Typeface.BOLD else Typeface.ITALIC), inizio, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            i = m.range.last + 1
        }
        sb.append(pulito, i, pulito.length)
        return sb
    }

    private fun copia(t: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Assistente", t))
        Toast.makeText(this, "Copiato.", Toast.LENGTH_SHORT).show()
    }

    private fun apri(link: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
            .onFailure { Toast.makeText(this, "Nessuna app per aprire il link.", Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val ALLEGA = 51
        private const val SCATTO = 52
        private const val PERMESSO_MICROFONO = 53
    }
}
