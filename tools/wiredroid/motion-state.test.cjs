const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
class Element{
  constructor(){this.attributes={};this.style={};this.dataset={};this.children=[];this.classList={toggle(){}};this.offsetWidth=100;}
  setAttribute(k,v){this.attributes[k]=String(v);}
  append(...elements){this.children.push(...elements);}
  querySelector(){return null;}
  querySelectorAll(){return [];}
  getCTM(){return {a:1,b:0};}
}
let nextFrame=0,time=0;const frames=new Map(),context={window:{matchMedia:()=>({matches:false})},document:{createElement:()=>new Element(),createElementNS:()=>new Element(),querySelector:()=>null},WireModels:{model:(part)=>({type:part.type})},requestAnimationFrame:callback=>{const id=++nextFrame;frames.set(id,callback);return id;},cancelAnimationFrame:id=>frames.delete(id)};
vm.runInNewContext(fs.readFileSync('app/src/main/assets/wiredroid/motion.js','utf8'),context);const M=context.window.WireMotion;
function attach(id,type){const div=new Element(),svg=new Element();svg.viewBox={baseVal:{x:0,y:0,width:100,height:100}};svg.createSVGPoint=()=>({x:0,y:0,matrixTransform(){return {x:this.x,y:this.y};}});M.attach(div,{id,kind:'passive'},{type},svg,()=>{},()=>{});return div;}
function animate(){for(let i=0;i<20&&frames.size;i++){time+=64;const callbacks=[...frames.values()];frames.clear();for(const callback of callbacks)callback(time);}}
M.begin();const div=attach('servo','servo'),scene={nodes:[{id:'servo'}]};M.preview('servo',180);animate();assert.equal(M.states().servo.angle,180);
M.sync(scene,new Map(),new Map([['servo',{active:true,angle:90,targetAngle:180,rpm:12,state:'C++'}]]),true);
assert.equal(M.states().servo.angle,90,'Activating physics replaces the old 180° preview before an animation frame');
assert.equal(M.states().servo.target,90,'Animation target retains physical angle semantics');
assert.equal(M.states().servo.targetAngle,180,'Commanded mechanical target is available separately');
assert.equal(M.states().servo.rpm,12);assert.equal(div.dataset.motionAngle,'90.00');assert.equal(div.dataset.motionActive,'true');
M.sync(scene,new Map(),new Map([['servo',{active:true,angle:92,targetAngle:180,rpm:12}]]),true);assert.equal(M.states().servo.angle,92);animate();assert.equal(M.states().servo.angle,92,'Animation does not integrate physical angles again');
M.stop();assert.equal(M.preview('servo',45),true);assert.equal(M.states().servo.angle,92,'Manual preview remains animated');animate();assert.equal(M.states().servo.angle,45);
M.begin();attach('motor','motor');M.sync({nodes:[{id:'motor'}]},new Map(),new Map(),true,new Map([['motor',{angle:420,targetAngle:450,rpm:30}]]));assert.equal(M.states().motor.angle,420);assert.equal(M.states().motor.targetAngle,450);assert.equal(M.states().motor.rpm,30);
console.log('PASS: physical angles replace stale manual preview synchronously, angle/active DOM state agrees, no second integration, manual preview stays animated, motor rpm and commanded target are exposed');
