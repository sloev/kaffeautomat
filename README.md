# Automat

Byg-selv salgsautomat til hvad som helst. Kunden betaler med MobilePay, og en
gammel Android-telefon bag plexiglas klarer resten: læser betalingen, viser
status i et lille vindue, filmer kunden og beder en Arduino om at udlevere
varen. Telefonen starter selv, melder løbende til jeres server og kan
fjernstyres derfra. Ingen abonnementer – kun MobilePay MyShops gebyr på 0,99 % pr. salg.

Første automat: **kaffeautomaten** ([mekanik](docs/kaffeautomaten-mekanik.pdf)).

| Mappe | Indhold |
|---|---|
| [docs/design.md](docs/design.md) | Hele designet: betaling, statusvindue, kamera, protokol, konfiguration, opsætning |
| [android/](android/) | Telefon-appen (Kotlin). Alt automat-specifikt står i `config.json` |
| [firmware/automat/](firmware/automat/) | Arduino-firmware: én motor + én sensor pr. søjle |
| [server/](server/) | Reference-server: heartbeat, statusside, alarmer når en automat er væk, fjernkommandoer |
| [docs/kaffeautomaten-mekanik.pdf](docs/kaffeautomaten-mekanik.pdf) | Kaffeautomatens mekanik: hængekøjer, wire og rampe |

## Ny automat

1. Byg mekanikken – én motor og én "vare landet"-sensor pr. søjle.
2. Ret `SLOTS[]` i firmwaren til dine pins.
3. Skriv en `config.json` med varer, priser og tekster (se `android/examples/`).
