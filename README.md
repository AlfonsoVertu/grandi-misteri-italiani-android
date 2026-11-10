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

## Cosa fa in rete

L'app parla solo con YouTube (feed RSS pubblici dei canali e miniature) e una volta ogni 6 ore legge
`https://mondo.workingwithweb.eu/grandi-misteri-italiani/versione.json` per proporre gli aggiornamenti. Il catalogo dei
video è dentro l'app (`app/src/misteri/assets/catalogo.json`); i video nuovi li aggiunge leggendo il feed di YouTube.

## Scaricare l'app

https://mondo.workingwithweb.eu/grandi-misteri-italiani-unofficial-app/

## Licenza

Codice pubblico per trasparenza, **tutti i diritti riservati** (non è open source): vedi [LICENSE](LICENSE).
Sviluppata da Working With Web.
