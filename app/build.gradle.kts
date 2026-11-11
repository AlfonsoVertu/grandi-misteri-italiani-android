import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * La firma sta in local.properties (che git ignora): percorso del keystore,
 * alias e password. Senza, si compila non firmato e l'APK non si installa:
 * meglio accorgersene subito che firmare con una chiave di comodo.
 */
val segreti = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val timbro = segreti.getProperty("firma.keystore")?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "it.bibliotecadialessandria.ricerca"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        targetSdk = 35
    }

    // (10/11/2026) un'app per canale: stesso codice (src/main), nome, link, colori, icona e catalogo
    // nella cartella del canale (src/<canale>). Firma comune: le app della famiglia si riconoscono.
    flavorDimensions += "canale"
    productFlavors {
        create("misteri") {
            dimension = "canale"
            applicationId = "eu.workingwithweb.canali.misteri"
            versionCode = 7
            versionName = "2.42.42.42"
        }
    }

    signingConfigs {
        if (timbro != null) {
            create("rilascio") {
                storeFile = timbro
                storePassword = segreti.getProperty("firma.password")
                keyAlias = segreti.getProperty("firma.alias")
                keyPassword = segreti.getProperty("firma.password")
            }
        }
    }

    buildTypes {
        release {
            if (timbro != null) signingConfig = signingConfigs.getByName("rilascio")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Il sito non ha un'API: si legge la sua pagina di ricerca. Licenza MIT.
    implementation("org.jsoup:jsoup:1.18.3")
    // (11/11/2026, fase 2b) i dati dell'indagine (note, teorie, segnalini, persone, memoria, piani) in Room. Licenza Apache 2.0.
    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    // la mappa: OpenStreetMap con osmdroid (Apache 2.0, nessuna chiave)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}
