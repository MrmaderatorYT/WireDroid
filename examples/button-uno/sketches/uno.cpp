#include <Arduino.h>

int previous = -1;
void setup() {
  pinMode(13, OUTPUT);
  pinMode(2, INPUT_PULLUP);
  Serial.begin(9600);
}
void loop() {
  int pressed = digitalRead(2) == LOW;
  digitalWrite(13, pressed ? HIGH : LOW);
  if (pressed != previous) {
    Serial.println(pressed ? "Button pressed" : "Button released");
    previous = pressed;
  }
  delay(20);
}
