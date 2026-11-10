# Grandi misteri italiani (Unofficial) · app Android

App **non ufficiale** che raccoglie i video sui grandi misteri d'Italia (delitti, stragi, mafie, logge, scandali)
pubblicati da sette canali YouTube: [Vox Investigazioni](https://www.youtube.com/@VoxInvestigazioni),
[Starting Finance](https://www.youtube.com/@StartingFinance), [Nova Lectio](https://www.youtube.com/@NovaLectio),
[Il Punto Economico](https://www.youtube.com/@ilpuntoeconomico), [Pulp Podcast](https://www.youtube.com/@Pulp_podcast),
[Roberto Saviano](https://www.youtube.com/@robertosavianoofficial569) e [Fanpage.it](https://www.youtube.com/@FanpageIt).
Non è affiliata a nessuno dei canali: i video sono loro.

- Ricerca fra circa 1.300 video sui casi, sul telefono e senza rete: parole (nel titolo, nella descrizione, nei tag), caso, tema, canale, tipo.
- I casi e i temi sono assegnati in automatico dai titoli e possono sbagliare.
- Novità e avvisi dei video nuovi dei canali, solo quando parlano di un caso.
- Preferiti e playlist, salvati solo sul telefono. Niente account, niente pubblicità, niente tracciamento.
- «I miei canali»: altri canali YouTube da seguire, condivisi con le altre app della stessa famiglia.
- Ricerca anche dentro il parlato dei video (trascrizioni di YouTube scaricate sul telefono, con il pulsante «Scarica trascrizione»).
- Assistente AI facoltativo, con la TUA chiave del fornitore (OpenRouter, OpenAI, Gemini, Anthropic, Groq e altri compatibili OpenAI): cerca nel catalogo, nelle trascrizioni e sul web, legge pagine, guarda foto, video e PDF, ascolta i vocali e risponde anche a voce; archivio Media dell'indagine. La chiave resta cifrata sul telefono (Android Keystore) e va solo al fornitore scelto.

## Cosa fa in rete

L'app parla solo con YouTube (feed RSS pubblici dei canali, miniature e sottotitoli), con il fornitore AI scelto dall'utente (solo se attiva l'assistente), con i siti che l'assistente cerca o legge (DuckDuckGo, Wikipedia, pagine indicate) e una volta ogni 6 ore legge
`https://mondo.workingwithweb.eu/grandi-misteri-italiani/versione.json` per proporre gli aggiornamenti. Il catalogo dei
video è dentro l'app (`app/src/misteri/assets/catalogo.json`); i video nuovi li aggiunge leggendo il feed di YouTube.

## Scaricare l'app

https://mondo.workingwithweb.eu/grandi-misteri-italiani-unofficial-app/ · controllo VirusTotal della 4.42.42:
[0 su 67](https://www.virustotal.com/gui/file/07692cc1c702080aa065155541b04e905d83aa49f9df1391de1ce6cb523844bf)

## Licenza

Codice pubblico per trasparenza, **tutti i diritti riservati** (non è open source): vedi [LICENSE](LICENSE).
Sviluppata da Working With Web.
