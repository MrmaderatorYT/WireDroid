#pragma once
#include "WireBus.h"
class TwoWire : public Stream {
 int sda=-1,scl=-1,address=0;unsigned long frequency=100000;
 std::vector<uint8_t> tx,rx;size_t cursor=0;bool begun=false;
public:
 void begin(){sda=wire_bus_pin(0);scl=wire_bus_pin(1);begun=true;}
 void begin(int data,int clock){sda=wire_pin(data);scl=wire_pin(clock);begun=true;}
 void end(){begun=false;tx.clear();rx.clear();}
 void setClock(uint32_t hz){frequency=hz;}void setWireTimeout(uint32_t,bool=false){}
 void beginTransmission(uint8_t a){if(!begun)begin();address=a;tx.clear();}
 uint8_t endTransmission(bool=true){auto result=wire_transaction("i2c",{sda,scl},address,tx,0,frequency);tx.clear();return result.empty()?4:result[0];}
 size_t write(uint8_t v)override{if(tx.size()>=1024)return 0;tx.push_back(v);return 1;}
 size_t write(int v){return write(uint8_t(v));}
 size_t write(unsigned int v){return write(uint8_t(v));}
 size_t write(long v){return write(uint8_t(v));}
 size_t write(unsigned long v){return write(uint8_t(v));}
 using Print::write;
 size_t requestFrom(uint8_t a,size_t count,bool=true){if(!begun)begin();rx=wire_transaction("i2c",{sda,scl},a,{},count,frequency);cursor=0;return rx.size();}
 size_t requestFrom(int a,int n){return requestFrom(uint8_t(a),size_t(n));}
 int available()override{return int(rx.size()-cursor);}
 int read()override{return available()?rx[cursor++]:-1;}
 int peek()override{return available()?rx[cursor]:-1;}void flush()override{}
};
extern TwoWire Wire;
