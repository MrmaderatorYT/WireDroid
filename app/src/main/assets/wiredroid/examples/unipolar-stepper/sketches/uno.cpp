#include <Arduino.h>
const uint8_t phases[]={1,3,2,6,4,12,8,9};
int phase=0;
void halfStep(int direction){phase=(phase+direction+8)%8;for(int i=0;i<4;i++)digitalWrite(i+2,phases[phase]&(1<<i)?HIGH:LOW);delay(8);}
void setup(){Serial.begin(9600);for(int i=2;i<=5;i++)pinMode(i,OUTPUT);}
void loop(){Serial.println("Unipolar forward");for(int i=0;i<200;i++)halfStep(1);delay(300);Serial.println("Unipolar reverse");for(int i=0;i<200;i++)halfStep(-1);for(int i=2;i<=5;i++)digitalWrite(i,LOW);delay(500);}
