'use strict';
const $=id=>document.getElementById(id), E=WireEngine, M=WireModels;
const solver=new WireElectronics.Solver(),devices=new WireDevices.Devices(),timeline=new WireSignals.Timeline(),servos=new WireMechanics.Servos();
let boardRegistry=null,configs=new Map(),mcuStates=new Map(),pulseStarts=new Map(),i2cTransactions=new Map(),virtualTimeUs=0,previousVirtualTime=0;
let groupMode=false,groupSelection=new Set(),capturedSignals=null;
const avrRuntime=new WireAVRRuntime.Runtime({event:nativeEvent,bus:mcuBus,state:(id,state)=>{mcuStates.set(id,state);},inputs:id=>{const n=scene.nodes.find(n=>n.id===id);if(n&&electricalResult)avrRuntime.inputs(id,voltsFor(n,electricalResult));},quantum:time=>{virtualTimeUs=time;updateSignals();workbench.debuggerState(mcuStates);},error:(id,error)=>{stop('Помилка MCU');log(id+': '+error.message,true);}});
const workbench=WireWorkbench.create({scene:()=>scene,catalog:()=>catalog,registry:()=>boardRegistry,config:configFor,saveConfig,stop,inspect,changed,focus:id=>focusIssue({nodes:[id],pins:[]}),error:showError,log,spiceState:()=>({outputs,modes,inputValues,inputs:inputValues,pressed,initial:solver.memory}),serialChoices:refreshSerialBoards});
let electricalResult=null,electricalTime=0,adcBits=new Map(),motorStates=new Map(),continuousAngles=new Map();
let catalog,scene,history,selected=null,pending=null,mode='select',category='starter',dirty=false,libraryLimit=80;
let view={x:65,y:60,scale:1}, workers=new Map(),outputs=new Map(),modes=new Map(),pressed=new Set(),running=false;
let servoPulses=new Map(),servoState=new Map();
let pinPositions=new Map(),lastValues=new Map(),saveTimer,runTimer,renderGeneration=0,loadFailed=false;
const svgCache=new Map(),starter=['arduino_Uno_Rev3(fix)','Arduino Nano3(fix)','resistor','LED-generic-5mm_6852162_005','Pushbutton'];
function sketchDefault(p){
  const pin=p.pins.find(x=>/^(D13|13)(\b|$)/i.test(x.name))||p.pins.find(x=>/^(D|GPIO|IO)?\d+|^A\d+/i.test(x.name))||p.pins[0];
  return '#include <Arduino.h>\n\nconst char *LED_PIN = '+JSON.stringify(pin.id)+';\n\nvoid setup() {\n  pinMode(LED_PIN, OUTPUT);\n  Serial.begin(9600);\n}\n\nvoid loop() {\n  digitalWrite(LED_PIN, HIGH);\n  delay(500);\n  digitalWrite(LED_PIN, LOW);\n  delay(500);\n}\n';
}
const newId=prefix=>prefix+'_'+Date.now().toString(36)+'_'+Math.random().toString(36).slice(2,8);
function kindFor(p,node={}) {
  if(['servo','continuousServo'].includes(M.model(p,node).type))return 'servo';
  if(p.board&&p.pins.length)return 'board';
  if(M.model(p,node).type==='resistor')return 'resistor';
  if(M.model(p,node).type==='led')return 'led';
  if(M.model(p,node).type==='button')return 'button';
  return 'passive';
}
function normalize() {for(const n of scene.nodes){n.kind=n.programmable?'board':kindFor(catalog.get(n.part),n);n.label=String(n.label||catalog.get(n.part).title).slice(0,100);}}
function demo() {
  return {version:1,name:'Blink circuit',nodes:[
    {id:'uno',part:starter[0],x:60,y:95,rotation:0,label:'Arduino Uno'},
    {id:'resistor',part:'resistor',x:430,y:110,rotation:0,label:'220 Ω'},
    {id:'led',part:starter[3],x:610,y:130,rotation:0,label:'Red LED'}
  ],wires:[
    {id:'w1',a:{node:'uno',pin:'connector56'},b:{node:'resistor',pin:'connector0'},color:'#4ade80'},
    {id:'w2',a:{node:'resistor',pin:'connector1'},b:{node:'led',pin:'connector1'},color:'#4ade80'},
    {id:'w3',a:{node:'led',pin:'connector0'},b:{node:'uno',pin:'connector88'},color:'#94a3b8'}]};
}
function log(text,error=false) {
  const lines=$('logs').textContent.split('\n');
  const prefix=new Date().toLocaleTimeString()+' '+(error?'✕ ':'');
  for(const line of String(text).split('\n').slice(-160))lines.push(prefix+line.slice(0,1600));
  $('logs').textContent=lines.slice(-180).join('\n');$('logs').scrollTop=$('logs').scrollHeight;
}
function status(text){$('status').replaceChildren();$('status').title=text;const dot=document.createElement('i');dot.style.background=running?'#4ade80':'#94a6ba';$('status').append(dot,document.createTextNode(text));}
function applyView(){ $('surface').style.transform=`translate(${view.x}px,${view.y}px) scale(${view.scale})`; $('zoomLabel').textContent=Math.round(view.scale*100)+'%'; }
function world(clientX,clientY){const r=$('viewport').getBoundingClientRect();return {x:(clientX-r.left-view.x)/view.scale,y:(clientY-r.top-view.y)/view.scale};}
function zoom(factor,cx,cy){const r=$('viewport').getBoundingClientRect();cx??=r.left+r.width/2;cy??=r.top+r.height/2;const p=world(cx,cy);view.scale=Math.max(.2,Math.min(3,view.scale*factor));view.x=cx-r.left-p.x*view.scale;view.y=cy-r.top-p.y*view.scale;applyView();}
function fit(){
  if(!scene.nodes.length){view={x:40,y:40,scale:1};applyView();return;}
  let x=Infinity,y=Infinity,right=-Infinity,bottom=-Infinity;
  for(const n of scene.nodes){const el=document.querySelector(`[data-node="${n.id}"]`);if(!el)continue;const r=el.getBoundingClientRect(),a=world(r.left,r.top),b=world(r.right,r.bottom);x=Math.min(x,a.x-30);y=Math.min(y,a.y-30);right=Math.max(right,b.x+30);bottom=Math.max(bottom,b.y+50);}
  if(!Number.isFinite(x)){view={x:40,y:40,scale:1};applyView();return;}
  const r=$('viewport').getBoundingClientRect();view.scale=Math.max(.2,Math.min(1.2,(r.width-40)/(right-x),(r.height-70)/(bottom-y)));view.x=(r.width-(right-x)*view.scale)/2-x*view.scale;view.y=(r.height-(bottom-y)*view.scale)/2-y*view.scale;applyView();
}
function refreshInfo(){ $('sceneInfo').textContent=scene.nodes.length+' деталей · '+scene.wires.length+' дротів';$('empty').hidden=!!scene.nodes.length;$('undo').disabled=history.index===0;$('redo').disabled=history.index===history.items.length-1; }
function changed(){stop();pending=null;normalize();history.push(scene);dirty=true;$('saveState').textContent='Незбережені зміни';refreshInfo();clearTimeout(saveTimer);saveTimer=setTimeout(save,600);}
function save(){
  if(loadFailed)return false;
  try{E.validate(scene,catalog);const data=JSON.stringify(scene);let result;
    if(window.WireHost)result=WireHost.saveScene(data);
    else{localStorage.setItem('wiredroid-scene',data);result='';}
    if(result)throw Error(result);
    dirty=false;$('saveState').textContent='Збережено';return true;
  }catch(error){log('Збереження: '+error.message,true);$('saveState').textContent='Помилка збереження';return false;}
}
async function loadSVG(part){
  if(!svgCache.has(part.image))svgCache.set(part.image,fetch(part.image).then(r=>{if(!r.ok)throw Error('Немає зображення '+part.title);return r.text();}));
  return svgCache.get(part.image);
}
function nodeWidth(n,p){const base=n.kind==='board'?330:n.kind==='led'?55:n.kind==='resistor'?125:n.kind==='button'?75:/breadboard/i.test(p.title)?400:170;return base*(n.scale??1);}
async function resizeSelected(value){
  const n=scene.nodes.find(n=>n.id===selected);if(!n||!Number.isFinite(value))return false;
  const scale=Math.round(Math.max(.25,Math.min(2,value))*100)/100;if(scale===(n.scale??1))return false;
  n.scale=scale;changed();await render();return true;
}
async function render(){
  const generation=++renderGeneration;pinPositions.clear();$('nodes').replaceChildren();$('wires').replaceChildren();
  const fragments=await Promise.all(scene.nodes.map(async n=>{
    const p=catalog.get(n.part),div=document.createElement('div');div.className='node';div.dataset.node=n.id;div.style.left=n.x+'px';div.style.top=n.y+'px';div.style.width=nodeWidth(n,p)+'px';div.style.transform=`rotate(${n.rotation||0}deg)`;
    div.style.setProperty('--pin-size',Math.max(5,14*(n.scale??1))+'px');
    div.style.setProperty('--pin-border',Math.max(1,2*(n.scale??1))+'px');
    div.style.setProperty('--led-color',n.ledColor||(/green/i.test(p.title)?'#4ade80':/yellow/i.test(p.title)?'#facc15':'#ff453a'));
    const text=await loadSVG(p),xml=new DOMParser().parseFromString(text,'image/svg+xml');const svg=document.importNode(xml.documentElement,true);
    if(svg.tagName.toLowerCase()==='parsererror')throw Error('Некоректне зображення '+p.title);
    let vb=svg.getAttribute('viewBox');if(!vb){const w=parseFloat(svg.getAttribute('width'))||100,h=parseFloat(svg.getAttribute('height'))||100;vb=`0 0 ${w} ${h}`;svg.setAttribute('viewBox',vb);}
    svg.removeAttribute('width');svg.removeAttribute('height');svg.classList.add('art');div.append(svg);
    if(n.kind==='led'&&/^#[0-9a-f]{6}$/i.test(n.ledColor||''))for(const shape of svg.querySelectorAll('[id^="color_"]'))shape.setAttribute('fill',n.ledColor);
    const label=document.createElement('div');label.className='node-label';label.textContent=n.label;div.append(label);
    div.addEventListener('pointerdown',e=>nodeDown(e,n));div.addEventListener('dblclick',()=>n.kind==='board'?editCode(n):inspect(n.id));
    return {div,n,p,svg};
  }));
  if(generation!==renderGeneration)return;
  WireMotion.begin();
  for(const item of fragments)$('nodes').append(item.div);
  applyView();
  for(const {div,n,p,svg} of fragments){
    WireMotion.attach(div,n,p,svg,setInput,previewServo,()=>status('Кут задає C++. Натисніть «Зупинити» для ручного повороту.'));
    for(const pin of p.pins){
      const shape=svg.querySelector('[id="'+(pin.terminalId||pin.svgId).replace(/"/g,'')+'"]') || svg.querySelector('[id="'+pin.svgId.replace(/"/g,'')+'"]');
      if(!shape){
        const missing=p.pins.filter(x=>!svg.querySelector('[id="'+(x.terminalId||x.svgId)+'"]')&&!svg.querySelector('[id="'+x.svgId+'"]'));
        const index=missing.findIndex(x=>x.id===pin.id),cols=Math.max(1,Math.floor(div.offsetWidth/22));
        const dot=document.createElement('button');dot.className='pin';dot.style.left=(12+(index%cols)*22)+'px';dot.style.top=(svg.getBoundingClientRect().height/view.scale+20+Math.floor(index/cols)*22)+'px';
        dot.dataset.pin=pin.id;dot.dataset.name=pin.name;dot.setAttribute('aria-label',n.label+' '+pin.name);dot.title=pin.name+' · контакт без SVG-позначки';
        dot.onpointerdown=e=>{e.stopPropagation();e.preventDefault();connectPin(n,pin);};div.append(dot);
        div.style.paddingBottom=(40+Math.ceil(missing.length/cols)*22)+'px';continue;
      }
      let box;try{box=shape.getBBox();}catch(_){continue;}
      const point=svg.createSVGPoint();point.x=box.x+box.width/2;point.y=box.y+box.height/2;
      const screen=point.matrixTransform(shape.getScreenCTM());
      const absolute=world(screen.x,screen.y);pinPositions.set(E.key(n.id,pin.id),absolute);
      // Convert through the unrotated element matrix so contact overlays rotate with the body.
      const svgPoint=point.matrixTransform(shape.getCTM());const vb=svg.viewBox.baseVal;
      const px=svgPoint.x,py=svgPoint.y;
      const dot=document.createElement('button');dot.className='pin';dot.style.left=px+'px';dot.style.top=py+'px';dot.dataset.name=pin.name;dot.dataset.pin=pin.id;dot.setAttribute('aria-label',n.label+' '+pin.name);dot.title=pin.name;
      dot.addEventListener('pointerdown',e=>{e.stopPropagation();e.preventDefault();connectPin(n,pin);});div.append(dot);
    }
  }
  for(const {n} of fragments)recalcPositions(n);
  drawWires();select(selected,false);refreshInfo();paintIssues();paintMotion();
}
function recalcPositions(n){
  const el=document.querySelector(`[data-node="${n.id}"]`);if(!el)return;
  for(const dot of el.querySelectorAll('.pin')){const r=dot.getBoundingClientRect();pinPositions.set(E.key(n.id,dot.dataset.pin),world(r.left+r.width/2,r.top+r.height/2));}
}
function drawWires(){
  $('wires').replaceChildren();$('wireHandles').replaceChildren();const NS='http://www.w3.org/2000/svg',net=selectedNetwork();
  for(const w of scene.wires){const a=pinPositions.get(E.key(w.a.node,w.a.pin)),b=pinPositions.get(E.key(w.b.node,w.b.pin));if(!a||!b)continue;
    const d=E.wirePath(a,b,w);
    const pick=e=>{e.stopPropagation();select(w.id);};
    const hit=document.createElementNS(NS,'path');hit.setAttribute('d',d);hit.setAttribute('class','wire wire-hit');hit.onpointerdown=pick;hit.ondblclick=e=>{e.stopPropagation();addWirePoint(w,world(e.clientX,e.clientY));};$('wires').append(hit);
    const path=document.createElementNS(NS,'path');path.dataset.wire=w.id;path.setAttribute('d',d);path.setAttribute('stroke',w.color||'#4ade80');path.setAttribute('class','wire'+(selected===w.id?' selected':'')+(net&&net.network.find(E.key(w.a.node,w.a.pin))===net.id?' net-highlight':''));path.onpointerdown=pick;path.ondblclick=hit.ondblclick;$('wires').append(path);
    if(selected===w.id)for(let i=0;i<(w.points||[]).length;i++){const p=w.points[i],dot=document.createElementNS(NS,'circle');dot.setAttribute('cx',p.x);dot.setAttribute('cy',p.y);dot.setAttribute('r',7/view.scale);dot.setAttribute('class','wire-point');dot.dataset.point=i;dot.onpointerdown=e=>dragWirePoint(e,w,i);$('wireHandles').append(dot);}
  }
  for(const dot of $('nodes').querySelectorAll('.pin'))dot.classList.toggle('net-pin',!!net&&net.network.find(E.key(dot.closest('.node').dataset.node,dot.dataset.pin))===net.id);
}
function select(id,toggle=true){selected=id;if(!groupMode&&toggle)groupSelection.clear();if(groupMode&&toggle&&id&&scene.nodes.some(n=>n.id===id)){if(groupSelection.has(id))groupSelection.delete(id);else groupSelection.add(id);}paintGroup();$('nodes').querySelectorAll('.node').forEach(el=>el.classList.toggle('selected',el.dataset.node===id));const hasNode=scene.nodes.some(n=>n.id===id);$('shrink').disabled=$('grow').disabled=!hasNode;inspect(id);drawWires();}
function inspect(id){
  const n=scene.nodes.find(n=>n.id===id),w=scene.wires.find(w=>w.id===id);$('details').replaceChildren();$('pinState').replaceChildren();$('selectionTitle').textContent=n?n.label:w?'З’єднання':'Схема';
  $('inspector').classList.toggle('mobile-open',!!id);
  if(n){
    const name=document.createElement('input');name.className='field';name.value=n.label;name.setAttribute('aria-label','Назва деталі');name.addEventListener('change',()=>{n.label=name.value.trim().slice(0,100)||catalog.get(n.part).title;changed();render().catch(showError);});$('details').append(name);
    const type=document.createElement('div');type.textContent=catalog.get(n.part).title;$('details').append(type);
    const size=document.createElement('div');size.className='size-control';
    const label=document.createElement('label');label.htmlFor='partSize';label.textContent='Розмір деталі';
    const percent=document.createElement('output');percent.textContent=Math.round((n.scale??1)*100)+'%';
    const slider=document.createElement('input');slider.id='partSize';slider.type='range';slider.min=25;slider.max=200;slider.step=5;slider.value=Math.round((n.scale??1)*100);slider.setAttribute('aria-label','Розмір деталі у відсотках');
    slider.oninput=()=>percent.textContent=slider.value+'%';slider.onchange=()=>resizeSelected(Number(slider.value)/100).catch(showError);
    const reset=document.createElement('button');reset.textContent='100%';reset.title='Початковий розмір';reset.onclick=()=>resizeSelected(1).catch(showError);
    size.append(label,percent,slider,reset);$('details').append(size);
    if(n.kind==='board'){const b=document.createElement('button');b.textContent='Відкрити програму →';b.onclick=()=>editCode(n);$('details').append(b);if(window.WireHost){const choose=document.createElement('button');choose.textContent='Обрати наявний .cpp/.ino';choose.onclick=()=>{stop();WireHost.chooseSketch(n.id);};$('details').append(choose);}}
    if(n.kind==='button'){const b=document.createElement('button');b.textContent='Утримуйте: натиснути';
      b.onpointerdown=e=>{e.preventDefault();b.setPointerCapture(e.pointerId);pressed.add(n.id);updateSignals();};
      b.onpointerup=b.onpointercancel=b.onlostpointercapture=()=>{pressed.delete(n.id);updateSignals();};$('details').append(b);}
    if(n.kind!=='board'&&catalog.get(n.part).pins.length){const b=document.createElement('button');b.textContent='Додати C/C++ програму GPIO';b.onclick=()=>{n.programmable=true;changed();inspect(n.id);editCode(n);};$('details').append(b);}
    modelInspector(n);inputInspector(n);workbench.ratings(n);if(n.kind==='board')workbench.boardInspector(n);
    if(n.kind==='passive'&&!M.model(catalog.get(n.part),n).modeled){const note=document.createElement('p');note.textContent='Доступні графіка, контакти й монтаж. Поведінкова модель цієї деталі не реалізована.';$('details').append(note);}
    for(const p of catalog.get(n.part).pins){if(!pinPositions.has(E.key(n.id,p.id)))continue;const row=document.createElement('div');row.className='pin-row';const a=document.createElement('span'),b=document.createElement('span');a.textContent=p.name;b.dataset.signal=E.key(n.id,p.id);b.textContent='—';row.append(a,b);$('pinState').append(row);}
  }else if(w){
    const label=document.createElement('p');label.textContent=w.a.node+' / '+w.a.pin+' → '+w.b.node+' / '+w.b.pin;$('details').append(label);
    const color=document.createElement('input');color.type='color';color.value=w.color||'#4ade80';color.setAttribute('aria-label','Колір з’єднання');color.onchange=()=>{w.color=color.value;changed();drawWires();};$('details').append(color);wireInspector(w);
  }else $('details').textContent='Виберіть плату, компонент або дріт';
  paintSignals();
}
function connectPin(n,pin){
  if(running&&mode!=='connect'){select(n.id);watchPin=E.key(n.id,pin.id);refreshMonitorChoices();consoleTab('monitor');return;}
  if(!pending){pending={node:n.id,pin:pin.id};select(n.id);highlightPending();status('Оберіть другий контакт: '+pin.name);return;}
  const a=pending,b={node:n.id,pin:pin.id};pending=null;highlightPending();
  if(E.key(a.node,a.pin)===E.key(b.node,b.pin)){status('З’єднання скасовано');return;}
  const candidate={id:newId('wire'),a,b,color:$('wireColor').value,routing:$('orthogonal').classList.contains('active')?'orthogonal':'curve'};scene.wires.push(candidate);
  try{E.validate(scene,catalog);changed();drawWires();status('Контакти з’єднано');}catch(error){scene.wires.pop();log(error.message,true);}
}
function highlightPending(){for(const el of $('nodes').querySelectorAll('.pin'))el.classList.toggle('pending',!!pending&&el.closest('.node').dataset.node===pending.node&&el.dataset.pin===pending.pin);}
let gesture=null,pointers=new Map(),pinch=null;

function nodeDown(e,n){
  if(e.target.closest('.pin')||e.button!==0)return;e.stopPropagation();
  if(running&&n.kind==='button'){pressed.add(n.id);updateSignals();e.currentTarget.setPointerCapture(e.pointerId);e.currentTarget.onpointerup=e.currentTarget.onpointercancel=e.currentTarget.onlostpointercapture=()=>{pressed.delete(n.id);updateSignals();};return;}
  const wasSelected=groupSelection.has(n.id);select(n.id,false);if(groupMode)groupSelection.add(n.id);else groupSelection.clear();paintGroup();
  if(mode==='connect')return;
  const pos=world(e.clientX,e.clientY),group=groupMode?selectedGroup():[n],ids=new Set(group.map(x=>x.id));gesture={type:'node',node:n,startX:pos.x,startY:pos.y,x:n.x,y:n.y,moved:false,id:e.pointerId,origins:group.map(n=>({n,x:n.x,y:n.y})),routes:scene.wires.filter(w=>ids.has(w.a.node)&&ids.has(w.b.node)&&w.points).map(w=>({w,points:E.clone(w.points)}))};e.currentTarget.setPointerCapture(e.pointerId);
  e.currentTarget.onpointermove=ev=>{if(!gesture||gesture.id!==ev.pointerId)return;const p=world(ev.clientX,ev.clientY),dx=p.x-gesture.startX,dy=p.y-gesture.startY;gesture.moved|=Math.abs(dx)+Math.abs(dy)>2;for(const origin of gesture.origins){origin.n.x=origin.x+dx;origin.n.y=origin.y+dy;const el=document.querySelector('[data-node="'+origin.n.id+'"]');el.style.left=origin.n.x+'px';el.style.top=origin.n.y+'px';recalcPositions(origin.n);}for(const route of gesture.routes)route.w.points=route.points.map(p=>({x:p.x+dx,y:p.y+dy}));drawWires();};
  e.currentTarget.onpointerup=e.currentTarget.onpointercancel=()=>{if(!gesture)return;const moved=gesture.moved;gesture=null;if(moved)changed();else if(groupMode&&wasSelected){groupSelection.delete(n.id);paintGroup();}};
}
$('viewport').addEventListener('pointerdown',e=>{
  if(e.target.closest('.node')||e.target.closest('.wire')||e.target.closest('.wire-point'))return;
  if(e.button!==0)return;
  pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});$('viewport').setPointerCapture(e.pointerId);
  if(pointers.size===2){const ps=[...pointers.values()];pinch={distance:Math.hypot(ps[0].x-ps[1].x,ps[0].y-ps[1].y)};gesture=null;return;}
  select(null);pending=null;highlightPending();gesture={type:'pan',x:e.clientX,y:e.clientY,vx:view.x,vy:view.y};
});
$('viewport').addEventListener('pointermove',e=>{
  if(pointers.has(e.pointerId))pointers.set(e.pointerId,{x:e.clientX,y:e.clientY});
  if(pinch&&pointers.size===2){const ps=[...pointers.values()],distance=Math.hypot(ps[0].x-ps[1].x,ps[0].y-ps[1].y);if(pinch.distance>0)zoom(distance/pinch.distance,(ps[0].x+ps[1].x)/2,(ps[0].y+ps[1].y)/2);pinch.distance=distance;return;}
  if(gesture?.type==='pan'){view.x=gesture.vx+e.clientX-gesture.x;view.y=gesture.vy+e.clientY-gesture.y;applyView();}
});
const endPointer=e=>{pointers.delete(e.pointerId);if(pointers.size<2)pinch=null;if(gesture?.type==='pan')gesture=null;};
$('viewport').addEventListener('pointerup',endPointer);$('viewport').addEventListener('pointercancel',endPointer);
$('viewport').addEventListener('wheel',e=>{e.preventDefault();zoom(e.deltaY<0?1.12:1/1.12,e.clientX,e.clientY);},{passive:false});
function renderLibrary(){
  const query=$('search').value.toLowerCase().trim();$('parts').replaceChildren();
  let parts=[...catalog.values()].filter(p=>p.title.toLowerCase().includes(query)||p.id.toLowerCase().includes(query));
  if(category==='starter'&&!query)parts=starter.map(id=>catalog.get(id)).filter(Boolean);
  if(category==='boards')parts=parts.filter(p=>p.board); 
  const total=parts.length;parts=parts.slice(0,libraryLimit);
  for(const p of parts){const b=document.createElement('button');b.className='part-card';const img=document.createElement('img');img.src=p.image;img.alt='';img.loading='lazy';const label=document.createElement('span');label.textContent=p.title;const sub=document.createElement('small');sub.textContent=p.pins.length+' контактів · '+(p.board?boardRegistry.target(p)?'GPIO / AVR профіль':'GPIO':M.model(p).modeled?M.model(p).label:'Монтаж');label.append(sub);b.append(img,label);b.onclick=()=>addPart(p);$('parts').append(b);}
  if(total>libraryLimit){const more=document.createElement('button');more.textContent='Ще 80 · '+parts.length+' із '+total;more.onclick=()=>{libraryLimit+=80;renderLibrary();};$('parts').append(more);}
  $('partCount').textContent=catalog.size;
}
async function addPart(p){
  if(scene.nodes.length>=250){log('Ліміт 250 деталей',true);return;}
  const r=$('viewport').getBoundingClientRect(),pos=world(r.left+r.width/2,r.top+r.height/2);
  const n={id:newId('part'),part:p.id,x:pos.x-100,y:pos.y-60,rotation:0,label:p.title,kind:kindFor(p)};
  if(n.kind==='board'&&window.WireHost){const err=WireHost.ensureSketch(n.id,sketchDefault(p));if(err){log(err,true);return;}}
  scene.nodes.push(n);changed();selected=n.id;document.body.classList.remove('show-library');await render();
}
function editCode(n){
  n??=scene.nodes.find(n=>n.id===selected&&n.kind==='board')||scene.nodes.find(n=>n.kind==='board');
  if(!n){log('Спочатку додайте плату або увімкніть її GPIO-програму',true);return;}
  if(!save())return;stop();
  if(window.WireHost){const err=WireHost.ensureSketch(n.id,sketchDefault(catalog.get(n.part)));if(err){log(err,true);return;}WireHost.editSketch(n.id);}
  else{const code=prompt('Програма '+n.label,localStorage.getItem('wiredroid-code-'+n.id)||sketchDefault(catalog.get(n.part)));if(code!==null)localStorage.setItem('wiredroid-code-'+n.id,code);}
}
function inputsFor(n,result){const values={};for(const p of catalog.get(n.part).pins){const v=result.values.get(E.key(n.id,p.id));values[p.id]=v||0;values[p.name]=v||0;const base=p.name.toUpperCase().split(/[ /]/)[0];values[base]=v||0;if(/^D\d+$/.test(base))values[base.slice(1)]=v||0;}return values;}
function stop(message){
  const wasRunning=running;running=false;if(window.WireHost)WireHost.stopNative();for(const worker of workers.values())worker.terminate();workers.clear();avrRuntime.stop();servos.reset();mcuStates.clear();pulseStarts.clear();i2cTransactions.clear();virtualTimeUs=previousVirtualTime=0;clearInterval(runTimer);servoPulses.clear();servoState.clear();motorStates.clear();continuousAngles.clear();solver.reset();electricalResult=null;WireMotion.stop();outputs.clear();modes.clear();pressed.clear();lastValues.clear();paintSignals();$('run').textContent='▶ Запустити';$('run').classList.add('primary');status(message||'Готово');
  const preview=document.querySelector('[data-servo-preview]');if(preview)preview.disabled=false;
  if(wasRunning)log(message||'Симуляцію зупинено');
}
function showError(e){log(e.message||String(e),true);}
function updateSignals(){
  if(!running)return;
  try{
    const now=performance.now(),dt=mcuStates.size?Math.max(0,Math.min(.05,(virtualTimeUs-previousVirtualTime)/1e6)):(electricalTime?Math.min(.05,(now-electricalTime)/1000):.001);electricalTime=now;previousVirtualTime=virtualTimeUs;
    for(const [key,pulse]of servoPulses)if(pulse.time!==undefined&&virtualTimeUs-pulse.time>60000)servoPulses.delete(key);
    const result=solver.solve(scene,catalog,outputs,modes,pressed,inputValues,dt,devices.drives);electricalResult=result;lastValues=result.values;motorStates=result.states;
    servoState=servos.update(scene,catalog,result,servoPulses,dt);for(const [id,state]of servoState)motorStates.set(id,state);
    devices.step(scene,catalog,result,outputs,dt,inputValues,motorStates);
    for(const n of scene.nodes){const worker=workers.get(n.id);if(worker)worker.postMessage({type:'inputs',values:inputsFor(n,result)});}
    paintMotion();paintSignals();paintDevices();recordSignals();
  }catch(error){stop('Помилка розрахунку');log(error.message,true);}
}

