# Betaling: MobilePay → Android-telefon → Arduino → motor

Mekanikken er beskrevet i [kaffeautomaten-mekanik.pdf](kaffeautomaten-mekanik.pdf)
(tekstil-hængekøjer, én stålwire, skrå rampe, vippeplade med mikrokontakt).
Dette dokument beskriver, hvordan kunden betaler trådløst, og hvordan betalingen
udløser motoren.

## Krav

- Kunden betaler med MobilePay og får sin pose i skuffen uden at nogen er til stede.
- **Ingen abonnementer.** Engangsudgifter og gebyr pr. transaktion er OK, faste
  månedlige betalinger er ikke.
- Billigt, robust og til at reparere selv.

## Overblik

```
 Kunde                        Automat
 ─────                        ───────────────────────────────────────────────
 Scanner QR / taster     ┌──────────────────────────┐   USB-OTG    ┌──────────┐
 MyShop-nummer  ───────► │ Android-telefon          │ ───────────► │ Arduino  │
                         │  - MobilePay MyShop-app  │  "DISPENSE"  │  Nano    │
 MobilePay sender        │  - Kaffeautomat-app:     │ ◄─────────── │          │
 notifikation til  ────► │    læser notifikationen, │  "OK"/"ERR"  └────┬─────┘
 telefonen               │    tjekker beløb,        │                   │
                         │    viser status på skærm │              MOSFET/H-bro
                         └──────────────────────────┘                   │
                                                              12 V gearmotor + spole
                                                              vippeplade → mikrokontakt
```

Telefonen gør tre ting på én gang:

1. **Kvitteringen** – den modtager MobilePay-notifikationen og læser beløbet.
2. **Hjernen** – den beslutter om der skal udleveres, holder styr på lager og
   fejl og husker hvilke betalinger der allerede er brugt.
3. **Skærmen** – den sidder bag en rude i fronten og viser QR-kode, pris,
   "Tak – din kaffe kommer", "Udsolgt" osv. Så slipper vi for et separat display.

Arduinoen er "dum": den kører motoren, til vippepladen melder at posen er landet,
og svarer tilbage. Al forretningslogik ligger i telefonen.

## 1. Hvilken MobilePay?

| Løsning | Abonnement | Pris | Egnet? |
|---|---|---|---|
| Privat MobilePay | Nej | Gratis | **Nej** – vilkårene forbyder kommerciel brug, og der er en loftgrænse på 150.000 kr./år. Kan bruges til test med egne penge. |
| **MobilePay MyShop** | **Nej** (siden okt. 2023) | 0,99 % pr. transaktion | **Ja.** Kræver CVR-nummer. Ved en pose til 60 kr. er gebyret ca. 0,60 kr. |
| ePayment / Point of Sale API | Aftale og integration | Gebyrer, kræver udvikling og typisk PSP | Overkill, og vi vil netop undgå API-aftaler. |

**Anbefaling: MobilePay MyShop.** Ingen fast pris, kun 0,99 % pr. salg. MyShop
giver et nummer og en QR-kode, som printes og sættes på automaten (eller vises på
telefonens skærm). Når der kommer en betaling, får MyShop-telefonen en
push-notifikation – og det er den, vi læser.

> Tjek inden byggeriet: præcis hvilken app MyShop-notifikationerne kommer fra
> (pakkenavn), og hvordan teksten ser ud. Appen herunder har en *log-tilstand*
> netop til det, så vi ikke gætter.

### Internet til telefonen – uden abonnement

Telefonen skal være online for at få notifikationer. Muligheder:

- **WiFi** på stedet (bedst, gratis hvis det allerede findes).
- **Taletidskort / forudbetalt SIM** uden binding. Notifikationer bruger meget lidt
  data; et kort med f.eks. et års gyldighed ved optankning er nok. Tjek at kortet
  ikke automatisk fornyer som abonnement.

## 2. Telefonen læser notifikationen

En lille Android-app (se [`android/`](../android)) bruger Androids
`NotificationListenerService`. Brugeren giver én gang tilladelse under
*Indstillinger → Notifikationsadgang*. Derefter får appen kopi af alle
notifikationer, og den:

1. **Filtrerer på pakkenavn.** Kun notifikationer fra MobilePay-appen tælles.
   Androids OS sætter pakkenavnet – en anden app kan ikke forfalske det.
