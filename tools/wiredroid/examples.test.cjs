const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const E=require('../../app/src/main/assets/wiredroid/engine.js');
const base='app/src/main/assets/wiredroid',catalog=new Map(JSON.parse(fs.readFileSync(base+'/catalog.json')).map(p=>[p.id,p]));
const examples=JSON.parse(fs.readFileSync(base+'/examples/index.json'));
assert.ok(examples.length>=14);assert.equal(new Set(examples.map(x=>x.id)).size,examples.length);
for(const item of examples){
 const folder=base+'/examples/'+item.id,scene=JSON.parse(fs.readFileSync(folder+'/circuit.wire.json'));
 E.validate(scene,catalog);
 if(!['blink-uno','button-uno','traffic-uno','alternating-nano','blink-esp32','servo-sweep','servo-pot'].includes(item.id)){for(const file of item.files)assert.equal(fs.readFileSync(folder+'/'+file,'utf8'),fs.readFileSync('examples/'+item.id+'/'+file,'utf8'));continue;}
 for(const n of scene.nodes){n.kind=n.id===item.board?'board':n.part==='resistor'?'resistor':n.part==='Pushbutton'?'button':E.modelFor(catalog.get(n.part))?.type==='servo'?'servo':E.modelFor(catalog.get(n.part))?'passive':'led';}
 for(const file of item.files){assert.equal(fs.readFileSync(folder+'/'+file,'utf8'),fs.readFileSync('examples/'+item.id+'/'+file,'utf8'));}
 assert.ok(fs.readFileSync(folder+'/sketches/'+item.board+'.cpp','utf8').includes('void setup()'));
 assert.equal(E.resolve(scene,catalog,new Map(),new Map(),new Set()).conflicts.length,0,item.id);
 const board=catalog.get(scene.nodes.find(n=>n.id===item.board).part);
 for(const led of scene.nodes.filter(n=>n.kind==='led')){
  const resistor=scene.wires.find(w=>w.b.node===led.id&&w.b.pin==='connector1').a.node;
  const pin=scene.wires.find(w=>w.b.node===resistor).a.pin,key=E.key(item.board,pin);
  const high=E.resolve(scene,catalog,new Map([[key,1]]),new Map([[key,'OUTPUT']]),new Set());
  assert.equal(high.conflicts.length,0);assert.equal(high.values.get(E.key(led.id,'connector1')),1,item.id);
  assert.equal(high.values.get(E.key(led.id,'connector0')),0,item.id);
 }
 if(item.id==='button-uno'){
  const pin=E.key('uno','connector63'),modes=new Map([[pin,'INPUT_PULLUP']]);
  assert.equal(E.resolve(scene,catalog,new Map(),modes,new Set()).values.get(pin),1);
  assert.equal(E.resolve(scene,catalog,new Map(),modes,new Set(['button'])).values.get(pin),0);
 }
 for(const scale of [.25,.5,2]){const small=E.clone(scene);small.nodes[0].scale=scale;E.validate(small,catalog);}
 for(const scale of [0,3,'small',null]){const invalid=E.clone(scene);invalid.nodes[0].scale=scale;assert.throws(()=>E.validate(invalid,catalog));}
}
console.log('PASS: '+examples.length+' complete examples, valid parts/contacts/wires, digital LED paths, button pullup, export copies and component size bounds');