function paintSignals(){
  for(const n of scene?.nodes||[]){const el=document.querySelector(`[data-node="${n.id}"]`);if(!el)continue;const p=catalog.get(n.part);
    for(const dot of el.querySelectorAll('.pin'))dot.classList.toggle('high',(lastValues.get(E.key(n.id,dot.dataset.pin))||0)>0);
    if(n.kind==='led'){const a=p.pins.find(p=>/anode/i.test(p.name)),c=p.pins.find(p=>/cathode/i.test(p.name));el.classList.toggle('led-on',(electricalResult?.currents.get(n.id)||0)>.0001);}
    if(n.kind==='board'){let pin;try{pin=E.programPin(p,13);}catch(_){}el.classList.toggle('builtin-on',!!pin&&(lastValues.get(E.key(n.id,pin))||0)>0);}
  }
  for(const el of $('pinState').querySelectorAll('[data-signal]')){const value=lastValues.get(el.dataset.signal);const volts=electricalResult?.volts.get(el.dataset.signal);el.textContent=value===undefined?'—':(volts??0).toFixed(3)+' V · '+(value<.2?'LOW':value>.8?'HIGH':'ADC');}
}
function nativeEvent(id,data){
  if(!running)return;
  const n=scene.nodes.find(n=>n.id===id&&n.kind==='board');if(!n)return;
  try{
    if(data.type==='serial'){serialReceive(id,data.text);log(n.label+': '+data.text);}
    else if(data.type==='log'){log(n.label+': '+data.text);}
    else if(data.type==='error'){stop('Помилка C/C++');log(n.label+': '+data.text,true);showDiagnostics(data.text);}
    else if(data.type==='ready'){status('Виконання C/C++');log(n.label+': setup() виконано');}
    else if(data.type==='breakpoint'){log(n.label+': точка зупинки PC 0x'+data.pc.toString(16));consoleTab('debugPanel');}
    else if(data.type==='adc-resolution'){adcBits.set(id,data.bits);}
    else if(data.type==='bus'){updateSignals();if(running){let reply;try{reply=devices.bus(n,data,scene,catalog,electricalResult).map(x=>x.toString(16).padStart(2,'0')).join('');}catch(error){reply='ERR:'+error.message;}WireHost.nativeBytesReply(id,reply);paintDevices();}}
    else {
      const p=catalog.get(n.part);if(!p.pins.some(x=>x.id===data.pin))throw Error('Невідомий контакт');
      const key=E.key(id,data.pin);
      if(data.type==='mode'){if(!['OUTPUT','OUTPUT_OPENDRAIN'].includes(data.mode)){servoPulses.delete(key);outputs.delete(key);}modes.set(key,data.mode);const problems=checkCircuit(false);if(problems.some(i=>i.severity==='error')){consoleTab('problems');stop('Небезпечне з’єднання виходів');return;}updateSignals();}
      else if(data.type==='write'||data.type==='pwm'){
        if(!['OUTPUT','OUTPUT_OPENDRAIN'].includes(modes.get(key)))throw Error('Встановіть pinMode(..., OUTPUT) перед записом');
        if(!Number.isFinite(data.value)||data.value<0||data.value>1)throw Error('Некоректний сигнал');
        if(data.source==='mcu'){virtualTimeUs=Math.max(virtualTimeUs,data.time);if(data.value===1)pulseStarts.set(key,data.time);else{const start=pulseStarts.get(key),pulse=data.time-start;if(start!==undefined&&pulse>=100&&pulse<=5000)servoPulses.set(key,{pulse,time:data.time});pulseStarts.delete(key);}}else servoPulses.delete(key);outputs.set(key,data.value);timeline.record(id,data.pin,data,(performance.now()-monitorStart)*1000);if(data.source==='mcu'){const cfg=configFor(n),source=boardRegistry.connector(p,data.pin,cfg.profile);for(const alias of p.pins){const target=boardRegistry.connector(p,alias.id,cfg.profile);if(alias.id!==data.pin&&target&&target.port===source?.port&&target.bit===source?.bit)timeline.record(id,alias.id,data,0);}}updateSignals();if(running)recordSignals(data.source!=='mcu');
      }else if(data.type==='servo'){
        if(modes.get(key)!=='OUTPUT')throw Error('Servo.attach має налаштувати вихід');
        if(!Number.isFinite(data.pulse)||!Number.isFinite(data.angle)||(data.pulse!==0&&(data.pulse<100||data.pulse>5000))||data.angle<0||data.angle>180)throw Error('Некоректний сигнал Servo');
        if(data.pulse===0){servoPulses.delete(key);outputs.delete(key);}else{servoPulses.set(key,{pulse:data.pulse,angle:data.angle});outputs.set(key,data.pulse/20000);}
        timeline.record(id,data.pin,data,(performance.now()-monitorStart)*1000);updateSignals();if(running)recordSignals(data.source!=='mcu');
      }else if(data.type==='read'){
        updateSignals();if(running){const carrier=timeline.valueAt(key,(performance.now()-monitorStart)*1000),event=timeline.eventAt(key,(performance.now()-monitorStart)*1000),value=event?.frequency&&carrier!==null?carrier:(lastValues.get(key)||0);WireHost.nativeReply(id,Math.round(value*((1<<(adcBits.get(id)||10))-1)));}
      }
    }
  }catch(error){stop('Помилка програми');log(n.label+': '+error.message,true);}
}
async function run(skipEditorSave=false){
  if(!running && !skipEditorSave && window.WireHost?.saveEditor){WireHost.saveEditor();return;}
  if(running){stop();return;}if(!save())return;
  const boards=scene.nodes.filter(n=>n.kind==='board');if(!boards.length){log('Додайте плату або GPIO-програму компонента',true);return;}
  if(boards.length>8){log('Одночасно можна виконувати до 8 програм плат',true);return;}
  if(!window.WireHost&&boards.some(n=>configFor(n).mode!=='mcu')){log('Запуск C/C++ доступний у WireDroid на ARM64 Android. AVR HEX можна перевіряти в браузері.');return;}
  if(boards.some(n=>configFor(n).mode==='usb')){workbench.showProject('usb');log('Оберіть пристрій USB і завантажте прошивку кнопкою у його налаштуваннях.');return;}
  if(boards.some(n=>configFor(n).mode==='mcu')&&boards.some(n=>configFor(n).mode!=='mcu')){log('Оберіть один режим часу для всіх плат схеми: GPIO або AVR MCU.',true);return;}
  if(checkCircuit(false,new Map()).some(i=>i.severity==='error')){consoleTab('problems');log('Виправте помилки з’єднання перед запуском',true);return;}
  inputValues=new Map(scene.nodes.filter(n=>{const model=E.modelFor(catalog.get(n.part));return model&&model.type!=='servo'||n.simulation;}).map(n=>[n.id,n.inputValue??.5]));traces.clear();monitorStart=performance.now();monitorTick=0;refreshMonitorChoices();refreshSerialBoards();
  solver.reset();devices.reset();servos.reset();timeline.reset();capturedSignals=null;adcBits.clear();mcuStates.clear();virtualTimeUs=previousVirtualTime=0;electricalTime=0;running=true;$('run').textContent='■ Зупинити';status('Компіляція C/C++');log('Запуск '+boards.length+' C/C++ програм');
  for(const n of boards){
    const p=catalog.get(n.part),cfg=configFor(n);adcBits.set(n.id,M.board(n,p).adcBits);
    if(cfg.mode==='mcu'){
      try{const target=boardRegistry.target(p,cfg.profile);if(target?.backend!=='avr8js-atmega328p')throw Error('Для вибраного MCU ще немає механізму виконання');
        const hex=window.WireHost?WireHost.readFirmware(n.id):localStorage.getItem('wiredroid-firmware-'+n.id);if(!hex||hex.startsWith('WIREDROID_ERROR:'))throw Error(hex?.slice(16)||'Імпортуйте HEX');
        const mappedPins=p.pins.map(pin=>{const mapped=boardRegistry.connector(p,pin.id,cfg.profile);return mapped?{...mapped,id:pin.id,aliasRank:/^[DA]\d/.test(pin.name)?0:1}:null;}).filter(Boolean).sort((a,b)=>a.aliasRank-b.aliasRank),primary=new Map();for(const pin of mappedPins){const key=pin.port+':'+(pin.bit??'adc'+pin.adc);if(!primary.has(key))primary.set(key,pin);}const pins=[...primary.values()];
        if(!pins.length)throw Error('Контакти цього зображення ще не зіставлені з MCU');
        adcBits.set(n.id,target.adcBits);await avrRuntime.load(n.id,{hex,pins,clockHz:target.clockHz,voltage:target.logicVoltage});
      }catch(error){stop('Помилка прошивки');log(n.label+': '+error.message,true);return;}
    }else{
      const err=WireHost.ensureSketch(n.id,sketchDefault(p))||WireHost.startNative(n.id,JSON.stringify({pins:p.pins,profile:M.board(n,p)}));
      if(err){stop('Помилка запуску');log(n.label+': '+err,true);return;}
    }
  }
  if(mcuStates.size){status('Виконання прошивки AVR');avrRuntime.run();}
  runTimer=setInterval(updateSignals,40);updateSignals();
}
function removeSelected(){if(groupSelection.size>1){const ids=new Set(groupSelection);scene.nodes=scene.nodes.filter(n=>!ids.has(n.id));scene.wires=scene.wires.filter(w=>!ids.has(w.a.node)&&!ids.has(w.b.node));groupSelection.clear();selected=null;changed();render().catch(showError);return;}if(!selected)return;stop();const deletingNode=scene.nodes.some(n=>n.id===selected);scene.nodes=scene.nodes.filter(n=>n.id!==selected);scene.wires=scene.wires.filter(w=>w.id!==selected&&(!deletingNode||w.a.node!==selected&&w.b.node!==selected));selected=null;changed();render().catch(showError);}
function restore(next){stop();scene=next;normalize();selected=null;pending=null;dirty=true;$('saveState').textContent='Незбережені зміни';render().catch(showError);clearTimeout(saveTimer);saveTimer=setTimeout(save,600);}
$('select').onclick=()=>{mode='select';$('select').classList.add('active');$('connect').classList.remove('active');pending=null;highlightPending();};
$('connect').onclick=()=>{mode='connect';$('connect').classList.add('active');$('select').classList.remove('active');status('Торкніться двох контактів');};
$('save').onclick=save;$('run').onclick=()=>run();$('code').onclick=()=>editCode();$('undo').onclick=()=>restore(history.undo());$('redo').onclick=()=>restore(history.redo());$('remove').onclick=removeSelected;
$('rotate').onclick=()=>{const n=scene.nodes.find(n=>n.id===selected);if(n){n.rotation=((n.rotation||0)+90)%360;changed();render().catch(showError);}};
$('shrink').onclick=()=>{const n=scene.nodes.find(n=>n.id===selected);if(n)resizeSelected((n.scale??1)-.25).catch(showError);};
$('grow').onclick=()=>{const n=scene.nodes.find(n=>n.id===selected);if(n)resizeSelected((n.scale??1)+.25).catch(showError);};
$('examples').onclick=()=>{if(window.WireHost){if(save()){stop();WireHost.openExamples();}}else log('Готові проєкти відкриваються у застосунку WireDroid.');};
$('zoomIn').onclick=()=>zoom(1.2);$('zoomOut').onclick=()=>zoom(1/1.2);$('fit').onclick=fit;$('search').oninput=()=>{libraryLimit=80;renderLibrary();};
for(const b of $('categories').querySelectorAll('button'))b.onclick=()=>{category=b.dataset.category;libraryLimit=80;for(const x of $('categories').querySelectorAll('button'))x.classList.toggle('active',x===b);renderLibrary();};
$('clear').onclick=()=>$('logs').textContent='';$('toggleConsole').onclick=()=>$('console').classList.toggle('collapsed');$('help').onclick=()=>$('helpDialog').showModal();$('closeHelp').onclick=()=>$('helpDialog').close();
document.addEventListener('keydown',e=>{
  if(e.target.matches('input,textarea,select'))return;
  if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='s'){e.preventDefault();save();}
  if((e.ctrlKey||e.metaKey)&&e.key.toLowerCase()==='z'){e.preventDefault();restore(e.shiftKey?history.redo():history.undo());}
  if(e.key==='Delete'||e.key==='Backspace'){e.preventDefault();removeSelected();}
  if(e.key==='Escape'){pending=null;highlightPending();select(null);}
});
window.addEventListener('resize',()=>{if(scene)for(const n of scene.nodes)recalcPositions(n);drawWires();});
document.addEventListener('visibilitychange',()=>{if(document.hidden){stop();if(dirty)save();}});
function applyTheme(theme){
  document.body.classList.toggle('embedded',!!theme.embedded);
  document.documentElement.style.colorScheme=theme.dark?'dark':'light';
  for(const name of ['bg','panel','line','text','muted','accent','console','success','error'])if(/^#[0-9a-f]{6}$/i.test(theme[name]||''))document.documentElement.style.setProperty('--'+name,theme[name]);
}
function toolTab(tab){
  const narrow=window.innerWidth<=760;
  if(tab==='canvas'){document.body.classList.remove('show-library','show-inspector');}
  if(tab==='library'){document.body.classList.toggle(narrow?'show-library':'hide-library');document.body.classList.remove('show-inspector');}
  if(tab==='inspector'){document.body.classList.toggle(narrow?'show-inspector':'hide-inspector');document.body.classList.remove('show-library');}
  for(const [id,name]of [['canvasTab','canvas'],['libraryTab','library'],['inspectorTab','inspector']])$(id).classList.toggle('active',tab===name);
  for(const n of scene?.nodes||[])recalcPositions(n);drawWires();
}
$('canvasTab').onclick=()=>toolTab('canvas');$('libraryTab').onclick=()=>toolTab('library');$('inspectorTab').onclick=()=>toolTab('inspector');
window.WireApp={ready:false,applyTheme,save,stop,saveFailed:message=>{stop();log(message||'Не вдалося зберегти код. Запуск скасовано.',true);},reloadPrograms:()=>{stop();log('Код оновлено. Натисніть «Запустити».');},nativeEvent,getScene:()=>E.clone(scene),getSignals:()=>Object.fromEntries(lastValues),getElectronics:()=>electricalResult?{volts:Object.fromEntries(electricalResult.volts),currents:Object.fromEntries(electricalResult.currents),states:Object.fromEntries(electricalResult.states)}:null,getDevices:()=>devices.snapshot(scene,catalog),getDeviceTransitions:()=>E.clone(devices.transitions),getTimeline:()=>Object.fromEntries(timeline.events),getMCU:()=>Object.fromEntries(mcuStates),getMetrics:()=>({electricalMethod:solver.last?.method,unknowns:solver.last?.unknowns,virtualTimeUs,boards:mcuStates.size,catalogEntries:catalog.size,sceneNodes:scene.nodes.length,sceneWires:scene.wires.length}),pauseMCU:()=>avrRuntime.pause(),stepMCU:id=>avrRuntime.step(id),continueMCU:()=>avrRuntime.run(),setFirmwareConfig:(id,cfg)=>saveConfig(scene.nodes.find(n=>n.id===id),cfg),librariesChanged:workbench.librariesChanged,firmwareChanged:id=>{stop();log('Прошивку '+id+' імпортовано. Натисніть «Запустити».');},showProject:workbench.showProject,duplicateGroup,alignGroup,run,addPart,select,connectPin,fit,resizeSelected,checkCircuit,setInput,sendSerial,getMotionStates:()=>WireMotion.states(),previewServo,getIssues:()=>E.clone(issues),getTraces:()=>Object.fromEntries(traces),addWirePoint:(id,point)=>{const w=scene.wires.find(w=>w.id===id);if(w)addWirePoint(w,point);},pressButton:(id,down)=>{if(down)pressed.add(id);else pressed.delete(id);updateSignals();}};
let issues=[],highlightedPins=new Set(),inputValues=new Map(),traces=new Map(),watchPin=null,monitorStart=performance.now(),monitorTick=0,serialBuffers=new Map();
function focusIssue(issue){
  const id=issue.nodes[0],n=scene.nodes.find(n=>n.id===id);highlightedPins=new Set(issue.pins);
  if(n){select(id);const el=document.querySelector(`[data-node="${id}"]`),r=$('viewport').getBoundingClientRect();view.x=r.width/2-(n.x+(el?.offsetWidth||100)/2)*view.scale;view.y=r.height/2-(n.y+(el?.offsetHeight||100)/2)*view.scale;applyView();}
  paintIssues();
}
function paintIssues(){
  for(const dot of $('nodes').querySelectorAll('.pin'))dot.classList.toggle('issue-pin',highlightedPins.has(E.key(dot.closest('.node').dataset.node,dot.dataset.pin)));
  for(const el of $('nodes').querySelectorAll('.node'))el.classList.toggle('issue-node',issues.some(i=>i.severity==='error'&&i.nodes.includes(el.dataset.node)));
}
function checkCircuit(show=true,currentModes=modes){
  issues=[...E.audit(scene,catalog,currentModes,pressed),...M.ratingIssues(scene,catalog,electricalResult)];highlightedPins=new Set(issues.filter(i=>i.severity!=='info').flatMap(i=>i.pins));$('problems').replaceChildren();
  const errors=issues.filter(i=>i.severity==='error').length,warnings=issues.filter(i=>i.severity==='warning').length;
  $('problemsTab').textContent=`Перевірка (${errors}/${warnings})`;
  if(!issues.length)$('problems').textContent='Помилок з’єднання не виявлено.';
  for(const issue of issues){const b=document.createElement('button');b.className='problem '+issue.severity;b.textContent=(issue.severity==='error'?'✕ ':issue.severity==='warning'?'⚠ ':'○ ')+issue.message;b.onclick=()=>focusIssue(issue);$('problems').append(b);}
  paintIssues();if(show)consoleTab('problems');return issues;
}
function consoleTab(name){
  $('console').classList.remove('collapsed');
  for(const id of ['logs','problems','monitor','serialPanel','debugPanel'])$(id).hidden=id!==name;
  for(const [id,panel]of [['logsTab','logs'],['problemsTab','problems'],['monitorTab','monitor'],['serialTab','serialPanel'],['debugTab','debugPanel']])$(id).classList.toggle('active',panel===name);
  if(name==='debugPanel')workbench.debuggerState(mcuStates);if(name==='monitor'){refreshMonitorChoices();drawMonitor();}if(name==='serialPanel')refreshSerialBoards();
}
function selectedNetwork(){
  const wire=scene.wires.find(w=>w.id===selected);if(!wire)return null;
  const network=E.topology(scene,catalog,pressed),id=network.find(E.key(wire.a.node,wire.a.pin));return {network,id};
}
function dragWirePoint(e,w,index){
  e.stopPropagation();e.preventDefault();const viewport=$('viewport');viewport.setPointerCapture(e.pointerId);stop();
  const move=ev=>{if(ev.pointerId!==e.pointerId)return;w.points[index]=world(ev.clientX,ev.clientY);drawWires();};
  const end=ev=>{if(ev.pointerId!==e.pointerId)return;viewport.removeEventListener('pointermove',move);viewport.removeEventListener('pointerup',end);viewport.removeEventListener('pointercancel',end);changed();drawWires();inspect(w.id);};
  viewport.addEventListener('pointermove',move);viewport.addEventListener('pointerup',end);viewport.addEventListener('pointercancel',end);
}
function addWirePoint(w,point){w.points??=[];if(w.points.length>=64){log('До 64 точок на дріт',true);return;}
  const a=pinPositions.get(E.key(w.a.node,w.a.pin)),b=pinPositions.get(E.key(w.b.node,w.b.pin));if(!a||!b)return;
  const points=[a,...w.points,b];let best=Infinity,index=0;
  for(let i=0;i<points.length-1;i++){const p=points[i],q=points[i+1],dx=q.x-p.x,dy=q.y-p.y,t=Math.max(0,Math.min(1,((point.x-p.x)*dx+(point.y-p.y)*dy)/(dx*dx+dy*dy||1))),d=Math.hypot(point.x-p.x-t*dx,point.y-p.y-t*dy);if(d<best){best=d;index=i;}}
  w.points.splice(index,0,point);w.routing=w.routing==='orthogonal'?'orthogonal':'polyline';changed();drawWires();inspect(w.id);
}
function wireInspector(w){
  const route=document.createElement('select');route.className='field';route.setAttribute('aria-label','Маршрут дроту');
  for(const [value,label]of [['curve','Плавна лінія'],['orthogonal','Під прямим кутом'],['polyline','Лінія через точки']]){const o=document.createElement('option');o.value=value;o.textContent=label;route.append(o);}route.value=w.routing||'curve';
  route.onchange=()=>{w.routing=route.value;changed();drawWires();};$('details').append(route);
  const add=document.createElement('button');add.textContent='Додати точку перегину';add.onclick=()=>{const a=pinPositions.get(E.key(w.a.node,w.a.pin)),b=pinPositions.get(E.key(w.b.node,w.b.pin));if(a&&b)addWirePoint(w,{x:(a.x+b.x)/2,y:(a.y+b.y)/2});};$('details').append(add);
  const hint=document.createElement('small');hint.textContent='Перетягніть круглі точки на полотні. Подвійне натискання на дріт додає точку.';$('details').append(hint);
  for(let i=0;i<(w.points||[]).length;i++){const b=document.createElement('button');b.textContent='Видалити точку '+(i+1);b.onclick=()=>{w.points.splice(i,1);changed();drawWires();inspect(w.id);};$('details').append(b);}
  const remove=document.createElement('button');remove.textContent='Видалити дріт';remove.onclick=removeSelected;$('details').append(remove);
}
function setInput(id,value){
  const n=scene.nodes.find(n=>n.id===id);if(!n)return false;
  value=Number(value);if(!Number.isFinite(value))return false;value=Math.max(0,Math.min(1,value));if(n.inputValue===value&&inputValues.get(id)===value)return true;n.inputValue=value;inputValues.set(id,value);
  dirty=true;history.push(scene);$('saveState').textContent='Незбережені зміни';clearTimeout(saveTimer);saveTimer=setTimeout(save,600);refreshInfo();updateSignals();paintMotion();return true;
}
function paintMotion(){if(scene)WireMotion.sync(scene,inputValues,servoState,running,motorStates);const preview=document.querySelector('[data-servo-preview]');if(preview)preview.disabled=running;}
function previewServo(id,angle){
  if(running||!WireMotion.preview(id,Number(angle)))return false;
  const slider=document.querySelector('[data-servo-preview="'+id+'"]');if(slider)slider.value=Math.max(0,Math.min(180,Number(angle)));
  status('Перегляд серво · тягніть важіль по колу.');return true;
}
function inputInspector(n){
  if(M.model(catalog.get(n.part),n).type==='encoder'){const label=document.createElement('label');label.textContent='Джерело руху енкодера';const sel=document.createElement('select');sel.setAttribute('aria-label',label.textContent);for(const [id,title]of [['','Ручний кут / encoderRpm'],...scene.nodes.filter(o=>['motor','stepper','unipolarStepper','servo','continuousServo','escMotor'].includes(M.model(catalog.get(o.part),o).type)).map(o=>[o.id,o.label])]){const opt=document.createElement('option');opt.value=id;opt.textContent=title;sel.append(opt);}sel.value=n.electrical?.mechanicalSource||'';sel.onchange=()=>{const m=M.model(catalog.get(n.part),n);n.electrical??={type:m.type,parameters:{},roles:m.roles};n.electrical.mechanicalSource=sel.value||null;changed();inspect(n.id);};label.append(sel);$('details').append(label);}
  const model=E.modelFor(catalog.get(n.part));
  if(model?.type==='servo'){
    const readout=document.createElement('output');readout.dataset.motionReadout=n.id;readout.className='servo-readout';readout.textContent='Servo · 0–180°';$('details').append(readout);
    const note=document.createElement('p');note.textContent='GND → gnd; живлення → vcc; вихід плати → pulse. Керуйте через Servo.h: attach / write / writeMicroseconds. Поворот корпусу ⟳ не змінює кут вала.';$('details').append(note);
    const preview=document.createElement('input');preview.type='range';preview.min=0;preview.max=180;preview.value=Math.round(WireMotion.states()[n.id]?.angle??90);preview.setAttribute('aria-label','Перегляд руху серво');preview.dataset.servoPreview=n.id;preview.disabled=running;preview.oninput=()=>previewServo(n.id,Number(preview.value));
    const label=document.createElement('small');label.textContent='Без запуску крутіть важіль пальцем прямо на схемі або цим повзунком. Під час запуску кут задає C++: спочатку натисніть «Зупинити».';$('details').append(label,preview);
  }
  if(['potentiometer','sensor'].includes(model?.type)||['ldr','encoder'].includes(M.model(catalog.get(n.part),n).type)||n.simulation){
    const panel=document.createElement('div');panel.className='input-control';
    const encoder=M.model(catalog.get(n.part),n).type==='encoder',maximum=encoder?360:1023;
    const title=document.createElement('label');title.textContent=encoder?'Кут енкодера, °':model?.type==='potentiometer'?'Положення потенціометра':'Задане значення датчика / ADC';
    const slider=document.createElement('input');slider.type='range';slider.min=0;slider.max=maximum;slider.value=Math.round((inputValues.get(n.id)??n.inputValue??.5)*maximum);slider.setAttribute('aria-label',title.textContent);
    const number=document.createElement('input');number.type='number';number.min=0;number.max=maximum;number.value=slider.value;number.setAttribute('aria-label',encoder?'Кут 0–360°':'ADC 0–1023');
    slider.oninput=()=>{number.value=slider.value;setInput(n.id,Number(slider.value)/maximum);};number.onchange=()=>{slider.value=number.value;number.value=slider.value;setInput(n.id,Number(slider.value)/maximum);};
    const note=document.createElement('small');note.textContent=encoder?'A/B → входи з INPUT_PULLUP; спільний контакт → GND. Ручний кут працює без прив’язаного ротора та при encoderRpm = 0.':model?.type==='potentiometer'?'З’єднайте leg1/leg2 з GND/живленням, wiper — з входом плати.':model?'Керований сигнал 0–1023. Потрібні живлення, GND і вихід. Значення не переводиться у фізичні одиниці.':'Ручне джерело на контакті. Протокол і електроніка датчика не моделюються.';
    panel.append(title,slider,number,note);$('details').append(panel);
  }
  if(n.kind==='passive'&&!M.model(catalog.get(n.part),n).modeled){
    const chooser=document.createElement('select');chooser.className='field';chooser.setAttribute('aria-label','Контакт ручного сигналу');
    const off=document.createElement('option');off.value='';off.textContent='Без моделі / ручний сигнал вимкнено';chooser.append(off);
    for(const p of catalog.get(n.part).pins){const o=document.createElement('option');o.value=p.id;o.textContent='Ручний ADC → '+p.name;chooser.append(o);}chooser.value=n.simulation?.pin||'';
    chooser.onchange=()=>{if(chooser.value)n.simulation={pin:chooser.value};else delete n.simulation;changed();inputValues.set(n.id,n.inputValue??.5);inspect(n.id);};$('details').append(chooser);
  }
}
function refreshMonitorChoices(){
  const previous=watchPin,channels=new Set([...$('signalChannels').selectedOptions].map(o=>o.value));$('signalSelect').replaceChildren();$('signalChannels').replaceChildren();
  const connected=new Set(scene.wires.flatMap(w=>[E.key(w.a.node,w.a.pin),E.key(w.b.node,w.b.pin)]));
  for(const n of scene.nodes)for(const p of catalog.get(n.part).pins){const k=E.key(n.id,p.id);if(n.kind!=='board'&&!connected.has(k))continue;const o=document.createElement('option');o.value=k;o.textContent=n.label+' · '+p.name;$('signalSelect').append(o);const channel=o.cloneNode(true);channel.selected=channels.has(k);$('signalChannels').append(channel);}
  watchPin=[...$('signalSelect').options].some(o=>o.value===previous)?previous:$('signalSelect').value;$('signalSelect').value=watchPin||'';
}
function recordSignals(force=false){
  const t=performance.now();if(!force&&t-monitorTick<80)return;monitorTick=t;
  const watched=new Set([watchPin,...scene.wires.flatMap(w=>[E.key(w.a.node,w.a.pin),E.key(w.b.node,w.b.pin)]),...outputs.keys()].filter(Boolean).slice(0,256));
  for(const k of [...traces.keys()])if(!watched.has(k))traces.delete(k);
  for(const k of watched){const v=lastValues.get(k);if(v===undefined)continue;let list=traces.get(k);if(!list){list=[];traces.set(k,list);}const sampleUs=mcuStates.size?monitorEndUs():(t-monitorStart)*1000;list.push({t:sampleUs/1000,v});if(!outputs.has(k)&&timeline.metadata(k)?.source!=='mcu'){const colon=k.indexOf(':'),id=k.slice(0,colon),pin=k.slice(colon+1);timeline.record(id,pin,{type:'sample',value:v,time:sampleUs,clock:mcuStates.size?'virtual':'wall',source:'electrical-model'},sampleUs);}while(list.length>250||list.length>1&&list[0].t<sampleUs/1000-10000)list.shift();}
  if(!$('monitor').hidden)drawMonitor();
}
function monitorChannels(){const selected=[...$('signalChannels').selectedOptions].map(o=>o.value);return selected.length?selected:[watchPin].filter(Boolean);}
function monitorEndUs(){if(timeline.metadata(watchPin)?.clock==='virtual')return Math.max(virtualTimeUs,...[...mcuStates.values()].map(s=>s.timeUs),timeline.range(watchPin).end);return Math.max(0,((running?performance.now():monitorTick)-monitorStart)*1000);}
function monitorWindow(){const width=Number($('signalWindow').value)*1000,end=monitorEndUs();return {start:Math.max(0,end-width),end:Math.max(end,1),width};}
function drawMonitor(){
 const svg=$('signalPlot');svg.replaceChildren();const NS='http://www.w3.org/2000/svg',window=monitorWindow(),start=capturedSignals?.start??window.start,end=capturedSignals?.end??window.end,width=Math.max(1,end-start),channels=capturedSignals?.channels||monitorChannels().slice(0,8).map(pin=>({pin,points:timeline.points(pin,start,end,2500),metadata:timeline.metadata(pin)})),colors=['#6297de','#4ade80','#facc15','#fb923c','#c084fc','#f472b6','#22d3ee','#f87171'];
 const rows=Math.max(1,channels.length),height=104/rows;
 for(let i=0;i<channels.length;i++){const channel=channels[i],points=channel.points.length?channel.points:(traces.get(channel.pin)||[]).filter(p=>p.t*1000>=start).map(p=>({time:p.t*1000,value:p.v})),base=(i+1)*height-4,amplitude=height-12;let d='';for(const p of points){const x=Math.max(0,Math.min(500,(p.time-start)/width*500)),y=base-p.value*amplitude;d+=d?` H${x} V${y}`:`M${x} ${y}`;}if(d){const path=document.createElementNS(NS,'path');path.setAttribute('d',d);path.setAttribute('class','plot-signal');path.style.stroke=colors[i];svg.append(path);}const text=document.createElementNS(NS,'text');text.setAttribute('x',4);text.setAttribute('y',i*height+10);text.setAttribute('fill',colors[i]);text.setAttribute('font-size','8');text.textContent=channel.pin;svg.append(text);}
 const value=lastValues.get(watchPin),volts=electricalResult?.volts.get(watchPin),event=timeline.eventAt(watchPin,end),metadata=timeline.metadata(watchPin),measure=timeline.measure(watchPin,start,end);
 const boardId=watchPin?.split(':')[0],board=scene.nodes.find(n=>n.id===boardId),bits=adcBits.get(boardId)||(board?M.board(board,catalog.get(board.part)).adcBits:10),maximum=(1<<bits)-1,output=['OUTPUT','OUTPUT_OPENDRAIN'].includes(modes.get(watchPin));$('signalValue').textContent=(value===undefined?'Немає сигналу':value===0?'LOW · 0':value===1?'HIGH · 1':output?'PWM '+Math.round((event?.value??value)*255)+'/255':'ADC '+Math.round(value*maximum)+'/'+maximum)+' · '+(volts??0).toFixed(3)+' V'+(event?.frequency?' · '+event.frequency+' Hz · '+(event.value*100).toFixed(2)+'%':'');
 $('signalMeasure').textContent=measure.frequencyHz?measure.frequencyHz.toFixed(3)+' Hz · період '+measure.periodUs.toFixed(3)+' μs · HIGH '+(measure.highUs??0).toFixed(3)+' μs · '+((measure.duty??0)*100).toFixed(2)+'%':'Потрібно кілька фронтів для вимірювання';
 $('signalHint').textContent=(capturedSignals?'Захоплено · ':'')+(metadata?.clock==='virtual'?'Віртуальний час AVR, фронти з часовими мітками тактів. Аналогова схема / обмін між MCU: квант до 1 ms.':'Час Android; PWM відновлюється з частоти та заповнення.')+(event?.reconstructed?' Відновлені фронти.':'');
}
function refreshSerialBoards(){const previous=$('serialBoard').value;$('serialBoard').replaceChildren();for(const n of scene.nodes.filter(n=>n.kind==='board')){const o=document.createElement('option');o.value=n.id;o.textContent=n.label;$('serialBoard').append(o);}if(window.WireHost?.usbStatus){try{const usb=workbench.json(WireHost.usbStatus());if(usb.connected){const o=document.createElement('option');o.value='__usb';o.textContent='USB · '+usb.device+' · '+usb.baud+' baud';$('serialBoard').append(o);}}catch(_){}}if([...$('serialBoard').options].some(o=>o.value===previous))$('serialBoard').value=previous;paintSerial();}
function paintSerial(){const id=$('serialBoard').value;$('serialOutput').textContent=serialBuffers.get(id)||'';$('serialOutput').scrollTop=$('serialOutput').scrollHeight;}
function serialReceive(id,text){serialBuffers.set(id,((serialBuffers.get(id)||'')+text).slice(-32768));paintSerial();}
function sendSerial(){const selectedId=$('serialBoard').value;if(selectedId==='__usb'){const text=$('serialInput').value+({none:'',lf:'\n',crlf:'\r\n'}[$('serialEnding').value]||''),bytes=new TextEncoder().encode(text),e=WireHost.usbWrite(btoa(String.fromCharCode(...bytes)));if(e){log(e,true);return;}$('serialInput').value='';return;}if(!running){log('Спочатку запустіть програму',true);return;}
  const id=$('serialBoard').value,text=$('serialInput').value+({none:'',lf:'\n',crlf:'\r\n'}[$('serialEnding').value]||'');
  if(mcuStates.has(id)){avrRuntime.serial(id,[...new TextEncoder().encode(text)]);$('serialInput').value='';return;}
  if(!window.WireHost?.sendSerial){log('Serial input потребує оновленого застосунку WireDroid',true);return;}
  const error=WireHost.sendSerial(id,text);if(error){log(error,true);return;}$('serialInput').value='';
}
$('check').onclick=()=>checkCircuit();
$('orthogonal').onclick=()=>{const w=scene.wires.find(w=>w.id===selected);if(w){w.routing='orthogonal';changed();drawWires();inspect(w.id);}else{$('orthogonal').classList.toggle('active');}};
$('logsTab').onclick=()=>consoleTab('logs');$('problemsTab').onclick=()=>checkCircuit();$('monitorTab').onclick=()=>consoleTab('monitor');$('serialTab').onclick=()=>consoleTab('serialPanel');
$('signalSelect').onchange=()=>{watchPin=$('signalSelect').value;drawMonitor();};$('serialBoard').onchange=paintSerial;$('serialSend').onclick=sendSerial;$('serialInput').onkeydown=e=>{if(e.key==='Enter'){e.preventDefault();sendSerial();}};
$('debugTab').onclick=()=>consoleTab('debugPanel');$('debugPause').onclick=()=>{avrRuntime.pause();workbench.debuggerState(mcuStates);};$('debugContinue').onclick=()=>avrRuntime.run();$('debugStep').onclick=()=>avrRuntime.step($('debugBoard').value).catch(showError);$('debugReset').onclick=()=>resetMCU($('debugBoard').value).catch(showError);$('debugBoard').onchange=()=>workbench.debuggerState(mcuStates);$('breakpointSet').onclick=()=>{const raw=$('breakpointAddress').value.trim(),address=Number(raw);if(!Number.isInteger(address)||address<0||address>=32768||address%2){log('Адреса PC має бути парною й у межах Flash',true);return;}avrRuntime.breakpoints($('debugBoard').value,[address]);log('Точка зупинки: 0x'+address.toString(16));};
$('serialClear').onclick=()=>{serialBuffers.delete($('serialBoard').value);paintSerial();};

async function init(){
  try{const [r,b]=await Promise.all([fetch('catalog.json'),fetch('boards.json')]);if(!r.ok||!b.ok)throw Error('Немає каталогу деталей або профілів плат');catalog=new Map((await r.json()).map(p=>[p.id,p]));boardRegistry=new WireBoards.Registry(await b.json());
    const raw=window.WireHost?WireHost.loadScene():localStorage.getItem('wiredroid-scene');
    if(raw&&raw.startsWith('WIREDROID_ERROR:'))throw Error(raw.slice(16));
    scene=raw?JSON.parse(raw):demo();E.validate(scene,catalog);normalize();history=new E.History(scene);
    if(window.WireHost){const theme=JSON.parse(WireHost.getTheme());applyTheme(theme);$('projectName').textContent=WireHost.projectName();}
    else $('projectName').textContent=scene.name||'Circuit workspace';
    renderLibrary();await render();fit();refreshMonitorChoices();refreshSerialBoards();window.WireApp.ready=true;if(window.WireHost?.recoveryInfo)for(const item of workbench.json(WireHost.recoveryInfo()))log('Відновлено '+item.file+': '+item.message);log('Готово · '+catalog.size+' деталей Fritzing');
    if(window.WireHost && WireHost.shouldRun())setTimeout(run,250);
  }catch(error){loadFailed=true;$('run').disabled=true;$('save').disabled=true;log('Не вдалося відкрити схему: '+error.message,true);status('Помилка відкриття');}
}
init();

function modelInspector(n){
  const part=catalog.get(n.part),m=M.model(part,n),box=document.createElement('details');box.className='electrical-settings';
  const title=document.createElement('summary');title.textContent=n.kind==='board'?'Профіль плати':m.label;box.append(title);
  const note=document.createElement('p');note.textContent=n.kind==='board'?'GPIO модель. Параметри нижче задають напругу, ADC і PWM; код GPIO виконується на Android. Режим прошивки AVR обирається нижче.':'Параметрична модель. Звірте значення з характеристиками своєї деталі.';box.append(note);if(n.kind!=='board'&&m.capability){const limits=document.createElement('small');limits.textContent=(m.capability.limits||[]).join(' · ');box.append(limits);}
  if(n.kind!=='board'){
    const select=document.createElement('select');select.className='field';select.setAttribute('aria-label','Електрична модель');
    for(const [type,label]of Object.entries(M.labels)){const option=document.createElement('option');option.value=type;option.textContent=label;select.append(option);}select.value=m.type;
    select.onchange=()=>{n.electrical={type:select.value,parameters:{},roles:m.roles};changed();inspect(n.id);};box.append(select);
    for(const [role,pin]of Object.entries(m.roles)){const label=document.createElement('label');label.textContent='Контакт '+role;const select=document.createElement('select');select.setAttribute('aria-label','Контакт моделі '+role);const off=document.createElement('option');off.value='';off.textContent='Не задано';select.append(off);for(const p of part.pins){const o=document.createElement('option');o.value=p.id;o.textContent=p.name;select.append(o);}select.value=pin||'';select.onchange=()=>{n.electrical??={type:m.type,parameters:{},roles:{}};n.electrical.roles[role]=select.value||null;changed();inspect(n.id);};label.append(select);box.append(label);}
  }
  const profile=M.board(n,part),fields=n.kind==='board'?['logicVoltage','adcBits','pwmFrequency','outputResistance','pullupResistance']:m.fields;
  const names={logicVoltage:'Логічна напруга / ADC reference, V',adcBits:'Розрядність ADC',pwmFrequency:'Частота PWM, Hz',outputResistance:'Опір GPIO, Ω',pullupResistance:'Опір підтягування, Ω'};
  for(const field of fields){const label=document.createElement('label');label.textContent=names[field]||M.fieldNames[field];const input=document.createElement('input');input.type='number';input.step='any';input.value=n.kind==='board'?profile[field]:m.params[field];input.setAttribute('aria-label',label.textContent);input.onchange=()=>{const old=E.clone(n);if(n.kind==='board'){n.boardProfile??={};n.boardProfile[field]=Number(input.value);}else{n.electrical??={type:m.type,parameters:{},roles:m.roles};n.electrical.parameters[field]=Number(input.value);}try{M.validate(n,part);changed();inspect(n.id);}catch(e){Object.keys(n).forEach(k=>delete n[k]);Object.assign(n,old);log(e.message,true);inspect(n.id);}};label.append(input);box.append(label);}
  const reading=document.createElement('output');reading.dataset.electrical=n.id;box.append(reading);$('details').append(box);
}
function paintDevices(){
  const snapshot=devices.snapshot(scene,catalog);
  for(const n of scene.nodes){const div=document.querySelector('[data-node="'+n.id+'"]');if(!div)continue;const s=snapshot[n.id];
    if(s?.type==='lcd'){let screen=div.querySelector('.lcd-display');if(!screen){screen=document.createElement('pre');screen.className='lcd-display';div.append(screen);}screen.textContent=s.powered&&s.display?s.text.join('\n'):'LCD · немає зображення';}
    if(s?.type==='ssd1306'){let screen=div.querySelector('.oled-display');if(!screen){screen=document.createElement('canvas');screen.className='oled-display';screen.width=128;screen.height=64;div.append(screen);}const ctx=screen.getContext('2d');ctx.fillStyle='#071820';ctx.fillRect(0,0,128,64);if(s.powered&&s.display){ctx.fillStyle='#e0fbff';for(let page=0;page<8;page++)for(let x=0;x<128;x++)for(let y=0;y<8;y++)if(s.pixels[page*128+x]>>y&1)ctx.fillRect(x,page*8+y,1,1);}}
    const reading=document.querySelector('[data-electrical="'+n.id+'"]'),state=electricalResult?.states.get(n.id);if(reading&&state)reading.textContent=(Number.isFinite(state.current)?'I = '+(state.current*1000).toFixed(3)+' mA':'Струм живлення не моделюється')+(state.rpm!==undefined?' · '+state.rpm.toFixed(1)+' rpm':state.power!==undefined?' · '+(state.power*1000).toFixed(3)+' mW':'');
  }
}

$('signalWindow').onchange=drawMonitor;
$('signalChannels').onchange=()=>{capturedSignals=null;drawMonitor();};
$('triggerCapture').onclick=()=>{try{const range=monitorWindow();capturedSignals=timeline.capture(monitorChannels(),{pin:watchPin,edge:$('triggerEdge').value,start:range.start,end:range.end,preUs:range.width/4,postUs:range.width*3/4});if(!capturedSignals)log('Фронт у вибраному вікні не знайдено');drawMonitor();}catch(e){showError(e);}};$('triggerLive').onclick=()=>{capturedSignals=null;drawMonitor();};
$('decodeSignal').onclick=()=>{try{const pins=monitorChannels(),range=monitorWindow(),protocol=$('signalDecoder').value,options={start:capturedSignals?.start??range.start,end:capturedSignals?.end??range.end,baud:Number($('decoderBaud').value),pin:watchPin,scl:pins[0],sda:pins[1],clock:pins[0],mosi:pins[1],miso:pins[2],select:pins[3],mode:Number($('decoderSpiMode').value)};const decoded=timeline.decode(protocol,options);$('decodedSignals').textContent=JSON.stringify(decoded,null,2);}catch(e){$('decodedSignals').textContent=e.message;}};
$('exportVcd').onclick=()=>{try{const range=monitorWindow(),data=timeline.vcdChannels(monitorChannels(),capturedSignals?.start??range.start,capturedSignals?.end??range.end),name='signals_'+Date.now()+'.vcd';if(window.WireHost?.saveSignal){const error=WireHost.saveSignal(name,data);if(error)log(error,true);else log('Збережено signals/'+name);}else{const url=URL.createObjectURL(new Blob([data],{type:'text/plain'})),a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);}}catch(error){log(error.message,true);}};

