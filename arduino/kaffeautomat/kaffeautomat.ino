// Kaffeautomat – motorstyring.
// Modtager tekstkommandoer fra telefonen (USB-serial eller Bluetooth-serial)
// og kører wire-motoren, til vippepladens mikrokontakt melder at en pose er landet.
// Protokol: se docs/betaling.md.

const uint8_t PIN_MOTOR_FWD = 5;   // MOSFET / H-bro IN1 (spol wiren op)
const uint8_t PIN_MOTOR_REV = 6;   // H-bro IN2 (baglæns, til genopfyldning). Ubrugt med enkelt MOSFET.
const uint8_t PIN_SWITCH    = 2;   // Mikrokontakt under vippepladen, til GND (INPUT_PULLUP)

const unsigned long MAX_RUN_MS    = 8000;  // Hård grænse: motoren kører aldrig længere end dette
const unsigned long MAX_REWIND_MS = 5000;
const unsigned long DEBOUNCE_MS   = 30;

String line;

void motorStop() {
  digitalWrite(PIN_MOTOR_FWD, LOW);
  digitalWrite(PIN_MOTOR_REV, LOW);
}

bool switchPressed() {
  if (digitalRead(PIN_SWITCH) != LOW) return false;
  delay(DEBOUNCE_MS);
  return digitalRead(PIN_SWITCH) == LOW;
}

void dispense() {
  if (switchPressed()) {
    // Vippepladen er allerede nede – en pose er ikke taget, eller kontakten sidder fast.
    Serial.println(F("ERR SWITCH"));
    return;
  }
  unsigned long start = millis();
  digitalWrite(PIN_MOTOR_FWD, HIGH);
  while (millis() - start < MAX_RUN_MS) {
    if (switchPressed()) {
      motorStop();
      Serial.println(F("OK"));
      return;
    }
  }
  motorStop();
  Serial.println(F("ERR JAM"));
}

void rewind(unsigned long ms) {
  if (ms > MAX_REWIND_MS) ms = MAX_REWIND_MS;
  digitalWrite(PIN_MOTOR_REV, HIGH);
  delay(ms);
  motorStop();
  Serial.println(F("OK"));
}

void handle(String cmd) {
  cmd.trim();
  if (cmd == "PING") {
    Serial.println(F("PONG"));
  } else if (cmd == "STATUS") {
    Serial.println(F("IDLE"));  // Kommandoer behandles synkront, så vi er altid ledige her
  } else if (cmd == "DISPENSE") {
    dispense();
  } else if (cmd.startsWith("REWIND ")) {
    rewind(cmd.substring(7).toInt());
  } else if (cmd.length() > 0) {
    Serial.println(F("ERR UNKNOWN"));
  }
}

void setup() {
  pinMode(PIN_MOTOR_FWD, OUTPUT);
  pinMode(PIN_MOTOR_REV, OUTPUT);
  motorStop();
  pinMode(PIN_SWITCH, INPUT_PULLUP);
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
