const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const assets=path.resolve(__dirname,'../../app/src/main/assets/wiredroid'),{Registry}=require(path.join(assets,'boards.js'));
const data=JSON.parse(fs.readFileSync(path.join(assets,'boards.json'))),catalog=JSON.parse(fs.readFileSync(path.join(assets,'catalog.json')));
const parts=catalog.parts||catalog,boards=parts.filter(p=>p.board),registry=new Registry(data);
assert.equal(data.boards.length,boards.length);assert.equal(registry.matrix().length,339);
for(const part of boards){const row=registry.board(part);assert(row);assert.equal(row.connectors.length,part.pins.length);if(!row.profile){assert.equal(row.support.firmware,'unavailable');assert.equal(row.support.mcu,'unavailable');}}
const uno=registry.target('arduino_Uno_Rev3(fix)'),nano=registry.target('Arduino Nano3(fix)');assert.equal(uno.mcu,'atmega328p');assert.equal(uno.core.version,'1.8.6');assert.equal(uno.upload.baud,115200);assert.equal(uno.upload.maxFlash,32256);assert.equal(nano.pins.find(p=>p.name==='A7').digital,false);assert.equal(registry.connector('arduino_Uno_Rev3(fix)','connector56').port,'B');assert.equal(registry.connector('arduino_Uno_Rev3(fix)','connector56').bit,5);assert.equal(registry.connector('arduino_Uno_Rev3(fix)','connector45'),null);assert.equal(registry.validateTarget('arduino:avr:nano:atmega328old').upload.baud,57600);assert.throws(()=>registry.validateTarget('made-up'));
console.log('PASS 339 independent support rows; Uno/Nano MCU/pin/core/upload metadata; unknown capabilities remain unavailable');