function configFor(n){if(configs.has(n.id))return configs.get(n.id);let cfg={mode:'gpio'};try{const raw=window.WireHost?.getFirmwareConfig?WireHost.getFirmwareConfig(n.id):localStorage.getItem('wiredroid-config-'+n.id);if(raw)cfg={...cfg,...workbench.json(raw)};}catch(e){log(e.message,true);}configs.set(n.id,cfg);return cfg;}
function saveConfig(n,cfg){if(!n)throw Error('Плату не знайдено');if(n.kind!=='board')return;if(window.WireHost?.saveFirmwareConfig){const e=WireHost.saveFirmwareConfig(n.id,JSON.stringify(cfg));if(e)throw Error(e);}else localStorage.setItem('wiredroid-config-'+n.id,JSON.stringify(cfg));configs.set(n.id,E.clone(cfg));log('Налаштування '+n.label+' збережено');}
function voltsFor(n,result){const out={};for(const p of catalog.get(n.part).pins)out[p.id]=result.volts.get(E.key(n.id,p.id))||0;return out;}
function mcuBus(id,event){const n=scene.nodes.find(n=>n.id===id);updateSignals();if(!running||!electricalResult)throw Error('Схему зупинено');
 const bus=e=>devices.bus(n,e,scene,catalog,electricalResult);
 if(event.bus==='spi')return bus(event);
 const previous=i2cTransactions.get(id),flush=()=>{if(previous?.data){const reply=bus({...previous,operation:undefined,count:0});i2cTransactions.delete(id);return reply;}return [0];};
 if(event.operation==='address'){flush();const reply=bus({...event,operation:undefined,count:0,data:''});i2cTransactions.set(id,{...event,operation:undefined,data:''});return reply;}
 if(event.operation==='byte'){if(!previous)throw Error('I²C без адреси');previous.data+=event.data;if(previous.data.length>8192)throw Error('Буфер I²C переповнений');return [0];}
 if(event.operation==='read'){flush();return bus({...event,operation:undefined,count:1,data:''});}
 if(event.operation==='stop'){const reply=flush();i2cTransactions.delete(id);paintDevices();return reply;}
 throw Error('Невідома операція I²C');}
