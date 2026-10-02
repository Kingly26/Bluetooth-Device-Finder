# Ritrova (Bluetooth Finder) — contesto per Claude Code

Rispondi sempre in **italiano**. L'utente lavora **solo da smartphone (Google Pixel)**, senza PC:
niente comandi da far eseguire in locale, niente Android Studio. Tutto passa da commit + GitHub Actions.

## Obiettivo
App Android per ritrovare oggetti persi (cuffie, PC, telefoni, qualsiasi dispositivo Bluetooth)
tramite un "radar" basato sull'intensità del segnale. **Solo locale**: nessun server, nessun account
(scelta esplicita dell'utente; ha una VPS ma per ora non va usata).

Richiesta dell'utente: "poter raggiungere tutti i dispositivi che appaiono e quelli a cui sono connesso,
con maggiore precisione possibile".

## Stato attuale
- Codice scritto ma **mai compilato** (nella sessione di origine l'SDK Android non era scaricabile).
  → Primo compito: far passare la build su GitHub Actions e correggere gli errori di compilazione.
- Repo: `Kingly26/Bluetooth-Finder-claude` (privata). Caricata a mano dal telefono: **verificare che
  la struttura sia corretta** (`app/`, `settings.gradle.kts`, ecc. alla radice, non dentro `ritrova/`)
  e che esista `.github/workflows/build.yml` (le cartelle col punto spesso saltano negli upload da mobile).
- Non c'è Gradle wrapper: il workflow installa Gradle 8.14.3 con `gradle/actions/setup-gradle`.
  Se serve, aggiungere il wrapper generandolo nel workflow o in sessione.

## Stack
- Kotlin 2.1.20, Jetpack Compose (BOM 2024.12.01), Material3, AGP 8.9.1
- minSdk 26, targetSdk/compileSdk 35, Java 17
- Package: `it.klab.ritrova`
- APK release firmato con chiave debug (installabile direttamente). Artifact Actions: `Ritrova-apk`.

## File
- `BtScanner.kt` — scansione BLE (LOW_LATENCY, MATCH_MODE_AGGRESSIVE) + discovery classica in loop,
  filtro di Kalman 1D sull'RSSI, stima distanza (log-distance, n=2.5, 1 m = txPower−41 o −60 dBm),
  rilevamento connessi (proxy A2DP/HEADSET + GATT) e associati, storico 30 s, trend ultimi 3 s vs 3 s prima,
  classificazione tipo (BluetoothClass + euristiche sul nome).
- `Sounder.kt` — bip "Geiger" (ToneGenerator) con intervallo che si accorcia avvicinandosi, vibrazione,
  trillo 2,5/3,5 kHz in loop via AudioTrack instradato alle cuffie BT connesse (setPreferredDevice),
  volume media al massimo e ripristinato allo stop.
- `MainActivity.kt` — Compose: schermata permessi/attiva BT, lista (Connessi / Associati / Nelle vicinanze,
  filtri Cuffie/PC/Telefoni, toggle "senza nome"), schermata ricerca (radar "Acqua / Fuochino / Fuoco /
  Ci sei sopra!", distanza stimata, freccia avvicinamento, grafico 30 s, pulsante "Fai suonare", consigli).
  In ricerca su dispositivo solo-BLE la discovery classica viene sospesa per avere più campioni.

## Limiti noti (da non "promettere" all'utente)
- Si trova solo ciò che è acceso e a portata BT (~10 m).
- Cuffie connesse di solito non pubblicizzano BLE → niente RSSI, solo "Fai suonare".
- Molti auricolari trasmettono solo fuori dalla custodia.
- Indirizzi BLE casuali/rotanti per privacy; gli associati sono risolti dal sistema.
- La scansione si ferma quando l'app va in background (onPause).

## Idee per dopo (non fatte)
- Ricordare "ultimo posto/ora in cui l'ho visto" per i dispositivi preferiti (scansione in background + GPS).
- Preferiti/rinomina dispositivi.
- Trovare il telefono stesso richiederebbe un altro dispositivo (es. bot Telegram via VPS): l'utente
  per ora ha scelto di non farlo.

## Modo di lavorare
- Dopo ogni modifica: commit su `main`, controllare l'esito di GitHub Actions, correggere finché è verde.
- Messaggi di commit brevi in italiano.
- Spiegare all'utente come scaricare l'APK: Actions → ultima esecuzione → Artifacts → Ritrova-apk.
