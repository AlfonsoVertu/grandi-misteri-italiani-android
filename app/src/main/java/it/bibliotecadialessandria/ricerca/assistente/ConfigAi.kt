package it.bibliotecadialessandria.ricerca.assistente

import android.content.Context

/**
 * La configurazione dell'assistente (10/11/2026): SEPARATA da FroWay e SoWay, sta solo in questa app.
 * Fornitore (indirizzo OpenAI-compatibile), modello principale e istruzioni personali in chiaro nelle preferenze
 * [PREF]; la chiave API nel [Portachiavi], una per indirizzo (come FroWay: `k|<indirizzo minuscolo>`).
 */
object ConfigAi {

    private const val PREF = "assistente_ai"

    data class Config(val endpoint: String, val modello: String, val istruzioni: String, val chiave: String) {
        /** Si puo' chiedere al modello: indirizzo buono, chiave e modello scelti. */
        val pronta get() = manca() == null && chiave.isNotBlank()
        /** Cosa manca, detto in chiaro; null se non manca niente. */
        fun manca(): String? = when {
            endpoint.isBlank() -> "Scegli il fornitore del modello."
            // (10/11/2026) Android blocca l'http in chiaro (provato sul Tab8: «Cleartext HTTP traffic not permitted»)
            // e l'app non lo permette nella configurazione di rete: per ora anche il modello in casa va in https.
            endpoint.trim().startsWith("http://", true) -> "Per ora l'app parla solo in https: Android blocca l'http in chiaro, anche verso un modello in casa."
            !ModelliAi.indirizzoBuono(endpoint) -> "L'indirizzo deve cominciare con https://."
            chiave.isBlank() && !ModelliAi.locale(endpoint) -> "Manca la chiave API del fornitore."
            modello.isBlank() -> "Scegli il modello."
            else -> null
        }
    }

    private fun pref(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun slot(endpoint: String) = "k|" + endpoint.trim().trimEnd('/').lowercase()

    fun leggi(ctx: Context): Config {
        val p = pref(ctx)
        val e = p.getString("endpoint", "").orEmpty()
        // un modello in casa (llama-server, LM Studio) spesso non vuole chiave: si manda un segnaposto
        val k = if (e.isBlank()) "" else Portachiavi.prendi(ctx, slot(e)).orEmpty().ifBlank { if (ModelliAi.locale(e)) "nessuna" else "" }
        return Config(e, p.getString("modello", "").orEmpty(), p.getString("istruzioni", "").orEmpty(), k)
    }

    fun haChiave(ctx: Context, endpoint: String) = endpoint.isNotBlank() && Portachiavi.ce(ctx, slot(endpoint))

    fun salva(ctx: Context, endpoint: String, modello: String, istruzioni: String) {
        pref(ctx).edit().putString("endpoint", endpoint.trim().trimEnd('/')).putString("modello", modello.trim())
            .putString("istruzioni", istruzioni).apply()
    }

    fun salvaChiave(ctx: Context, endpoint: String, chiave: String) = Portachiavi.metti(ctx, slot(endpoint), chiave.trim())

    fun chiave(ctx: Context, endpoint: String): String = Portachiavi.prendi(ctx, slot(endpoint)).orEmpty()

    fun cancellaChiave(ctx: Context, endpoint: String) = Portachiavi.togli(ctx, slot(endpoint))

    /** I modelli per modalita' (10/11/2026): audio (vocali), immagini (foto), video. Vuoto = usa il principale se sa farlo. */
    data class Modelli(val audio: String, val immagini: String, val video: String)

    fun modelli(ctx: Context): Modelli = pref(ctx).let {
        Modelli(it.getString("modello_audio", "").orEmpty(), it.getString("modello_immagini", "").orEmpty(), it.getString("modello_video", "").orEmpty())
    }

    fun salvaModelli(ctx: Context, m: Modelli) {
        pref(ctx).edit().putString("modello_audio", m.audio.trim()).putString("modello_immagini", m.immagini.trim())
            .putString("modello_video", m.video.trim()).apply()
    }

    /** Il file che lo script del PC (strumenti/configura_ai.ps1) mette con adb nella cartella privata dell'app. */
    const val FILE_IMPORT = "assistente_config.json"

    /**
     * (10/11/2026) Se c'e' il file di configurazione messo dal PC lo legge, salva tutto (chiave nel Portachiavi) e lo
     * cancella subito, sovrascrivendolo prima. Restituisce il riepilogo da mostrare, o null se non c'era niente.
     * Il file sta in Android/data/<pacchetto>/files: le altre app non lo leggono.
     */
    fun importaSeCe(ctx: Context): String? {
        val f = java.io.File(ctx.getExternalFilesDir(null) ?: return null, FILE_IMPORT)
        if (!f.exists()) return null
        val testo = runCatching { f.readText() }.getOrNull()
        runCatching { f.writeText(" ".repeat(f.length().toInt().coerceAtMost(65536))) }
        f.delete()
        val o = runCatching { org.json.JSONObject(testo.orEmpty()) }.getOrNull() ?: return "Il file di configurazione non era leggibile: l'ho cancellato."
        val e = o.optString("endpoint").trim().trimEnd('/')
        if (!e.startsWith("https://", true)) return "Nel file di configurazione l'indirizzo non è in https: non l'ho usato."
        val modello = o.optString("modello").trim()
        salva(ctx, e, modello, leggi(ctx).istruzioni)
        val k = o.optString("chiave").trim()
        if (k.isNotEmpty()) salvaChiave(ctx, e, k)
        val m = Modelli(o.optString("modello_audio"), o.optString("modello_immagini"), o.optString("modello_video"))
        salvaModelli(ctx, m)
        return "Configurazione importata: ${ModelliAi.nomeDi(e) ?: e}, testo «$modello»" +
            (if (m.immagini.isNotBlank()) ", immagini «${m.immagini}»" else "") +
            (if (m.audio.isNotBlank()) ", audio «${m.audio}»" else "") +
            (if (m.video.isNotBlank()) ", video «${m.video}»" else "") +
            (if (k.isNotEmpty()) ". Chiave salvata cifrata." else ".")
    }
}
