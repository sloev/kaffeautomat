# Kaffeautomat

Selvbetjent automat, der sælger poser kaffe. Kunden betaler med MobilePay, og
maskinen slipper en pose ned ad en rampe til en skuffe.

- [docs/kaffeautomaten-mekanik.pdf](docs/kaffeautomaten-mekanik.pdf) – mekanikken: tekstil-hængekøjer, én stålwire og en skrå rampe.
- [docs/betaling.md](docs/betaling.md) – betaling uden abonnement: MobilePay MyShop → Android-telefon læser notifikationen → Arduino kører motoren.
- [arduino/kaffeautomat/](arduino/kaffeautomat/) – Arduino-sketch: motor + stopkontakt, styret med tekstkommandoer over serial.
- [android/](android/) – kildekode-skelet til telefon-appen (notifikationslæser og beløbs-parser med tests).
