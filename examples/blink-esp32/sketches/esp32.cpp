#include <Arduino.h>

const char *LED_PIN = "connector17"; // A0 у Fritzing-профілі
void setup() {
  pinMode(LED_PIN, OUTPUT);
  Serial.begin(9600);
}
void loop() {
  digitalWrite(LED_PIN, HIGH);
  Serial.println("ESP32 GPIO high");
  delay(350);
  digitalWrite(LED_PIN, LOW);
  delay(350);
}
