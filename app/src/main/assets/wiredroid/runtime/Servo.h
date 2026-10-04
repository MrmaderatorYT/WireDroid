#pragma once
#include "Arduino.h"
// WireDroid simulation adapter; no target MCU timer or physical PWM generation.
#define MIN_PULSE_WIDTH 544
#define MAX_PULSE_WIDTH 2400
#define DEFAULT_PULSE_WIDTH 1500
#define MAX_SERVOS 12
#define INVALID_SERVO 255
int wire_servo_claim();
void wire_servo(int pin,int pulse,int angle);
class Servo {
  int channel, pin=-1, minimum=MIN_PULSE_WIDTH, maximum=MAX_PULSE_WIDTH, pulse=DEFAULT_PULSE_WIDTH;
  int connect(int resolved,int low,int high){
    if(channel>=MAX_SERVOS||resolved<0||low<100||high>5000||low>=high)return INVALID_SERVO;
    if(pin>=0&&pin!=resolved)detach();
    pin=resolved;minimum=low;maximum=high;pulse=constrain(pulse,minimum,maximum);
    wire_mode(pin,OUTPUT);wire_servo(pin,pulse,read());return channel;
  }
public:
  Servo():channel(wire_servo_claim()){}
  uint8_t attach(int value){return connect(wire_pin(value),MIN_PULSE_WIDTH,MAX_PULSE_WIDTH);}
  uint8_t attach(int value,int low,int high){return connect(wire_pin(value),low,high);}
  uint8_t attach(const char*value){return connect(wire_pin(value),MIN_PULSE_WIDTH,MAX_PULSE_WIDTH);}
  uint8_t attach(const char*value,int low,int high){return connect(wire_pin(value),low,high);}
  void detach(){if(pin>=0){wire_servo(pin,0,0);wire_mode(pin,INPUT);pin=-1;}}
  bool attached()const{return pin>=0;}
  void write(int value){if(value>=MIN_PULSE_WIDTH){writeMicroseconds(value);return;}value=constrain(value,0,180);writeMicroseconds(minimum+(maximum-minimum)*value/180);}
  void writeMicroseconds(int value){pulse=constrain(value,minimum,maximum);if(pin>=0)wire_servo(pin,pulse,read());}
  int read()const{return constrain(((pulse-minimum)*180+(maximum-minimum)/2)/(maximum-minimum),0,180);}
  int readMicroseconds()const{return pulse;}
};
