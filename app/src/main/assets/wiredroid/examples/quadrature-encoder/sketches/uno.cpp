#include <Arduino.h>
volatile long position=0;
volatile uint8_t previous=0;
void edge(){const int8_t delta[]={0,1,-1,0,-1,0,0,1,1,0,0,-1,0,-1,1,0};uint8_t phase=(digitalRead(2)?1:0)|(digitalRead(3)?2:0);position+=delta[(previous<<2)|phase];previous=phase;}
void setup(){Serial.begin(9600);pinMode(2,INPUT_PULLUP);pinMode(3,INPUT_PULLUP);previous=(digitalRead(2)?1:0)|(digitalRead(3)?2:0);attachInterrupt(digitalPinToInterrupt(2),edge,CHANGE);attachInterrupt(digitalPinToInterrupt(3),edge,CHANGE);}
void loop(){noInterrupts();long value=position;interrupts();Serial.print("Encoder: ");Serial.println(value);delay(10);}
