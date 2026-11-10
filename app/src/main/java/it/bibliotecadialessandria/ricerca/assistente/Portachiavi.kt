package it.bibliotecadialessandria.ricerca.assistente

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Il portachiavi dell'assistente (10/11/2026, STUDIO_ASSISTENTE_AI.md §4.3): le chiavi API dell'utente cifrate con
 * una chiave AES-GCM dell'Android Keystore, che non esce mai dal telefono e non si esporta. Nelle preferenze
 * [FILE] resta solo il testo cifrato con il suo IV, in Base64.
 *
 * Perche' non EncryptedSharedPreferences come FroWay: security-crypto e' deprecata, e il Keystore fa lo stesso
 * senza dipendenze (API 23+, l'app parte da 24).
 *
 * Regole: il valore non va MAI nei log; il file e' escluso dal backup (res/xml/backup.xml e estrazione.xml), e
 * comunque, ripristinato su un altro telefono, non si potrebbe decifrare (la chiave del Keystore non viaggia).
 */
object Portachiavi {

    /** Il file delle preferenze con i valori cifrati: lo stesso nome sta nelle regole di backup. */
    const val FILE = "portachiavi_ai"
    private const val ALIAS = "assistente_ai_portachiavi"
    private const val ARCHIVIO = "AndroidKeyStore"
    private const val CIFRARIO = "AES/GCM/NoPadding"

    private fun chiave(): SecretKey {
        val ks = KeyStore.getInstance(ARCHIVIO).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ARCHIVIO)
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gen.generateKey()
    }

    private fun pref(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun metti(ctx: Context, nome: String, valore: String) {
        if (valore.isEmpty()) { togli(ctx, nome); return }
        val c = Cipher.getInstance(CIFRARIO).apply { init(Cipher.ENCRYPT_MODE, chiave()) }
        val cifrato = c.doFinal(valore.toByteArray(Charsets.UTF_8))
        val b64 = { b: ByteArray -> Base64.encodeToString(b, Base64.NO_WRAP) }
        pref(ctx).edit().putString(nome, b64(c.iv) + ":" + b64(cifrato)).apply()
    }

    /** Il valore in chiaro, o null se non c'e' o non si decifra (es. Keystore azzerato): allora va rimesso. */
    fun prendi(ctx: Context, nome: String): String? {
        val salvato = pref(ctx).getString(nome, null) ?: return null
        return runCatching {
            val (iv, dati) = salvato.split(":", limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
            val c = Cipher.getInstance(CIFRARIO).apply { init(Cipher.DECRYPT_MODE, chiave(), GCMParameterSpec(128, iv)) }
            String(c.doFinal(dati), Charsets.UTF_8)
        }.getOrNull()
    }

    fun ce(ctx: Context, nome: String): Boolean = pref(ctx).contains(nome)

    fun togli(ctx: Context, nome: String) { pref(ctx).edit().remove(nome).apply() }
}
