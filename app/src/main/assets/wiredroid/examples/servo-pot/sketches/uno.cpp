#include <Arduino.h>
#include <Servo.h>

Servo motor;

void setup() {
  motor.attach(9);
}

void loop() {
  int value = analogRead(A0);
  motor.write(map(value, 0, 1023, 0, 180));
  delay(20);
}
