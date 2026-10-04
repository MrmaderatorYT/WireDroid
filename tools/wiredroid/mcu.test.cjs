'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {Machine,parseHex}=require('../../app/src/main/assets/wiredroid/mcu.js');
const hex=name=>fs.readFileSync(path.join(__dirname,'fixtures',name+'.hex'),'utf8');
const pins=[{id:'led',port:'B',bit:5},{id:'irq',port:'D',bit:2},{id:'result',port:'B',bit:0},{id:'pwm',port:'D',bit:5},{id:'analog',port:'C',bit:0,adc:0}];
function run(machine,ms){let events=[];for(let i=0;i<ms;i++){events.push(...machine.advance(16000));for(const e of events.filter(e=>e.type==='bus'))if(machine.busPending)machine.replyBus(e.token,e.count?[0]:[0]);}return events;}
const blink=new Machine({hex:hex('avr-blink'),pins,clockHz:16000000});
const events=run(blink,510).filter(e=>e.type==='write'&&e.pin==='led');assert.equal(events.length,7);assert.deepEqual(events.map(e=>e.value),[0,1,0,1,0,1,0]);for(let i=2;i<events.length;i++)assert.ok(Math.abs(events[i].time-events[i-1].time-100000)<30);assert.ok(events.every(e=>e.time===e.cycles/16&&e.clock==='virtual'));
const repeated=new Machine({hex:hex('avr-blink'),pins,clockHz:16000000});assert.deepEqual(run(repeated,510).filter(e=>e.type==='write'&&e.pin==='led'),events,'identical initial state gives identical instruction timestamps');
const validation=new Machine({hex:hex('avr-validation'),pins,clockHz:16000000});validation.inputs({analog:2.5,irq:5});let output=run(validation,25);assert.match(output.filter(e=>e.type==='serial').map(e=>e.text).join(''),/WireDroid AVR[\s\S]*ADC=512 INT=0/);const pwm=output.filter(e=>e.type==='write'&&e.pin==='pwm');assert.ok(pwm.length>20,'hardware Timer0 PWM edges');validation.inputs({irq:0,analog:0});output=run(validation,25);assert.match(output.filter(e=>e.type==='serial').map(e=>e.text).join(''),/ADC=0 INT=1/);assert.ok(output.some(e=>e.type==='write'&&e.pin==='result'&&e.value===1),'real external interrupt ISR');
validation.paused=true;const cycles=validation.cpu.cycles;validation.advance(16000);assert.equal(validation.cpu.cycles,cycles);validation.step();assert.ok(validation.cpu.cycles>cycles);validation.breakpoints.add(validation.cpu.pc);validation.paused=false;assert.ok(validation.advance(16000).some(e=>e.type==='breakpoint'));assert.equal(validation.paused,true);
validation.eeprom[12]=42;validation.reset();assert.equal(validation.eeprom[12],42);assert.equal(validation.cpu.cycles,0);
for(const bad of ['x',':00000001FE',':00000001FF',hex('avr-blink')+'\n:00000001FF'])assert.throws(()=>parseHex(bad));assert.throws(()=>parseHex(hex('avr-blink'),512));
assert.equal(parseHex(hex('avr-nano')).used,922);assert.ok(parseHex(hex('avr-multifile')).used>5000);
console.log('PASS AVR: real Arduino core HEX, 100ms GPIO, deterministic virtual clocks, Timer0 PWM, ADC, UART output, external interrupt ISR, pause/step/breakpoint/reset, persistent EEPROM, strict HEX and Nano/multi-file artifacts');
