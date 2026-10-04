#include <Arduino.h>
volatile uint16_t interruptCount=0;
void edge(){interruptCount++;digitalWrite(8,!digitalRead(8));}
void setup(){pinMode(13,OUTPUT);pinMode(8,OUTPUT);pinMode(2,INPUT_PULLUP);analogWrite(5,64);attachInterrupt(digitalPinToInterrupt(2),edge,FALLING);Serial.begin(115200);Serial.println("WireDroid AVR");}
void loop(){static bool on=false;digitalWrite(13,on=!on);Serial.print("ADC=");Serial.print(analogRead(A0));Serial.print(" INT=");noInterrupts();uint16_t count=interruptCount;interrupts();Serial.println(count);delay(10);}
