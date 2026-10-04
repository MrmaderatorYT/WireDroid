importScripts('vendor/avr8js.js','mcu.js');
let machine=null,target=null,stepPending=false,activeRequest=null;
function send(type='batch',events=machine.takeEvents(),requestId=null){postMessage({type,events,state:machine.snapshot(),requestId});}
function complete(){const requestId=activeRequest;activeRequest=null;send('complete',machine.takeEvents(),requestId);}
function advance(){
 if(target!==null){const events=machine.advance(Math.max(1,Math.min(1000000,target-machine.cpu.cycles)));send('batch',events);if(machine.busPending)return;target=null;complete();}
 else if(stepPending&&!machine.busPending){stepPending=false;complete();}
}
onmessage=({data})=>{try{
 if(data.type==='load'){machine=new WireMCU.Machine(data.options);target=null;stepPending=false;activeRequest=null;send('loaded',machine.takeEvents(),data.requestId);}
 else if(data.type==='advance'){if(machine.paused)machine.skipBreakpoint=true;machine.paused=false;target=machine.cpu.cycles+data.cycles;activeRequest=data.requestId;advance();}
 else if(data.type==='pause'){machine.paused=true;target=null;stepPending=false;const cancelled=activeRequest;activeRequest=null;send('state',machine.takeEvents(),cancelled??data.requestId);}
 else if(data.type==='step'){target=null;activeRequest=data.requestId;const events=machine.step(data.count||1);stepPending=!!machine.busPending;if(stepPending)send('batch',events);else{const requestId=activeRequest;activeRequest=null;send('complete',events,requestId);}}
 else if(data.type==='reset'){target=null;stepPending=false;activeRequest=null;machine.reset();machine.paused=true;send('state',machine.takeEvents(),data.requestId);}
 else if(data.type==='inputs')machine.inputs(data.volts||{});
 else if(data.type==='serial')machine.sendSerial(data.bytes||[]);
 else if(data.type==='breakpoints')machine.breakpoints=new Set((data.addresses||[]).map(x=>Math.floor(x/2)));
 else if(data.type==='bus-reply'){machine.replyBus(data.token,data.reply);advance();}
}catch(error){target=null;postMessage({type:'error',text:error.message});}};
