#pragma once
#include <Arduino.h>
#include <array>
#include <fstream>
#include <stdexcept>
class EEPROMClass {
 std::array<uint8_t,4096> data;std::string path;bool initialized=false;
 void init(){if(initialized)return;initialized=true;data.fill(255);const char*name=std::getenv("WIRE_EEPROM_FILE");if(name){path=name;std::ifstream file(path,std::ios::binary);if(file)file.read((char*)data.data(),data.size());}}
 void bounds(int p,size_t n=1){if(p<0||size_t(p)+n>data.size())throw std::out_of_range("EEPROM address");}
public:
 bool begin(size_t n=4096){init();return n<=data.size();}
 uint8_t read(int p){init();bounds(p);return data[p];}
 void write(int p,uint8_t v){init();bounds(p);data[p]=v;commit();}void update(int p,uint8_t v){if(read(p)!=v)write(p,v);}
 int length(){return data.size();}uint8_t operator[](int p){return read(p);}
 template<class T>T& get(int p,T&value){init();bounds(p,sizeof(T));memcpy(&value,data.data()+p,sizeof(T));return value;}
 template<class T>const T& put(int p,const T&value){init();bounds(p,sizeof(T));memcpy(data.data()+p,&value,sizeof(T));commit();return value;}
 bool commit(){if(path.empty())return false;std::ofstream file(path,std::ios::binary|std::ios::trunc);file.write((char*)data.data(),data.size());return bool(file);}
 void end(){commit();}
};
extern EEPROMClass EEPROM;
