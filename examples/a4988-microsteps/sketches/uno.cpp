#include <Arduino.h>
const uint8_t STEP=2, DIR=3, MS1=4, MS2=5, MS3=6, ENABLE=7, SLEEP=8;
void pulse(){digitalWrite(STEP,HIGH);delayMicroseconds(2000);digitalWrite(STEP,LOW);delayMicroseconds(2000);}
void setup(){Serial.begin(9600);for(int pin=2;pin<=8;pin++)pinMode(pin,OUTPUT);digitalWrite(ENABLE,LOW);digitalWrite(SLEEP,HIGH);digitalWrite(DIR,HIGH);delay(2);}
void loop(){
  const uint8_t masks[]={0,1,2,3,7},resolutions[]={1,2,4,8,16};
  for(int mode=0;mode<5;mode++){
    digitalWrite(MS1,masks[mode]&1?HIGH:LOW);digitalWrite(MS2,masks[mode]&2?HIGH:LOW);digitalWrite(MS3,masks[mode]&4?HIGH:LOW);
    Serial.print("Microsteps: ");Serial.println(resolutions[mode]);
    for(int i=0;i<50*resolutions[mode];i++)pulse();delay(200);
  }
  digitalWrite(ENABLE,HIGH);Serial.println("Outputs disabled");delay(500);digitalWrite(ENABLE,LOW);
  digitalWrite(SLEEP,LOW);Serial.println("Sleep");delay(500);digitalWrite(SLEEP,HIGH);delay(2);
  digitalWrite(DIR,digitalRead(DIR)?LOW:HIGH);
}
