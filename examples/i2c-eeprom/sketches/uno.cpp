#include <Arduino.h>
#include <Wire.h>
void setup(){Serial.begin(9600);Wire.begin();Wire.beginTransmission(0x50);Wire.write(0);Wire.write(10);Wire.write(42);Serial.println(Wire.endTransmission());}
void loop(){Wire.beginTransmission(0x50);Wire.write(0);Wire.write(10);Wire.endTransmission();Wire.requestFrom(0x50,1);if(Wire.available())Serial.println(Wire.read());delay(1000);}
