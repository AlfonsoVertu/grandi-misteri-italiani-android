package it.bibliotecadialessandria.ricerca.assistente

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import it.bibliotecadialessandria.ricerca.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Le impostazioni dell'assistente AI (10/11/2026, STUDIO_ASSISTENTE_AI.md §4.3), separate da FroWay e SoWay:
 * fornitore (lista di FroWay o indirizzo a mano), chiave API (cifrata nel [Portachiavi], mai mostrata, mai nei log),
 * «Prova» (legge `/models`), modello principale scelto dall'elenco, istruzioni personali, «Cancella chiave».
 */
class ImpostazioniAiActivity : Aspetto() {

    private val ambito = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var indirizzo: EditText
    private lateinit var chiave: EditText
    private lateinit var modello: EditText
    private lateinit var istruzioni: EditText
    private lateinit var esito: TextView
    private lateinit var statoChiave: TextView
    private var modelli: List<ModelliAi.Modello>? = null
    private lateinit var mAudio: EditText
    private lateinit var mImmagini: EditText
    private lateinit var mVideo: EditText

    override fun onCreate(stato: Bundle?) {
        super.onCreate(stato)
        ConfigAi.importaSeCe(this)?.let { toast(it) }
        val cfg = ConfigAi.leggi(this)
        val scorri = ScrollView(this).apply { isFillViewport = true }
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(28)) }
        scorri.addView(corpo, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(scorri)

        corpo.addView(nota("‹ Indietro").apply { textSize = 15f; setPadding(0, dp(4), 0, dp(4)); setOnClickListener { finish() } }, sotto(8))
        corpo.addView(TextView(this).apply {
            text = "Impostazioni dell'assistente AI"; textSize = 22f
            setTypeface(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD); setTextColor(colore(R.color.testo))
        }, sotto(12))

        // l'avviso, in chiaro, prima di tutto (come FroWay)
        val avviso = card().apply { background = sfondo(colore(R.color.card), colore(R.color.oro), 14) }
        avviso.addView(titoletto("Prima di cominciare"), sotto(4))
        avviso.addView(nota("Quello che scrivi all'assistente lo legge il fornitore del modello che scegli qui. " +
            "L'assistente usa la TUA chiave: le richieste le paghi tu al fornitore (molti hanno modelli gratuiti). " +
            "La chiave resta cifrata su questo telefono e va solo a quel fornitore: l'app non ha server suoi."))
        corpo.addView(avviso, sotto(12))

        // fornitore
        val forn = card()
        forn.addView(etichetta("Fornitore del modello"), sotto(8))
        indirizzo = campo("https://… (indirizzo compatibile OpenAI)", cfg.endpoint).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val nome = nota(nomeFornitore(cfg.endpoint))
        // scritto a mano: il nome del fornitore e lo stato della chiave seguono l'indirizzo
        indirizzo.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                modelli = null; nome.text = nomeFornitore(endpoint()); if (::statoChiave.isInitialized) aggiornaStatoChiave()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        forn.addView(bottone("Scegli il fornitore", pieno = true) { scegliFornitore(nome) }, sotto(8))
        forn.addView(nome, sotto(6))
        forn.addView(nota("Oppure scrivi l'indirizzo a mano, compatibile OpenAI e in https (es. un tuo server con llama-server dietro https):"), sotto(4))
        forn.addView(indirizzo, sotto(4))
        corpo.addView(forn, sotto(12))

        // chiave
        val ch = card()
        ch.addView(etichetta("Chiave API"), sotto(8))
        statoChiave = nota("")
        ch.addView(statoChiave, sotto(6))
        chiave = campo("Incolla qui la chiave", "").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            typeface = android.graphics.Typeface.DEFAULT   // il campo password altrimenti passa al monospazio
            isSaveEnabled = false
        }
        ch.addView(chiave, sotto(8))
        val riga = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        riga.addView(bottone("Prova") { prova() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        riga.addView(bottone("Cancella chiave") { cancellaChiave() }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        ch.addView(riga, sotto(6))
        esito = nota("")
        ch.addView(esito)
        corpo.addView(ch, sotto(12))
        aggiornaStatoChiave()

        // modello
        val mo = card()
        mo.addView(etichetta("Modello principale"), sotto(8))
        modello = campo("es. openai/gpt-4o-mini", cfg.modello)
        mo.addView(modello, sotto(8))
        mo.addView(bottone("Scegli dall'elenco del fornitore") { scegliModello() }, sotto(4))
        mo.addView(nota("Serve un modello che sappia usare gli strumenti (tool calling). Nell'elenco cerca anche «gratis»."))
        corpo.addView(mo, sotto(12))

        // modelli per modalita' (facoltativi): vocali, foto, video
        val mm = ConfigAi.modelli(this)
        val mp = card()
        mp.addView(etichetta("Modelli per audio, immagini e video (facoltativi)"), sotto(8))
        mp.addView(nota("Per le immagini (foto e schermate)"), sotto(2))
        mImmagini = campo("es. google/gemini-2.5-flash", mm.immagini); mp.addView(mImmagini, sotto(6))
        mp.addView(nota("Per l'audio (messaggi vocali)"), sotto(2))
        mAudio = campo("es. google/gemini-2.5-flash", mm.audio); mp.addView(mAudio, sotto(6))
        mp.addView(nota("Per i video"), sotto(2))
        mVideo = campo("es. google/gemini-2.5-flash", mm.video); mp.addView(mVideo, sotto(6))
        mp.addView(nota("Vuoti: l'assistente usa il modello principale, se sa farlo. Lo script del PC (configura_ai.ps1) li sceglie da solo."))
        corpo.addView(mp, sotto(12))

        // istruzioni personali
        val ist = card()
        ist.addView(etichetta("Istruzioni personali"), sotto(8))
        istruzioni = campo("Es.: rispondi in modo breve; dammi sempre i link ai video", cfg.istruzioni, righe = 3)
        ist.addView(istruzioni, sotto(4))
        ist.addView(nota("Si aggiungono a quelle dell'assistente, che restano (citare i video, distinguere fatti e ipotesi)."))
        corpo.addView(ist, sotto(12))

        corpo.addView(bottone("Salva", pieno = true) { salva(); toast("Salvato."); finish() }, sotto(8))
        corpo.addView(nota("Le impostazioni dell'assistente sono solo di questa app: non toccano FroWay né SoWay.").apply { gravity = Gravity.CENTER })
    }

    override fun onDestroy() { ambito.cancel(); super.onDestroy() }

    private fun endpoint() = indirizzo.text.toString().trim().trimEnd('/')

    private fun nomeFornitore(e: String) = when {
        e.isBlank() -> "Nessun fornitore scelto."
        else -> (ModelliAi.nomeDi(e)?.let { "Scelto: $it" } ?: "Indirizzo a mano") + (if (ModelliAi.locale(e)) " · nella rete di casa" else "")
    }

    private fun aggiornaStatoChiave() {
        val e = endpoint()
        statoChiave.text = when {
            e.isBlank() -> "Scegli prima il fornitore: la chiave si salva per quel fornitore."
            ConfigAi.haChiave(this, e) -> "C'è già una chiave salvata per questo fornitore (non si mostra). Scrivine una nuova per cambiarla."
            ModelliAi.locale(e) -> "Un modello in casa di solito non vuole chiave."
            else -> "Nessuna chiave salvata per questo fornitore."
        }
    }

    private fun scegliFornitore(nome: TextView) {
        val voci = ModelliAi.NOTI.keys.toList() + "Indirizzo a mano…"
        AlertDialog.Builder(this).setTitle("Fornitore del modello").setItems(voci.toTypedArray()) { _, i ->
            if (i < ModelliAi.NOTI.size) indirizzo.setText(ModelliAi.NOTI.getValue(voci[i]))
            else { indirizzo.setText(""); indirizzo.requestFocus() }
            modelli = null
            nome.text = nomeFornitore(endpoint())
            aggiornaStatoChiave()
        }.show()
    }

    /** Salva tutto; la chiave solo se ne e' stata scritta una (il campo poi si svuota: non resta in vista). */
    private fun salva(): Boolean {
        val e = endpoint()
        if (e.isNotBlank() && !e.startsWith("https://", true)) {
            toast("L'indirizzo deve cominciare con https:// (Android blocca l'http in chiaro)."); return false
        }
        ConfigAi.salva(this, e, modello.text.toString(), istruzioni.text.toString())
        ConfigAi.salvaModelli(this, ConfigAi.Modelli(mAudio.text.toString(), mImmagini.text.toString(), mVideo.text.toString()))
        val k = chiave.text.toString().trim()
        if (k.isNotEmpty() && e.isNotBlank()) { ConfigAi.salvaChiave(this, e, k); chiave.setText("") }
        aggiornaStatoChiave()
        return true
    }

    private fun caricaModelli(dopo: (List<ModelliAi.Modello>) -> Unit) {
        if (!salva()) return
        val e = endpoint()
        if (e.isBlank()) { esito.text = "Scegli prima il fornitore."; return }
        esito.text = "Chiedo al fornitore l'elenco dei modelli…"
        val k = ConfigAi.chiave(this, e)
        ambito.launch {
            runCatching { withContext(Dispatchers.IO) { ModelliAi.elenco(e, k) } }
                .onSuccess { modelli = it; dopo(it) }
                .onFailure { esito.text = "Non funziona: ${it.message ?: "errore di rete"}" }
        }
    }

    private fun prova() = caricaModelli { lista ->
        esito.text = if (lista.isEmpty()) "Il fornitore risponde, ma non dà l'elenco dei modelli: scrivi il nome del modello a mano."
            else "Funziona: il fornitore offre ${lista.size} modelli." +
                (if (modello.text.isBlank()) " Ora scegli il modello." else "")
    }

    private fun scegliModello() {
        modelli?.takeIf { it.isNotEmpty() }?.let { l -> ModelliAi.scegli(this, l) { m -> modello.setText(m.id); salva() }; return }
        caricaModelli { lista ->
            if (lista.isEmpty()) { esito.text = "Nessun modello nell'elenco: scrivi il nome a mano."; return@caricaModelli }
            esito.text = "${lista.size} modelli."
            ModelliAi.scegli(this, lista) { m -> modello.setText(m.id); salva() }
        }
    }

    private fun cancellaChiave() {
        val e = endpoint()
        if (e.isBlank() || !ConfigAi.haChiave(this, e)) { toast("Non c'è nessuna chiave salvata per questo fornitore."); return }
        AlertDialog.Builder(this).setTitle("Cancellare la chiave?")
            .setMessage("La chiave di ${ModelliAi.nomeDi(e) ?: e} sarà tolta da questo telefono. L'assistente non funzionerà finché non ne metti un'altra.")
            .setPositiveButton("Cancella") { _, _ -> ConfigAi.cancellaChiave(this, e); aggiornaStatoChiave(); esito.text = "Chiave cancellata." }
            .setNegativeButton("Annulla", null).show()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_LONG).show()
}
