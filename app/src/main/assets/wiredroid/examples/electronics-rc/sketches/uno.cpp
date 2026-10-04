#include <Arduino.h>
void setup(){pinMode(9,OUTPUT);Serial.begin(9600);}
void loop(){digitalWrite(9,millis()%6000<3000?HIGH:LOW);Serial.println(analogRead(A0)*5.0/1023.0,3);delay(50);}
