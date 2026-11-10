package it.bibliotecadialessandria.ricerca.assistente

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import it.bibliotecadialessandria.ricerca.R

/**
 * I mattoncini dell'interfaccia delle schermate dell'assistente (10/11/2026): gli stessi di RicercaActivity (card,
 * bottoni a pillola oro, note tenui), cosi' le schermate nuove hanno lo stesso aspetto, chiaro o scuro.
 */
open class Aspetto : Activity() {

    /** Il tema scuro o chiaro scelto nell'app (stessa preferenza di RicercaActivity). */
    override fun attachBaseContext(base: Context) {
        val conf = Configuration(base.resources.configuration)
        val scuro = base.getSharedPreferences("aspetto", Context.MODE_PRIVATE).getBoolean("scuro", true)
        conf.uiMode = (conf.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            (if (scuro) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        super.attachBaseContext(base.createConfigurationContext(conf))
    }

    fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun colore(id: Int) = ContextCompat.getColor(this, id)
    fun sotto(m: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(m) }
    fun sopra(m: Int) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(m) }

    fun sfondo(pieno: Int, bordo: Int, raggio: Int = 14) = GradientDrawable().apply {
        setColor(pieno); cornerRadius = dp(raggio).toFloat(); setStroke(dp(1), bordo)
    }

    fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = sfondo(colore(R.color.card), colore(R.color.bordo))
        setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    fun etichetta(t: String) = TextView(this).apply {
        text = t.uppercase(); textSize = 12f; letterSpacing = 0.12f
        setTypeface(Typeface.SANS_SERIF, Typeface.BOLD)
        setTextColor(colore(R.color.oro))
    }

    fun titoletto(t: String) = TextView(this).apply {
        text = t; textSize = 15f
        setTypeface(Typeface.SERIF, Typeface.BOLD)
        setTextColor(colore(R.color.testo))
    }

    fun nota(t: String) = TextView(this).apply {
        text = t; textSize = 13f; setTextColor(colore(R.color.tenue))
        setPadding(0, dp(2), 0, 0)
    }

    fun campo(suggerimento: String, valore: String, righe: Int = 1) = EditText(this).apply {
        hint = suggerimento; setText(valore); textSize = 16f
        if (righe <= 1) { isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT }
        else { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = righe; maxLines = righe * 3 }
        setTextColor(colore(R.color.testo)); setHintTextColor(colore(R.color.tenue))
        background = sfondo(colore(R.color.fondo), colore(R.color.bordo), 10)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun bottone(t: String, pieno: Boolean = false, vai: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 15f; stateListAnimator = null
        setTextColor(colore(if (pieno) R.color.su_oro else R.color.testo))
        background = if (pieno) sfondo(colore(R.color.oro), colore(R.color.oro), 10)
                     else sfondo(colore(R.color.card), colore(R.color.oro), 10)
        setOnClickListener { vai() }
    }

    fun tinta() = ColorStateList.valueOf(colore(R.color.oro))
}
