package it.bibliotecadialessandria.ricerca

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * LE LINGUE DELLE APP DEI CANALI (14/11/2026, lo stesso sistema di FroWay del 06/10/2026): italiano, inglese, turco.
 *
 * QUALE LINGUA la decide il canale con [Marca.LINGUA]: "it" (le app italiane, sempre in italiano come prima),
 * "tr" o "en" fissa, oppure "auto" = la lingua del telefono (italiano, turco, se no inglese). I testi propri del
 * canale stanno in Marca.kt gia' nella sua lingua e non passano dal dizionario.
 *
 * Le scritte del codice comune stanno nel codice, in italiano. Invece di riscriverle tutte, l'app le traduce mentre le mostra:
 * un dizionario per lingua (assets/lingue/en.json, tr.json) con la frase italiana come chiave. Le frasi con una parte
 * che cambia («Hai {} canali») sono modelli: `{}` nella chiave, `{}` o `{0}` `{1}` nella traduzione (se l'ordine
 * cambia). L'elenco delle chiavi lo fa `strumenti/estrai_frasi.py` leggendo il codice.
 *
 * DOVE SI TRADUCE. Ogni schermata (le scritte, i pulsanti, i suggerimenti dei campi) mentre si disegna, le finestre
 * di dialogo ([Dialogo]) e i messaggi a comparsa ([toast]). MAI quello che scrivono le persone: i campi di testo
 * restano come sono (si traduce solo il suggerimento), e messaggi, post e nomi si segnano con [no].
 *
 * QUELLO CHE NON C'E' NEL DIZIONARIO resta in italiano: niente traduzioni inventate al volo.
 */
object Lingua {

    private const val PREF = "canali_lingua"
    const val AUTOMATICA = "auto"
    val SCELTE = listOf(AUTOMATICA to "Automatica (lingua del telefono)", "it" to "Italiano", "en" to "English", "tr" to "Türkçe")