async function resetMCU(id){for(const map of [outputs,modes,servoPulses,pulseStarts])for(const key of map.keys())if(key.startsWith(id+':'))map.delete(key);i2cTransactions.delete(id);timeline.reset();solver.reset();devices.reset();servos.reset();previousVirtualTime=virtualTimeUs=0;await avrRuntime.reset(id);updateSignals();}
function showDiagnostics(text){if(!window.WireHost?.parseDiagnostics)return;const diagnostics=workbench.json(WireHost.parseDiagnostics(text));if(!diagnostics.length)return;consoleTab('problems');for(const d of diagnostics){const b=document.createElement('button');b.className='problem '+d.severity;b.textContent=d.file+':'+d.line+':'+d.column+' · '+d.message;b.onclick=()=>{const e=WireHost.openDiagnostic(d.file,d.line,d.column);if(e)log(e,true);};$('problems').append(b);}}
function paintGroup(){for(const el of $('nodes').querySelectorAll('.node'))el.classList.toggle('group-selected',groupSelection.has(el.dataset.node));}
function selectedGroup(){return scene.nodes.filter(n=>groupSelection.size?groupSelection.has(n.id):n.id===selected);}
function alignGroup(){const nodes=selectedGroup();if(nodes.length<2){log('Оберіть кілька деталей у режимі «Група»');return;}const y=Math.min(...nodes.map(n=>n.y));for(const n of nodes)n.y=y;changed();render().catch(showError);}
function duplicateGroup(){const nodes=selectedGroup();if(!nodes.length)return;if(scene.nodes.length+nodes.length>250)throw Error('Ліміт 250 деталей');const map=new Map(nodes.map(n=>[n.id,newId('part')])),copies=nodes.map(n=>({...E.clone(n),id:map.get(n.id),x:n.x+35,y:n.y+35,label:n.label+' копія'})),wires=scene.wires.filter(w=>map.has(w.a.node)&&map.has(w.b.node)).map(w=>({...E.clone(w),id:newId('wire'),a:{...w.a,node:map.get(w.a.node)},b:{...w.b,node:map.get(w.b.node)},points:w.points?.map(p=>({x:p.x+35,y:p.y+35}))}));if(scene.wires.length+wires.length>2000)throw Error('Ліміт 2000 дротів');for(const original of nodes){const copy=copies.find(n=>n.id===map.get(original.id));if(copy.kind==='board'&&window.WireHost?.duplicateSketch){const e=WireHost.duplicateSketch(original.id,copy.id);if(e)throw Error(e);}saveConfig(copy,configFor(original));}scene.nodes.push(...copies);scene.wires.push(...wires);groupSelection=new Set(copies.map(n=>n.id));selected=copies[0].id;changed();render().then(paintGroup).catch(showError);}
$('groupSelect').onclick=()=>{groupMode=!groupMode;groupSelection.clear();$('groupSelect').classList.toggle('active',groupMode);paintGroup();status(groupMode?'Торкніться деталей для вибору групи':'Готово');};$('duplicate').onclick=()=>{try{duplicateGroup();}catch(e){showError(e);}};$('align').onclick=alignGroup;
setInterval(()=>{if(document.hidden||!window.WireHost?.usbRead)return;try{const status=workbench.json(WireHost.usbStatus());if(status.connected&&!status.uploading){const data=WireHost.usbRead(4096);if(data)serialReceive('__usb',new TextDecoder().decode(Uint8Array.from(atob(data),c=>c.charCodeAt(0))));}}catch(_){}},150);
