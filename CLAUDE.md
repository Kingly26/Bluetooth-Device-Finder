# Bluetooth-Device-Finder (sul telefono: BT Finder) — contesto per Claude Code

Rispondi sempre in **italiano**.

## Obiettivo
App Android per trovare oggetti persi (cuffie, PC, telefoni, qualsiasi dispositivo Bluetooth)
tramite l'intensità del segnale, con stima della direzione. **Solo locale**: nessun server, nessun account
(scelta esplicita dell'utente; ha una VPS ma per ora non va usata).

## Stato attuale
- Repo: `Kingly26/Bluetooth-Device-Finder` (pubblica), licenza MIT.
- La build passa su GitHub Actions (`.github/workflows/build.yml`, push su `main` e pull request).
- Non c'è Gradle wrapper: il workflow installa Gradle 8.14.3 con `gradle/actions/setup-gradle`.
- L'APK è firmato con la chiave debug della macchina che compila: le build di Actions e quelle locali
  hanno firme diverse e non si aggiornano una sopra l'altra (serve disinstallare). Chiave fissa: da fare.

## Stack
- Kotlin 2.1.20, Jetpack Compose (BOM 2024.12.01), Material3, AGP 8.9.1
- minSdk 26, targetSdk/compileSdk 35, Java 17
- Package e applicationId: `io.github.kingly26.btfinder`
- Artifact Actions: `Bluetooth-Device-Finder-apk`.

## File (`app/src/main/java/io/github/kingly26/btfinder/`)
- `BtScanner.kt` — scansione BLE (LOW_LATENCY) + discovery classica in loop, filtro di Kalman 1D sull'RSSI,
  stima distanza (log-distance, n=2.5), connessi (proxy A2DP/HEADSET + GATT) e associati, lettura RSSI
  degli associati via collegamento GATT, classificazione tipo, modalità demo con dispositivi finti
  (solo emulatore).
- `Direction.kt` — bussola (sensore di rotazione) e `DirectionFinder`: abbina l'RSSI alla direzione del
  telefono in 24 settori e stima da che parte il segnale è più forte.
- `Sounder.kt` — bip di avvicinamento (ToneGenerator su STREAM_MUSIC), vibrazione, trillo instradato
  alle cuffie BT connesse.
- `MainActivity.kt` — Compose: permessi, home con selettore Elenco/Radar (radar generale a disco
  inclinato con pallini numerati), ricerca singola con cono direzionale.
- Testi: `res/values/strings.xml` (inglese, predefinito) e `res/values-it/strings.xml` (italiano).
  Nessun testo visibile all'utente va scritto nel codice Kotlin.

## Limiti noti (da non "promettere" all'utente)
- Si trova solo ciò che è acceso e a portata BT (~10 m).
- Direzione e distanza sono stime: muri e riflessi le spostano.
- Cuffie connesse di solito non pubblicizzano BLE → niente RSSI, solo "Fai suonare".
- Molti auricolari trasmettono solo fuori dalla custodia.
- Su Android 12+ servono permesso di posizione e posizione accesa, altrimenti la scansione è vuota.
- La scansione si ferma quando l'app va in background (onPause).

## Idee per dopo (non fatte)
- Ricordare "ultimo posto/ora in cui l'ho visto" per i dispositivi preferiti.
- Preferiti/rinomina dispositivi.
- Chiave di firma fissa, Release su GitHub.

## Modo di lavorare
- Sviluppo dal PC Windows dell'utente: strumenti in `C:\Users\Kingly\android-dev` (JDK 17, Gradle,
  Android SDK, emulatore `btfinder`). `dev.ps1` compila e installa sull'emulatore.
- Verificare le modifiche all'interfaccia con una schermata dell'emulatore prima di proporle.
- Commit su `main` con messaggi brevi in italiano; controllare l'esito di GitHub Actions.
- APK per l'utente: Actions → ultima esecuzione → Artifacts → Bluetooth-Device-Finder-apk.
