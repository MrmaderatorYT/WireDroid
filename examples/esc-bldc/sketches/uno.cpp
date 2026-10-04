#include <Arduino.h>
#include <Servo.h>
Servo esc;
void setup(){Serial.begin(9600);esc.attach(9);esc.writeMicroseconds(1000);Serial.println("ESC arming");delay(1500);}
void loop(){Serial.println("BLDC accelerate");for(int pulse=1000;pulse<=1800;pulse+=50){esc.writeMicroseconds(pulse);delay(150);}delay(1000);Serial.println("BLDC stop");esc.writeMicroseconds(1000);delay(2000);}
