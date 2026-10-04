#include <Wire.h>
#include <SPI.h>
#include <EEPROM.h>
// Sketch/library constructors may use the adapters before setup().
TwoWire Wire __attribute__((init_priority(102)));
SPIClass SPI __attribute__((init_priority(102)));
EEPROMClass EEPROM __attribute__((init_priority(102)));
