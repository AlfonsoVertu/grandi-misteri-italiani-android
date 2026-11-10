package it.bibliotecadialessandria.ricerca.strumenti

import org.json.JSONArray
import org.json.JSONObject

/**
 * Gli errori che INSEGNANO (10/11/2026): traduzione in Kotlin di `odoo_gpt/controllers/errori.py` di SoWay.
 *
 * Il manifesto di la': «non segnalare: insegnare». Ogni errore dice
 *   1. cosa e' andato storto (`errore`, e `error` uguale per i modelli che cercano la parola inglese);
 *   2. la riga da copiare (`fai_cosi`, SEMPRE per prima: un modello piccolo copia quello che legge per primo);
 *   3. dove guardare (`come_scoprire`).
 * Mai un vicolo cieco: un nome sbagliato porta con se' i nomi veri. JSONObject tiene l'ordine di inserimento,
 * quindi l'ordine delle chiavi qui e' quello che legge il modello.
 */
object Errore {

    /** Lo scheletro: fai_cosi per primo, poi ok/codice/errore. */
    private fun base(codice: String, errore: String, faiCosi: JSONObject?): JSONObject {
        val o = JSONObject()
        if (faiCosi != null) o.put("fai_cosi", faiCosi)
        return o.put("ok", false).put("codice", codice).put("errore", errore).put("error", errore)
    }

    /** La bussola: come vedere le azioni dello strumento e cosa vuole una di esse. */
    fun bussola(azione: String?): JSONObject = JSONObject()
        .put("le azioni di questo strumento", JSONObject().put("action", "documentazione"))
        .apply { if (!azione.isNullOrBlank()) put("cosa vuole questa azione", JSONObject().put("action", "documentazione_$azione")) }

    /** Mancano dei parametri: TUTTI insieme, non uno alla volta. */
    fun manca(strumento: String, azione: String, mancano: List<Parametro>, faiCosi: JSONObject): JSONObject {
        val nomi = mancano.joinToString(", ") { "`${it.nome}`" }
        return base("mancano_parametri", "Mancano: $nomi.",
            JSONObject().put("strumento", strumento).put("action", azione).put("parametri", faiCosi))
            .put("mancano", JSONArray().also { a -> mancano.forEach { p ->
                a.put(JSONObject().put("nome", p.nome).put("tipo", p.tipo).put("cos_e", p.cosE)) } })
            .put("come_scoprire", bussola(azione))
    }

    /** Qualcosa che non c'e' (un video, un caso, un canale): sempre con i vicini. */
    fun nonEsiste(cosa: String, valore: String, forseCercavi: List<String>, faiCosi: JSONObject? = null): JSONObject =
        base("non_esiste", "Non trovo $cosa «$valore».", faiCosi)
            .put("forse_cercavi", JSONArray(forseCercavi.take(8)))

    /** Manca qualcosa che deve mettere la persona (non il modello): cosa manca e dove si mette. */
    fun nonConfigurato(cosaManca: String, doveSiMette: String): JSONObject =
        base("non_configurato", "Non configurato: $cosaManca.", null).put("dove_si_mette", doveSiMette)

    /** Una cosa che il servizio esterno non da' (es. un video senza sottotitoli), con la strada alternativa. */
    fun nonDisponibile(errore: String, faiCosi: JSONObject? = null): JSONObject = base("non_disponibile", errore, faiCosi)

    /** Un valore che non va bene (tipo, formato, fuori dai valori ammessi). */
    fun valoreSbagliato(strumento: String, azione: String, errore: String, faiCosi: JSONObject, ammessi: List<String>? = null): JSONObject =
        base("valore_sbagliato", errore, JSONObject().put("strumento", strumento).put("action", azione).put("parametri", faiCosi))
            .apply { if (ammessi != null) put("valori_ammessi", JSONArray(ammessi.take(60))) }
            .put("come_scoprire", bussola(azione))

    /** Un errore qualunque (rete, servizio), detto in chiaro, con la strada per riprovare. */
    fun generico(errore: String, faiCosi: JSONObject? = null, codice: String = "errore"): JSONObject = base(codice, errore, faiCosi)

    /** Aggiunge la bussola a un errore che non ce l'ha. */
    fun conBussola(e: JSONObject, azione: String?): JSONObject = if (e.has("come_scoprire")) e else e.put("come_scoprire", bussola(azione))
}
