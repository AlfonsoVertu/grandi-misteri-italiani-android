package it.bibliotecadialessandria.ricerca.indagine

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.assistente.Aspetto
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine
import it.bibliotecadialessandria.ricerca.strumenti.linkAlSecondo
import it.bibliotecadialessandria.ricerca.strumenti.mmss
import it.bibliotecadialessandria.ricerca.strumenti.secondiDa

/**
 * La base delle schermate dell'indagine (11/11/2026, fase 2b): testata con «‹», titolo e bottoni; una casella di ricerca
 * facoltativa; la lista che si ridisegna da sola quando i dati cambiano (anche se e' l'assistente a cambiarli).
 * Stesso aspetto delle altre schermate ([Aspetto]: chiaro o scuro).
 */
abstract class Schermata : Aspetto() {

    protected lateinit var lista: LinearLayout
    protected var cerca: EditText? = null
    protected lateinit var radice: LinearLayout
    private val principale = Handler(Looper.getMainLooper())

    abstract val titolo: String
    open val sottotitolo: String = ""
    open val conRicerca: Boolean = false
    open val suggerimentoRicerca: String = "Cerca…"
    /** I bottoni in testata: testo e cosa fanno. */
    open fun bottoniTesta(): List<Pair<String, () -> Unit>> = emptyList()
    abstract fun disegna()

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        radice = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(colore(R.color.fondo)) }
        val testa = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = sfondo(colore(R.color.card), colore(R.color.bordo), 0)
        }
        testa.addView(nota("‹").apply { textSize = 26f; setPadding(dp(4), 0, dp(12), 0); setOnClickListener { finish() }; contentDescription = "Indietro" })
        val nomi = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nomi.addView(TextView(this).apply { text = titolo; textSize = 18f; maxLines = 2
            setTypeface(Typeface.SERIF, Typeface.BOLD); setTextColor(colore(R.color.testo)) })
        if (sottotitolo.isNotBlank()) nomi.addView(nota(sottotitolo).apply { textSize = 12f })
        testa.addView(nomi, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bottoniTesta().forEachIndexed { k, (t, f) ->
            testa.addView(bottone(t, pieno = k == bottoniTesta().size - 1) { f() }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(6) })
        }
        radice.addView(testa, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        if (conRicerca) {
            cerca = campo(suggerimentoRicerca, "").apply { addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { disegna() }
            }) }
            radice.addView(cerca, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(12), dp(10), dp(12), dp(4)) })
        }
        sopraLaLista(radice)
        val scorri = ScrollView(this)
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(24)) }
        scorri.addView(lista)
        radice.addView(scorri, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(radice)
    }

    /** Per chi vuole mettere qualcosa fra la testata e la lista. */
    open fun sopraLaLista(radice: LinearLayout) {}

    override fun onResume() {
        super.onResume()
        Archivio.cambiato = { principale.post { if (!isFinishing) disegna() } }
        disegna()
    }

    override fun onPause() {
        Archivio.cambiato = null
        super.onPause()
    }

    val testoCercato: String get() = cerca?.text?.toString()?.trim().orEmpty()

    // ── mattoncini ───────────────────────────────────────────────────

    fun vuoto(titolo: String, testo: String) = lista.addView(card().apply {
        addView(titoletto(titolo), sotto(4)); addView(nota(testo))
    }, sotto(10))

    /** Una card cliccabile con titolo, righe tenui e un segno di chi l'ha scritta. */
    fun voce(titolo: String, righe: List<String>, autore: String = "", tocca: () -> Unit): View = card().apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        val alto = LinearLayout(this@Schermata).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        alto.addView(titoletto(titolo).apply { maxLines = 3 }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        segnoAutore(autore)?.let { alto.addView(it) }
        addView(alto)
        righe.filter { it.isNotBlank() }.forEach { addView(nota(it).apply { maxLines = 3 }) }
        setOnClickListener { tocca() }
    }

    fun segnoAutore(autore: String): TextView? = when (autore) {
        "assistente", "ai" -> "✦ AI"; "indagine" -> "◆ precaricata"; else -> null
    }?.let { t -> TextView(this).apply { text = t; textSize = 11f; setTextColor(colore(R.color.oro)); setPadding(dp(8), 0, 0, 0) } }

    /** Una riga piccola con un'azione a destra (es. «▶ 12:30» … «✕»). */
    fun rigaAzione(testo: String, tocca: (() -> Unit)?, toglie: (() -> Unit)? = null): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(TextView(this@Schermata).apply { text = testo; textSize = 14.5f
            setTextColor(colore(if (tocca != null) R.color.oro else R.color.testo)); tocca?.let { f -> setOnClickListener { f() } } },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (toglie != null) addView(nota("✕").apply { textSize = 16f; setPadding(dp(12), 0, dp(4), 0); contentDescription = "Togli"
            setOnClickListener { toglie() } })
    }

    fun sezione(titolo: String, dentro: LinearLayout.() -> Unit): View = card().apply {
        addView(etichetta(titolo), sotto(6)); dentro()
    }

    fun fila(vararg b: Pair<String, () -> Unit>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        b.forEachIndexed { k, (t, f) -> addView(bottone(t) { f() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
            if (k > 0) leftMargin = dp(6) }) }
    }

    fun apriVideo(youtubeId: String, secondi: Int?) = apri(this, linkAlSecondo(youtubeId, secondi))

    fun testoVideo(youtubeId: String, secondi: Int?, etichetta: String = ""): String {
        val t = AiutiIndagine.titolo(this, youtubeId).ifBlank { "Video $youtubeId" }
        return "▶  " + (if (t.length > 50) t.take(48) + "…" else t) + (secondi?.let { " · ${mmss(it)}" } ?: "") +
            (if (etichetta.isNotBlank()) " — $etichetta" else "")
    }

    fun avvisa(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()

    /** Un dialogo con dei campi; [fatto] riceve i valori (nello stesso ordine). */
    fun chiedi(titolo: String, campi: List<Triple<String, String, Int>>, si: String = "Salva", spiega: String = "",
               fatto: (List<String>) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        if (spiega.isNotBlank()) box.addView(nota(spiega), sotto(8))
        val e = campi.map { (sugg, valore, righe) -> campo(sugg, valore, righe).also { box.addView(it, sotto(8)) } }
        AlertDialog.Builder(this).setTitle(titolo).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(si) { _, _ -> fatto(e.map { it.text.toString().trim() }) }
            .setNegativeButton("Annulla", null).show()
    }

    fun conferma(titolo: String, testo: String, si: String = "Cancella", fatto: () -> Unit) {
        AlertDialog.Builder(this).setTitle(titolo).setMessage(testo)
            .setPositiveButton(si) { _, _ -> fatto() }.setNegativeButton("Annulla", null).show()
    }

    /** Chiede un video (id o link) e il minuto; [fatto] riceve id, secondi (null = intero) ed etichetta. */
    fun chiediVideo(fatto: (String, Int?, String) -> Unit) {
        chiedi("Collega un video", listOf(Triple("Link o id del video YouTube", "", 1), Triple("Minuto (es. 12:30, vuoto = tutto)", "", 1),
            Triple("Cosa c'è a quel minuto (facoltativo)", "", 1)), "Collega") { v ->
            val id = AiutiIndagine.idDi(v[0]) ?: return@chiedi avvisa("Non è un link o un id di YouTube.")
            val sec = v[1].ifBlank { null }?.let { secondiDa(it) ?: return@chiedi avvisa("Il minuto si scrive come 12:30.") }
                ?: Regex("[?&]t=(\\d+)").find(v[0])?.groupValues?.get(1)?.toIntOrNull()
            fatto(id, sec, v[2])
        }
    }

    /** Sceglie da un elenco (id, nome). */
    fun scegli(titolo: String, voci: List<Pair<Long, String>>, vuoto: String, fatto: (Long) -> Unit) {
        if (voci.isEmpty()) { avvisa(vuoto); return }
        AlertDialog.Builder(this).setTitle(titolo).setItems(voci.map { it.second }.toTypedArray()) { _, k -> fatto(voci[k].first) }
            .setNegativeButton("Annulla", null).show()
    }

    companion object {
        fun apri(a: Activity, link: String) {
            runCatching { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) }
                .onFailure { Toast.makeText(a, "Nessuna app per aprire il link.", Toast.LENGTH_LONG).show() }
        }

        fun vai(ctx: Context, cosa: Class<*>, id: Long = 0, extra: Intent.() -> Unit = {}) =
            ctx.startActivity(Intent(ctx, cosa).putExtra("id", id).apply(extra))
    }
}
