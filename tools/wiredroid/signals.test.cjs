const assert=require('node:assert/strict');
const {Timeline}=require('../../app/src/main/assets/wiredroid/signals.js');
const timeline=()=>new Timeline(),record=(t,p,time,value,options={})=>t.record('uno',p,{type:'write',time,value,clock:'virtual',source:'mcu',...options},500000+time);
{
 const t=timeline();record(t,'p',0,0);record(t,'p',.0625,1);record(t,'q',.125,1);assert.equal(t.events.get('uno:p')[1].time,.0625);assert.equal(t.metadata('uno:p').clock,'virtual');const vcd=t.vcdChannels(['uno:p','uno:q'],0,1);assert.match(vcd,/\$timescale 1ps/);assert.match(vcd,/#62500\n1!/);assert.match(vcd,/#125000\n1"/);assert.match(vcd,/clock=virtual; source=mcu/);
 t.record('esp','p',{type:'write',time:0,value:0},500000);assert.throws(()=>t.vcdChannels(['uno:p','esp:p'],0,1),/різні годинники/);
 assert.throws(()=>t.record('uno','p',{type:'write',time:2,value:0},2),/різні годинники/);
}
{
 const t=timeline();t.record('uno','p',{type:'pwm',time:100,value:.25,frequency:1000},500);assert.equal(t.events.get('uno:p')[0].time,500);assert.equal(t.valueAt('uno:p',749),1);assert.equal(t.valueAt('uno:p',750),0);assert.match(t.vcd('uno:p',500,2500),/contains-reconstructed/);assert.equal(t.measure('uno:p',500,5500).periodUs,1000);assert.equal(t.measure('uno:p',500,5500).highUs,250);
}
{
 const t=timeline();for(const [at,value]of [[0,0],[10,1],[20,0],[30,1],[40,0],[50,1],[60,0]])record(t,'p',at,value);
 const m=t.measure('uno:p',0,60);assert.equal(m.periodUs,20);assert.equal(m.highUs,10);assert.equal(m.duty,.5);assert.equal(m.frequencyHz,50000);
 const trigger=t.trigger('uno:p',{edge:'falling',start:0,end:60,preUs:5,postUs:8});assert.equal(trigger.time,20);assert.equal(trigger.start,15);assert.equal(trigger.end,28);const captured=t.capture(['uno:p'],{pin:'uno:p',edge:'rising',start:0,end:60,preUs:4,postUs:6});assert.equal(captured.trigger.time,10);assert.equal(captured.clock.clock,'virtual');
}
function uart(t,value,{baud=100000,parity='none',badParity=false,badStop=false,invert=false}={}){const b=1e6/baud,start=20,put=(at,v)=>record(t,'tx',at,v^(invert?1:0));put(0,1);put(start,0);let ones=0;for(let i=0;i<8;i++){const bit=value>>i&1;ones+=bit;put(start+(i+1)*b,bit);}let stop=9;if(parity!=='none'){put(start+9*b,(parity==='even'?ones%2:1-ones%2)^(badParity?1:0));stop++;}put(start+stop*b,badStop?0:1);put(start+(stop+2)*b,1);return {baud,parity,invert};}
{
 const t=timeline(),options=uart(t,0x41);const d=t.decodeUART('uno:tx',options);assert.equal(d.frames.length,1);assert.equal(d.frames[0].value,0x41);assert.equal(d.frames[0].text,'A');assert.deepEqual(d.errors,[]);
 const p=timeline(),po=uart(p,0x55,{parity:'even',badParity:true});assert.equal(p.decodeUART('uno:tx',po).errors[0].error,'parity');const f=timeline(),fo=uart(f,0x55,{badStop:true});assert.equal(f.decodeUART('uno:tx',fo).errors[0].error,'framing');const inv=timeline(),io=uart(inv,0x82,{invert:true});assert.equal(inv.decodeUART('uno:tx',io).frames[0].value,0x82);
}
function spi(mode,order){const t=timeline(),idle=mode>=2?1:0;record(t,'sck',0,idle);record(t,'cs',0,1);record(t,'mosi',0,0);record(t,'miso',0,0);record(t,'cs',5,0);let time=10;const input=0xa5,output=0x3c;
 for(let i=0;i<8;i++){const bit=order==='lsb'?i:7-i;if(mode%2===0){record(t,'mosi',time,input>>bit&1);record(t,'miso',time,output>>bit&1);}record(t,'sck',time+2,1-idle);if(mode%2===1){record(t,'mosi',time+3,input>>bit&1);record(t,'miso',time+3,output>>bit&1);}record(t,'sck',time+6,idle);time+=10;}record(t,'cs',time+5,1);return t.decodeSPI({clock:'uno:sck',mosi:'uno:mosi',miso:'uno:miso',select:'uno:cs',mode,order});}
{
 for(const mode of [0,1,2,3])for(const order of ['msb','lsb']){const d=spi(mode,order);assert.equal(d.frames.length,1);assert.equal(d.frames[0].mosi,0xa5);assert.equal(d.frames[0].miso,0x3c);assert.deepEqual(d.errors,[]);}
}
{
 const t=timeline();let time=0;const put=(pin,value)=>record(t,pin,time,value);put('scl',1);put('sda',1);time=5;put('sda',0);
 function byte(value,ack){for(let bit=7;bit>=0;bit--){time+=5;put('scl',0);time+=1;put('sda',value>>bit&1);time+=4;put('scl',1);}time+=5;put('scl',0);time+=1;put('sda',ack?0:1);time+=4;put('scl',1);time+=5;put('scl',0);}
 byte(0xa0,true);byte(0x2a,false);time+=1;put('sda',0);time+=4;put('scl',1);time+=5;put('sda',1);time+=5;put('scl',1);
 const d=t.decodeI2C({scl:'uno:scl',sda:'uno:sda'});assert.equal(d.transactions.length,1);assert.equal(d.transactions[0].address,0x50);assert.equal(d.transactions[0].read,false);assert.equal(d.transactions[0].stopped,true);assert.deepEqual(d.frames.map(f=>f.value),[0xa0,0x2a]);assert.deepEqual(d.frames.map(f=>f.ack),[true,false]);assert.deepEqual(d.errors,[]);
}
{
 const t=new Timeline({maxEvents:16,maxTotal:128});for(let i=0;i<300;i++)record(t,'p'+i%8,i,i%2);assert(t.total<=128);assert([...t.events.values()].every(v=>v.length<=16));assert.throws(()=>t.vcd('uno:p0',0,Number.MAX_SAFE_INTEGER),/довге|часове/);
 const dense=timeline();dense.record('uno','p',{type:'pwm',time:0,value:.5,frequency:1e8},0);assert.throws(()=>dense.vcd('uno:p',0,1000),/100000 фронтів/);
}

{
 const t=timeline();t.record('uno','p',{type:'pwm',time:0,value:.5,frequency:1000},0);t.record('uno','p',{type:'write',time:1000,value:0},1000);assert.deepEqual(t.points('uno:p',0,2000),[{time:0,value:1},{time:500,value:0},{time:1000,value:0}]);assert.equal(t.edges('uno:p',0,2000).filter(e=>e.edge==='rising').length,0,'overriding PWM must not invent a carrier edge at commandboundary');
}
{
 const t=timeline();let time=0;const put=(pin,value)=>record(t,pin,time,value);put('scl',1);put('sda',1);time=5;put('sda',0);
 const byte=(value,ack)=>{for(let bit=7;bit>=0;bit--){time+=5;put('scl',0);time+=1;put('sda',value>>bit&1);time+=4;put('scl',1);}time+=5;put('scl',0);time+=1;put('sda',ack?0:1);time+=4;put('scl',1);time+=5;put('scl',0);};
 byte(0xa0,true);byte(0,true);time+=1;put('sda',1);time+=4;put('scl',1);time+=5;put('sda',0);byte(0xa1,true);byte(42,false);time+=1;put('sda',0);time+=4;put('scl',1);time+=5;put('sda',1);
 const d=t.decodeI2C({scl:'uno:scl',sda:'uno:sda'});assert.equal(d.transactions.length,2);assert.equal(d.transactions[1].repeatedStart,true);assert.equal(d.transactions[1].read,true);assert.equal(d.transactions[1].address,0x50);assert.equal(d.transactions[1].bytes[1].value,42);assert.deepEqual(d.errors,[]);
}
console.log('PASS virtual timestamps/1ps multichannel VCD/clocks; PWM provenance; period/pulse/trigger; UART framing/parity/invert; SPI4modes/MSB/LSB; I2C START/address/ACK/NACK/STOP; bounded traces');
