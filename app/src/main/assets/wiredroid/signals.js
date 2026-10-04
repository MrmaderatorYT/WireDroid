(function(root){
 'use strict';
 const MAX_EDGES=100000;
 function windowCheck(start,end){if(!Number.isFinite(start)||!Number.isFinite(end)||start<0||end<start||end>1e12)throw Error('Некоректне часове вікно сигналів');}
 function binary(value,threshold=.5){return value==null?null:Number(value>=threshold);}
 class Timeline{
  constructor(options={}){this.maxEvents=Math.min(16384,Math.max(16,options.maxEvents||4096));this.maxTotal=Math.min(262144,Math.max(128,options.maxTotal||65536));this.maxChannels=Math.min(4096,Math.max(1,options.maxChannels||1024));this.events=new Map();this.epochs=new Map();this.channels=new Map();this.total=0;this.sequence=0;}
  reset(){this.events.clear();this.epochs.clear();this.channels.clear();this.total=0;this.sequence=0;}
  record(board,pin,event,now){
   if(!['write','pwm','servo','sample'].includes(event.type))return false;
   const virtual=event.clock==='virtual'||event.source==='mcu',clock=virtual?'virtual':event.clock||'wall',domain=event.clockId||event.domain||clock,source=event.source||(virtual?'mcu':'android-gpio');
   const raw=Number.isFinite(event.time)?event.time:now;if(!Number.isFinite(raw)||!Number.isFinite(now)&&!virtual||raw<0||raw>1e12)throw Error('Некоректна часова мітка сигналу');
   const key=board+':'+pin;if(!this.events.has(key)&&this.events.size>=this.maxChannels)throw Error('Перевищено ліміт каналів сигналів');
   const metadata=this.channels.get(key);if(metadata&&metadata.domain!==domain)throw Error('Один канал містить різні годинники; почніть новий запис');
   if(!virtual&&!this.epochs.has(board))this.epochs.set(board,now-raw);
   const time=virtual?raw:raw+(this.epochs.get(board)||0),value=event.value??event.pulse/20000,frequency=event.type==='servo'?50:event.frequency||0;
   if(!Number.isFinite(time)||!Number.isFinite(value)||!Number.isFinite(frequency)||frequency<0||frequency>1e8)throw Error('Некоректний сигнал');
   const reconstructed=!!event.reconstructed||frequency>0;
   const e={time,type:event.type,value,frequency,clock,domain,source,reconstructed,sequence:this.sequence++};if(Number.isFinite(event.cycles))e.cycles=event.cycles;
   let list=this.events.get(key);if(!list)this.events.set(key,list=[]);if(list.length&&time<list.at(-1).time)return false;
   list.push(e);this.total++;if(list.length>this.maxEvents){list.shift();this.total--;}
   this.channels.set(key,{board,pin,clock,domain,source,reconstructed:!!metadata?.reconstructed||reconstructed});
   while(this.total>this.maxTotal){let oldest=null;for(const values of this.events.values())if(values.length&&(!oldest||values[0].sequence<oldest[0].sequence))oldest=values;if(!oldest)break;oldest.shift();this.total--;}
   return true;
  }
  metadata(pin){const meta=this.channels.get(pin);return meta?{...meta,retained:(this.events.get(pin)||[]).length}:null;}
  range(pins=[...this.events.keys()]){if(typeof pins==='string')pins=[pins];const lists=pins.map(p=>this.events.get(p)||[]).filter(p=>p.length);return {start:lists.length?Math.min(...lists.map(p=>p[0].time)):0,end:lists.length?Math.max(...lists.map(p=>p.at(-1).time)):0};}
  clock(pins){if(typeof pins==='string')pins=[pins];const meta=pins.map(p=>this.metadata(p)).filter(Boolean),domains=new Set(meta.map(p=>p.domain));if(domains.size>1)throw Error('Канали мають різні годинники; експортуйте їх окремо');return {domain:meta[0]?.domain||'unknown',clock:meta[0]?.clock||'unknown',sources:[...new Set(meta.map(p=>p.source))],reconstructed:meta.some(p=>p.reconstructed)};}
  eventAt(pin,time){const list=this.events.get(pin)||[];let lo=0,hi=list.length;while(lo<hi){const mid=(lo+hi)>>1;if(list[mid].time<=time)lo=mid+1;else hi=mid;}return lo?list[lo-1]:null;}
  valueAt(pin,time){const e=this.eventAt(pin,time);if(!e)return null;if(!e.frequency||e.value<=0||e.value>=1)return e.value;const period=1e6/e.frequency,position=(time-e.time)%period;return position<e.value*period?1:0;}
  points(pin,start,end,max=2000){
   windowCheck(start,end);max=Math.min(MAX_EDGES+1,Math.max(1,max));
   const list=this.events.get(pin)||[],segments=[this.eventAt(pin,start),...list.filter(e=>e.time>start&&e.time<=end)].filter(Boolean),out=[];
   for(let i=0;i<segments.length;i++){
    const e=segments[i],a=Math.max(start,e.time),b=Math.min(end,segments[i+1]?.time??end);out.push({time:a,value:this.valueAt(pin,a)});if(out.length>=max)return out;
    if(e.frequency&&e.value>0&&e.value<1){const period=1e6/e.frequency;let cycle=Math.max(0,Math.floor((a-e.time)/period));while(e.time+cycle*period<=b){for(const [offset,value]of [[0,1],[e.value*period,0]]){const time=e.time+cycle*period+offset;if(time>a&&(time<b||i===segments.length-1&&time<=b))out.push({time,value});if(out.length>=max)return out;}cycle++;}}
   }
   return out;
  }
  edges(pin,start,end,threshold=.5){const points=this.points(pin,start,end,MAX_EDGES+1);if(points.length>MAX_EDGES)throw Error('Забагато фронтів: зменште часове вікно');let previous=null;const result=[];for(const p of points){const value=binary(p.value,threshold);if(previous!==null&&value!==previous)result.push({time:p.time,value,edge:value?'rising':'falling'});previous=value;}return result;}
  measure(pin,start,end){
   const edges=this.edges(pin,start,end),rising=edges.filter(e=>e.value),falling=edges.filter(e=>!e.value),periods=[];for(let i=1;i<rising.length;i++)periods.push(rising[i].time-rising[i-1].time);
   const high=[],low=[];for(let i=1;i<edges.length;i++)(edges[i-1].value?high:low).push(edges[i].time-edges[i-1].time);
   const average=list=>list.length?list.reduce((a,b)=>a+b,0)/list.length:null,periodUs=average(periods),highUs=average(high),lowUs=average(low);
   return {pin,periodUs,frequencyHz:periodUs?1e6/periodUs:null,highUs,lowUs,duty:highUs!=null&&lowUs!=null?highUs/(highUs+lowUs):null,rising:rising.length,falling:falling.length,metadata:this.metadata(pin)};
  }
  trigger(pin,options={}){const range=this.range(pin),start=options.start??range.start,end=options.end??range.end,edge=options.edge||'rising';if(!['rising','falling','any'].includes(edge))throw Error('Невідомий тип тригера');const match=this.edges(pin,start,end,options.level??.5).find(e=>edge==='any'||e.edge===edge);if(!match)return null;const pre=Math.max(0,options.preUs||0),post=Math.max(0,options.postUs||0);return {...match,pin,start:Math.max(start,match.time-pre),end:Math.min(end,match.time+post),metadata:this.metadata(pin)};}
  capture(pins,options={}){if(typeof pins==='string')pins=[pins];const clock=this.clock(pins),range=this.range(pins),trigger=this.trigger(options.pin||pins[0],{...options,start:options.start??range.start,end:options.end??range.end});if(!trigger)return null;return {clock,trigger,start:trigger.start,end:trigger.end,channels:pins.map(pin=>({pin,metadata:this.metadata(pin),points:this.points(pin,trigger.start,trigger.end,options.maxPoints||2000)}))};}
  vcd(pin,start,end){return this.vcdChannels([pin],start,end);}
  vcdChannels(pins,start,end,options={}){
   windowCheck(start,end);if(typeof pins==='string')pins=[pins];pins=[...new Set(pins)];if(!pins.length||pins.length>256)throw Error('Виберіть від 1 до 256 каналів');const clock=this.clock(pins);if(options.clock&&options.clock!==clock.domain)throw Error('Неправильний годинник експорту');
   const virtual=clock.clock==='virtual',scale=virtual?1000000:1,unit=virtual?'1ps':'1us';if((end-start)*scale>Number.MAX_SAFE_INTEGER)throw Error('Часове вікно VCD надто довге');
   const identifier=i=>{let text='';do{text=String.fromCharCode(33+i%94)+text;i=Math.floor(i/94)-1;}while(i>=0);return text;};
   let total=0;const changes=[];for(let i=0;i<pins.length;i++){const pin=pins[i],points=this.points(pin,start,end,MAX_EDGES+1);total+=points.length;if(total>MAX_EDGES)throw Error('Понад 100000 фронтів: зменште часове вікно VCD');for(const p of points)changes.push({time:Math.round((p.time-start)*scale),value:p.value>=.5?'1':'0',id:identifier(i)});}
   changes.sort((a,b)=>a.time-b.time||a.id.localeCompare(b.id));
   let data='$timescale '+unit+' $end\n$comment clock='+clock.domain+'; source='+clock.sources.join(',')+'; pwm='+(clock.reconstructed?'contains-reconstructed':'recorded-edges')+' $end\n$scope module WireDroid $end\n';
   for(let i=0;i<pins.length;i++){const name=pins[i].replace(/[^A-Za-z0-9_$]/g,'_');data+='$var wire 1 '+identifier(i)+' '+name+' $end\n';const m=this.metadata(pins[i]);data+='$comment channel='+name+'; source='+(m?.source||'unknown')+'; reconstructed='+!!m?.reconstructed+' $end\n';}
   data+='$upscope $end\n$enddefinitions $end\n';let time=null;for(const p of changes){if(time!==p.time){data+='#'+p.time+'\n';time=p.time;}data+=p.value+p.id+'\n';}return data;
  }
  decodeUART(pin,options={}){
   const clock=this.clock([pin]),range=this.range(pin),start=options.start??range.start,end=options.end??range.end,baud=options.baud||9600,bits=options.bits||8,parity=options.parity||'none',stopBits=options.stopBits||1,invert=!!options.invert,max=Math.min(4096,options.maxFrames||1024);
   if(!Number.isFinite(baud)||baud<1||baud>1e8||!Number.isInteger(bits)||bits<5||bits>9||!['none','even','odd'].includes(parity)||![1,1.5,2].includes(stopBits))throw Error('Некоректні параметри UART');
   const bitUs=1e6/baud,frames=[],errors=[];let busy=-Infinity;const read=time=>{const value=binary(this.valueAt(pin,time));return value==null?null:value^(invert?1:0);};
   for(const edge of this.edges(pin,start,end)){
    if(edge.value!==(invert?1:0)||edge.time<busy)continue;const at=edge.time,frameEnd=at+(1+bits+(parity==='none'?0:1)+stopBits)*bitUs;if(frameEnd>end){errors.push({time:at,error:'truncated'});break;}
    if(read(at+.5*bitUs)!==0)continue;let value=0,ones=0;const faults=[];
    for(let i=0;i<bits;i++){const bit=read(at+(1.5+i)*bitUs);if(bit==null){faults.push('missing-data');break;}value+=bit*2**i;ones+=bit;}
    if(parity!=='none'){const expected=parity==='even'?ones%2:1-ones%2;if(read(at+(1.5+bits)*bitUs)!==expected)faults.push('parity');}
    const stopAt=at+(1+bits+(parity==='none'?0:1))*bitUs;for(const fraction of [.5,stopBits-.25])if(read(stopAt+fraction*bitUs)!==1){faults.push('framing');break;}
    frames.push({time:at,end:frameEnd,value,text:value<256?String.fromCharCode(value):'',errors:faults});for(const error of faults)errors.push({time:at,error});busy=frameEnd;if(frames.length>=max)break;
   }
   return {protocol:'uart',pin,baud,bits,parity,stopBits,clock,frames,errors};
  }
  decodeSPI(options={}){
   const {clock:pin,mosi,miso,select}=options;if(!pin||!mosi&&!miso)throw Error('Для SPI потрібні SCK і MOSI або MISO');const channels=[pin,mosi,miso,select].filter(Boolean),clock=this.clock(channels),range=this.range(channels),start=options.start??range.start,end=options.end??range.end,mode=options.mode??0,bits=options.bits??8,order=options.order||'msb',active=options.active??0,max=Math.min(4096,options.maxFrames||1024);
   if(![0,1].includes(active)||![0,1,2,3].includes(mode)||!Number.isInteger(bits)||bits<1||bits>32||!['msb','lsb'].includes(order))throw Error('Некоректні параметри SPI');
   const sampleRising=mode===0||mode===3,frames=[],errors=[];let word=null,selectedEpoch=null;
   const finish=()=>{if(word?.count)errors.push({time:word.time,error:'partial-word',bits:word.count});word=null;};
   for(const edge of this.edges(pin,start,end)){
    if(select){const cs=this.eventAt(select,edge.time);if(!cs||binary(this.valueAt(select,edge.time))!==active){finish();selectedEpoch=null;continue;}if(selectedEpoch!==cs.time){finish();selectedEpoch=cs.time;}}
    if(edge.value!==(sampleRising?1:0))continue;if(!word)word={time:edge.time,end:edge.time,count:0,mosi:mosi?0:null,miso:miso?0:null};
    for(const [name,channel]of [['mosi',mosi],['miso',miso]])if(channel){const bit=binary(this.valueAt(channel,edge.time));if(bit==null){errors.push({time:edge.time,error:'missing-'+name});word[name]=null;}else if(word[name]!=null)word[name]=order==='msb'?word[name]*2+bit:word[name]+bit*2**word.count;}
    word.count++;word.end=edge.time;if(word.count===bits){frames.push({...word,bits,mode,order});word=null;if(frames.length>=max)break;}
   }
   finish();return {protocol:'spi',clock,mode,order,bits,frames,errors};
  }
  decodeI2C(options={}){
   const {scl,sda}=options;if(!scl||!sda)throw Error('Для I²C потрібні SCL і SDA');const clock=this.clock([scl,sda]),range=this.range([scl,sda]),start=options.start??range.start,end=options.end??range.end,max=Math.min(4096,options.maxFrames||1024);
   const changes=[...this.edges(scl,start,end).map(e=>({...e,pin:'scl'})),...this.edges(sda,start,end).map(e=>({...e,pin:'sda'}))].sort((a,b)=>a.time-b.time||(a.pin==='sda'?-1:1));if(changes.length>MAX_EDGES)throw Error('Забагато фронтів I²C');
   const transactions=[],frames=[],errors=[];let transaction=null,byte=0,count=0,byteStart=0;
   for(const edge of changes){
    if(edge.pin==='sda'&&binary(this.valueAt(scl,edge.time))===1){
     if(edge.value===0){const repeated=!!transaction;if(transaction){transaction.end=edge.time;transaction.repeatedEnd=true;if(count>1)errors.push({time:edge.time,error:'partial-byte',bits:count});}transaction={time:edge.time,end:null,address:null,addressSeen:false,read:false,bytes:[],stopped:false,repeatedStart:repeated};transactions.push(transaction);byte=0;count=0;}
     else if(transaction){transaction.end=edge.time;transaction.stopped=true;if(count>1)errors.push({time:edge.time,error:'partial-byte',bits:count});transaction=null;count=0;}
    }else if(edge.pin==='scl'&&edge.value===1&&transaction){
     const bit=binary(this.valueAt(sda,edge.time));if(bit==null){errors.push({time:edge.time,error:'missing-data'});continue;}
     if(count===0)byteStart=edge.time;
     if(count<8){byte=byte*2+bit;count++;}
     else{const ack=bit===0,frame={time:byteStart,end:edge.time,value:byte,ack,address:!transaction.addressSeen};frames.push(frame);transaction.bytes.push(frame);if(frame.address){transaction.addressSeen=true;transaction.read=!!(byte&1);if((byte&0xf8)===0xf0){transaction.unsupported='10-bit-address';errors.push({time:byteStart,error:'10-bit-address-unsupported'});}else transaction.address=byte>>1;}byte=0;count=0;if(frames.length>=max)break;}
    }
   }
   if(transaction){transaction.end=end;transaction.truncated=true;if(count)errors.push({time:end,error:'partial-byte',bits:count});}
   return {protocol:'i2c',clock,transactions,frames,errors};
  }
  decode(protocol,options={}){if(protocol==='uart')return this.decodeUART(options.pin,options);if(protocol==='spi')return this.decodeSPI(options);if(protocol==='i2c')return this.decodeI2C(options);throw Error('Невідомий декодер сигналів');}
 }
 const api={Timeline};if(typeof module!=='undefined')module.exports=api;root.WireSignals=api;
})(typeof globalThis!=='undefined'?globalThis:this);
