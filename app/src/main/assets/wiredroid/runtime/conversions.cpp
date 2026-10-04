#include "api/itoa.h"
#include "api/deprecated-avr-comp/avr/dtostrf.h"
#include <cstdio>
static char* convert(unsigned long v,char*s,int radix,bool negative){if(radix<2||radix>36){*s=0;return s;}char b[65];int n=0;do{b[n++]="0123456789abcdefghijklmnopqrstuvwxyz"[v%radix];v/=radix;}while(v);char*out=s;if(negative)*out++='-';while(n)*out++=b[--n];*out=0;return s;}
extern "C" char* itoa(int v,char*s,int r){bool negative=v<0&&r==10;return convert(negative?0UL-(unsigned long)v:(unsigned long)(unsigned int)v,s,r,negative);}
extern "C" char* ltoa(long v,char*s,int r){bool negative=v<0&&r==10;return convert(negative?0UL-(unsigned long)v:(unsigned long)v,s,r,negative);}
extern "C" char* utoa(unsigned v,char*s,int r){return convert(v,s,r,false);}
extern "C" char* ultoa(unsigned long v,char*s,int r){return convert(v,s,r,false);}
extern "C" char* dtostrf(double v,signed char w,unsigned char p,char*s){std::sprintf(s,"%*.*f",int(w),int(p),v);return s;}
