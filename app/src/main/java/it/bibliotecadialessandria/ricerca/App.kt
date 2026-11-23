package it.bibliotecadialessandria.ricerca

import android.app.Application

/** (14/11/2026) All'avvio: la lingua dell'interfaccia (vedi [Lingua] e Marca.LINGUA) e la traduzione di ogni schermata. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Lingua.carica(this)
        Lingua.osserva(this)
    }
}
