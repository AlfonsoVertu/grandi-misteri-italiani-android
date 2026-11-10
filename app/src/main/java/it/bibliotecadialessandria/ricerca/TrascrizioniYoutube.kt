package it.bibliotecadialessandria.ricerca

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le trascrizioni dei video YouTube, scaricate DAL TELEFONO (10/11/2026, STUDIO_ASSISTENTE_AI.md §4.8). Codice unico:
 * lo usano il pulsante «Scarica trascrizione» (senza AI) e lo strumento `trascrizioni` dell'assistente.
 *
 * DA DOVE. I sottotitoli del player (`captions.playerCaptionsTracklistRenderer.captionTracks`):
 *  1. chiamata Innertube `youtubei/v1/player` con il client ANDROID: i suoi `baseUrl` si scaricano senza «PO token»
 *     (provato il 10/11/2026 dal PC: 152 KB di testo per un video di Vox);
 *  2. ripiego: le tracce scritte nella pagina `watch` (`ytInitialPlayerResponse`). Con il client web pero' YouTube
 *     chiede il PO token (`exp=xpe` nel baseUrl) e la risposta arriva VUOTA: lo stesso giorno, 0 byte.
 *     Si tiene come ultima prova, e se e' vuota si dice chiaramente che YouTube non la da'.
 * Una sola richiesta per video, su azione della persona (o dell'assistente), mai in blocco; poi si legge dalla cache
 * in `files/trascrizioni/`. Mai l'audio del video, mai server nostri, mai aggiramenti dei blocchi «non sei un bot».
 */
object TrascrizioniYoutube {

    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Mobile Safari/537.36"
    private const val UA_ANDROID = "com.google.android.youtube/20.10.38 (Linux; U; Android 14) gzip"
    private const val CLIENT_ANDROID = "20.10.38"

    data class Traccia(val lingua: String, val nome: String, val automatica: Boolean, val traducibile: Boolean, val url: String)
    data class Riga(val inizioMs: Int, val durataMs: Int, val testo: String)
    data class Info(val titolo: String, val canale: String, val durata: Int)
    data class Trascrizione(
        val youtubeId: String, val titolo: String, val canale: String, val durata: Int,
        val lingua: String, val automatica: Boolean, val tradotta: Boolean, val scaricata: Long, val righe: List<Riga>,
    )

    /** Il video non ha (o YouTube non da') sottotitoli: [lingue] sono quelle che ci sono, se ce ne sono. */
    class NonDisponibile(messaggio: String, val lingue: List<String> = emptyList()) : RuntimeException(messaggio)

    // ── rete ─────────────────────────────────────────────────────────

    private fun prendi(indirizzo: String, corpo: String? = null, ua: String = UA): String {
        val c = (URL(indirizzo).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept-Language", "it-IT,it;q=0.9,en;q=0.5")
            // il consenso ai cookie gia' dato: senza, dall'Europa arriva la pagina di consenso al posto del video
            setRequestProperty("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            if (corpo != null) {
                requestMethod = "POST"; doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            }
        }
        try {
            val code = c.responseCode
            if (code == 429) throw RuntimeException("YouTube chiede di andare piu' piano (troppe richieste): riprova fra qualche minuto.")
            if (code !in 200..299) throw RuntimeException("YouTube ha risposto HTTP $code.")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun tracceDa(player: JSONObject): List<Traccia> {
        val t = player.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")
            ?.optJSONArray("captionTracks") ?: return emptyList()
        return (0 until t.length()).mapNotNull { t.optJSONObject(it) }.map { x ->
            val nome = x.optJSONObject("name")?.let { n -> n.optString("simpleText").ifBlank {
                n.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty() } }.orEmpty()
            var u = x.optString("baseUrl").replace(Regex("&fmt=[^&]*"), "")
            if (u.startsWith("/")) u = "https://www.youtube.com$u"
            Traccia(x.optString("languageCode"), nome, x.optString("kind") == "asr", x.optBoolean("isTranslatable"), u)
        }.filter { it.url.startsWith("http") }
    }

    private fun infoDa(player: JSONObject): Info = player.optJSONObject("videoDetails").let { d ->
        Info(d?.optString("title").orEmpty(), d?.optString("author").orEmpty(), d?.optString("lengthSeconds")?.toIntOrNull() ?: 0)
    }

    /** La risposta del player: prima il client Android (niente PO token), poi la pagina web. */
    private fun player(id: String): List<Pair<JSONObject, String>> {
        val fuori = ArrayList<Pair<JSONObject, String>>()
        val pagina = runCatching { prendi("https://www.youtube.com/watch?v=$id&hl=it") }.getOrNull()
        val chiaveApi = pagina?.let { Regex("\"INNERTUBE_API_KEY\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        runCatching {
            val corpo = JSONObject().put("videoId", id).put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "ANDROID").put("clientVersion", CLIENT_ANDROID).put("androidSdkVersion", 34)
                .put("hl", "it").put("gl", "IT"))).toString()
            val u = "https://www.youtube.com/youtubei/v1/player" + (chiaveApi?.let { "?key=$it" } ?: "")
            fuori.add(JSONObject(prendi(u, corpo, UA_ANDROID)) to "android")
        }
        pagina?.let { p ->
            Regex("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});\\s*(?:var |</script>)").find(p)?.groupValues?.get(1)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { fuori.add(it to "web") }
        }
        if (fuori.isEmpty()) throw RuntimeException("Non riesco a raggiungere YouTube. Controlla la connessione.")
        return fuori
    }

    /** Le tracce disponibili di un video (per lo strumento `lingue`). */
    fun tracce(id: String): Pair<Info, List<Traccia>> {
        val p = player(id)
        val conTracce = p.firstOrNull { tracceDa(it.first).isNotEmpty() } ?: p.first()
        return infoDa(conTracce.first).let { if (it.titolo.isBlank()) infoDa(p.first().first) else it } to tracceDa(conTracce.first)
    }

    /** Scarica e legge una traccia: formato XML semplice (`<text start dur>`), molto piu' leggero del json3. */
    private fun scaricaTraccia(url: String): List<Riga> {
        val xml = prendi(url, ua = UA)
        if (xml.isBlank()) return emptyList()
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val righe = doc.select("text").mapNotNull { e ->
            val inizio = ((e.attr("start").toDoubleOrNull() ?: return@mapNotNull null) * 1000).toInt()
            val durata = ((e.attr("dur").toDoubleOrNull() ?: 0.0) * 1000).toInt()
            // il testo arriva con le entita' due volte (&amp;#39;): si sciolgono ancora
            val t = Parser.unescapeEntities(e.text(), false).replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) null else Riga(inizio, durata, t)
        }
        if (righe.isNotEmpty()) return righe
        // il formato srv3 (<p t= d=>), se YouTube un giorno cambia quello predefinito
        return doc.select("p").mapNotNull { e ->
            val t = Parser.unescapeEntities(e.text(), false).replace(Regex("\\s+"), " ").trim()
            val inizio = e.attr("t").toIntOrNull() ?: return@mapNotNull null
            if (t.isEmpty()) null else Riga(inizio, e.attr("d").toIntOrNull() ?: 0, t)
        }
    }

    // ── cache ────────────────────────────────────────────────────────

    private fun cartella(ctx: Context) = File(ctx.filesDir, "trascrizioni").apply { mkdirs() }
    private fun file(ctx: Context, id: String) = File(cartella(ctx), "$id.json")

    private fun salva(ctx: Context, t: Trascrizione) {
        val righe = JSONArray()
        t.righe.forEach { righe.put(JSONArray().put(it.inizioMs).put(it.durataMs).put(it.testo)) }
        file(ctx, t.youtubeId).writeText(JSONObject().put("id", t.youtubeId).put("titolo", t.titolo).put("canale", t.canale)
            .put("durata", t.durata).put("lingua", t.lingua).put("automatica", t.automatica).put("tradotta", t.tradotta)
            .put("scaricata", t.scaricata).put("righe", righe).toString())
    }

    fun salvata(ctx: Context, id: String): Trascrizione? = runCatching {
        val o = JSONObject(file(ctx, id).takeIf { it.exists() }?.readText() ?: return null)
        val r = o.getJSONArray("righe")
        Trascrizione(o.getString("id"), o.optString("titolo"), o.optString("canale"), o.optInt("durata"), o.optString("lingua"),
            o.optBoolean("automatica"), o.optBoolean("tradotta"), o.optLong("scaricata"),
            (0 until r.length()).map { r.getJSONArray(it).let { a -> Riga(a.getInt(0), a.getInt(1), a.getString(2)) } })
    }.getOrNull()

    /** Gli id delle trascrizioni gia' sul telefono, le piu' recenti prima. */
    fun salvate(ctx: Context): List<String> = cartella(ctx).listFiles()?.filter { it.name.endsWith(".json") }
        ?.sortedByDescending { it.lastModified() }?.map { it.name.removeSuffix(".json") }.orEmpty()

    // ── la funzione unica ────────────────────────────────────────────

    /**
     * La trascrizione di un video: dalla cache se c'e' gia', altrimenti da YouTube. Lingua preferita [lingua]; se non
     * c'e', i sottotitoli nella lingua del video tradotti da YouTube in [lingua] (se si puo'), oppure quelli che ci sono.
     * Fuori dal filo dell'interfaccia. Lancia [NonDisponibile] se il video non ha sottotitoli.
     */
    fun scarica(ctx: Context, id: String, lingua: String = "it", forza: Boolean = false): Trascrizione {
        if (!forza) salvata(ctx, id)?.takeIf { it.lingua.equals(lingua, true) || lingua.isBlank() }?.let { return it }
        val prove = player(id)
        val info = prove.map { infoDa(it.first) }.firstOrNull { it.titolo.isNotBlank() } ?: Info("", "", 0)
        val tutte = ArrayList<String>()
        var stato: String? = null
        for ((p, _) in prove) {
            val tr = tracceDa(p)
            if (stato == null) stato = p.optJSONObject("playabilityStatus")?.optString("reason")?.ifBlank { null }
            tr.forEach { if (it.lingua !in tutte) tutte.add(it.lingua) }
            if (tr.isEmpty()) continue
            // in ordine: la lingua chiesta scritta a mano, poi automatica, poi tradotta da YouTube, poi quella che c'e'
            val scelte = listOfNotNull(
                tr.firstOrNull { it.lingua.startsWith(lingua, true) && !it.automatica }?.let { it to false },
                tr.firstOrNull { it.lingua.startsWith(lingua, true) }?.let { it to false },
                tr.firstOrNull { it.traducibile && !it.automatica }?.let { it.copy(url = it.url + "&tlang=$lingua") to true },
                tr.firstOrNull { it.traducibile }?.let { it.copy(url = it.url + "&tlang=$lingua") to true },
                tr.first() to false,
            ).distinctBy { it.first.url }
            for ((t, tradotta) in scelte) {
                val righe = runCatching { scaricaTraccia(t.url) }.getOrDefault(emptyList())
                if (righe.isEmpty()) continue
                val fatta = Trascrizione(id, info.titolo, info.canale, info.durata,
                    if (tradotta) lingua else t.lingua, t.automatica, tradotta, System.currentTimeMillis(), righe)
                runCatching { salva(ctx, fatta) }
                return fatta
            }
        }
        throw NonDisponibile(when {
            tutte.isNotEmpty() -> "YouTube non ha dato i sottotitoli di questo video (ci sono in: ${tutte.joinToString(", ")}). Riprova fra un po'."
            stato != null -> "YouTube non mostra questo video: $stato"
            else -> "Questo video non ha sottotitoli né trascrizione automatica su YouTube."
        }, tutte)
    }

    // ── il testo per il file ─────────────────────────────────────────

    /** Le righe accorpate a blocchi di ~[ogniMs] (i sottotitoli automatici vanno a pezzi di 2-3 secondi). */
    fun blocchi(t: Trascrizione, ogniMs: Int = 8000): List<Riga> {
        val fuori = ArrayList<Riga>()
        var inizio = -1; var fine = 0; val testo = StringBuilder()
        for (r in t.righe) {
            if (inizio < 0) inizio = r.inizioMs
            if (testo.isNotEmpty()) testo.append(' ')
            testo.append(r.testo); fine = r.inizioMs + r.durataMs
            if (r.inizioMs - inizio >= ogniMs || r.testo.endsWith(".") && r.inizioMs - inizio >= ogniMs / 2) {
                fuori.add(Riga(inizio, fine - inizio, testo.toString())); inizio = -1; testo.clear()
            }
        }
        if (testo.isNotEmpty()) fuori.add(Riga(inizio, fine - inizio, testo.toString()))
        return fuori
    }

    fun tempo(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
    }

    /** Il file .txt: intestazione (titolo, canale, link, lingua, se automatica) e poi «[mm:ss] testo». */
    fun testo(t: Trascrizione): String {
        val sb = StringBuilder()
        sb.append(t.titolo.ifBlank { "Video YouTube" }).append('\n')
        if (t.canale.isNotBlank()) sb.append("Canale: ").append(t.canale).append('\n')
        sb.append("https://www.youtube.com/watch?v=").append(t.youtubeId).append('\n')
        sb.append("Lingua: ").append(t.lingua)
        if (t.automatica) sb.append(" · sottotitoli generati automaticamente da YouTube")
        if (t.tradotta) sb.append(" · tradotti automaticamente da YouTube")
        sb.append('\n')
        sb.append("Scaricata il ").append(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(t.scaricata)))
            .append(" con ").append(Marca.NOME).append('\n').append('\n')
        blocchi(t).forEach { sb.append('[').append(tempo(it.inizioMs)).append("] ").append(it.testo).append('\n') }
        return sb.toString()
    }

    /** Un nome di file pulito dal titolo. */
    fun nomeFile(t: Trascrizione): String =
        "Trascrizione - " + t.titolo.ifBlank { t.youtubeId }.replace(Regex("[\\\\/:*?\"<>|#]+"), " ").replace(Regex("\\s+"), " ").trim().take(80) + ".txt"
}
