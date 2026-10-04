#include <Arduino.h>
#include <vector>
#include <LiquidCrystal.h>
#include "display.h"
LiquidCrystal lcd(12,11,5,4,3,2);
void setup(){Serial.begin(9600);Serial.println(-0.5,3);std::vector<int> values{1,2,3};int sum=0;for(int n:values)sum+=n;Serial.print("C/STL ");Serial.println(sum+measure());lcd.begin(16,2);lcd.print("WireDroid C++");}
void loop(){lcd.setCursor(0,1);lcd.print(message());delay(250);}
