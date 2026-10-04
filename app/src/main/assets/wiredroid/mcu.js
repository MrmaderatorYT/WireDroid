(function(root){
 'use strict';
 const A=root.AVR8||(typeof require==='function'?require('./vendor/avr8js.cjs'):null);
 function parseHex(text,maxBytes=32768){
  if(typeof text!=='string'||text.length>2097152)throw Error('Прошивка HEX завелика');
  const bytes=new Uint8Array(maxBytes).fill(255);const written=new Uint8Array(maxBytes);let base=0,end=false,used=0;
  for(const line of text.trim().split(/\r?\n/)){
   if(!line.trim())continue;if(end)throw Error('Дані після завершення HEX');
   if(!/^:[0-9a-f]+$/i.test(line)||line.length%2!==1)throw Error('Некоректний запис HEX');
   const data=Array.from({length:(line.length-1)/2},(_,i)=>parseInt(line.slice(1+i*2,3+i*2),16));
   if(data.length<5||data.length!==data[0]+5||data.reduce((a,b)=>(a+b)&255,0)!==0)throw Error('Контрольна сума HEX не збігається');
   const count=data[0],address=(data[1]<<8)|data[2],type=data[3],payload=data.slice(4,-1);
   if(type===0){const start=base+address;if(start+count>maxBytes)throw Error('Прошивка перевищує Flash вибраного MCU');for(let i=0;i<count;i++){if(written[start+i]&&bytes[start+i]!==payload[i])throw Error('Суперечливі дані HEX');written[start+i]=1;}bytes.set(payload,start);used=Math.max(used,start+count);}
   else if(type===1){if(count||address)throw Error('Некоректне завершення HEX');end=true;}
   else if(type===2||type===4){if(count!==2||address)throw Error('Некоректна адреса HEX');base=((payload[0]<<8)|payload[1])*(type===2?16:65536);}
   else if(type===3||type===5){if(count!==4||address)throw Error('Некоректна стартова адреса HEX');}
   else throw Error('Непідтримуваний тип запису HEX '+type);
  }if(!end||!used)throw Error('Порожня або незавершена прошивка HEX');return {bytes,used};
 }
 class Machine{
  constructor(options){this.options=options;this.breakpoints=new Set();this.sequence=0;this.reset();}
  reset(){
   const {bytes,used}=parseHex(this.options.hex);this.flashUsed=used;this.clockHz=this.options.clockHz||16000000;
   if(!Number.isFinite(this.clockHz)||this.clockHz<100000||this.clockHz>32000000)throw Error('Некоректна частота MCU');
   this.cpu=new A.CPU(new Uint16Array(bytes.buffer),2048);this.events=[];this.serial=[];this.serialInput=[];this.pinStates=new Map();this.busPending=null;this.paused=false;
   this.ports={B:new A.AVRIOPort(this.cpu,A.portBConfig),C:new A.AVRIOPort(this.cpu,A.portCConfig),D:new A.AVRIOPort(this.cpu,A.portDConfig)};
   this.timers=[new A.AVRTimer(this.cpu,A.timer0Config),new A.AVRTimer(this.cpu,A.timer1Config),new A.AVRTimer(this.cpu,A.timer2Config)];
   this.adc=new A.AVRADC(this.cpu,A.adcConfig);this.adc.avcc=this.options.voltage||5;this.adc.aref=this.options.voltage||5;
   this.usart=new A.AVRUSART(this.cpu,A.usart0Config,this.clockHz);this.usart.onByteTransmit=value=>this.serial.push(value);
   const memory=new A.EEPROMMemoryBackend(1024);if(this.eeprom)memory.memory.set(this.eeprom);this.eeprom=memory.memory;this.eepromDevice=new A.AVREEPROM(this.cpu,A.eepromConfig,memory);
   this.spi=new A.AVRSPI(this.cpu,A.spiConfig,this.clockHz);this.spi.onByte=value=>this.requestBus({bus:'spi',address:this.spi.spiMode|(this.spi.dataOrder==='lsbFirst'?4:0),data:value.toString(16).padStart(2,'0'),count:1,frequency:this.spi.spiFrequency,pins:this.busPins('spi')},reply=>this.cpu.addClockEvent(()=>this.spi.completeTransfer(reply[0]||0),this.spi.transferCycles));
   this.twi=new A.AVRTWI(this.cpu,A.twiConfig,this.clockHz);this.twiAddress=0;const m=this;
   this.twi.eventHandler={start(){m.twi.completeStart();},stop(){m.requestBus({bus:'i2c',address:m.twiAddress,data:'',count:0,pins:m.busPins('i2c'),operation:'stop'},()=>m.twi.completeStop());},
    connectToSlave(address,write){m.twiAddress=address;m.requestBus({bus:'i2c',address,data:'',count:0,pins:m.busPins('i2c'),frequency:m.twi.sclFrequency,operation:'address',write},reply=>m.cpu.addClockEvent(()=>m.twi.completeConnect(reply[0]===0),m.twiByteCycles()));},
    writeByte(value){m.requestBus({bus:'i2c',address:m.twiAddress,data:value.toString(16).padStart(2,'0'),count:0,pins:m.busPins('i2c'),frequency:m.twi.sclFrequency,operation:'byte'},reply=>m.cpu.addClockEvent(()=>m.twi.completeWrite(reply[0]===0),m.twiByteCycles()));},
    readByte(){m.requestBus({bus:'i2c',address:m.twiAddress,data:'',count:1,pins:m.busPins('i2c'),frequency:m.twi.sclFrequency,operation:'read'},reply=>m.cpu.addClockEvent(()=>m.twi.completeRead(reply[0]??255),m.twiByteCycles()));}};
   for(const port of Object.values(this.ports))port.addListener(()=>this.capturePins());this.capturePins();
  }
  twiByteCycles(){return Math.max(1,Math.round(this.clockHz/this.twi.sclFrequency*9));}
  get time(){return this.cpu.cycles*1e6/this.clockHz;}
  emit(event){this.events.push({...event,time:this.time,cycles:this.cpu.cycles,clock:'virtual',source:'mcu'});if(this.events.length>20000)throw Error('Забагато подій MCU в одному кроці');}
  capturePins(){for(const pin of this.options.pins||[]){if(!pin.port||pin.bit===undefined)continue;const state=this.ports[pin.port].pinState(pin.bit),previous=this.pinStates.get(pin.id);if(previous===state)continue;this.pinStates.set(pin.id,state);this.emit({type:'mode',pin:pin.id,mode:state===A.PinState.Input?'INPUT':state===A.PinState.InputPullUp?'INPUT_PULLUP':'OUTPUT'});if(state===A.PinState.High||state===A.PinState.Low)this.emit({type:'write',pin:pin.id,value:state===A.PinState.High?1:0});}}
  busPins(bus){return (bus==='i2c'?['SDA','SCL']:['SCK','MOSI','MISO','SS']).map(role=>(this.options.pins||[]).find(p=>(p.roles||[]).includes(role))?.id??null);}
  requestBus(event,complete){if(this.busPending)throw Error('Одночасні транзакції MCU не підтримуються');const token=++this.sequence;this.busPending={token,complete};this.emit({type:'bus',token,...event});}
  replyBus(token,reply){if(!this.busPending||this.busPending.token!==token)return;const p=this.busPending;this.busPending=null;if(typeof reply==='string'&&reply.startsWith('ERR:'))throw Error(reply.slice(4));p.complete(Array.isArray(reply)?reply:[]);}
  inputs(values){for(const pin of this.options.pins||[]){const volts=Number(values[pin.id]);if(!Number.isFinite(volts))continue;if(pin.adc!==undefined)this.adc.channelValues[pin.adc]=Math.max(0,Math.min(this.adc.avcc,volts));if(pin.port&&pin.bit!==undefined)this.ports[pin.port].setPin(pin.bit,volts>=this.adc.avcc*.5);}}
  sendSerial(bytes){if(!Array.isArray(bytes)||bytes.length+this.serialInput.length>8192||bytes.some(b=>!Number.isInteger(b)||b<0||b>255))throw Error('Некоректний або переповнений буфер UART');this.serialInput.push(...bytes);}
  pumpSerial(){if(this.serialInput.length&&this.usart.rxEnable&&!this.usart.rxBusy&&!(this.cpu.data[this.usart.config.UCSRA]&128))this.usart.writeByte(this.serialInput.shift(),false);}
  step(count=1){if(!Number.isInteger(count)||count<1||count>100000)throw Error('Некоректна кількість кроків');this.paused=false;for(let i=0;i<count&&!this.busPending;i++){this.instruction();}this.paused=true;return this.takeEvents();}
  instruction(){if(this.cpu.pc>=this.cpu.progMem.length)throw Error('PC за межами Flash');this.pumpSerial();A.avrInstruction(this.cpu);this.cpu.tick();}
  advance(cycles=16000){if(!Number.isInteger(cycles)||cycles<1||cycles>1000000)throw Error('Некоректний квант MCU');const end=this.cpu.cycles+cycles;while(this.cpu.cycles<end&&!this.busPending&&!this.paused){if(this.breakpoints.has(this.cpu.pc)&&!this.skipBreakpoint){this.paused=true;this.emit({type:'breakpoint',pc:this.cpu.pc*2});break;}this.skipBreakpoint=false;this.instruction();}return this.takeEvents();}
  takeEvents(){if(this.serial.length)this.emit({type:'serial',text:String.fromCharCode(...this.serial.splice(0,8192))});return this.events.splice(0);}
  snapshot(){return {backend:'avr8js-atmega328p',pc:this.cpu.pc*2,cycles:this.cpu.cycles,timeUs:this.time,clockHz:this.clockHz,paused:this.paused,waitingBus:!!this.busPending,registers:Array.from(this.cpu.data.slice(0,32)),sreg:this.cpu.SREG,sp:this.cpu.SP,flashUsed:this.flashUsed,uartBaud:this.usart.baudRate};}
 }
 const api={Machine,parseHex};if(typeof module!=='undefined')module.exports=api;root.WireMCU=api;
})(typeof globalThis!=='undefined'?globalThis:this);
