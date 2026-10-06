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
| **Server** (`server/`) | Modtager heartbeat, viser status, alarmerer når en automat er væk, sender kommandoer | Jeres egen; reference i Python |

Appen kender ikke til kaffe, wire eller ramper. Den ved kun: *varer har en pris
og et søjle-nummer*, og *søjle n udleveres med `DISPENSE n`*.

## 1. Betaling: MobilePay MyShop

| Løsning | Abonnement | Pris | Egnet? |
|---|---|---|---|
| Privat MobilePay | Nej | Gratis | **Nej** – vilkårene forbyder kommerciel brug; loft 150.000 kr./år. Fin til test. |
| **MobilePay MyShop** | **Nej** (siden okt. 2023) | 0,99 % pr. salg | **Ja.** Kræver CVR. 60 kr. → ca. 0,60 kr. i gebyr. |
| ePayment / POS API | Aftale + integration | Gebyrer + udvikling | Overkill. |

Telefonen kører på **WiFi** på stedet.

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

Hvordan appen holder sig selv i gang, står i afsnit 7.

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

## 6. Telefon ↔ motorstyring: USB

**USB-OTG** til Arduino Nano/Uno (eller ESP32) via
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).
Appen åbner selv, når boardet sættes i (vælg "Brug altid").

**Telefonen skal kunne lade op og være USB-host samtidig** – den kører døgnet
rundt. Det kan langt fra alle. Løsning: et USB-C-hub med *Power Delivery
pass-through* (laderen i hubbens PD-port, Arduinoen i en USB-A-port). **Test det
med den konkrete telefon, før der købes flere** – appen viser batteri og
"lader/lader ikke" på serverens statusside, så det kan ses efter et døgn.

Arduinoen forsynes fra 12 V-forsyningen (VIN), så telefonen ikke skal levere
strøm til den; USB bruges kun til data. Fælles GND mellem motor og Arduino.

`"link": {"type": "fake"}` kører hele appen uden hardware.

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

## 7. Autostart: appen er altid tændt

Lag på lag, så automaten kommer op igen uden at nogen skal derud:

| Hvad går galt | Hvad sker der |
|---|---|
| **Telefonen genstarter** | Appen er telefonens **startskærm (HOME)**, så Android åbner den efter opstart. `BootReceiver` starter desuden hjernen (betaling + heartbeat) med det samme. |
| **Appen bryder ned** | Android genstarter processen for notifikationslytterens skyld; hjernen starter igen, nedbruddet skrives til `nedbrud.txt` og sendes til serveren. |
| **Skærmen er lukket** (nedbrud, "tilbage"-knap) | Hjernens vagthund åbner skærmen igen efter 30 s. Kræver tilladelsen *Vis over andre apps* (ejer-menuen → "Tillad automatisk genåbning"). Pauses 10 min, når ejeren åbner indstillinger fra menuen. |
| **Notifikationslytteren kobles fra** | `requestRebind` straks og ved hver `onResume`. Vises på skærm og server. |
| **Appen opdateres** | `MY_PACKAGE_REPLACED` starter den igen. |
| **Strømsvigt – telefonen er slukket** | Telefonen skal selv tænde, når strømmen kommer igen. Se herunder. |

Betalinger behandles af hjernen i appens proces, **også hvis skærmen ikke er
åben** (så er der bare ingen status og intet kamera).

**Telefonen tænder selv ved strøm:** det afhænger af modellen.

- Mange Motorola/Pixel-telefoner: `fastboot oem off-mode-charge 0` (kræver
  oplåst bootloader på nogle) – så booter de, når laderen får strøm.
- Nogle Samsung/Xiaomi har *Planlagt tænd/sluk* i indstillingerne.
- Ellers: lad batteriet være nok til at klare kortere strømsvigt, og lad
  serveren alarmere, hvis heartbeat forsvinder.

Vælg telefonmodel efter dette og USB-kravet i afsnit 6.

**Telefonens indstillinger:** ingen skærmlås, batterioptimering slået fra for
Automat og MobilePay, automatiske systemopdateringer om natten (eller fra),
WiFi altid tændt i dvale.

## 8. Server: "jeg er tændt" + fjernstyring

Telefonen sender hvert `heartbeatSec` (60 s) en `POST {url}/heartbeat` til jeres
server. Telefonen kalder kun ud, så der skal ikke åbnes porte på stedet.
Serveren **skal** være https.

```json
"server": { "url": "https://automat.example.dk/api", "token": "lang-tilfældig-streng", "heartbeatSec": 60 }
```

**Request** (`Authorization: Bearer <token>`):

