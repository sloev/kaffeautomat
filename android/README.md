# Automat – Android-app

Kotlin, minSdk 26 (Android 8). Se [docs/design.md](../docs/design.md) for hvordan
den virker og sættes op.

```sh
./gradlew testDebugUnitTest   # unit tests (parser, vare-valg, dedup, config)
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
```

Kræver Android SDK (sæt `sdk.dir` i `local.properties` eller `ANDROID_HOME`).

| Fil | Ansvar |
|---|---|
| `Config.kt` | Læser `config.json` |
| `PaymentListener.kt` | `NotificationListenerService` – får notifikationerne |
| `PaymentParser.kt` | Genkender MobilePay og læser beløbet |
| `Matcher.kt` | Beløb → hvilke varer fra hvilke søjler |
| `Ledger.kt` | Hver betaling behandles én gang, også efter genstart |
| `Machine.kt` | Hjernen: kø, lager, motorstyring, sundhed, hvad skærmen viser |
| `DeviceLink.kt` | Tekstprotokol til Arduino over USB-serial (+ `FakeLink` til test) |
| `ClipRecorder.kt` | Videoklip af kunden pr. køb (CameraX, uden lyd) |
| `ScanActivity.kt` | Scanner parrings-QR-koden fra dashboardet |
| `ServerClient.kt` | Parring, heartbeat til serveren, udbakke for hændelser, kommandoer |
| `BootReceiver.kt` | Starter automaten efter opstart og opdatering |
| `Logs.kt` | `salg.csv`, `notifikationer.log`, push til ejeren via ntfy.sh |
| `MainActivity.kt` | Sort skærm med lille statusvindue, ejer-menu (langt tryk) |

Appens standard-config er [`examples/kaffeautomat/config.json`](../examples/kaffeautomat/config.json) (kopieres ind ved build). Flere eksempler i [`examples/`](../examples).
