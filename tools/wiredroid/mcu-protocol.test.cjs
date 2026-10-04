'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {Machine}=require('../../app/src/main/assets/wiredroid/mcu.js');
const options={hex:fs.readFileSync(path.join(__dirname,'fixtures/avr-blink.hex'),'utf8'),clockHz:16000000,pins:[{id:'sda',port:'C',bit:4,roles:['SDA']},{id:'scl',port:'C',bit:5,roles:['SCL']},{id:'sck',port:'B',bit:5,roles:['SCK']},{id:'mosi',port:'B',bit:3,roles:['MOSI']},{id:'miso',port:'B',bit:4,roles:['MISO']},{id:'ss',port:'B',bit:2,roles:['SS']}]};
function machine(){const m=new Machine(options);m.advance(20000);m.takeEvents();return m;}
{
 const m=machine(),c=m.usart.config;m.cpu.writeData(c.UCSRB,0x18);m.cpu.writeData(c.UBRRH,0);m.cpu.writeData(c.UBRRL,103);m.sendSerial([65,0,66,255]);let received=[];
 for(let i=0;i<100;i++){m.advance(1000);if(m.cpu.data[c.UCSRA]&128)received.push(m.cpu.readData(c.UDR));if(received.length===4)break;}
 assert.deepEqual(received,[65,0,66,255]);assert.equal(m.serialInput.length,0);assert.equal(m.usart.rxBusy,false);assert.throws(()=>m.sendSerial(Array(8193).fill(1)));assert.throws(()=>m.sendSerial([256]));
}
{
 const m=machine(),c=m.spi.config;m.cpu.writeData(c.SPCR,0x50);m.cpu.writeData(c.SPDR,0xa5);let events=m.takeEvents();const request=events.find(e=>e.type==='bus');assert(request);assert.equal(request.bus,'spi');assert.deepEqual(request.pins,['sck','mosi','miso','ss']);assert.equal(m.busPending.token,request.token);const before=m.cpu.cycles;m.advance(1000);assert.equal(m.cpu.cycles,before,'CPU waits only for external bus reply');
 m.replyBus(request.token,[0x3c]);assert.equal(m.busPending,null);assert.equal(m.cpu.data[c.SPSR]&128,0,'SPI must not complete immediately on host reply');m.advance(m.spi.transferCycles+20);assert(m.cpu.data[c.SPSR]&128);assert.equal(m.cpu.readData(c.SPDR),0x3c);
}
{
 const m=machine(),c=m.twi.config;m.cpu.writeData(c.TWBR,72);m.cpu.writeData(c.TWCR,0xa4);m.advance(50);assert.equal(m.twi.status,0x08);m.cpu.writeData(c.TWDR,0xa0);m.cpu.writeData(c.TWCR,0x84);let events=m.advance(100);const address=events.find(e=>e.type==='bus');assert(address);assert.equal(address.operation,'address');assert.equal(address.address,0x50);assert.equal(address.frequency,100000);m.replyBus(address.token,[0]);assert.equal(m.twi.status,0x08,'address ACK schedules9realSCLclocks');m.advance(m.twiByteCycles()+10);assert.equal(m.twi.status,0x18);
 m.cpu.writeData(c.TWDR,42);m.cpu.writeData(c.TWCR,0x84);events=m.advance(100);const data=events.find(e=>e.type==='bus');assert.equal(data.operation,'byte');assert.equal(data.data,'2a');m.replyBus(data.token,[1]);m.advance(m.twiByteCycles()+10);assert.equal(m.twi.status,0x30,'data NACK reaches actual TWI status register');
}
console.log('PASS MCU register-level UART RX queue/nullbyte/overflow, SPI hostreply+hardwarecycles, TWI100kHz/addressACK/dataNACK delayed9clock completion; compiledecho still separate');
