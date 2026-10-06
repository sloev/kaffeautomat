# Automat – design

En byg-selv salgsautomat til **hvad som helst**: kaffe, æg, honning, nøgler,
reservedele. Kunden betaler med MobilePay, en Android-telefon læser betalingen,
filmer kunden, viser status bag et stykke plexiglas og beder en Arduino om at
udlevere varen.

Første anvendelse er kaffeautomaten, hvis mekanik står i
[kaffeautomaten-mekanik.pdf](kaffeautomaten-mekanik.pdf) (tekstil-hængekøjer,
én stålwire, skrå rampe, vippeplade med mikrokontakt).

## Krav

- Trådløs betaling med MobilePay, ingen personale til stede.
- **Ingen abonnementer.** Engangsudgifter og gebyr pr. salg er OK.
- **Generelt:** en ny automat (andre varer, priser, flere søjler, anden mekanik)
  kræver kun en ny `config.json` og evt. andre pin-numre i firmwaren – ingen ny app.
- Telefonen sidder bag plexiglas: lille statusvindue, kamera der filmer kunden.

## Overblik

```
                    ┌──────────── plexiglas med maske ─────────────┐
                    │  ○ kamera-hul      ┌──────────────────────┐  │
                    │                    │ statusvindue         │  │
                    │                    │ "Kaffe · 60 kr"      │  │
                    │                    │ "Betal med MobilePay"│  │
                    │                    └──────────────────────┘  │
                    │   resten af skærmen er sort / dækket         │
                    └──────────────────────────────────────────────┘
 Kunde betaler               Android-telefon (Automat-appen)               Motorstyring
 med MobilePay  ──notif.──►  læser beløb → finder vare → køer   ──USB──►   Arduino/ESP32
                             filmer klip · logger salg · ntfy   ◄─"OK"──   motor + sensor pr. søjle
```

| Del | Ansvar | Generelt via |
|---|---|---|
| **Android-app** (`android/`) | Betaling, vare-valg, lager, kø, skærm, kamera, log, besked til ejer | `config.json` |
| **Firmware** (`firmware/automat/`) | Kør søjle *n*'s motor til søjle *n*'s sensor melder "landet" | `SLOTS[]`-tabellen |
| **Mekanik** | Selve udleveringen (wire, spiral, skub…) | Bygges pr. automat |

Appen kender ikke til kaffe, wire eller ramper. Den ved kun: *varer har en pris
og et søjle-nummer*, og *søjle n udleveres med `DISPENSE n`*.

## 1. Betaling: MobilePay MyShop

| Løsning | Abonnement | Pris | Egnet? |
|---|---|---|---|
| Privat MobilePay | Nej | Gratis | **Nej** – vilkårene forbyder kommerciel brug; loft 150.000 kr./år. Fin til test. |
| **MobilePay MyShop** | **Nej** (siden okt. 2023) | 0,99 % pr. salg | **Ja.** Kræver CVR. 60 kr. → ca. 0,60 kr. i gebyr. |
| ePayment / POS API | Aftale + integration | Gebyrer + udvikling | Overkill. |

Telefonen skal være online for at få notifikationer: **WiFi** på stedet, eller et
**taletidskort** uden binding (notifikationer bruger næsten ingen data).

## 2. Telefonen læser notifikationen

`PaymentListener` (en `NotificationListenerService`) får kopi af alle
notifikationer, når ejeren har givet *Notifikationsadgang*.

1. **Log-tilstand:** med `logAllNotifications: true` skrives alle notifikationer
   til `notifikationer.log`. Brug den til at finde MobilePay MyShops **rigtige
   pakkenavn og tekst**, og ret `payment.packages` og `payment.amountRegex`.
   Slå den fra bagefter.
2. **Kun MobilePay tæller.** Pakkenavnet sættes af Android og kan ikke forfalskes af
   andre apps.
3. **Titel og tekst læses som `CharSequence`** og den lange `bigText` foretrækkes,
   så teksten ikke er afkortet. Gruppe-sammendrag ignoreres.
4. **Hver betaling behandles én gang** – også efter genstart. Nøglen er
   notifikationens `key` + dens eget tidsstempel (`Notification.when`) + beløb +
   tekst. Den er stabil når Android genposter samme notifikation, men forskellig
   for to betalinger. *Bekræftes i log-tilstand.*
5. **Notifikation fra MobilePay uden beløb** (reklame, ændret format) logges, og
   ejeren får besked.

### Holde lytteren i live

- Appen er telefonens **startskærm (HOME)** og står altid i forgrunden. Så dræber
  Android den ikke, og den starter selv efter genstart.
- `onListenerDisconnected` → `requestRebind`, og appen beder om rebind ved hver
  `onResume`.
- Slå **batterioptimering fra** for MobilePay og Automat. Telefonen sidder i
  opladeren. Ingen skærmlås.
- Skærmen viser "Ude af drift", hvis adgangen mangler eller lytteren er koblet fra.

