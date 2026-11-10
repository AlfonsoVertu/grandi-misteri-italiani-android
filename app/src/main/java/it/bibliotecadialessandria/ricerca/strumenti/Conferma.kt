package it.bibliotecadialessandria.ricerca.strumenti

import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * La conferma a due passi (11/11/2026), come `models/conferma.py` di SoWay (STUDIO_ASSISTENTE_AI.md §3.7): chi
 * cancella non lo fa alla prima chiamata. La prima risponde `serve_conferma` con un riepilogo e un `conferma_token`
 * MONOUSO, valido 10 minuti e legato agli argomenti; la seconda, con quel token, esegue. Il modello deve chiedere
 * alla persona in mezzo (lo dice il prompt): con un semplice `confermo=true` si confermava da solo.
 */
object Conferma {

    private const val DURATA_MS = 10 * 60_000L
    private class Attesa(val impronta: String, val scade: Long)
    private val attese = ConcurrentHashMap<String, Attesa>()
    private val caso = SecureRandom()

    /** Gli argomenti che contano (tutti tranne il token), in ordine fisso. */
    private fun impronta(strumento: String, azione: String, a: JSONObject): String =
        "$strumento/$azione/" + a.keys().asSequence().filter { it != "conferma_token" && it != "action" }.sorted()
            .joinToString("&") { "$it=${a.opt(it)}" }

    /** Prima chiamata: il token e la risposta che spiega cosa succede. */
    fun richiedi(strumento: String, azione: String, a: JSONObject, riepilogo: String, cosaSuccede: String,
                 comeSiTornaIndietro: String): JSONObject {
        val ora = System.currentTimeMillis()
        attese.entries.removeIf { it.value.scade < ora }
        val token = (1..12).map { "abcdefghjkmnpqrstuvwxyz23456789"[caso.nextInt(31)] }.joinToString("")
        attese[token] = Attesa(impronta(strumento, azione, a), ora + DURATA_MS)
        val fai = JSONObject().put("action", azione)
        a.keys().asSequence().filter { it != "action" && it != "conferma_token" }.forEach { fai.put(it, a.opt(it)) }
        fai.put("conferma_token", token)
        return JSONObject()
            .put("fai_cosi", fai)
            .put("ok", false).put("stato", "in_attesa_di_conferma").put("codice", "serve_conferma")
            .put("errore", "Serve la conferma della persona.").put("error", "Confirmation required.")
            .put("riepilogo", riepilogo)
            .put("perche", "E' un'operazione che non si annulla da sola: la persona deve dire di si'.")
            .put("cosa_succede", cosaSuccede)
            .put("come_si_torna_indietro", comeSiTornaIndietro)
            .put("conferma_token", token)
            .put("istruzioni", "Chiedi alla persona se vuole davvero procedere, mostrandole il riepilogo. SOLO se dice di si', " +
                "richiama la stessa azione con gli stessi parametri e `conferma_token`. Il token vale 10 minuti e una volta sola.")
    }

    /** Seconda chiamata: null se il token e' buono (e lo consuma), altrimenti perche' no. */
    fun valida(token: String, strumento: String, azione: String, a: JSONObject): String? {
        val t = attese.remove(token.trim()) ?: return "Il `conferma_token` non e' valido o e' gia' stato usato."
        if (t.scade < System.currentTimeMillis()) return "Il `conferma_token` e' scaduto (vale 10 minuti)."
        if (t.impronta != impronta(strumento, azione, a)) return "Il `conferma_token` era per un'altra operazione (parametri diversi)."
        return null
    }
}
