package it.bibliotecadialessandria.ricerca.assistente

import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * (10/11/2026) Copiato alla lettera da FroWay (Duplex.kt), cambiati solo pacchetto e nomi dei fili.
 *
 * IL DUPLEX DELLE AI (07/10/2026, chiesto da Alfonso: «le ai analizzano i messaggi uno alla volta, non hanno una
 * pila»). Lo stesso schema della Coda dell'app SoWay (Coda.kt, ChatBollaActivity) e del bot WhatsApp con il worker.
 *
 * PRIMA. Scrivere a un'AI mentre rispondeva dava «sta ancora rispondendo» e il messaggio si perdeva (il campo era
 * gia' vuoto). Al campanello la domanda nuova si ignorava e arrivava una seconda risposta separata, se arrivava.
 *
 * ADESSO, per ogni conversazione (una [chiave]):
 * 1. chi riceve non aspetta: il messaggio si salva subito e [bussa] torna;
 * 2. un TURNO che sale a ogni messaggio;
 * 3. una FINESTRA di [FINESTRA] ms: ogni messaggio nuovo la fa ripartire, cosi' chi scrive tre righe di fila manda
 *    un pensiero solo;
 * 4. UN SOLO LAVORO per conversazione: se ce n'e' gia' uno, il messaggio nuovo lo rende superato;
 * 5. il lavoro conosce il suo turno e chiede [annullato] prima di ogni giro del modello, prima di ogni strumento e
 *    mentre la risposta arriva a pezzi: se il turno e' cambiato si ferma, non salva niente, e si riparte con tutto
 *    quello che e' arrivato (l'accorpamento lo fa chi lavora, leggendo la conversazione: vedi Assistenti.genera);
 * 6. il segno «al lavoro» si toglie sempre (finally): un segno rimasto su bloccava la coda per sempre (bug di SoWay).
 */
object Duplex {

    private const val TAG = "Assistente"

    /** L'attesa dopo l'ultimo messaggio prima di rispondere (la stessa di SoWay). */
    @Volatile var FINESTRA = 900L

    private class Stato {
        var turno = 0
        /** L'ultimo turno a cui si e' risposto davvero: quello stesso non si rifa' (la finestra e la fine di un
         *  lavoro superato possono chiamare [parti] tutte e due: trovato dalle prove, una risposta doppia). */
        var fatto = 0
        var alLavoro = false
        var lavoro: ((Int, () -> Boolean) -> Unit)? = null
        var partenza: ScheduledFuture<*>? = null
    }

    private val stati = ConcurrentHashMap<String, Stato>()

    private val orologio = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "assistente-duplex").apply { isDaemon = true }
    }

    /**
     * E' arrivato qualcosa per la conversazione [chiave]: fra [FINESTRA] ms (se non arriva altro) parte [lavoro], su
     * un filo suo. Il lavoro riceve il suo turno e una funzione che dice se nel frattempo e' arrivato altro.
     */
    fun bussa(chiave: String, lavoro: (mio: Int, annullato: () -> Boolean) -> Unit) {
        val s = stati.getOrPut(chiave) { Stato() }
        synchronized(s) {
            s.turno++
            s.lavoro = lavoro
            s.partenza?.cancel(false)
            s.partenza = orologio.schedule({ parti(chiave) }, FINESTRA, TimeUnit.MILLISECONDS)
        }
    }

    private fun parti(chiave: String) {
        val s = stati[chiave] ?: return
        val mio: Int
        val lavoro: (Int, () -> Boolean) -> Unit
        synchronized(s) {
            // C'e' gia' un lavoro: quando finisce, vede che il turno e' cambiato e ripassa di qui.
            if (s.alLavoro || s.fatto == s.turno) return
            lavoro = s.lavoro ?: return
            s.alLavoro = true
            mio = s.turno
        }
        Thread({
            try {
                lavoro(mio) { synchronized(s) { s.turno != mio } }
            } catch (e: Throwable) {
                Log.w(TAG, "duplex: il lavoro di una conversazione si e' fermato", e)
            } finally {
                val ancora = synchronized(s) {
                    s.alLavoro = false
                    if (s.turno == mio) s.fatto = mio
                    s.turno != mio
                }
                // Arrivato altro mentre lavorava: si riparte subito (la finestra e' gia' passata o ripassera').
                if (ancora) orologio.execute { parti(chiave) }
            }
        }, "assistente-duplex-lavoro").apply { isDaemon = true; start() }
    }

    /** Sta lavorando, o aspetta la fine della finestra per farlo. */
    fun occupato(chiave: String): Boolean = stati[chiave]?.let { s ->
        synchronized(s) { s.alLavoro || s.partenza?.isDone == false }
    } ?: false

    /** Il turno di adesso (per le prove e per chi vuole sapere se e' arrivato altro). */
    fun turno(chiave: String): Int = stati[chiave]?.let { synchronized(it) { it.turno } } ?: 0
}
