# Ritrova

Radar Bluetooth per ritrovare cuffie, PC, telefoni e qualsiasi dispositivo che trasmette in zona.
Funziona tutto in locale sul telefono: nessun server, nessun account.

## Cosa fa
- **Lista** di tutto ciò che il telefono sente: connessi ora, associati, nelle vicinanze (BLE + Bluetooth classico), con filtri Cuffie / PC / Telefoni.
- **Modalità ricerca** (tocca un dispositivo): radar "Acqua → Fuochino → Fuoco → Ci sei sopra!", distanza stimata, freccia avvicinamento/allontanamento, grafico degli ultimi 30 s.
- **Bip tipo contatore Geiger**: più ti avvicini più accelerano; vibra quando sei molto vicino.
- **Fai suonare** le cuffie *connesse*: manda un trillo acuto a volume massimo direttamente nelle cuffie.
- **Precisione**: RSSI passato in un filtro di Kalman, scansione BLE a bassa latenza, e in ricerca BLE la discovery classica viene sospesa per avere più campioni al secondo.

## Limiti (fisica, non bug)
- Si trova solo ciò che è **acceso e a portata Bluetooth** (≈10 m in casa, meno attraverso i muri).
- Molte cuffie trasmettono solo **fuori dalla custodia** o con la custodia aperta; se sono scariche non c'è nulla da sentire.
- Le cuffie **connesse** di norma non trasmettono pubblicità BLE, quindi non hanno un valore di segnale: per quelle usa "Fai suonare".
- La distanza in metri è una **stima**: corpi, muri e orientamento la falsano anche di parecchio. Fidati di più della tendenza (▲/▼) che del numero.
- Alcuni dispositivi cambiano indirizzo MAC ogni pochi minuti per privacy: quelli **associati** al telefono invece vengono riconosciuti sempre.

## Build
Push su `main` → GitHub Actions compila l'APK → lo scarichi da *Actions › ultima esecuzione › Artifacts › Ritrova-apk*.
L'APK è firmato con la chiave debug, quindi si installa direttamente (abilita "Installa app sconosciute").

Struttura:
```
app/src/main/java/it/klab/ritrova/
  BtScanner.kt    scansione BLE + classic, filtro Kalman, stima distanza, connessi/associati
  Sounder.kt      bip Geiger, vibrazione, trillo nelle cuffie
  MainActivity.kt interfaccia Compose (permessi, lista, modalità ricerca)
```
Requisiti: Android 8.0+ (minSdk 26), target Android 15.
