const assert=require('node:assert/strict'),fs=require('node:fs'),E=require('../../app/src/main/assets/wiredroid/engine.js');
const catalog=new Map(JSON.parse(fs.readFileSync('app/src/main/assets/wiredroid/catalog.json')).map(p=>[p.id,p])),scene=JSON.parse(fs.readFileSync('app/src/main/assets/wiredroid/examples/servo-pot/circuit.wire.json'));
for(const n of scene.nodes)n.kind=n.id==='uno'?'board':n.id==='servo'?'servo':'passive';
const model=E.modelFor(catalog.get('servo'));assert.equal(model.type,'servo');assert.equal(E.modelFor(catalog.get('potentiometer_slider_5')).type,'potentiometer');
let outputs=new Map([['uno:connector52',.075]]),modes=new Map([['uno:connector52','OUTPUT']]),pulses=new Map([['uno:connector52',{angle:90,pulse:1500}]]),pressed=new Set();
let values=E.resolve(scene,catalog,outputs,modes,pressed,new Map([['pot',.75]])).values;
assert.equal(E.servoStates(scene,catalog,values,pulses,pressed).get('servo').active,true);assert.equal(E.servoStates(scene,catalog,values,pulses,pressed).get('servo').angle,90);
assert.equal(E.servoStates(scene,catalog,values,new Map(),pressed).get('servo').active,false,'Ordinary PWM is not a Servo position command');
for(const connector of ['connector0','connector1','connector2']){const bad=E.clone(scene);bad.wires=bad.wires.filter(w=>!(w.b.node==='servo'&&w.b.pin===connector));const resolved=E.resolve(bad,catalog,outputs,modes,pressed).values;assert.equal(E.servoStates(bad,catalog,resolved,pulses,pressed).get('servo').active,false,'Disconnect '+connector);}
assert.ok(!E.audit(scene,catalog,modes,pressed).some(i=>i.severity==='error'));console.log('PASS: servo model, powered pulse propagation, no position from PWM alone, missing power/GND/signal stops motion, slider potentiometer bus support');
