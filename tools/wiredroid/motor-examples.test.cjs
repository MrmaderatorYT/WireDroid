const assert=require('node:assert/strict'),fs=require('node:fs'),E=require('../../app/src/main/assets/wiredroid/engine.js'),M=require('../../app/src/main/assets/wiredroid/models.js'),{Solver}=require('../../app/src/main/assets/wiredroid/electronics.js'),{Devices}=require('../../app/src/main/assets/wiredroid/devices.js');
const base='app/src/main/assets/wiredroid',catalog=new Map(JSON.parse(fs.readFileSync(base+'/catalog.json')).map(p=>[p.id,p]));
for(const id of ['a4988-microsteps','unipolar-stepper']){
 const scene=JSON.parse(fs.readFileSync(base+'/examples/'+id+'/circuit.wire.json'));for(const n of scene.nodes)n.kind=n.id==='uno'?'board':'passive';E.validate(scene,catalog);const solver=new Solver(),devices=new Devices(),outputs=new Map(),modes=new Map(),part=catalog.get(scene.nodes[0].part);let result;
 const set=(pin,value)=>{const k=E.key('uno',E.programPin(part,pin));outputs.set(k,value);modes.set(k,'OUTPUT');};
 const tick=(count=1)=>{for(let i=0;i<count;i++){result=solver.solve(scene,catalog,outputs,modes,new Set(),new Map(),.002,devices.drives);devices.step(scene,catalog,result,outputs,.002);}};
 if(id==='a4988-microsteps'){
  for(let p=2;p<=8;p++)set(p,[3,4,5,6,8].includes(p)?1:0);tick(20);const start=result.states.get('motor').angle;
  for(let i=0;i<64;i++){set(2,1);tick();set(2,0);tick();}tick(10);assert.ok(result.states.get('motor').angle>start+5,'A4988 example turns rotor');assert.equal(devices.snapshot(scene,catalog).driver.resolution,16);assert.ok(Math.abs(result.states.get('motor').coilA)<=.401&&Math.abs(result.states.get('motor').coilB)<=.401);
  set(7,1);tick(5);assert.equal(devices.drives.size,0);
 }else{
  for(let p=2;p<=5;p++)set(p,0);tick(10);const phases=[1,3,2,6,4,12,8,9];
  for(let i=0;i<40;i++){for(let p=0;p<4;p++)set(p+2,phases[i%8]>>p&1);tick(4);}const angle=result.states.get('motor').angle;assert.ok(angle>20,'Unipolar example advances rotor');
  for(let i=39;i>=0;i--){for(let p=0;p<4;p++)set(p+2,phases[i%8]>>p&1);tick(4);}assert.ok(result.states.get('motor').angle<angle-20,'Unipolar example reverses rotor');
 }
 assert.ok(!E.audit(scene,catalog,modes).some(i=>i.severity==='error'),'Example electrical preflight');
}
console.log('PASS: ready A4988 1/16-current-limited rotation/enable and ULN2003 unipolar half-step forward/reverse example wiring');
