# Automat

A DIY vending machine for anything – coffee, eggs, honey, keys – paid with
**MobilePay**, run by an **old Android phone**, with **no subscriptions**.

The phone sits behind a sheet of plexiglass. It reads the MobilePay payment
notification, shows status in a small window, films the customer during the sale,
and tells an Arduino over USB which column to release. A Cloudflare Worker on the
free plan provisions machines, pairs phones by QR code, receives heartbeats,
shows a dashboard and alerts you when something goes wrong.

The worked example is **[Kaffeautomaten](examples/kaffeautomat)**, a coffee
vending machine built from textile hammocks, one steel wire and a sloped ramp.

> Most of the documentation and the UI are in Danish – MobilePay is a Danish
> payment app and the first machines are in Denmark. Code comments are mixed.

```
  customer pays          Android phone (app)                     Arduino
  with MobilePay ─notif─► reads amount → picks product ─USB──────► motor + sensor
                          status window, video clip, log ◄─"OK"── per column
                                    │ heartbeat / commands / config
                                    ▼
                          Cloudflare Worker + D1 ── dashboard, provisioning, ntfy alerts
```

| Folder | What |
|---|---|
| [`android/`](android) | The phone app (Kotlin). Everything machine-specific is in `config.json`. |
| [`firmware/automat/`](firmware/automat) | Arduino firmware: one motor + one "item landed" sensor per column. |
| [`worker/`](worker) | Server: Cloudflare Worker + D1 + static dashboard. |
| [`examples/`](examples) | Machine configs: `kaffeautomat` (with mechanics PDF), `aeg` (eggs), `test-uden-hardware`. |
| [`docs/design.md`](docs/design.md) | The full design (Danish): payment, status window, camera, protocol, autostart, server, NFC analysis, setup checklist. |

## How it works

1. **Payment** – MobilePay MyShop (0.99 % per transaction, no monthly fee). A
   `NotificationListenerService` reads the "you received 60,00 kr" notification.
   Each payment is processed exactly once; the amount picks the product.
2. **Dispensing** – the app sends `DISPENSE <column>` over USB serial; the firmware
   runs that column's motor until its sensor reports the item landed (hard timeout).
3. **Screen and camera** – the screen is black except a small, configurable status
   window; the front camera records a short clip (no audio) per sale, deleted after 30 days.
4. **Always on** – the app is the phone's home screen, restarts after crashes and boot,
   and reopens its screen if closed.
5. **Server** – heartbeat every minute with health, battery, stock and events; commands
   (refill, clear fault, set stock, test dispense) and config changes come back in the reply.

## Getting started

1. Deploy the server: [`worker/README.md`](worker/README.md).
2. Build the app: `cd android && ./gradlew assembleDebug` (needs the Android SDK).
3. Flash the firmware: `arduino-cli compile -b arduino:avr:nano firmware/automat` and upload.
4. In the dashboard: **Ny automat** → pick an example → the page shows a pairing QR code.
5. On the phone: long-press the screen → **Forbind til server: scan QR-kode**.
6. Follow the setup checklist in [`docs/design.md`](docs/design.md) section 13 (notification
   access, home screen, battery optimisation, find the real MobilePay notification format).

## Status

Prototype. The app, firmware and server build and their tests pass; nothing has run
in a real machine yet. The MobilePay package name and notification text in the
example configs are guesses until checked on a real phone (the app has a logging
mode for exactly that).

## Legal

Filming customers in Denmark requires a sign and at most 30 days' retention –
check Datatilsynet's guidance. Selling via MobilePay requires MobilePay MyShop
(a CVR number), not a private account.

## License

[MIT](LICENSE)
