#pragma once
#include <Arduino.h>
#include <vector>
#include <string>
std::vector<uint8_t> wire_transaction(const std::string&,const std::vector<int>&,int,const std::vector<uint8_t>&,int,unsigned long);
