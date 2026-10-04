'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),vm=require('node:vm');
const assets=path.resolve(__dirname,'../../app/src/main/assets/wiredroid');
// Execute the actual worker protocol with asynchronous, FIFO message delivery.
class TestWorker {
 constructor(){this.dead=false;const self=this;this.context=vm.createContext({console,postMessage(data){if(data.type==='bus-reply'&&this.holdBusReply){(this.heldReplies??=[]).push(data);return;}setImmediate(()=>{if(!self.dead)self.onmessage?.({data:structuredClone(data)});});},importScripts(...files){for(const file of files)vm.runInContext(fs.readFileSync(path.join(assets,file),'utf8'),self.context,{filename:file});}});vm.runInContext(fs.readFileSync(path.join(assets,'mcu-worker.js'),'utf8'),this.context,{filename:'mcu-worker.js'});}
 postMessage(data){if(data.type==='bus-reply'&&this.holdBusReply){(this.heldReplies??=[]).push(data);return;}setImmediate(()=>{if(this.dead)return;try{this.context.onmessage({data:structuredClone(data)});}catch(error){this.onerror?.(error);}});}
 terminate(){this.dead=true;}
}
const scope=vm.createContext({Worker:TestWorker,setTimeout,clearTimeout,console});vm.runInContext(fs.readFileSync(path.join(assets,'avr-runtime.js'),'utf8'),scope);const {Runtime}=scope.WireAVRRuntime;
const firmware=fs.readFileSync(path.join(__dirname,'fixtures/avr-blink.hex'),'utf8'),options={hex:firmware,pins:[{id:'led',port:'B',bit:5}],clockHz:16000000};
(async()=>{
 const errors=[],rt=new Runtime({event(){},state(){},bus(){return [];},error(id,error){errors.push(error);}});
 try{await rt.load('uno',options);const before=rt.sessions.get('uno').state;const stepped=await rt.step('uno',1);assert(stepped.cycles>before.cycles,'step must resolve with its completed state, not preceding pause acknowledgement');assert.equal(stepped.paused,true);assert.equal(stepped.cycles,rt.sessions.get('uno').state.cycles);
 await rt.reset('uno');assert.equal(rt.sessions.get('uno').state.cycles,0);
 const session=rt.sessions.get('uno'),originalPost=session.worker.postMessage.bind(session.worker);session.worker.postMessage=data=>{if(data.type!=='step')originalPost(data);};rt.timeoutMs=30;await assert.rejects(rt.step('uno',1),/MCU не відповідає/);assert.equal(session.wait,null);session.worker.postMessage=originalPost;rt.timeoutMs=5000;await rt.reset('uno');
 session.worker.holdBusReply=true;vm.runInContext('machine.cpu.writeData(machine.spi.config.SPCR,0x50);machine.cpu.writeData(machine.spi.config.SPDR,42);',session.worker.context);const pendingAdvance=rt.request(session,{type:'advance',cycles:16000});await new Promise(resolve=>setTimeout(resolve,10));rt.pause();const paused=await pendingAdvance;assert.equal(paused.paused,true);assert.equal(paused.waitingBus,true);session.worker.holdBusReply=false;for(const reply of session.worker.heldReplies)originalPost(reply);await new Promise(resolve=>setTimeout(resolve,10));const afterReply=await rt.step('uno',1);assert.equal(afterReply.waitingBus,false);assert(afterReply.cycles>paused.cycles);await rt.reset('uno');
 await rt.load('nano',options);rt.run();await new Promise(resolve=>setTimeout(resolve,80));rt.pause();await new Promise(resolve=>setTimeout(resolve,20));const states=[...rt.sessions.values()].map(s=>s.state);assert(states.every(s=>s.paused));assert(Math.abs(states[0].timeUs-states[1].timeUs)<=1001,'multiboard ordering remains bounded by one quantum');assert.deepEqual(errors,[]);console.log('PASS actual worker/runtime async pause-step-reset acknowledgements, timeout cleanup, pause during pendingbus and bounded1ms multiboard ordering');
 }finally{rt.stop();}
})().catch(error=>{console.error(error);process.exitCode=1;});