2. **Udtrækker beløbet** med et regex, f.eks. `"Du har modtaget 60,00 kr. fra …"`.
   Regex og pakkenavn er konfiguration, ikke hårdkodet, fordi MobilePay kan
   ændre teksten.
3. **Deduplikerer.** Android kan genposte/opdatere den samme notifikation. Vi
   gemmer en nøgle (`notification key + postTime + beløb + tekst`) i en lille
   database og behandler hver betaling præcis én gang – også efter genstart.
4. **Afgør antal poser:** `antal = beløb / pris`, kun hvis det går lige op og
   der er poser nok på lager. Ellers udleveres intet, og betalingen markeres
   til manuel refundering i loggen.
5. **Lægger i kø** og sender `DISPENSE` til Arduinoen én pose ad gangen. Betaler
   to kunder samtidig, kommer poserne efter hinanden.

### Kendte faldgruber

- **Batterioptimering** dræber baggrundsapps. Slå batterioptimering fra for både
  MobilePay og vores app, og lad telefonen sidde i opladeren hele tiden.
- **Notifikationsindhold:** "Vis beløb i notifikationer" / skjulte notifikationer
  på låseskærm skal være slået til, ellers står beløbet ikke i teksten.
- **Grupperede notifikationer:** kommer der mange betalinger hurtigt, kan de blive
  samlet. Vi læser hver enkelt notifikation (ikke kun sammendraget), og
  afstemmer dagligt mod MyShops oversigt.
- **Opdateringer af MobilePay** kan ændre tekstformatet. Appen viser "Ude af drift"
  og sender en besked til ejeren, hvis den modtager en MobilePay-notifikation, den
  ikke kan læse.
- **Kiosktilstand:** brug Androids *skærmfastgørelse* (app pinning), så forbipasserende
  ikke kan bruge telefonen. Appen starter selv ved opstart (`BOOT_COMPLETED`).

## 3. Telefon ↔ Arduino

### Fysisk forbindelse

