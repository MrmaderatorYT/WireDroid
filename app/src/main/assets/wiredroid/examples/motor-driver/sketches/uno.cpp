#include <Arduino.h>
void setup(){pinMode(2,OUTPUT);pinMode(3,OUTPUT);}
void loop(){digitalWrite(2,HIGH);digitalWrite(3,LOW);analogWrite(9,180);delay(2000);analogWrite(9,0);delay(500);digitalWrite(2,LOW);digitalWrite(3,HIGH);analogWrite(9,220);delay(2000);analogWrite(9,0);delay(500);}
