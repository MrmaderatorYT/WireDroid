#pragma once
#include "WireBus.h"
#define SPI_MODE0 0
#define SPI_MODE1 1
#define SPI_MODE2 2
#define SPI_MODE3 3
#define SPI_CLOCK_DIV2 2
#define SPI_CLOCK_DIV4 4
#define SPI_CLOCK_DIV8 8
#define SPI_CLOCK_DIV16 16
class SPISettings {
public:
 uint32_t clock;BitOrder order;uint8_t mode;
 SPISettings(uint32_t c=4000000,uint8_t o=MSBFIRST,uint8_t m=SPI_MODE0):clock(c),order(BitOrder(o)),mode(m){}
};
class SPIClass {
 int clock=-1,miso=-1,mosi=-1,select=-1;SPISettings settings;bool begun=false;
public:
 void begin(){clock=wire_bus_pin(2);mosi=wire_bus_pin(3);miso=wire_bus_pin(4);select=wire_bus_pin(5);begun=true;}
 void begin(int c,int i,int o,int s=-1){clock=wire_pin(c);miso=wire_pin(i);mosi=wire_pin(o);select=s<0?-1:wire_pin(s);begun=true;}
 void end(){begun=false;}void beginTransaction(const SPISettings&s){settings=s;}
 void endTransaction(){}void setBitOrder(uint8_t o){settings.order=BitOrder(o);}void setDataMode(uint8_t m){settings.mode=m;}
 void setClockDivider(uint8_t d){settings.clock=16000000/(d?d:1);}
 uint8_t transfer(uint8_t v){if(!begun)begin();auto bytes=wire_transaction("spi",{clock,mosi,miso,select},settings.mode|(settings.order==LSBFIRST?4:0),{v},1,settings.clock);return bytes.empty()?0:bytes[0];}
 uint16_t transfer16(uint16_t v){uint8_t a=transfer(settings.order==LSBFIRST?v&255:v>>8),b=transfer(settings.order==LSBFIRST?v>>8:v&255);return settings.order==LSBFIRST?a|(uint16_t(b)<<8):(uint16_t(a)<<8)|b;}
 void transfer(void*p,size_t n){auto b=(uint8_t*)p;for(size_t i=0;i<n;i++)b[i]=transfer(b[i]);}
 void usingInterrupt(int){}void notUsingInterrupt(int){}
};
extern SPIClass SPI;
