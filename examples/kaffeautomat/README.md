# Example: Kaffeautomaten

Sells 500 g bags of coffee. Bags hang in textile hammocks held by a single steel wire;
the motor winds the wire up, releasing the bags one at a time from the bottom onto a
gently sloped ramp. A tilting plate with a micro switch tells the Arduino the bag has landed.

- [`mekanik.pdf`](mekanik.pdf) – the mechanism (in Danish): hammocks, wire, ramp, forces, parts list.
- [`config.json`](config.json) – the app config. Also the app's built-in default and the
  `kaffeautomat` template in the dashboard.

One column with a 12 V gear motor on slot 0 of [the firmware](../../firmware/automat).
