#pragma once
// Official ArduinoCore-API implemented by the native WireDroid simulator.
#ifndef EXTENDED_PIN_MODE
#define EXTENDED_PIN_MODE 1
#endif
#ifndef HOST
#define HOST 1
#endif
#ifndef ARDUINO
#define ARDUINO 10819
#endif
#define ARDUINO_ARCH_WIREDROID 1
#include "api/ArduinoAPI.h"
#ifdef __cplusplus
#include "api/HardwareSerial.h"
#include <algorithm>
using namespace arduino;
#ifndef PROGMEM
#define PROGMEM
#endif
int wire_pin(int); int wire_pin(const char*);
void wire_mode(int,int); void wire_write(int,int); int wire_read(int);
int wire_bus_pin(int); double wire_default_pwm_frequency(int);
void wire_pwm(int,double,double); void wire_frequency(int,double);
int wire_analog_bits();
void wire_serial_byte(uint8_t); void wire_serial(const char*); void wire_serial(long); void wire_serial(double);
int wire_serial_input(int); size_t wire_serial_bytes(const uint8_t*,size_t);
inline void pinMode(const char*p,PinMode m){wire_mode(wire_pin(p),m);}
inline void pinMode(int p,PinMode m){wire_mode(wire_pin(p),m);}
inline void pinMode(int p,int m){wire_mode(wire_pin(p),m);}
inline void digitalWrite(const char*p,int v){wire_write(wire_pin(p),v?255:0);}
inline void digitalWrite(int p,PinStatus v){wire_write(wire_pin(p),v==LOW?0:255);}
inline void digitalWrite(int p,int v){wire_write(wire_pin(p),v?255:0);}
inline int digitalRead(const char*p){return wire_read(wire_pin(p))>=(1<<wire_analog_bits())/2;}
inline int analogRead(const char*p){return wire_read(wire_pin(p));}
inline void analogWrite(const char*p,int v){analogWrite((pin_size_t)(200000+wire_pin(p)),v);}
void analogReadResolution(int); void analogWriteResolution(int);
void analogWriteFrequency(pin_size_t,double);
inline int digitalPinToInterrupt(int p){return p;}
void interrupts(); void noInterrupts();
class WireSerial final : public HardwareSerial {
public:
 void begin(unsigned long)override{} void begin(unsigned long,uint16_t)override{}
 void end()override{} int available()override{return wire_serial_input(0);}
 int read()override{return wire_serial_input(1);} int peek()override{return wire_serial_input(2);}
 void flush()override{} size_t write(uint8_t v)override{wire_serial_byte(v);return 1;}
 size_t write(int v){return write(uint8_t(v));}
 size_t write(unsigned int v){return write(uint8_t(v));}
 size_t write(long v){return write(uint8_t(v));}
 size_t write(unsigned long v){return write(uint8_t(v));}
 size_t write(const uint8_t*b,size_t n)override{return wire_serial_bytes(b,n);}
 using Print::write; operator bool()override{return true;}
};
extern WireSerial Serial;

#endif // __cplusplus
