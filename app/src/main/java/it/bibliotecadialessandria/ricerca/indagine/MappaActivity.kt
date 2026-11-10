package it.bibliotecadialessandria.ricerca.indagine

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import it.bibliotecadialessandria.ricerca.R
import it.bibliotecadialessandria.ricerca.strumenti.AiutiIndagine
import it.bibliotecadialessandria.ricerca.strumenti.mmss
import it.bibliotecadialessandria.ricerca.strumenti.secondiDa
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * «Mappa» (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §4.10): OpenStreetMap con osmdroid. Segnalini (tocco lungo =
 * nuovo segnalino), linee fra segnalini («Nuova linea»: tocchi i segnalini in ordine, poi «Fine»), scheda del segnalino
 * con nota, video e persone collegate, filtro per teoria, ricerca di un luogo con Nominatim.
 *
 * POLICY DELLE TESSERE (operations.osmfoundation.org/policies/tiles): User-Agent con il nome dell'app ([Luoghi.ua]),
 * la cache su disco di osmdroid (nella cartella dell'app), nessuno scaricamento in blocco per l'uso offline,
 * l'attribuzione «© OpenStreetMap contributors» sempre visibile.
 *
 * Extra: "teoria" (filtro), "segnalino" (centra e apre la scheda), "nota" (il prossimo segnalino si collega a quella nota).
 */
class MappaActivity : Schermata() {

    override val titolo = "Mappa"
    override val sottotitolo = "Tieni premuto per mettere un segnalino"
    override fun bottoniTesta() = listOf<Pair<String, () -> Unit>>("Cerca" to { cercaLuogo() }, "Opzioni" to { opzioni() })

    private lateinit var mappa: MapView
    private lateinit var barra: TextView
    private var teoria: Long = 0
    private var perNota: Long = 0
    /** La linea in costruzione: gli id dei segnalini toccati, in ordine (null = non si sta tracciando). */
    private var inCostruzione: MutableList<Long>? = null
    private var centrato = false

    override fun onCreate(stato: Bundle?) {
        Configuration.getInstance().apply {
            userAgentValue = Luoghi.ua(this@MappaActivity)
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid_tessere")
            // la cache non deve crescere senza limiti (niente uso «offline» in blocco)
            tileFileSystemCacheMaxBytes = 60L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 45L * 1024 * 1024
        }
        teoria = intent.getLongExtra("teoria", 0)
        perNota = intent.getLongExtra("nota", 0)
        super.onCreate(stato)
        (lista.parent as View).visibility = View.GONE
    }

    override fun sopraLaLista(radice: LinearLayout) {
        barra = TextView(this).apply { textSize = 13.5f; setTextColor(colore(R.color.su_oro)); setBackgroundColor(colore(R.color.oro))
            setPadding(dp(14), dp(8), dp(14), dp(8)); visibility = View.GONE; setOnClickListener { finisciLinea() } }
        radice.addView(barra, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        mappa = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            minZoomLevel = 3.0; maxZoomLevel = 19.0
            controller.setZoom(6.0); controller.setCenter(GeoPoint(42.3, 12.6))
            isTilesScaledToDpi = true
        }
        radice.addView(mappa, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
    }

    override fun onResume() { super.onResume(); mappa.onResume() }
    override fun onPause() { mappa.onPause(); super.onPause() }
    override fun onDestroy() { mappa.onDetach(); super.onDestroy() }

    override fun disegna() {
        if (!::mappa.isInitialized) return
        val i = Archivio.di(this)
        mappa.overlays.clear()
        mappa.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean { if (p != null && inCostruzione == null) nuovoSegnalino(p.latitude, p.longitude); return true }
        }))
        val segnalini = if (teoria > 0) Archivio.segnaliniDellaTeoria(this, teoria) else i.segnalini()
        val visibili = segnalini.map { it.id }.toSet()
        // le linee: solo i tratti fra segnalini visibili
        i.linee().forEach { l ->
            val punti = i.punti(l.id).filter { it.segnalinoId in visibili }.mapNotNull { i.segnalino(it.segnalinoId) }
            if (punti.size < 2) return@forEach
            mappa.overlays.add(Polyline(mappa).apply {
                setPoints(punti.map { GeoPoint(it.lat, it.lon) })
                outlinePaint.color = runCatching { Color.parseColor(l.colore) }.getOrDefault(Color.RED)
                outlinePaint.strokeWidth = dp(4).toFloat()
                title = l.titolo
                setOnClickListener { _, _, _ -> schedaLinea(l.id); true }
            })
        }
        val scelti = inCostruzione.orEmpty()
        segnalini.forEach { s ->
            mappa.overlays.add(Marker(mappa).apply {
                position = GeoPoint(s.lat, s.lon)
                title = s.titolo
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = getDrawable(R.drawable.ic_segnalino)
                if (s.id in scelti) alpha = 0.55f
                setOnMarkerClickListener { _, _ ->
                    val c = inCostruzione
                    if (c != null) { c.add(s.id); aggiornaBarra(); disegna() } else scheda(s.id)
                    true
                }
            })
        }
        mappa.overlays.add(CopyrightOverlay(this))
        mappa.invalidate()
        val filtro = if (teoria > 0) i.teoria(teoria)?.titolo else null
        barra.visibility = if (filtro != null || inCostruzione != null || perNota > 0) View.VISIBLE else View.GONE
        if (inCostruzione == null) barra.text = when {
            perNota > 0 -> "Tieni premuto sul punto della nota «${i.nota(perNota)?.titolo ?: ""}»"
            filtro != null -> "Teoria: $filtro (${segnalini.size} luoghi) · tocca qui per vederli tutti"
            else -> ""
        }
        if (!centrato) {
            centrato = true
            val focus = intent.getLongExtra("segnalino", 0).takeIf { it > 0 }?.let { i.segnalino(it) }
            when {
                focus != null -> { mappa.controller.setZoom(16.0); mappa.controller.setCenter(GeoPoint(focus.lat, focus.lon)); mappa.post { scheda(focus.id) } }
                segnalini.size == 1 -> { mappa.controller.setZoom(15.0); mappa.controller.setCenter(GeoPoint(segnalini[0].lat, segnalini[0].lon)) }
                segnalini.size > 1 -> mappa.post {
                    runCatching { mappa.zoomToBoundingBox(BoundingBox.fromGeoPoints(segnalini.map { GeoPoint(it.lat, it.lon) }).increaseByScale(1.4f), false) }
                    if (mappa.zoomLevelDouble > 16) mappa.controller.setZoom(16.0)
                }
            }
        }
        if (filtro != null && inCostruzione == null && perNota == 0L) barra.setOnClickListener { teoria = 0; centrato = true; disegna() }
        else barra.setOnClickListener { finisciLinea() }
    }

    // ── segnalini ────────────────────────────────────────────────────

    private fun nuovoSegnalino(lat: Double, lon: Double, titolo: String = "") {
        val i = Archivio.di(this)
        val nota = if (perNota > 0) i.nota(perNota) else null
        chiedi("Nuovo segnalino", listOf(Triple("Titolo", titolo.ifBlank { nota?.titolo.orEmpty() }, 1), Triple("Cosa è successo qui (con la fonte)", "", 3),
            Triple("Video (link o id, facoltativo)", "", 1), Triple("Minuto nel video (es. 12:30)", "", 1)),
            spiega = "%.5f, %.5f".format(java.util.Locale.ROOT, lat, lon) + (nota?.let { "\nCollegato alla nota «${it.titolo}»" } ?: "")) { v ->
            if (v[0].isBlank()) { avvisa("Serve un titolo."); return@chiedi }
            val vid = v[2].ifBlank { null }?.let { AiutiIndagine.idDi(it) ?: return@chiedi avvisa("Il video non è un link di YouTube.") }
            val sec = if (vid != null) v[3].ifBlank { null }?.let { secondiDa(it) } else null
            val id = i.metti(Segnalino(lat = lat, lon = lon, titolo = v[0], testo = v[1], youtubeId = vid, secondi = sec, notaId = nota?.id))
            if (teoria > 0) i.metti(Legame(da = "teoria", daId = teoria, verso = "segnalino", versoId = id))
            perNota = 0
            disegna()
        }
    }

    private fun scheda(id: Long) {
        val i = Archivio.di(this)
        val s = i.segnalino(id) ?: return
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }
        var finestra: AlertDialog? = null
        if (s.testo.isNotBlank()) box.addView(nota(s.testo).apply { textSize = 15f; setTextColor(colore(R.color.testo)); setTextIsSelectable(true) }, sotto(8))
        box.addView(nota("%.5f, %.5f".format(java.util.Locale.ROOT, s.lat, s.lon) + (segnoAutore(s.autore)?.text?.let { " · $it" } ?: "")).apply { textSize = 11f }, sotto(6))
        if (s.youtubeId != null) box.addView(rigaAzione(testoVideo(s.youtubeId, s.secondi), { apriVideo(s.youtubeId, s.secondi) }))
        s.notaId?.let { i.nota(it) }?.let { n -> box.addView(rigaAzione("✎  Nota: ${n.titolo}" + if (n.fonte.isNotBlank()) "\n     ${n.fonte}" else "",
            { finestra?.dismiss(); vai(this, NotaActivity::class.java, n.id) })) }
        i.legamiVerso("segnalino", s.id).filter { it.da == "persona" }.mapNotNull { i.persona(it.daId) }.forEach { p ->
            box.addView(rigaAzione("👤  ${p.nome}" + if (p.ruolo.isNotBlank()) " · ${p.ruolo}" else "", { finestra?.dismiss(); vai(this, PersonaActivity::class.java, p.id) })) }
        i.linee().filter { l -> i.punti(l.id).any { it.segnalinoId == s.id } }.forEach { l -> box.addView(rigaAzione("〰  Linea: ${l.titolo}", { finestra?.dismiss(); schedaLinea(l.id) })) }
        finestra = AlertDialog.Builder(this).setTitle(s.titolo).setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Cancella") { _, _ -> conferma("Cancellare «${s.titolo}»?", "Il segnalino sparisce dalla mappa, con i suoi passaggi nelle linee. Non si torna indietro.") {
                Archivio.cancellaSegnalino(this, s); disegna() } }
            .setNegativeButton("Modifica") { _, _ -> modifica(s) }.show()
    }

    private fun modifica(s: Segnalino) {
        chiedi("Modifica segnalino", listOf(Triple("Titolo", s.titolo, 1), Triple("Testo", s.testo, 3),
            Triple("Video (link o id)", s.youtubeId.orEmpty(), 1), Triple("Minuto", s.secondi?.let { mmss(it) }.orEmpty(), 1))) { v ->
            val vid = v[2].ifBlank { null }?.let { AiutiIndagine.idDi(it) }
            Archivio.di(this).aggiorna(s.copy(titolo = v[0].ifBlank { s.titolo }, testo = v[1], youtubeId = vid,
                secondi = if (vid != null) v[3].ifBlank { null }?.let { secondiDa(it) } else null,
                modificato = System.currentTimeMillis()))
            disegna()
        }
    }

    // ── linee ────────────────────────────────────────────────────────

    private fun aggiornaBarra() {
        val c = inCostruzione ?: return
        val i = Archivio.di(this)
        barra.visibility = View.VISIBLE
        barra.text = "Nuova linea: tocca i segnalini in ordine (${c.size}: " + c.mapNotNull { i.segnalino(it)?.titolo }.joinToString(" → ") +
            ") · tocca qui per finire"
    }

    private fun finisciLinea() {
        val c = inCostruzione ?: return
        inCostruzione = null
        if (c.size < 2) { avvisa("Una linea vuole almeno due segnalini."); disegna(); return }
        chiedi("Nome della linea", listOf(Triple("Es. «Il percorso del corteo»", "", 1))) { v ->
            Archivio.nuovaLinea(this, v[0].ifBlank { "Linea" }, c); disegna()
        }
        disegna()
    }

    private fun schedaLinea(id: Long) {
        val i = Archivio.di(this); val l = i.linea(id) ?: return
        val punti = i.punti(id).mapNotNull { i.segnalino(it.segnalinoId)?.titolo }
        AlertDialog.Builder(this).setTitle("〰  ${l.titolo}").setMessage(punti.joinToString("\n→ "))
            .setPositiveButton("Chiudi", null)
            .setNeutralButton("Cancella") { _, _ -> conferma("Cancellare la linea «${l.titolo}»?", "I segnalini restano.") { i.togliLinea(id); disegna() } }
            .setNegativeButton("Rinomina") { _, _ -> chiedi("Rinomina", listOf(Triple("Nome", l.titolo, 1))) { v -> i.aggiorna(l.copy(titolo = v[0].ifBlank { l.titolo })); disegna() } }
            .show()
    }

    // ── opzioni ──────────────────────────────────────────────────────

    private fun opzioni() {
        val voci = arrayOf("Nuova linea fra segnalini", "Filtra per teoria", "Mostra tutti i segnalini")
        AlertDialog.Builder(this).setTitle("Mappa").setItems(voci) { _, k ->
            when (k) {
                0 -> { inCostruzione = mutableListOf(); aggiornaBarra(); disegna() }
                1 -> scegli("Quale teoria?", Archivio.di(this).teorie().map { it.id to it.titolo }, "Non ci sono teorie.") { t -> teoria = t; centrato = false; disegna() }
                else -> { teoria = 0; centrato = false; disegna() }
            }
        }.show()
    }

    /** Cerca un luogo con Nominatim (una richiesta, su tocco della persona) e propone di metterci un segnalino. */
    private fun cercaLuogo() {
        chiedi("Cerca un luogo", listOf(Triple("Es. piazza Fontana, Milano", "", 1)), "Cerca") { v ->
            if (v[0].isBlank()) return@chiedi
            Thread {
                val r = runCatching { Luoghi.cerca(this, v[0]) }
                runOnUiThread {
                    r.onFailure { avvisa(it.message ?: "Ricerca non riuscita.") }.onSuccess { l ->
                        if (l.isEmpty()) { avvisa("Nessun luogo trovato."); return@onSuccess }
                        AlertDialog.Builder(this).setTitle("Risultati (© OpenStreetMap)").setItems(l.map { it.nome }.toTypedArray()) { _, k ->
                            mappa.controller.setZoom(16.0); mappa.controller.setCenter(GeoPoint(l[k].lat, l[k].lon))
                            AlertDialog.Builder(this).setMessage("Mettere un segnalino qui?").setPositiveButton("Sì") { _, _ ->
                                nuovoSegnalino(l[k].lat, l[k].lon, l[k].nome.substringBefore(',')) }.setNegativeButton("No", null).show()
                        }.show()
                    }
                }
            }.start()
        }
    }
}