## 3. Fra beløb til vare (generelt)

Varer står i `config.json` med pris og søjle. Reglerne (`Matcher.kt`):

1. Beløbet er **præcis prisen** på en vare med lager → én af den.
2. Ellers: beløbet er **et helt antal gange præcis én pris** → så mange
   (f.eks. 120 kr. = 2 × kaffe).
3. Ellers **afvises** betalingen: skærmen siger "du får pengene retur", linjen i
   `salg.csv` hedder `REFUNDER`, og ejeren får besked.

**Varer med samme pris er ombyttelige**: to søjler med samme kaffe tømmes i den
rækkefølge, de står i config. Har to *forskellige* varer samme pris, kan appen
ikke se forskel – giv dem forskellige priser (f.eks. 59 og 60 kr.).
Flertydige beløb (120 kr. = 2 × 60 = 3 × 40) afvises.

## 4. Statusvinduet bag plexiglas

Hele skærmen er **sort**, kun et lille vindue viser tekst (og evt. en QR-kode).
Vinduets position og størrelse står i `config.json` som brøkdele af skærmen:

```json
"display": { "x": 0.08, "y": 0.12, "width": 0.84, "height": 0.22,
             "textSizeSp": 20, "brightness": 0.4, "qrPayload": "" }
```

- Plexiglasset får en **maske** (sort folie / maling på bagsiden) med to huller:
  ét over statusvinduet og ét over kameraet. Ret `display` til, så teksten
  passer i hullet.
- Sort skærm = slukkede pixels på OLED: lavt strømforbrug og lidt varme.
  Vinduet flytter sig et par pixels hvert 10. minut mod **indbrænding**.
- Lav lysstyrke (`brightness`) – det er en lille tekst bag glas.
- Montér telefonen **tæt på plexiglasset**, så der ikke kommer refleksioner fra
  skærmen ind i kameraet.
- `qrPayload` kan vise en QR-kode til MyShop. Ellers printes QR-koden og sættes
  på fronten.

Hvad vinduet viser (højeste prioritet først):

| Tilstand | Tekst | Farve |
|---|---|---|
| Lige efter en betaling | `texts.busy` → `texts.pickUp` | grøn |
| Forkert beløb / fejl | `texts.wrongAmount` / `texts.failed` | rød |
| Fejl i config, fastkørt søjle, mangler notifikationsadgang | `texts.outOfService` + årsag | rød |
| Intet internet / ingen motorstyring | `texts.outOfService` + årsag | gul |
| Alt lager 0 | `texts.soldOut` | gul |
| Klar | Én linje pr. vare: "navn · pris" (eller "Udsolgt") + `texts.ready` | hvid |

## 5. Kameraet filmer kunden

Ved hver betaling optager appen et **videoklip uden lyd** med frontkameraet:
fra betalingen kommer, til `tailMs` (20 s) efter udleveringen. Kommer der flere
betalinger, forlænges klippet. Filnavnet står i `salg.csv` ud for betalingen, så
en klage ("jeg betalte, men fik intet") kan slås op. Klip ældre end
`retentionDays` slettes automatisk.

```json
"camera": { "enabled": true, "lens": "front", "tailMs": 20000, "retentionDays": 30 }
```

**Jura (Danmark) – tjek selv Datatilsynets vejledning om tv-overvågning:**

- Der skal være et **skilt** om tv-overvågning, synligt før man når automaten.
- Optagelser må som udgangspunkt **højst gemmes i 30 dage** (derfor standard 30).
- Optag **ikke lyd** – appen gør det ikke.
- Ret kameraet mod den, der står ved automaten, ikke ud over vej og fortov.
- Brug kun klippene til formålet (klager, hærværk).

*Senere:* et rullende for-klip (de 10 s før betalingen) kræver løbende optagelse
i en ringbuffer. Det er udeladt for at spare strøm og slid.

## 6. Telefon ↔ motorstyring

**USB-OTG** til Arduino Nano/Uno eller ESP32 via
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).
Appen åbner selv, når boardet sættes i (vælg "Brug altid").

**Udfordring:** mange telefoner kan ikke **lade op og være USB-host samtidig**.
Test et USB-C-hub med *Power Delivery-pass-through*. Virker det ikke: Bluetooth
(HC-05 / ESP32) – protokollen er den samme, kun `DeviceLink` skal have en ny
implementering. `"link": {"type": "fake"}` kører hele appen uden hardware.

### Protokol (9600 baud, linjer afsluttet med `\n`)

| Telefon sender | Svar | Betydning |
|---|---|---|
| `PING` | `PONG` | Hvert 10. s. Mangler svaret, genforbinder appen. |
| `DISPENSE <n>` | `OK` | Søjle *n*'s sensor meldte "vare landet". |
| | `ERR JAM` | Ingen vare inden 8 s. Motor stoppet. |
| | `ERR SENSOR` | Sensoren var allerede aktiv (vare ikke taget / kontakt sidder fast). |
| | `ERR SLOT` | Søjlen findes ikke. |
| `REWIND <n> <ms>` | `OK` | Kør baglæns (genopfyldning), maks 5 s. |
| `SLOTS` | antal | Antal søjler i firmwaren. |