**USB-OTG (som foreslået).** Arduino Nano/Uno med USB-serial-chip (CH340/FTDI/16U2)
virker fra Android via biblioteket
[usb-serial-for-android](https://github.com/mik3y/usb-serial-for-android).
Telefonen forsyner Arduinoens logik (lille strømforbrug); motoren får sin egen
12 V-forsyning med fælles GND.

**Udfordring:** mange telefoner kan ikke **lade op og være USB-host på samme tid**.
Telefonen skal køre 24/7, så det skal løses. Muligheder i prioriteret rækkefølge:

1. Find en telefon, der understøtter OTG + opladning via et USB-C-hub med
   *Power Delivery-pass-through* – test det, før der købes flere.
2. **Bluetooth i stedet for USB:** HC-05-modul på Arduinoen (ca. 40 kr.) eller
   en ESP32 i stedet for Arduino (ca. 50 kr., har både BLE og WiFi). Så sidder
   telefonen i en almindelig oplader. Protokollen herunder er den samme.
3. ESP32 på samme WiFi som telefonen og simpel HTTP (`POST /dispense`).

Protokollen er tekstlinjer, så den virker ens over USB, Bluetooth-serial og TCP.

### Protokol (9600 baud, linjer afsluttet med `\n`)

| Telefon sender | Arduino svarer | Betydning |
|---|---|---|
| `PING` | `PONG` | Lever du? Sendes hvert 10. s. |
| `DISPENSE` | `OK` | Motor kørte, vippepladen meldte pose landet. |
| | `ERR JAM` | Ingen pose inden timeout (tom søjle, wire sidder fast). Motor stoppet. |
| | `ERR SWITCH` | Vippepladen er allerede nede (pose ikke taget / kontakt sidder fast). Motor startes ikke. |
| `REWIND <ms>` | `OK` | Kør motoren baglæns (til genopfyldning). |

Kommandoer behandles én ad gangen; kommer der en ny, mens motoren kører, venter
den i serial-bufferen. Telefonen sender alligevel først næste `DISPENSE`, når den
har fået svar.

Arduinoen kører altid med en **hård maksimal motortid** (f.eks. 8 s), så et
softwarefejl i telefonen aldrig kan køre motoren i stykker eller vikle hele wiren op.

## 4. Udlevering trin for trin

1. Kunden scanner QR-koden på skærmen og betaler 60 kr.
2. MobilePay viser notifikation: *"Du har modtaget 60,00 kr. fra …"*.
3. Appen genkender MobilePay, læser 60,00, slår op: ikke set før → 1 pose.
4. Skærmen viser *"Tak! Din kaffe er på vej"*. Appen sender `DISPENSE`.
5. Arduino starter motoren (trin 1–3 i PDF'en), posen falder på rampen og
   trykker vippepladen ned → mikrokontakt → motor stop → `OK`.
6. Appen tæller lageret ned og skriver salget i loggen. Skærmen viser
   *"Tag din kaffe i skuffen"* og derefter QR-koden igen.
7. Når lageret er 0, viser skærmen *"Udsolgt"* og skjuler QR-koden, så ingen
   betaler for en tom maskine.

### Fejl og refundering

| Situation | Hvad sker der |
|---|---|
| Forkert beløb (f.eks. 50 kr.) | Ingen udlevering. Skærmen: *"Beløbet passer ikke – du får pengene retur"*. Logges til manuel refundering i MyShop. |
| `ERR JAM` | Skærmen: *"Fejl – du får pengene retur"*. Maskinen går "Ude af drift". Ejeren får besked. |
| Arduino svarer ikke på `PING` | "Ude af drift", QR-kode skjules. |
| Telefonen mister internet | Kan ikke opdages af kunden før betaling → appen tjekker forbindelse og skjuler QR-koden, når den er offline. |

**Besked til ejeren uden abonnement:** [ntfy.sh](https://ntfy.sh) (gratis
push-beskeder via HTTP) eller en e-mail via en gratis mailkonto. SMS fra
taletidskortet er også en mulighed men koster pr. besked.

## 5. Flere kaffetyper (senere)

Én motor + én wire pr. søjle. Kunden skal vælge søjle, men MobilePay-notifikationen
fortæller kun beløb (og evt. kundens besked). Muligheder:

- **Forskellig pris pr. type** (59, 60, 61 kr.) – simpelt, men lidt kluntet.
- **Knap før betaling:** kunden trykker på en fysisk knap / telefonens skærm for
  den ønskede type, og den næste betaling inden for 2 minutter går til den
  søjle. Kræver at kun én kunde ad gangen – skærmen viser "Optaget".
- **Besked i betalingen** ("A", "B") – kun hvis beskeden kommer med i
  notifikationsteksten.

Start med én type.

## 6. Komponenter (betaling + styring)

| Del | Forslag | Ca. pris |
|---|---|---|
| Telefon | Brugt Android 8+ med god batteristatus | 0–500 kr. |
| Mikrocontroller | Arduino Nano (klon) – eller ESP32 hvis Bluetooth/WiFi | 30–60 kr. |
| Motorstyring | Logic-level MOSFET-modul (kun én retning) eller DRV8871 H-bro (baglæns til genopfyldning) | 20–60 kr. |
| Stopkontakt | Mikrokontakt med arm under vippepladen (fra PDF'en) | 10 kr. |
| USB | OTG-adapter/kabel, evt. USB-C-hub med PD-pass-through | 50–150 kr. |
| Strøm | 12 V-forsyning til motor + USB-lader til telefon | 100–150 kr. |
| Internet | WiFi eller taletidskort | 0 kr. / pr. forbrug |
| MobilePay MyShop | Kræver CVR | 0,99 % pr. salg |

## 7. Plan

1. **Log-tilstand:** installer appen på telefonen med MyShop, send dig selv en
   betaling, og gem den rå notifikation (pakkenavn, title, text). Ret regex.
2. **Bænktest:** Arduino + motor + mikrokontakt på bordet, styret fra en PC via
   Serial Monitor med `DISPENSE`.
3. **Telefon ↔ Arduino:** samme via USB-OTG. Test samtidig om telefonen kan lade.
4. **Hele kæden** på én søjle med tre poser (som PDF'ens næste skridt), med
   ris i stedet for kaffe.
5. **Udholdenhedstest:** lad telefonen køre en uge og send en betaling hver dag.
   Tjek at appen stadig lever efter genstart og strømsvigt.