    @Volatile private var codice: String? = null
    @Volatile private var dizionario: Map<String, String> = emptyMap()
    @Volatile private var modelli: List<Modello> = emptyList()
    private val cache = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, String>?) = size > 2000
    }

    private class Modello(val chiave: String, val schema: Pattern, val indizio: String, val traduzione: String)

    private fun p(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** La scelta salvata: [AUTOMATICA], "it", "en" o "tr". */
    fun scelta(ctx: Context): String = p(ctx).getString("lingua", AUTOMATICA) ?: AUTOMATICA

    /** La lingua che si usa davvero: la scelta, o quella del telefono (italiano, turco, se no inglese). */
    fun attiva(ctx: Context): String {
        if (Marca.LINGUA != AUTOMATICA) return Marca.LINGUA
        return scelta(ctx).takeIf { it != AUTOMATICA } ?: telefono()
    }

    /** La lingua del telefono fra quelle che l'app sa: italiano, turco, se no inglese. */
    fun telefono(): String = when (Locale.getDefault().language) { "it" -> "it"; "tr" -> "tr"; else -> "en" }

    fun scegli(ctx: Context, lingua: String) {
        p(ctx).edit().putString("lingua", lingua).commit()
        carica(ctx)
    }

    /** Carica il dizionario della lingua attiva (si chiama all'avvio e quando si cambia). */
    @Synchronized
    fun carica(ctx: Context) {
        val l = attiva(ctx)
        synchronized(cache) { cache.clear() }
        if (l == "it") { codice = "it"; dizionario = emptyMap(); modelli = emptyList(); return }
        val j = runCatching {
            JSONObject(ctx.assets.open("lingue/$l.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        }.getOrNull()
        usa(l, j)
    }

    /** Il dizionario di una lingua (anche nelle prove sul computer). */
    @Synchronized
    internal fun usa(l: String, j: JSONObject?) {
        synchronized(cache) { cache.clear() }
        val esatte = HashMap<String, String>()
        val schemi = ArrayList<Modello>()
        j?.keys()?.forEach { k ->
            val v = j.optString(k)
            if (v.isEmpty()) return@forEach
            if (k.contains("{}")) modello(k, v)?.let { schemi += it } else esatte[k] = v
        }
        dizionario = esatte
        modelli = schemi.sortedByDescending { it.chiave.length }   // i piu' lunghi (piu' precisi) prima
        codice = l
    }

    private fun modello(chiave: String, traduzione: String): Modello? {
        val pezzi = chiave.split("{}")
        val fisso = pezzi.filter { it.isNotBlank() }
        if (fisso.isEmpty()) return null
        val schema = Pattern.compile("^" + pezzi.joinToString("(.+?)") { Pattern.quote(it) } + "$", Pattern.DOTALL)
        return Modello(chiave, schema, fisso.maxByOrNull { it.length }!!, traduzione)
    }

    /** La traduzione con i valori al loro posto: `{}` in ordine, oppure `{0}` `{1}`. */
    private fun riempi(traduzione: String, valori: List<String>): String {
        if (Regex("""\{\d+\}""").containsMatchIn(traduzione))
            return Regex("""\{(\d+)\}""").replace(traduzione) { m -> valori.getOrNull(m.groupValues[1].toInt()) ?: m.value }
        val sb = StringBuilder()
        var i = 0
        var da = 0
        while (true) {
            val k = traduzione.indexOf("{}", da)
            if (k < 0) { sb.append(traduzione, da, traduzione.length); break }
            sb.append(traduzione, da, k).append(valori.getOrNull(i++).orEmpty())
            da = k + 2
        }
        return sb.toString()
    }

    /** Una riga (senza spazi agli estremi): esatta, poi i modelli. Null se non c'e'. */
    private fun una(riga: String): String? {
        dizionario[riga]?.let { return it }
        for (m in modelli) {
            if (!riga.contains(m.indizio)) continue
            val mt = m.schema.matcher(riga)
            if (mt.matches()) {
                // le parti variabili si traducono a loro volta (un'etichetta dentro un'altra frase)
                val valori = (1..mt.groupCount()).map { mt.group(it).let { v -> dizionario[v.trim()]?.let { t -> v.replace(v.trim(), t) } ?: v } }
                return riempi(m.traduzione, valori)
            }
        }
        return null
    }

    /** Una scritta fissa dell'interfaccia fuori dalle schermate (notifiche, canali di notifica): tradotta o com'e'. */
    @JvmStatic
    fun t(s: String): String = traduci(s)?.toString() ?: s

    /** Traduce un testo dell'interfaccia; se non lo conosce lo lascia com'e'. */
    fun traduci(testo: CharSequence?): CharSequence? {
        if (testo == null || testo.isEmpty() || codice == null || codice == "it") return testo
        // un testo con stili o link (Formato) non si tocca: si perderebbero
        if (testo is Spanned && testo.getSpans(0, testo.length, Any::class.java).isNotEmpty()) return testo
        val s = testo.toString()
        synchronized(cache) { cache[s] }?.let { return it }
        val fatto = traduciTesto(s)
        synchronized(cache) { cache[s] = fatto }
        return fatto
    }

    fun traduciTesto(s: String): String {
        val dentro = s.trim()
        if (dentro.isEmpty() || !dentro.any { it.isLetter() }) return s
        val prima = s.substring(0, s.indexOf(dentro))
        val dopo = s.substring(s.indexOf(dentro) + dentro.length)
        una(dentro)?.let { return prima + it + dopo }
        // piu' righe: riga per riga (le frasi unite nel codice con «\n»)
        if (dentro.contains('\n')) {
            val righe = dentro.split('\n')
            var cambiato = false
            val t = righe.joinToString("\n") { r ->
                val rr = r.trim()
                if (rr.isEmpty()) r else una(rr)?.let { cambiato = true; r.replace(rr, it) } ?: r
            }
            if (cambiato) return prima + t + dopo
        }
        // frasi incollate nel codice («…quello che scrivi: » + «spento»): pezzo per pezzo, dopo «:», «.», «·», «—»
        val pezzi = Regex("""(?<=[:.!?·—])\s+""").split(dentro)
        if (pezzi.size > 1) {
            val separatori = Regex("""(?<=[:.!?·—])\s+""").findAll(dentro).map { it.value }.toList()
            var cambiato = false
            val t = StringBuilder()
            pezzi.forEachIndexed { i, pz ->
                t.append(una(pz.trim())?.also { cambiato = true } ?: pz)
                if (i < separatori.size) t.append(separatori[i])
            }
            if (cambiato) return prima + t + dopo
        }
        return s
    }

    // ── le schermate ──────────────────────────────────────────────────

    /** Un testo scritto da una persona (messaggio, post, nome): non si traduce mai, ne' lui ne' quello che contiene. */
    fun no(v: View): View { v.setTag(R.id.lingua_no, true); return v }

    /** Traduce le scritte di una vista e di quello che contiene. */
    fun traduciAlbero(v: View?) {
        if (v == null || codice == null || codice == "it") return
        if (v.getTag(R.id.lingua_no) == true) return
        if (v is TextView) {
            if (v is EditText) traduciSuggerimento(v) else traduciScritta(v)
            if (v.hint != null && v !is EditText) traduciSuggerimento(v)
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) traduciAlbero(v.getChildAt(i))
    }

    private fun traduciScritta(v: TextView) {
        val ora = v.text ?: return
        if (ora.isEmpty()) return
        // gia' tradotta da noi, e nessuno l'ha cambiata dopo
        if (v.getTag(R.id.lingua_mostrato) === ora || v.getTag(R.id.lingua_mostrato)?.toString() == ora.toString()) return
        val t = traduci(ora)
        if (t != null && t.toString() != ora.toString()) {
            v.text = t
            v.setTag(R.id.lingua_mostrato, v.text)
        } else v.setTag(R.id.lingua_mostrato, ora)
    }

    private fun traduciSuggerimento(v: TextView) {
        val ora = v.hint ?: return
        if (v.getTag(R.id.lingua_suggerimento)?.toString() == ora.toString()) return
        val t = traduci(ora)
        if (t != null && t.toString() != ora.toString()) v.hint = t
        v.setTag(R.id.lingua_suggerimento, v.hint)
    }

    /** Ogni schermata, da quando si apre: le scritte si traducono a ogni disegno (solo quelle nuove o cambiate). */
    fun osserva(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) = guarda(a.window?.decorView)
            override fun onActivityCreated(a: Activity, s: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, s: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Traduce una finestra adesso e ogni volta che si ridisegna. */
    fun guarda(radice: View?) {
        if (radice == null || radice.getTag(R.id.lingua_guardata) == true) { traduciAlbero(radice); return }
        radice.setTag(R.id.lingua_guardata, true)
        radice.viewTreeObserver.addOnGlobalLayoutListener { traduciAlbero(radice) }
        traduciAlbero(radice)
    }

    // ── i menu (PopupMenu) ────────────────────────────────────────────

    /**
     * Una voce di menu: si vede tradotta, ma il titolo italiano resta nascosto (titleCondensed) perche' chi
     * risponde al tocco confronta quello ([originale]).
     */
    fun voce(menu: android.view.Menu, titolo: CharSequence): android.view.MenuItem =
        menu.add(traduci(titolo) ?: titolo).apply { titleCondensed = titolo }

    /** Il titolo italiano di una voce di menu (quello con cui il codice la riconosce). */
    fun originale(voce: android.view.MenuItem): String = (voce.titleCondensed ?: voce.title).toString()

    // ── messaggi a comparsa e finestre di dialogo ─────────────────────

    /** Come Toast.makeText, tradotto. */
    @JvmStatic
    fun toast(c: Context, testo: CharSequence?, durata: Int): Toast = Toast.makeText(c, traduci(testo), durata)
}

/**
 * Una finestra di dialogo tradotta (06/10/2026): come AlertDialog.Builder, ma quando si apre si traducono titolo,
 * messaggio, pulsanti e quello che c'e' dentro, anche le voci di un elenco che compaiono dopo.
 */
class Dialogo(c: Context, tema: Int = 0) : android.app.AlertDialog.Builder(c, tema) {
    override fun create(): android.app.AlertDialog {
        val d = super.create()
        // la finestra si riempie quando si apre: la si guarda da subito, e a ogni disegno si traduce
        Lingua.guarda(d.window?.decorView)
        return d
    }

    override fun setItems(items: Array<out CharSequence>?, listener: android.content.DialogInterface.OnClickListener?) =
        super.setItems(items?.map { Lingua.traduci(it) ?: it }?.toTypedArray(), listener)

    override fun setMultiChoiceItems(items: Array<out CharSequence>?, checked: BooleanArray?,
                                     listener: android.content.DialogInterface.OnMultiChoiceClickListener?) =
        super.setMultiChoiceItems(items?.map { Lingua.traduci(it) ?: it }?.toTypedArray(), checked, listener)

    override fun setSingleChoiceItems(items: Array<out CharSequence>?, checked: Int,
                                      listener: android.content.DialogInterface.OnClickListener?) =
        super.setSingleChoiceItems(items?.map { Lingua.traduci(it) ?: it }?.toTypedArray(), checked, listener)
}
