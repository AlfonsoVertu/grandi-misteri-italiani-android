package it.bibliotecadialessandria.ricerca.assistente

import android.app.Activity
import android.app.AlertDialog
import it.bibliotecadialessandria.ricerca.Marca
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * I fornitori di modelli, e i modelli che ognuno offre col loro prezzo. Copiato da FroWay (Assistenti.kt, che a sua
 * volta viene dall'app SoWay) il 10/11/2026: cambiati solo l'User-Agent e la finestra di scelta (qui un AlertDialog
 * semplice, FroWay ha i suoi Stile/Dialogo). Aggiunti [Modello.vedeImmagini] e [Modello.senteAudio].
 */
object ModelliAi {

    val UA get() = Marca.NOME.replace(" ", "") + " (Android; assistente AI)"

    /** Gli indirizzi gia' pronti: si sceglie il nome, non si scrive l'indirizzo (che si sbaglia una volta su tre). */
    val NOTI = linkedMapOf(
        "OpenRouter" to "https://openrouter.ai/api/v1",
        "OpenAI" to "https://api.openai.com/v1",
        // Anthropic espone Claude anche con uno strato compatibile OpenAI: stessa chiamata, chiave dell'account API.
        "Anthropic (Claude)" to "https://api.anthropic.com/v1",
        "Groq" to "https://api.groq.com/openai/v1",
        "Google (Gemini)" to "https://generativelanguage.googleapis.com/v1beta/openai",
        "NVIDIA (build)" to "https://integrate.api.nvidia.com/v1",
        "DeepSeek" to "https://api.deepseek.com/v1",
        "Mistral" to "https://api.mistral.ai/v1",
        "Together" to "https://api.together.xyz/v1",
    )

    private val CASA = Regex("^172\\.(1[6-9]|2\\d|3[01])\\.")

    /** Il modello gira in casa (rete locale)? Allora quello che legge non esce verso un fornitore. */
    fun locale(endpoint: String): Boolean {
        val casa = endpoint.trim().substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return casa == "localhost" || casa.endsWith(".local") || casa.startsWith("10.") || casa.startsWith("192.168.") ||
            casa.startsWith("127.") || CASA.containsMatchIn(casa)
    }

    /**
     * Un indirizzo a cui si puo' mandare una conversazione: in https, oppure in http ma solo verso la
     * propria rete (il modello che gira in casa). In http su internet la leggerebbe chiunque sta in mezzo.
     */
    fun indirizzoBuono(endpoint: String): Boolean {
        val e = endpoint.trim()
        if (e.startsWith("https://", true)) return true
        if (!e.startsWith("http://", true)) return false
        return locale(e)
    }

    fun nomeDi(endpoint: String): String? {
        val e = endpoint.trim().trimEnd('/')
        return NOTI.entries.firstOrNull { it.value.trimEnd('/') == e }?.key
    }

    data class Modello(
        val id: String, val accetta: List<String>, val contesto: Int,
        /** Quanto costa un milione di token in ingresso, in dollari. -1 = il fornitore non lo dice. */
        val prezzoIn: Double = -1.0, val prezzoOut: Double = -1.0,
    ) {
        val vedeImmagini get() = accetta.any { it.equals("image", true) }
        val senteAudio get() = accetta.any { it.equals("audio", true) }
        fun prezzo() = when {
            prezzoIn < 0 -> "prezzo non dichiarato dal fornitore"
            prezzoIn == 0.0 && prezzoOut == 0.0 -> "GRATIS"
            else -> "\$%.2f in · \$%.2f out per milione di token".format(prezzoIn, prezzoOut)
        }
        fun riga() = id + "\n" + prezzo() + (if (contesto > 0) " · ${contesto / 1000}k" else "") +
            (if (vedeImmagini) " · vede le immagini" else "")
    }

    /** I modelli di un fornitore. La chiave e' facoltativa: certi (OpenRouter, NVIDIA) pubblicano il listino a chiunque. */
    fun elenco(endpoint: String, chiave: String): List<Modello> {
        val c = (URL(endpoint.trimEnd('/') + "/models").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000; readTimeout = 60000
            if (chiave.isNotBlank()) setRequestProperty("Authorization", "Bearer $chiave")
            // Anthropic vuole anche la sua versione per l'elenco dei modelli
            if (endpoint.contains("anthropic.com")) {
                setRequestProperty("x-api-key", chiave); setRequestProperty("anthropic-version", "2023-06-01")
            }
            setRequestProperty("User-Agent", UA)
        }
        val code = c.responseCode
        val testo = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code == 401 || code == 403) throw RuntimeException(
            if (chiave.isBlank()) "Questo fornitore mostra i modelli solo a chi ha la chiave: salvala e riprova."
            else "Il fornitore non accetta questa chiave (HTTP $code).")
        if (code !in 200..299) throw RuntimeException("Il fornitore ha risposto HTTP $code.")
        val dati = JSONObject(testo).optJSONArray("data") ?: return emptyList()
        val fuori = ArrayList<Modello>()
        for (i in 0 until dati.length()) {
            val m = dati.optJSONObject(i) ?: continue
            // Le modalita' stanno in due posti diversi a seconda del fornitore: alla radice o dentro "architecture".
            val arch = m.optJSONObject("architecture")
            val a = m.optJSONArray("input_modalities") ?: arch?.optJSONArray("input_modalities")
            val accetta = if (a == null) listOf("text") else (0 until a.length()).map { a.optString(it) }
            // Chi non manda i prezzi resta a -1, che vuol dire «non lo so» e non «gratis».
            val pr = m.optJSONObject("pricing")
            fuori.add(Modello(m.optString("id").removePrefix("models/"), accetta,
                m.optInt("context_window", m.optInt("context_length", 0)),
                pr?.optString("prompt")?.toDoubleOrNull()?.times(1_000_000) ?: -1.0,
                pr?.optString("completion")?.toDoubleOrNull()?.times(1_000_000) ?: -1.0))
        }
        return fuori.filter { it.id.isNotBlank() }.sortedBy { it.id }
    }

    /** Fa scegliere, con una casella per cercare: OpenRouter ne ha piu' di quattrocento. Si cerca anche «gratis». */
    fun scegli(a: Activity, lista: List<Modello>, preso: (Modello) -> Unit) {
        val d = a.resources.displayMetrics.density
        val colonna = android.widget.LinearLayout(a).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), 0)
        }
        val cerca = android.widget.EditText(a).apply { hint = "Cerca: qwen, flash, gratis…"; setSingleLine() }
        val elenco = android.widget.ListView(a)
        colonna.addView(cerca)
        colonna.addView(elenco, android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, (380 * d).toInt()))
        val finestra = AlertDialog.Builder(a).setTitle("Modelli (" + lista.size + ")")
            .setView(colonna).setNegativeButton("Annulla", null).create()
        var mostrati = lista
        fun rifai(q: String) {
            val t = q.trim().lowercase()
            mostrati = when {
                t.isEmpty() -> lista
                t == "gratis" || t == "free" -> lista.filter { it.prezzoIn == 0.0 && it.prezzoOut == 0.0 }
                else -> lista.filter { it.id.lowercase().contains(t) }
            }
            elenco.adapter = android.widget.ArrayAdapter(a, android.R.layout.simple_list_item_1, mostrati.map { it.riga() })
        }
        rifai("")
        cerca.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { rifai(s?.toString() ?: "") }
            override fun beforeTextChanged(c: CharSequence?, a1: Int, b: Int, d1: Int) {}
            override fun onTextChanged(c: CharSequence?, a1: Int, b: Int, d1: Int) {}
        })
        elenco.setOnItemClickListener { _, _, i, _ -> finestra.dismiss(); mostrati.getOrNull(i)?.let(preso) }
        finestra.show()
    }
}
