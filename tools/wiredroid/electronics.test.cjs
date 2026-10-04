const assert=require('node:assert/strict');
const E=require('../../app/src/main/assets/wiredroid/engine.js');
const M=require('../../app/src/main/assets/wiredroid/models.js');
const {Solver}=require('../../app/src/main/assets/wiredroid/electronics.js');
const pins=names=>names.map((name,i)=>({id:'p'+i,name}));
const catalog=new Map([
 ['board',{id:'board',title:'Arduino Uno',pins:pins(['GND','5V','D2','A0'])}],
 ['resistor',{id:'resistor',title:'Resistor',pins:pins(['a','b'])}],
 ['capacitor',{id:'capacitor',title:'Capacitor',pins:pins(['a','b'])}],
 ['inductor',{id:'inductor',title:'Inductor',pins:pins(['a','b'])}],
 ['led',{id:'led',title:'LED',pins:pins(['anode','cathode'])}],
 ['dc_motor',{id:'dc_motor',title:'DC motor',pins:pins(['a','b'])}],
 ['npn',{id:'npn',title:'NPN transistor',pins:pins(['collector','emitter','base'])}],
 ['nmos',{id:'nmos',title:'N-channel MOSFET',pins:pins(['drain','source','gate'])}],
 ['stepper',{id:'stepper',title:'Stepper',pins:pins(['A','C','B','D'])}]
]);
const node=(id,part,parameters={})=>({id,part,kind:part==='board'?'board':part==='resistor'?'resistor':'passive',x:0,y:0,label:id,electrical:part==='board'?undefined:{type:M.model(catalog.get(part)).type,parameters}});
const wire=(a,ap,b,bp)=>({id:[a,ap,b,bp].join('_'),a:{node:a,pin:'p'+ap},b:{node:b,pin:'p'+bp}});
const scene=(nodes,wires)=>({version:1,nodes:[node('uno','board'),...nodes],wires});
const near=(a,b,epsilon=1e-5)=>assert.ok(Math.abs(a-b)<epsilon,`${a} ≈ ${b}`);
let s=scene([node('r1','resistor',{resistance:1000}),node('r2','resistor',{resistance:1000})],[wire('uno',1,'r1',0),wire('r1',1,'r2',0),wire('r2',1,'uno',0),wire('r1',1,'uno',3)]);
let solver=new Solver(),r=solver.solve(s,catalog);near(r.volts.get('uno:p3'),2.5);near(r.currents.get('r1'),.0025);
s=scene([node('r','resistor',{resistance:1000}),node('c','capacitor',{capacitance:1e-6})],[wire('uno',1,'r',0),wire('r',1,'c',0),wire('c',1,'uno',0)]);
solver=new Solver();r=solver.solve(s,catalog,new Map(),new Map(),new Set(),new Map(),.001);near(r.volts.get('c:p0'),2.5);r=solver.solve(s,catalog,new Map(),new Map(),new Set(),new Map(),.001);near(r.volts.get('c:p0'),3.75);
s=scene([node('r','resistor',{resistance:220}),node('led','led')],[wire('uno',1,'r',0),wire('r',1,'led',0),wire('led',1,'uno',0)]);r=new Solver().solve(s,catalog);near(r.volts.get('led:p0'),1.78,.01);assert.ok(r.currents.get('led')>.014&&r.currents.get('led')<.015);
s=scene([node('l','inductor',{inductance:.01}),node('r','resistor',{resistance:100})],[wire('uno',1,'l',0),wire('l',1,'r',0),wire('r',1,'uno',0)]);r=new Solver().solve(s,catalog,new Map(),new Map(),new Set(),new Map(),.0001);near(r.currents.get('l'),.025);
s=scene([node('m','dc_motor')],[wire('uno',1,'m',0),wire('m',1,'uno',0)]);solver=new Solver();for(let i=0;i<20;i++)r=solver.solve(s,catalog,new Map(),new Map(),new Set(),new Map(),.001);assert.ok(r.states.get('m').rpm>0&&r.states.get('m').angle>0);
const angle=r.states.get('m').angle;solver.reset();s.wires=[wire('uno',1,'m',1),wire('m',0,'uno',0)];for(let i=0;i<20;i++)r=solver.solve(s,catalog,new Map(),new Map(),new Set(),new Map(),.001);assert.ok(r.states.get('m').rpm<0&&r.states.get('m').angle<0);near(r.states.get('m').angle,-angle);
for(const type of ['npn','nmos']){
 s=scene([node('q',type),node('r','resistor',{resistance:1000}),node('rb','resistor',{resistance:10000})],[wire('uno',1,'r',0),wire('r',1,'q',0),wire('q',1,'uno',0),wire('uno',2,'rb',0),wire('rb',1,'q',2)]);
 r=new Solver().solve(s,catalog,new Map([['uno:p2',1]]),new Map([['uno:p2','OUTPUT']]));assert.ok(r.volts.get('q:p0')<.1,`${type} conducts`);
 r=new Solver().solve(s,catalog,new Map([['uno:p2',0]]),new Map([['uno:p2','OUTPUT']]));assert.ok(r.volts.get('q:p0')>4.9,`${type} turns off`);
}
s=scene([], [wire('uno',1,'uno',0)]);assert.throws(()=>new Solver().solve(s,catalog),/Живлення/);
console.log('PASS: divider voltage/current, RC charging, LED diode equation, RL transient, DC motor direction and inertia, NPN/MOS switch, rail short');