Ved opstart sender firmwaren `READY`. Den har en **hård maks-køretid**, så en fejl
i telefonen aldrig kan køre en motor i stykker.

Ved enhver fejl under udlevering går automaten **ude af drift**, indtil ejeren
nulstiller den i ejer-menuen – så næste kunde ikke betaler for en fastkørt søjle.

## 7. Konfiguration (`config.json`)

Ligger i `Android/data/dk.automat/files/config.json` på telefonen og kan rettes
via USB fra en computer. Første gang kopieres standarden fra appen
(`android/app/src/main/assets/config.json`). Flere eksempler i `android/examples/`.

| Felt | Betydning |
|---|---|
| `name` | Overskrift i statusvinduet og i beskeder til ejeren |
| `payment.packages` | Pakkenavne der tælles som MobilePay |
| `payment.amountRegex` | Regex hvis første gruppe er beløbet ("60,00") |
| `payment.logAllNotifications` | Skriv alle notifikationer til `notifikationer.log` |
| `products[]` | `id`, `name`, `priceOre` (øre), `slot` (søjle), `capacity` (antal ved fuld) |
| `link` | `type` (`usb`/`fake`), `baud`, `dispenseTimeoutMs` |
| `display` | Statusvinduet, se afsnit 4 |
| `camera` | Se afsnit 5 |
| `ntfyTopic` | Emne på [ntfy.sh](https://ntfy.sh) til push-beskeder til ejeren (gratis) |
| `texts` | Alle tekster kunden ser |

## 8. Filer på telefonen

I `Android/data/dk.automat/files/`:

| Fil | Indhold |
|---|---|
| `salg.csv` | Én linje pr. betaling: tid, betaling, beløb, `OK`/`REFUNDER`, detaljer, klip |
| `notifikationer.log` | Rå notifikationer (log-tilstand) og ulæselige MobilePay-notifikationer |
| `betalinger.txt` | Behandlede betalinger (dedup) |
| `klip/` | Videoklip |

## 9. Ejer-menu

Langt tryk på skærmen (lågen skal være åben, skærmen sidder bag glas):

- **Genopfyld alle søjler** – lager = `capacity` og fejl nulstilles.
  *Lageret starter på 0 ved første installation – automaten viser "Udsolgt",
  indtil der er genopfyldt.*
- **Nulstil fejl**
- **Simulér betaling** – kør hele kæden (vare-valg, motor, klip, log) uden MobilePay.
- **Spol søjle tilbage**
- **Notifikationsadgang**
- **Genindlæs config.json**

## 10. Opsætning af en ny telefon

1. Nulstil telefonen, log ind i MobilePay MyShop, slå skærmlås fra.
2. Installer Automat-appen, giv kamera-tilladelse og notifikationsadgang.
3. Vælg Automat som **standard-startskærm** (Indstillinger → Apps → Standardapps).
4. Slå batterioptimering fra for Automat og MobilePay.
5. Log-tilstand: send en betaling på 1 kr., læs `notifikationer.log`, ret
   `payment` i `config.json`, genindlæs.
6. Tilslut motorstyringen, vælg "Brug altid".
7. Genopfyld, **Simulér betaling**, og derefter en rigtig betaling.

## 11. Komponenter (elektronik)

| Del | Forslag | Ca. pris |
|---|---|---|
| Telefon | Brugt Android 8+ (helst OLED-skærm) | 0–500 kr. |
| Mikrocontroller | Arduino Nano-klon – eller ESP32 til Bluetooth | 30–60 kr. |
| Motorstyring | Logic-level MOSFET-modul, eller DRV8871 H-bro pr. søjle (baglæns) | 20–60 kr. |
| Sensor | Mikrokontakt under vippeplade / i udtag | 10 kr. |
| USB | OTG-kabel, evt. USB-C-hub med PD-pass-through | 50–150 kr. |
| Strøm | 12 V-forsyning til motorer + USB-lader | 100–150 kr. |
| Plexiglas | 3–4 mm, sort folie som maske | 50–100 kr. |
| MobilePay MyShop | Kræver CVR | 0,99 % pr. salg |

## 12. Plan

1. **Log-tilstand** på MyShop-telefonen: find pakkenavn og tekstformat.
2. **Bænktest** af firmwaren: motor + mikrokontakt, kommandoer fra Serial Monitor.
3. **App med `fake`-link**: tjek statusvindue gennem plexiglas, kamera-klip, salg.csv.
4. **Telefon ↔ Arduino over USB**: test samtidig opladning → ellers Bluetooth.
5. **Én søjle med tre poser** (ris i stedet for kaffe).
6. **Udholdenhed**: en uge med en betaling om dagen, genstart og strømsvigt undervejs.
