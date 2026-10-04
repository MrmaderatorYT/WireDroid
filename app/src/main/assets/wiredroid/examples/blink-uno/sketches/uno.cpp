#include <Arduino.h>

void setup() {
  pinMode(13, OUTPUT);
  Serial.begin(9600);
}
void loop() {
  digitalWrite(13, HIGH);
  Serial.println("LED on");
  delay(500);
  digitalWrite(13, LOW);
  delay(500);
}
