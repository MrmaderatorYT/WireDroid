#include <Arduino.h>
#include <Stepper.h>
Stepper motor(200,2,3,4,5);
void setup(){motor.setSpeed(20);}
void loop(){motor.step(100);delay(500);motor.step(-100);delay(500);}