```json
{
  "deviceId": "3f0c…",              "name": "Kaffeautomaten",   "appVersion": "0.1",
  "time": 1791300000000,             "appStartedAt": 1791290000000, "phoneUptimeSec": 86400,
  "screen":  { "title": "Kaffeautomaten", "lines": ["Kaffe · 60 kr", "Betal med MobilePay"], "mood": "NORMAL" },
  "health":  { "online": true, "motorLink": true, "notificationAccess": true, "uiVisible": true,
               "canRelaunchUi": true, "camera": true, "fault": null, "configError": null },
  "battery": { "level": 100, "charging": true, "temperatureC": 31.5 },
  "stock":   { "kaffe-mellem": 2 },
  "counts":  { "payments": 4, "ok": 3, "refund": 1 },
  "events":  [ { "ts": 1791299990000, "type": "sale", "payment": "…", "amountOre": 6000,
                 "items": "1x Kaffe, mellemristet 500 g", "clip": "20261006-101500_1a2b3c.mp4" } ]
}
```

Hændelser ligger i `udbakke.jsonl` på telefonen, til serveren har svaret 2xx – så
intet går tabt, hvis WiFi er nede. Typer: `start` (evt. med `previousCrash`),
`boot`, `sale`, `refund`, `fault`, `refill`, `unreadable`, `motorLink`,
`notificationAccess`, `configError`, `command`.

**Svar** – valgfrie kommandoer, som telefonen udfører med det samme og
kvitterer for med en `command`-hændelse (`result: "ok"` eller fejltekst):

```json
{ "commands": [
  { "id": "a1", "cmd": "refill" },
  { "id": "a2", "cmd": "clearFault" },
  { "id": "a3", "cmd": "setStock", "productId": "kaffe-mellem", "count": 3 },
  { "id": "a4", "cmd": "reloadConfig" },
  { "id": "a5", "cmd": "setConfig", "config": { "...": "hele config.json" } },
  { "id": "a6", "cmd": "simulate", "amountOre": 6000 }
] }
```

Kommandoer leveres højst én gang. `simulate` udleverer en vare uden betaling –
beskyt token og admin-adgang derefter.

**Hvem opdager at telefonen er død?** Det kan telefonen ikke selv. Serveren
alarmerer, når der ikke er kommet heartbeat i `OFFLINE_AFTER` sekunder.

**Reference-server:** [`server/server.py`](../server/server.py) – ét Python-script
uden afhængigheder: tager imod heartbeat, statusside med knapper (genopfyld,
nulstil fejl, genindlæs config), hændelseslog og alarmer via ntfy.sh (offline,
refundering, fejl, nedbrud). Kør den bag Caddy/nginx for https.

## 9. NFC / kort-betaling? (vurderet – ikke nu)

Telefonen *kan* tage imod kontaktløse kort (Visa/Mastercard, også MobilePay og
Apple/Google Pay i mobilens wallet) med **SoftPOS / Tap to Pay on Android**.
Fordelen er stor: betalingen bekræftes direkte i appen af betalingsudbyderen –
ingen notifikationslæsning – og appen sætter selv beløbet.

| Udbyder | Danmark | Abonnement | Bemærkninger |
|---|---|---|---|
| **SumUp Tap-to-Pay SDK** | Ja (SumUp har Tap to Pay i DK) | Nej, gebyr pr. transaktion | SDK-adgang skal godkendes af SumUp. Android 11+, NFC. Betaling slås fra på telefoner med udviklertilstand, USB-debugging eller root. |
| Stripe Terminal Tap to Pay | Ikke i Danmark pt. | Nej | Til ubemandet salg anbefaler Stripe en dedikeret læser. |

Hvorfor ikke nu:

1. **NFC-antennen sidder på bagsiden** af næsten alle telefoner. Med skærm og
   frontkamera vendt mod kunden vender antennen ind i automaten. Kort-betaling og
   statusvindue/kamera på samme telefon kræver en model med antenne foran, eller
   en anden placering (f.eks. bagkameraet filmer, skærmen vender ind).
2. **Ubemandet brug** skal godkendes af udbyderen (kortreglerne er anderledes for
   ubemandede automater). Spørg SumUp, før der bygges.
3. Udviklertilstand skal være slået fra – også på den telefon, der kører appen.
4. Kunden skal kunne **vælge vare** før betaling (beløbet sættes af appen). Det
   kræver knapper – f.eks. trykknapper på Arduinoen, der sender `BUTTON <n>` til
   telefonen.

