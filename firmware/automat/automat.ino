// Automat – motorstyring.
// Modtager tekstkommandoer fra telefonen (USB-serial eller Bluetooth-serial) og kører
// en søjles motor, til søjlens sensor melder at varen er landet. Protokol: docs/design.md.
//
// Generelt: en "søjle" er bare én motor + én sensor. Mekanikken kan være wire og
// hængekøjer (kaffeautomaten), en spiral, et skuffe-skub osv. – firmwaren er den samme.

struct Slot {
  uint8_t fwd;     // Motor fremad (MOSFET / H-bro IN1)
  uint8_t rev;     // Motor baglæns (H-bro IN2), 255 = ingen baglæns
  uint8_t sensor;  // Kontakt der går til GND, når varen er landet (INPUT_PULLUP). Flere søjler må dele sensor.
};

// Tilpas til din automat. Nummeret i listen er søjlens nummer (slot) i config.json.
Slot SLOTS[] = {
  {5, 6, 2},
  // {9, 10, 2},
  // {11, 255, 3},
};
const uint8_t SLOT_COUNT = sizeof(SLOTS) / sizeof(SLOTS[0]);

const unsigned long MAX_RUN_MS    = 8000;  // Hård grænse: en motor kører aldrig længere end dette
const unsigned long MAX_REWIND_MS = 5000;
const unsigned long DEBOUNCE_MS   = 30;

String line;

void stopAll() {
  for (uint8_t i = 0; i < SLOT_COUNT; i++) {
    digitalWrite(SLOTS[i].fwd, LOW);
    if (SLOTS[i].rev != 255) digitalWrite(SLOTS[i].rev, LOW);
  }
}

bool sensorActive(const Slot& s) {
  if (digitalRead(s.sensor) != LOW) return false;
  delay(DEBOUNCE_MS);
  return digitalRead(s.sensor) == LOW;
}

void dispense(int n) {
  if (n < 0 || n >= SLOT_COUNT) { Serial.println(F("ERR SLOT")); return; }
  const Slot& s = SLOTS[n];
  if (sensorActive(s)) {
    // Sensoren er allerede aktiv – en vare er ikke taget, eller kontakten sidder fast.
    Serial.println(F("ERR SENSOR"));
    return;
  }
  unsigned long start = millis();
  digitalWrite(s.fwd, HIGH);
  while (millis() - start < MAX_RUN_MS) {
    if (sensorActive(s)) {
      stopAll();
      Serial.println(F("OK"));
      return;
    }
  }
  stopAll();
  Serial.println(F("ERR JAM"));
}

void rewind(int n, unsigned long ms) {
  if (n < 0 || n >= SLOT_COUNT || SLOTS[n].rev == 255) { Serial.println(F("ERR SLOT")); return; }
  if (ms > MAX_REWIND_MS) ms = MAX_REWIND_MS;
  digitalWrite(SLOTS[n].rev, HIGH);
  delay(ms);
  stopAll();
  Serial.println(F("OK"));
}

void handle(String cmd) {
  cmd.trim();
  if (cmd == "PING") {
    Serial.println(F("PONG"));
  } else if (cmd == "SLOTS") {
    Serial.println(SLOT_COUNT);
  } else if (cmd == "DISPENSE") {
    dispense(0);
  } else if (cmd.startsWith("DISPENSE ")) {
    dispense(cmd.substring(9).toInt());
  } else if (cmd.startsWith("REWIND ")) {
    // REWIND <slot> <ms>
    String rest = cmd.substring(7);
    int sp = rest.indexOf(' ');
    if (sp < 0) { Serial.println(F("ERR ARGS")); return; }
    rewind(rest.substring(0, sp).toInt(), rest.substring(sp + 1).toInt());
  } else if (cmd.length() > 0) {
    Serial.println(F("ERR UNKNOWN"));
  }
}

void setup() {
  for (uint8_t i = 0; i < SLOT_COUNT; i++) {
    pinMode(SLOTS[i].fwd, OUTPUT);
    if (SLOTS[i].rev != 255) pinMode(SLOTS[i].rev, OUTPUT);
    pinMode(SLOTS[i].sensor, INPUT_PULLUP);
  }
  stopAll();
  Serial.begin(9600);
  Serial.println(F("READY"));
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n') {
      handle(line);
      line = "";
    } else if (line.length() < 32) {
      line += c;
    }
  }
}
