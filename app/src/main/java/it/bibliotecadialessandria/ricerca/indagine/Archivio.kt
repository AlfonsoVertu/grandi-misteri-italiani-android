package it.bibliotecadialessandria.ricerca.indagine

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update

/**
 * I dati dell'indagine (11/11/2026, fase 2b, STUDIO_ASSISTENTE_AI.md §4.5, §6, §7): note, video delle note al secondo,
 * teorie, segnalini e linee della mappa, persone di interesse con la loro cronologia, i collegamenti fra tutto
 * ([Legame]), la memoria dell'assistente e i piani dei compiti lunghi. Tutto solo sul telefono, in Room.
 *
 * `autore` dice chi ha scritto ("utente" | "assistente" | "indagine" per le indagini precaricate); `origine` e' la
 * chiave stabile delle indagini precaricate («david_rossi:n1»), cosi' l'import non duplica.
 * Le tabelle sono piccole (centinaia di righe): l'interfaccia legge anche dal filo principale.
 */

@Entity(tableName = "nota")
data class Nota(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titolo: String, val testo: String, val fonte: String = "",
    val autore: String = "utente", val origine: String = "",
    val creata: Long = System.currentTimeMillis(), val modificata: Long = System.currentTimeMillis(),
)

@Entity(tableName = "nota_video",
    foreignKeys = [ForeignKey(entity = Nota::class, parentColumns = ["id"], childColumns = ["notaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("notaId"), Index("youtubeId")])
data class NotaVideo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notaId: Long, val youtubeId: String,
    /** null = il video intero. */
    val secondi: Int?, val etichetta: String = "",
)

@Entity(tableName = "teoria")
data class Teoria(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titolo: String, val descrizione: String = "",
    val autore: String = "utente", val origine: String = "",
    val creata: Long = System.currentTimeMillis(), val modificata: Long = System.currentTimeMillis(),
)

@Entity(tableName = "nota_teoria", primaryKeys = ["notaId", "teoriaId"],
    foreignKeys = [ForeignKey(entity = Nota::class, parentColumns = ["id"], childColumns = ["notaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Teoria::class, parentColumns = ["id"], childColumns = ["teoriaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("teoriaId")])
data class NotaTeoria(val notaId: Long, val teoriaId: Long)

@Entity(tableName = "segnalino",
    foreignKeys = [ForeignKey(entity = Nota::class, parentColumns = ["id"], childColumns = ["notaId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("notaId"), Index("youtubeId")])
data class Segnalino(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lat: Double, val lon: Double,
    val titolo: String, val testo: String = "",
    val youtubeId: String? = null, val secondi: Int? = null,
    val notaId: Long? = null,
    val autore: String = "utente", val origine: String = "",
    val creato: Long = System.currentTimeMillis(), val modificato: Long = System.currentTimeMillis(),
)

@Entity(tableName = "linea")
data class Linea(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titolo: String, val colore: String = "#C62828",
    val autore: String = "utente", val origine: String = "",
    val creata: Long = System.currentTimeMillis(),
)

@Entity(tableName = "linea_punto", primaryKeys = ["lineaId", "ordine"],
    foreignKeys = [ForeignKey(entity = Linea::class, parentColumns = ["id"], childColumns = ["lineaId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Segnalino::class, parentColumns = ["id"], childColumns = ["segnalinoId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("segnalinoId")])
data class LineaPunto(val lineaId: Long, val ordine: Int, val segnalinoId: Long)

@Entity(tableName = "persona")
data class Persona(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nome: String, val ruolo: String = "", val descrizione: String = "",
    /** La foto: un'immagine dell'archivio Media (id), facoltativa. */
    val fotoId: Long? = null,
    val autore: String = "utente", val origine: String = "",
    val creata: Long = System.currentTimeMillis(), val modificata: Long = System.currentTimeMillis(),
)

@Entity(tableName = "cronologia",
    foreignKeys = [ForeignKey(entity = Persona::class, parentColumns = ["id"], childColumns = ["personaId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("personaId")])
data class VoceCronologia(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val personaId: Long,
    /** Quando: testo libero («9 marzo 2013», «2013-03-06 sera»). */
    val quando: String, val cosa: String,
    /** La fonte: video+minuto (link), pagina web, documento. */
    val fonte: String = "",
    val ordine: Int = 0,
)

/**
 * Un collegamento fra due cose, con un'etichetta: persona → video (al secondo) / segnalino / nota / teoria / media /
 * persona («moglie di»), e teoria → segnalino / media. [verso] = "video" usa [youtubeId] e [secondi] (versoId = 0).
 */
@Entity(tableName = "legame", indices = [Index("da", "daId"), Index("verso", "versoId")])
data class Legame(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val da: String, val daId: Long,
    val verso: String, val versoId: Long = 0,
    val youtubeId: String? = null, val secondi: Int? = null,
    val etichetta: String = "",
)

@Entity(tableName = "memoria")
data class Memoria(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** istruzione | flusso | fatto | preferenza */
    val tipo: String, val titolo: String, val testo: String,
    /** utente | ai */
    val creataDa: String = "utente",
    val creata: Long = System.currentTimeMillis(), val modificata: Long = System.currentTimeMillis(),
)

@Entity(tableName = "piano")
data class Piano(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titolo: String, val obiettivo: String = "",
    val aperto: Boolean = true,
    /** Il taccuino: appunti che l'assistente scrive per se' mentre lavora (risultati intermedi, fonti). */
    val appunti: String = "",
    val esito: String = "",
    val creato: Long = System.currentTimeMillis(), val modificato: Long = System.currentTimeMillis(),
)

@Entity(tableName = "passo",
    foreignKeys = [ForeignKey(entity = Piano::class, parentColumns = ["id"], childColumns = ["pianoId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("pianoId")])
data class Passo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pianoId: Long, val ordine: Int, val testo: String,
    /** da_fare | in_corso | fatto | bloccato */
    val stato: String = "da_fare",
    val esito: String = "",
)

@Dao
interface Indice {
    // note
    @Insert fun metti(n: Nota): Long
    @Update fun aggiorna(n: Nota)
    @Delete fun togli(n: Nota)
    @Query("SELECT * FROM nota ORDER BY modificata DESC") fun note(): List<Nota>
    @Query("SELECT * FROM nota WHERE id = :id") fun nota(id: Long): Nota?
    @Query("SELECT * FROM nota WHERE origine = :origine AND origine != '' LIMIT 1") fun notaDa(origine: String): Nota?
    @Query("SELECT * FROM nota WHERE titolo LIKE :q OR testo LIKE :q OR fonte LIKE :q ORDER BY modificata DESC") fun cercaNote(q: String): List<Nota>

    @Insert fun metti(v: NotaVideo): Long
    @Query("DELETE FROM nota_video WHERE id = :id") fun togliVideo(id: Long)
    @Query("SELECT * FROM nota_video WHERE notaId = :notaId ORDER BY id") fun videoDi(notaId: Long): List<NotaVideo>
    @Query("SELECT * FROM nota_video WHERE youtubeId = :youtubeId") fun noteDelVideo(youtubeId: String): List<NotaVideo>

    // teorie
    @Insert fun metti(t: Teoria): Long
    @Update fun aggiorna(t: Teoria)
    @Delete fun togli(t: Teoria)
    @Query("SELECT * FROM teoria ORDER BY modificata DESC") fun teorie(): List<Teoria>
    @Query("SELECT * FROM teoria WHERE id = :id") fun teoria(id: Long): Teoria?
    @Query("SELECT * FROM teoria WHERE origine = :origine AND origine != '' LIMIT 1") fun teoriaDa(origine: String): Teoria?
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun metti(x: NotaTeoria): Long
    @Query("DELETE FROM nota_teoria WHERE notaId = :notaId AND teoriaId = :teoriaId") fun togliNotaDaTeoria(notaId: Long, teoriaId: Long)
    @Query("SELECT nota.* FROM nota JOIN nota_teoria ON nota.id = nota_teoria.notaId WHERE nota_teoria.teoriaId = :teoriaId ORDER BY nota.creata DESC, nota.id DESC")
    fun noteDellaTeoria(teoriaId: Long): List<Nota>
    @Query("SELECT teoria.* FROM teoria JOIN nota_teoria ON teoria.id = nota_teoria.teoriaId WHERE nota_teoria.notaId = :notaId")
    fun teorieDellaNota(notaId: Long): List<Teoria>

    // mappa
    @Insert fun metti(s: Segnalino): Long
    @Update fun aggiorna(s: Segnalino)
    @Delete fun togli(s: Segnalino)
    @Query("SELECT * FROM segnalino ORDER BY id") fun segnalini(): List<Segnalino>
    @Query("SELECT * FROM segnalino WHERE id = :id") fun segnalino(id: Long): Segnalino?
    @Query("SELECT * FROM segnalino WHERE origine = :origine AND origine != '' LIMIT 1") fun segnalinoDa(origine: String): Segnalino?
    @Query("SELECT * FROM segnalino WHERE notaId = :notaId") fun segnaliniDellaNota(notaId: Long): List<Segnalino>
    @Insert fun metti(l: Linea): Long
    @Update fun aggiorna(l: Linea)
    @Query("DELETE FROM linea WHERE id = :id") fun togliLinea(id: Long)
    @Query("SELECT * FROM linea ORDER BY id") fun linee(): List<Linea>
    @Query("SELECT * FROM linea WHERE id = :id") fun linea(id: Long): Linea?
    @Query("SELECT * FROM linea WHERE origine = :origine AND origine != '' LIMIT 1") fun lineaDa(origine: String): Linea?
    @Insert fun metti(p: LineaPunto)
    @Query("DELETE FROM linea_punto WHERE lineaId = :lineaId") fun togliPunti(lineaId: Long)
    @Query("SELECT * FROM linea_punto WHERE lineaId = :lineaId ORDER BY ordine") fun punti(lineaId: Long): List<LineaPunto>
    @Query("SELECT COUNT(*) FROM linea_punto WHERE lineaId = :lineaId") fun quantiPunti(lineaId: Long): Int

    // persone
    @Insert fun metti(p: Persona): Long
    @Update fun aggiorna(p: Persona)
    @Delete fun togli(p: Persona)
    @Query("SELECT * FROM persona ORDER BY nome COLLATE NOCASE") fun persone(): List<Persona>
    @Query("SELECT * FROM persona WHERE id = :id") fun persona(id: Long): Persona?
    @Query("SELECT * FROM persona WHERE origine = :origine AND origine != '' LIMIT 1") fun personaDa(origine: String): Persona?
    @Query("SELECT * FROM persona WHERE nome LIKE :q OR ruolo LIKE :q OR descrizione LIKE :q ORDER BY nome COLLATE NOCASE") fun cercaPersone(q: String): List<Persona>
    @Insert fun metti(c: VoceCronologia): Long
    @Query("DELETE FROM cronologia WHERE id = :id") fun togliVoce(id: Long)
    @Query("SELECT * FROM cronologia WHERE personaId = :personaId ORDER BY ordine, id") fun cronologia(personaId: Long): List<VoceCronologia>

    // legami
    @Insert fun metti(l: Legame): Long
    @Query("DELETE FROM legame WHERE id = :id") fun togliLegame(id: Long)
    @Query("SELECT * FROM legame WHERE id = :id") fun legame(id: Long): Legame?
    @Query("SELECT * FROM legame WHERE da = :da AND daId = :daId ORDER BY id") fun legamiDa(da: String, daId: Long): List<Legame>
    @Query("SELECT * FROM legame WHERE verso = :verso AND versoId = :versoId ORDER BY id") fun legamiVerso(verso: String, versoId: Long): List<Legame>
    @Query("DELETE FROM legame WHERE (da = :tipo AND daId = :id) OR (verso = :tipo AND versoId = :id)") fun togliLegamiDi(tipo: String, id: Long)

    // memoria
    @Insert fun metti(m: Memoria): Long
    @Update fun aggiorna(m: Memoria)
    @Delete fun togli(m: Memoria)
    @Query("SELECT * FROM memoria ORDER BY modificata DESC") fun memorie(): List<Memoria>
    @Query("SELECT * FROM memoria WHERE id = :id") fun memoria(id: Long): Memoria?

    // piani
    @Insert fun metti(p: Piano): Long
    @Update fun aggiorna(p: Piano)
    @Query("SELECT * FROM piano WHERE id = :id") fun piano(id: Long): Piano?
    @Query("SELECT * FROM piano WHERE aperto = 1 ORDER BY modificato DESC LIMIT 1") fun pianoAperto(): Piano?
    @Query("SELECT * FROM piano ORDER BY modificato DESC LIMIT :quanti") fun piani(quanti: Int): List<Piano>
    @Insert fun metti(p: Passo): Long
    @Update fun aggiorna(p: Passo)
    @Query("SELECT * FROM passo WHERE pianoId = :pianoId ORDER BY ordine") fun passi(pianoId: Long): List<Passo>
}

@Database(entities = [Nota::class, NotaVideo::class, Teoria::class, NotaTeoria::class, Segnalino::class, Linea::class,
    LineaPunto::class, Persona::class, VoceCronologia::class, Legame::class, Memoria::class, Piano::class, Passo::class],
    version = 1, exportSchema = false)
abstract class ArchivioDb : RoomDatabase() {
    abstract fun indice(): Indice
}

/** L'accesso unico ai dati dell'indagine. */
object Archivio {
    @Volatile private var db: ArchivioDb? = null

    fun di(ctx: Context): Indice = (db ?: synchronized(this) {
        db ?: Room.databaseBuilder(ctx.applicationContext, ArchivioDb::class.java, "indagine.db")
            .allowMainThreadQueries()
            .build().also { db = it }
    }).indice()

    fun transazione(ctx: Context, blocco: () -> Unit) { di(ctx); db!!.runInTransaction(blocco) }

    /** Chi ascolta i cambiamenti (le schermate aperte): avvisato dopo ogni scrittura fatta dagli strumenti. */
    @Volatile var cambiato: (() -> Unit)? = null
    fun avvisa() { cambiato?.invoke() }

    // ── cancellazioni con i legami ───────────────────────────────────

    fun cancellaNota(ctx: Context, n: Nota) = transazione(ctx) { di(ctx).togliLegamiDi("nota", n.id); di(ctx).togli(n) }
    fun cancellaTeoria(ctx: Context, t: Teoria) = transazione(ctx) { di(ctx).togliLegamiDi("teoria", t.id); di(ctx).togli(t) }
    fun cancellaPersona(ctx: Context, p: Persona) = transazione(ctx) { di(ctx).togliLegamiDi("persona", p.id); di(ctx).togli(p) }

    /** Un segnalino via: i suoi passaggi nelle linee spariscono (cascata) e una linea con meno di due punti si toglie. */
    fun cancellaSegnalino(ctx: Context, s: Segnalino) = transazione(ctx) {
        val i = di(ctx)
        i.togliLegamiDi("segnalino", s.id)
        i.togli(s)
        i.linee().forEach { l -> if (i.quantiPunti(l.id) < 2) i.togliLinea(l.id) else rinumera(i, l.id) }
    }

    private fun rinumera(i: Indice, lineaId: Long) {
        val p = i.punti(lineaId)
        if (p.withIndex().all { (k, x) -> x.ordine == k }) return
        i.togliPunti(lineaId)
        p.forEachIndexed { k, x -> i.metti(LineaPunto(lineaId, k, x.segnalinoId)) }
    }

    fun nuovaLinea(ctx: Context, titolo: String, punti: List<Long>, autore: String = "utente", origine: String = "", colore: String = "#C62828"): Long {
        var id = 0L
        transazione(ctx) {
            id = di(ctx).metti(Linea(titolo = titolo, colore = colore, autore = autore, origine = origine))
            punti.forEachIndexed { k, s -> di(ctx).metti(LineaPunto(id, k, s)) }
        }
        return id
    }

    fun mettiPunti(ctx: Context, lineaId: Long, punti: List<Long>) = transazione(ctx) {
        di(ctx).togliPunti(lineaId); punti.forEachIndexed { k, s -> di(ctx).metti(LineaPunto(lineaId, k, s)) }
    }

    /** I segnalini di una teoria: quelli delle sue note e quelli collegati alla teoria direttamente. */
    fun segnaliniDellaTeoria(ctx: Context, teoriaId: Long): List<Segnalino> {
        val i = di(ctx)
        val note = i.noteDellaTeoria(teoriaId).map { it.id }.toSet()
        val diretti = i.legamiDa("teoria", teoriaId).filter { it.verso == "segnalino" }.map { it.versoId }.toSet()
        val diPersone = i.legamiVerso("teoria", teoriaId).filter { it.da == "persona" }.flatMap { l ->
            i.legamiDa("persona", l.daId).filter { it.verso == "segnalino" }.map { it.versoId } }.toSet()
        return i.segnalini().filter { (it.notaId != null && it.notaId in note) || it.id in diretti || it.id in diPersone }
    }

    /** Le persone di una teoria (collegate alla teoria o a una sua nota). */
    fun personeDellaTeoria(ctx: Context, teoriaId: Long): List<Persona> {
        val i = di(ctx)
        val note = i.noteDellaTeoria(teoriaId).map { it.id }.toSet()
        val ids = (i.legamiVerso("teoria", teoriaId).filter { it.da == "persona" }.map { it.daId } +
            note.flatMap { n -> i.legamiVerso("nota", n).filter { it.da == "persona" }.map { it.daId } }).toSet()
        return ids.mapNotNull { i.persona(it) }.sortedBy { it.nome.lowercase() }
    }
}