Arkitekturen er klar til det: betaling er bare en kilde til `Payment`-objekter.
Kort-betaling bliver en ekstra kilde ved siden af MobilePay, når 1–4 er afklaret.

## 10. Konfiguration (`config.json`)

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
| `ntfyTopic` | Emne på [ntfy.sh](https://ntfy.sh) til push-beskeder direkte fra telefonen (gratis) |
| `server` | `url`, `token`, `heartbeatSec` – se afsnit 8. Tom `url` = ingen server |
| `texts` | Alle tekster kunden ser |

## 11. Filer på telefonen

I `Android/data/dk.automat/files/`:

| Fil | Indhold |
|---|---|
| `salg.csv` | Én linje pr. betaling: tid, betaling, beløb, `OK`/`REFUNDER`, detaljer, klip |
| `notifikationer.log` | Rå notifikationer (log-tilstand) og ulæselige MobilePay-notifikationer |
| `betalinger.txt` | Behandlede betalinger (dedup) |
| `udbakke.jsonl` | Hændelser der venter på at blive sendt til serveren |
| `nedbrud.txt` | Seneste nedbrud (sendes og slettes ved næste opstart) |
| `klip/` | Videoklip |

## 12. Ejer-menu

Langt tryk på skærmen (lågen skal være åben, skærmen sidder bag glas):

- **Genopfyld alle søjler** – lager = `capacity` og fejl nulstilles.
  *Lageret starter på 0 ved første installation – automaten viser "Udsolgt",
  indtil der er genopfyldt.*
- **Nulstil fejl**
- **Simulér betaling** – kør hele kæden (vare-valg, motor, klip, log) uden MobilePay.
- **Spol søjle tilbage**
- **Notifikationsadgang**
- **Genindlæs config.json**
- **Tillad automatisk genåbning af skærmen** (*Vis over andre apps*)

Menuen viser også lager, enheds-id, sidste kontakt med serveren og hvor filerne ligger.

## 13. Opsætning af en ny telefon

1. Nulstil telefonen, forbind til WiFi, log ind i MobilePay MyShop, slå skærmlås fra.
2. Installer Automat-appen, giv kamera-tilladelse, notifikationsadgang og
   *Vis over andre apps*.
3. Vælg Automat som **standard-startskærm** (Indstillinger → Apps → Standardapps).
4. Slå batterioptimering fra for Automat og MobilePay. Sæt telefonen til at
   tænde ved strøm, hvis modellen kan (afsnit 7).
5. Sæt `server.url` og `server.token` i `config.json`, og se automaten dukke op på
   statussiden.
6. Log-tilstand: send en betaling på 1 kr., læs `notifikationer.log`, ret
   `payment` i `config.json`, genindlæs.
7. Tilslut motorstyringen via USB-hubben, vælg "Brug altid".
8. Genopfyld, **Simulér betaling**, og derefter en rigtig betaling.
9. Træk strømmen i 10 min og sæt den i igen: kommer telefon, app og heartbeat selv op?

## 14. Komponenter (elektronik)

| Del | Forslag | Ca. pris |
|---|---|---|
| Telefon | Brugt Android 8+ (helst OLED-skærm) | 0–500 kr. |
| Mikrocontroller | Arduino Nano-klon | 30 kr. |
| Motorstyring | Logic-level MOSFET-modul, eller DRV8871 H-bro pr. søjle (baglæns) | 20–60 kr. |
| Sensor | Mikrokontakt under vippeplade / i udtag | 10 kr. |
| USB | USB-C-hub med PD-pass-through (lad + OTG samtidig) | 100–200 kr. |
| Server | Lille VPS eller eksisterende server, Caddy for https | det I har |
| Strøm | 12 V-forsyning til motorer + USB-lader | 100–150 kr. |
| Plexiglas | 3–4 mm, sort folie som maske | 50–100 kr. |
| MobilePay MyShop | Kræver CVR | 0,99 % pr. salg |

## 15. Plan

1. **Log-tilstand** på MyShop-telefonen: find pakkenavn og tekstformat.
2. **Bænktest** af firmwaren: motor + mikrokontakt, kommandoer fra Serial Monitor.
3. **App med `fake`-link**: tjek statusvindue gennem plexiglas, kamera-klip, salg.csv.
4. **Telefon ↔ Arduino over USB**: test samtidig opladning via hub i et døgn
   (batteri-kurven på serverens statusside).
5. **Server** op, og test strømsvigt/genstart/nedbrud (afsnit 7).
6. **Én søjle med tre poser** (ris i stedet for kaffe).
7. **Udholdenhed**: en uge med en betaling om dagen, genstart og strømsvigt undervejs.
