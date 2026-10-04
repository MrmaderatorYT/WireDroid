#include <Arduino.h>
#include <Wire.h>
#include <SPI.h>
#include <EEPROM.h>
#include <LiquidCrystal.h>
#include <Stepper.h>
#include <Counter.h>
#include "measure.h"
LiquidCrystal lcd(12,11,5,4,3,2);
Stepper stepper(200,8,9,10,13);
void setup(){Serial.begin(9600);Wire.begin();SPI.begin();EEPROM.update(0,42);lcd.begin(16,2);lcd.print("WireDroid AVR");stepper.setSpeed(60);Serial.println(measure()+counter());}
void loop(){stepper.step(1);delay(10);}
