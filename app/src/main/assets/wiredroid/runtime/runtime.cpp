#include <Arduino.h>
#include <unistd.h>
#include <time.h>
#include <cerrno>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <cmath>
#include <map>
#include <vector>
#include <string>
#include <stdexcept>
static uint64_t clockUs(){timespec t{};clock_gettime(CLOCK_MONOTONIC,&t);return uint64_t(t.tv_sec)*1000000+t.tv_nsec/1000;}
// Arduino libraries can call GPIO/delay from global constructors before main.
// Initialize shared state on first use rather than relying on object link order.
static uint64_t& epoch(){static uint64_t value=clockUs();return value;}
unsigned long micros(){uint64_t start=epoch();return clockUs()-start;} unsigned long millis(){return micros()/1000;}
static void text(const std::string&s){size_t off=0;while(off<s.size()){ssize_t n=::write(STDOUT_FILENO,s.data()+off,s.size()-off);if(n<0&&errno==EINTR)continue;if(n<=0)throw std::runtime_error("GPIO output closed");off+=n;}}
static std::string response(){std::string s;char c;while(::read(STDIN_FILENO,&c,1)==1){if(c=='\n')return s;if(s.size()>=8192)throw std::runtime_error("GPIO reply overflow");s+=c;}throw std::runtime_error("GPIO input closed");}
static void valid(int pin){if(pin<0)throw std::runtime_error("Unknown pin");}
void wire_mode(int p,int m){valid(p);text("@W M "+std::to_string(p)+" "+std::to_string(m)+" "+std::to_string(micros())+"\n");}
void wire_write(int p,int v){valid(p);text("@W W "+std::to_string(p)+" "+std::to_string(v)+" "+std::to_string(micros())+"\n");}
int wire_read(int p){valid(p);text("@W R "+std::to_string(p)+" "+std::to_string(micros())+"\n");return std::stoi(response());}
static int adcBits=WIRE_ADC_BITS,pwmBits=8;
static std::map<int,double>& frequencies(){static std::map<int,double> value;return value;}
int wire_analog_bits(){return adcBits;}
void analogReadResolution(int b){if(b<1||b>16)throw std::runtime_error("ADC resolution: 1..16 bits");adcBits=b;text("@W ADC "+std::to_string(b)+"\n");}
void analogWriteResolution(int b){if(b<1||b>16)throw std::runtime_error("PWM resolution: 1..16 bits");pwmBits=b;}
void wire_frequency(int p,double hz){valid(p);if(!std::isfinite(hz)||hz<=0||hz>1000000)throw std::runtime_error("PWM frequency: 0..1 MHz");frequencies()[p]=hz;}
void analogWriteFrequency(pin_size_t p,double hz){wire_frequency(wire_pin((int)p),hz);}
void wire_pwm(int p,double duty,double hz){valid(p);wire_mode(p,OUTPUT);text("@W PWM "+std::to_string(p)+" "+std::to_string(std::clamp(duty,0.0,1.0))+" "+std::to_string(hz)+" "+std::to_string(micros())+"\n");}
extern "C" void pinMode(pin_size_t p,PinMode m){wire_mode(wire_pin((int)p),m);}
extern "C" void digitalWrite(pin_size_t p,PinStatus s){wire_write(wire_pin((int)p),s==LOW?0:255);}
extern "C" PinStatus digitalRead(pin_size_t p){return wire_read(wire_pin((int)p))>=((1<<adcBits)/2)?HIGH:LOW;}
extern "C" int analogRead(pin_size_t p){return wire_read(wire_pin((int)p));}
extern "C" void analogReference(uint8_t mode){if(mode!=0)throw std::runtime_error("Set ADC reference voltage in board inspector");}
extern "C" void analogWrite(pin_size_t pin,int v){int p=pin>=200000?pin-200000:wire_pin((int)pin);wire_pwm(p,double(v)/((1<<pwmBits)-1),frequencies().count(p)?frequencies()[p]:wire_default_pwm_frequency(p));}
static int servoChannels;int wire_servo_claim(){return servoChannels++;}
void wire_servo(int p,int pulse,int angle){valid(p);text("@W V "+std::to_string(p)+" "+std::to_string(pulse)+" "+std::to_string(angle)+" "+std::to_string(micros())+"\n");}
struct Interrupt{voidFuncPtr fn=nullptr;voidFuncPtrParam withParam=nullptr;void*param=nullptr;PinStatus mode=CHANGE;int last=-1;};
static std::map<pin_size_t,Interrupt>& callbacks(){static std::map<pin_size_t,Interrupt> value;return value;}
static bool enabled=true,polling=false;
static std::map<int,unsigned long>& toneEnds(){static std::map<int,unsigned long> value;return value;}
static void poll(){auto& tones=toneEnds();auto& handlers=callbacks();for(auto it=tones.begin();it!=tones.end();){if((long)(micros()-it->second)>=0){wire_write(it->first,0);it=tones.erase(it);}else ++it;}if(!enabled||polling||handlers.empty())return;polling=true;for(auto&[p,i]:handlers){int value=digitalRead(p);if(i.last>=0&&i.last!=value&&(i.mode==CHANGE||(i.mode==RISING&&value==HIGH)||(i.mode==FALLING&&value==LOW))){if(i.fn)i.fn();else if(i.withParam)i.withParam(i.param);}i.last=value;}polling=false;}
void interrupts(){enabled=true;}void noInterrupts(){enabled=false;}
extern "C" void attachInterrupt(pin_size_t p,voidFuncPtr fn,PinStatus mode){callbacks()[p]={fn,nullptr,nullptr,mode,-1};}
extern "C" void attachInterruptParam(pin_size_t p,voidFuncPtrParam fn,PinStatus mode,void*param){callbacks()[p]={nullptr,fn,param,mode,-1};}
extern "C" void detachInterrupt(pin_size_t p){callbacks().erase(p);}
extern "C" void delayMicroseconds(unsigned int us){timespec request{us/1000000,long(us%1000000)*1000},remaining{};while(nanosleep(&request,&remaining)<0&&errno==EINTR)request=remaining;poll();}
extern "C" void delay(unsigned long ms){while(ms>10){delayMicroseconds(10000);ms-=10;}delayMicroseconds(ms*1000);}
extern "C" void yield(){poll();delayMicroseconds(100);}
extern "C" unsigned long pulseIn(pin_size_t p,uint8_t state,unsigned long timeout){unsigned long start=micros();while(digitalRead(p)==state)if(micros()-start>=timeout)return 0;while(digitalRead(p)!=state)if(micros()-start>=timeout)return 0;unsigned long edge=micros();while(digitalRead(p)==state)if(micros()-start>=timeout)return 0;return micros()-edge;}
extern "C" unsigned long pulseInLong(pin_size_t p,uint8_t s,unsigned long t){return pulseIn(p,s,t);}
unsigned long pulseIn(uint8_t p,uint8_t s,unsigned long t){return pulseIn((pin_size_t)p,s,t);}
unsigned long pulseInLong(uint8_t p,uint8_t s,unsigned long t){return pulseIn((pin_size_t)p,s,t);}
extern "C" void shiftOut(pin_size_t d,pin_size_t c,BitOrder order,uint8_t v){for(int i=0;i<8;i++){digitalWrite(d,(v&(1<<(order==LSBFIRST?i:7-i)))?HIGH:LOW);digitalWrite(c,HIGH);digitalWrite(c,LOW);}}
extern "C" uint8_t shiftIn(pin_size_t d,pin_size_t c,BitOrder order){uint8_t v=0;for(int i=0;i<8;i++){digitalWrite(c,HIGH);v|=(digitalRead(d)==HIGH?1:0)<<(order==LSBFIRST?i:7-i);digitalWrite(c,LOW);}return v;}
void tone(uint8_t p,unsigned int hz,unsigned long duration){wire_pwm(wire_pin(p),.5,hz);if(duration)toneEnds()[wire_pin(p)]=micros()+duration*1000;else toneEnds().erase(wire_pin(p));}
void noTone(uint8_t p){toneEnds().erase(wire_pin(p));wire_write(wire_pin(p),0);}
long random(long n){return n>0?::rand()%n:0;}long random(long a,long b){return b>a?a+random(b-a):a;}void randomSeed(unsigned long s){::srand(s);}
static std::string hex(const uint8_t*b,size_t n){static const char digits[]="0123456789abcdef";std::string s;s.reserve(n*2);for(size_t i=0;i<n;i++){s+=digits[b[i]>>4];s+=digits[b[i]&15];}return s;}
int wire_serial_input(int op){text(op==0?"@W A\n":op==1?"@W C\n":"@W P\n");return std::stoi(response());}
size_t wire_serial_bytes(const uint8_t*b,size_t n){size_t written=0;while(written<n){size_t count=std::min(size_t(4000),n-written);text("@W S "+hex(b+written,count)+"\n");written+=count;}return n;}
void wire_serial_byte(uint8_t v){wire_serial_bytes(&v,1);}void wire_serial(const char*s){if(s)wire_serial_bytes((const uint8_t*)s,strlen(s));}
void wire_serial(long v){Serial.print(v);}void wire_serial(double v){Serial.print(v,3);}
std::vector<uint8_t> wire_transaction(const std::string&kind,const std::vector<int>&pins,int address,const std::vector<uint8_t>&data,int count,unsigned long hz){
 std::string message="@W BUS "+kind+" "+std::to_string(address)+" "+std::to_string(count)+" "+std::to_string(hz)+" "+std::to_string(micros())+" ";
 for(size_t i=0;i<pins.size();i++){if(i)message+=",";message+=std::to_string(pins[i]);}message+=" "+(data.empty()?"-":hex(data.data(),data.size()))+"\n";text(message);
 std::string s=response();if(s.rfind("ERR:",0)==0)throw std::runtime_error(s.substr(4));std::vector<uint8_t> out;
 if(s.size()%2)throw std::runtime_error("Invalid peripheral reply");for(size_t i=0;i<s.size();i+=2)out.push_back(std::stoi(s.substr(i,2),nullptr,16));return out;
}
WireSerial Serial __attribute__((init_priority(101)));
extern "C" void init(){} extern "C" void initVariant(){}
__attribute__((weak)) int main(){(void)epoch();setvbuf(stdout,nullptr,_IONBF,0);try{setup();text("@W READY\n");for(;;){loop();poll();delayMicroseconds(1000);}}catch(const std::exception&e){text(std::string("@W E ")+e.what()+"\n");return 2;}catch(...){text("@W E Native program failed\n");return 3;}}
