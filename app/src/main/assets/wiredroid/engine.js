(function(root) {
  'use strict';
  const clone = value => JSON.parse(JSON.stringify(value));
  const key = (node, pin) => node + ':' + pin;
  function validate(scene, catalog) {
    if (!scene || scene.version !== 1 || !Array.isArray(scene.nodes) || !Array.isArray(scene.wires)) throw Error('Непідтримуваний формат схеми');
    if (scene.nodes.length > 250 || scene.wires.length > 2000) throw Error('Схема перевищує ліміт: 250 деталей / 2000 дротів');
    const nodes = new Map();
    for (const n of scene.nodes) {
      if (!/^[a-zA-Z0-9_-]{1,64}$/.test(n.id) || nodes.has(n.id)) throw Error('Некоректний або повторний ID деталі');
      if (!catalog.has(n.part)) throw Error('Невідома деталь: ' + n.part);
      if (!Number.isFinite(n.x) || !Number.isFinite(n.y) || Math.abs(n.x)>100000 || Math.abs(n.y)>100000) throw Error('Некоректні координати');
      if (![0,90,180,270].includes(n.rotation || 0)) throw Error('Некоректний поворот');
      if (n.scale!==undefined && (!Number.isFinite(n.scale) || n.scale<.25 || n.scale>2)) throw Error('Розмір деталі має бути від 25% до 200%');
      if (n.ledColor!==undefined && !/^#[0-9a-f]{6}$/i.test(n.ledColor)) throw Error('Некоректний колір LED');
      if(n.simulation!==undefined&&(!n.simulation||!catalog.get(n.part).pins.some(p=>p.id===n.simulation.pin)))throw Error('Некоректний контакт ручного сигналу');
      if(n.inputValue!==undefined&&(!Number.isFinite(n.inputValue)||n.inputValue<0||n.inputValue>1))throw Error('Некоректне значення входу');
      root.WireModels?.validate(n,catalog.get(n.part));
      nodes.set(n.id, n);
    }
    const wireIds = new Set(), pairs = new Set();
    for (const w of scene.wires) {
      if (!w || typeof w.id!=='string' || wireIds.has(w.id)||nodes.has(w.id)) throw Error('Повторний ID дроту');
      wireIds.add(w.id);
      for (const endpoint of [w.a, w.b]) {
        const n = endpoint && nodes.get(endpoint.node);
        if (!n || !catalog.get(n.part).pins.some(p=>p.id===endpoint.pin)) throw Error('Дріт підключений до невідомого контакту');
      }
      const a=key(w.a.node,w.a.pin), b=key(w.b.node,w.b.pin), pair=[a,b].sort().join('|');
      if (a===b || pairs.has(pair)) throw Error('Повторне з’єднання');
      pairs.add(pair);
      if (w.routing!==undefined&&!['curve','orthogonal','polyline'].includes(w.routing))throw Error('Некоректний маршрут дроту');
      if(w.points!==undefined&&(!Array.isArray(w.points)||w.points.length>64||w.points.some(p=>!p||!Number.isFinite(p.x)||!Number.isFinite(p.y)||Math.abs(p.x)>100000||Math.abs(p.y)>100000)))throw Error('Некоректні точки дроту');
      if (w.color && !/^#[0-9a-fA-F]{6}$/.test(w.color)) throw Error('Некоректний колір дроту');
    }
    return scene;
  }
  class History {
    constructor(initial) { this.items=[clone(initial)]; this.index=0; }
    push(scene) {
      if (JSON.stringify(scene)===JSON.stringify(this.items[this.index])) return;
      this.items.splice(this.index+1); this.items.push(clone(scene));
      if (this.items.length>80) this.items.shift();
      this.index=this.items.length-1;
    }
    undo() { if(this.index>0)this.index--; return clone(this.items[this.index]); }
    redo() { if(this.index<this.items.length-1)this.index++; return clone(this.items[this.index]); }
  }
  // Copper topology keeps resistors separate for electrical rule checks and highlighting.
  function topology(scene,catalog,pressed=new Set(),conductResistors=false) {
    const parent=new Map(), adjacency=new Map();
    const find=k=>{if(!parent.has(k))parent.set(k,k);const p=parent.get(k);if(p!==k)parent.set(k,find(p));return parent.get(k);};
    const join=(a,b)=>{parent.set(find(a),find(b));};
    const edge=(a,b,resistor=false)=>{if(!adjacency.has(a))adjacency.set(a,[]);if(!adjacency.has(b))adjacency.set(b,[]);adjacency.get(a).push({to:b,resistor});adjacency.get(b).push({to:a,resistor});if(!resistor||conductResistors)join(a,b);};
    for(const n of scene.nodes){const p=catalog.get(n.part);for(const pin of p.pins)find(key(n.id,pin.id));
      for(const bus of p.buses||[])for(let i=1;i<bus.length;i++)edge(key(n.id,bus[0]),key(n.id,bus[i]));
      if(n.kind==='resistor'||(n.kind==='button'&&pressed.has(n.id))){const model=root.WireModels?.model(p,n),pins=model&&(n.kind==='resistor'||n.electrical?.type==='button')?[model.roles.a,model.roles.b].filter(Boolean):p.pins.map(p=>p.id);for(let i=1;i<pins.length;i++)edge(key(n.id,pins[0]),key(n.id,pins[i]),n.kind==='resistor');}
    }
    for(const w of scene.wires)edge(key(w.a.node,w.a.pin),key(w.b.node,w.b.pin));
    return {find,keys:[...parent.keys()],adjacency};
  }
  const ground=name=>/^(GND|GROUND|−|-)(\b|$)/i.test(name.trim());
  const supply=name=>/^(5V|3V3|3\.3V|VCC|VDD|VUSB|VBUS|\+)$/.test(name.toUpperCase().trim());
  function modelFor(part) {
    const pins=part.pins;
    if(pins.length>=3&&pins.length<=4&&/servo/i.test(part.id+' '+part.title)){
      const signal=pins.find(p=>/^(pulse|signal)$/i.test(p.name)),low=pins.find(p=>ground(p.name)),high=pins.find(p=>supply(p.name)||/^power$/i.test(p.name));
      if(signal&&low&&high)return {type:'servo',signal:signal.id,low:low.id,high:high.id};
    }
    if(pins.length>=3&&pins.length<=6&&pins.some(p=>/^wiper$/i.test(p.name))&&pins.some(p=>/^leg1$/i.test(p.name))&&pins.some(p=>/^leg2$/i.test(p.name)))return {type:'potentiometer',signal:pins.find(p=>/^wiper$/i.test(p.name)).id,low:pins.find(p=>/^leg1$/i.test(p.name)).id,high:pins.find(p=>/^leg2$/i.test(p.name)).id};
    const signal=pins.find(p=>/^(VOUT|SIG|S)$/i.test(p.name)),gnd=pins.find(p=>ground(p.name)),power=pins.find(p=>supply(p.name)||/^\+Vs$/i.test(p.name));
    if(pins.length===3&&signal&&gnd&&power)return {type:'sensor',signal:signal.id,low:gnd.id,high:power.id};
    return null;
  }
  function audit(scene,catalog,modes=new Map(),pressed=new Set()) {
    const copper=topology(scene,catalog,pressed),conductive=topology(scene,catalog,pressed,true),issues=[],connected=new Set();
    for(const w of scene.wires){connected.add(key(w.a.node,w.a.pin));connected.add(key(w.b.node,w.b.pin));}
    const groups=new Map(),byPin=new Map();
    for(const n of scene.nodes)for(const pin of catalog.get(n.part).pins){const k=key(n.id,pin.id),id=copper.find(k);byPin.set(k,{node:n,pin});if(!groups.has(id))groups.set(id,[]);groups.get(id).push(k);}
    const issue=(code,severity,message,pins)=>issues.push({id:code+':'+pins.join(','),code,severity,message,pins,nodes:[...new Set(pins.map(k=>byPin.get(k)?.node.id).filter(Boolean))]});
    for(const pins of groups.values()) {
      const powers=pins.filter(k=>byPin.get(k).node.kind==='board'&&supply(byPin.get(k).pin.name));
      const grounds=pins.filter(k=>byPin.get(k).node.kind==='board'&&ground(byPin.get(k).pin.name));
      if(powers.length&&grounds.length)issue('power-short','error','Живлення напряму з’єднане з GND',pins);
      const drivers=pins.filter(k=>modes.get(k)==='OUTPUT'||byPin.get(k).node.simulation?.pin===byPin.get(k).pin.id);
      if(drivers.length>1)issue('output-output','error','Два виходи в одному ланцюзі: '+drivers.map(k=>byPin.get(k).node.label+' '+byPin.get(k).pin.name).join(' ↔ '),pins);
      if(drivers.length&&powers.concat(grounds).length)issue('output-rail','error','Вихід напряму під’єднаний до живлення або GND',pins);
    }
    const connectedRoots=new Set([...connected].map(k=>copper.find(k))),boardRoots=new Set(),boardConductive=new Set();
    for(const [k,item]of byPin)if(item.node.kind==='board'&&connected.has(k)){boardRoots.add(copper.find(k));boardConductive.add(conductive.find(k));}
    for(const n of scene.nodes){const part=catalog.get(n.part);
      if(n.kind==='board') {const unused=part.pins.filter(p=>!connected.has(key(n.id,p.id)));if(unused.length)issue('unused-board','info',n.label+': '+unused.length+' вільних контактів (можна залишити)',unused.map(p=>key(n.id,p.id)));continue;}
      // Bus-connected equivalent pads count as connected, even if only one pad has a wire.
      const unused=part.pins.filter(p=>!connectedRoots.has(copper.find(key(n.id,p.id))));
      if(unused.length)issue('unconnected','warning',n.label+': нез’єднані '+unused.map(p=>p.name).join(', '),unused.map(p=>key(n.id,p.id)));
      if(n.kind==='led'){
        const model=root.WireModels?.model(part,n),a=model?part.pins.find(p=>p.id===model.roles.a):part.pins.find(p=>/anode/i.test(p.name)),c=model?part.pins.find(p=>p.id===model.roles.b):part.pins.find(p=>/cathode/i.test(p.name));if(!a||!c)continue;
        const starts=[key(n.id,a.id),key(n.id,c.id)];
        // Any direct copper path from either LED terminal to a board can bypass a resistor.
        const direct=starts.some(k=>boardRoots.has(copper.find(k)));
        const reachable=starts.every(k=>boardConductive.has(conductive.find(k)));
        const protectedSide=starts.some(k=>!boardRoots.has(copper.find(k))&&boardConductive.has(conductive.find(k)));
        if(reachable&&direct&&!protectedSide)issue('led-resistor','warning',n.label+': LED підключено до плати без послідовного резистора',starts);
      }
    }
    return issues;
  }
  function route(a,b,w) {
    const points=[a,...(w.points||[]),b];
    if(w.routing==='orthogonal'){
      const routed=[points[0]];for(let i=1;i<points.length;i++){const p=routed[routed.length-1],q=points[i];if(p.x!==q.x&&p.y!==q.y)routed.push({x:q.x,y:p.y});routed.push(q);}return routed;
    }
    return points;
  }
  function wirePath(a,b,w){
    const points=route(a,b,w);
    if(w.routing==='orthogonal'||w.routing==='polyline')return points.map((p,i)=>(i?'L':'M')+p.x+' '+p.y).join(' ');
    let d='M'+a.x+' '+a.y;for(let i=1;i<points.length;i++){const p=points[i-1],q=points[i],mid=(p.x+q.x)/2;d+=` C${mid} ${p.y},${mid} ${q.y},${q.x} ${q.y}`;}return d;
  }
  function nets(scene,catalog,pressed) {return topology(scene,catalog,pressed,true);}
  function resolve(scene,catalog,outputs,modes,pressed,inputs=new Map()) {
    const network=nets(scene,catalog,pressed), drivers=new Map(), pullups=new Set();
    const drive=(pin,value,label)=>{
      const id=network.find(pin); if(!drivers.has(id))drivers.set(id,[]);
      drivers.get(id).push({value,label});
    };
    for(const n of scene.nodes) {
      const part=catalog.get(n.part);
      for(const p of part.pins) {
        const pin=key(n.id,p.id), name=p.name.toUpperCase();
        if(n.kind==='board') {
          if(ground(name))drive(pin,0,n.label+': '+p.name);
          if(supply(name))drive(pin,1,n.label+': '+p.name);
        }
        if(modes.get(pin)==='OUTPUT' && outputs.has(pin))drive(pin,outputs.get(pin),n.label+': '+p.name);
        if(modes.get(pin)==='INPUT_PULLUP')pullups.add(network.find(pin));
      }
    }
    // External ADC sources are active only with modeled power/reference connections.
    const railValue=k=>{const list=drivers.get(network.find(k))||[];return list.length?Math.max(...list.map(d=>d.value)):null;};
    for(const n of scene.nodes){const model=modelFor(catalog.get(n.part));
      if(model&&model.type!=='servo'&&inputs.has(n.id)){const lo=railValue(key(n.id,model.low)),hi=railValue(key(n.id,model.high));
        if(lo!==null&&hi!==null&&(model.type==='potentiometer'||(lo===0&&hi>0)))drive(key(n.id,model.signal),lo+(hi-lo)*inputs.get(n.id),n.label+' ADC');
      }
      if(n.simulation?.pin&&inputs.has(n.id))drive(key(n.id,n.simulation.pin),inputs.get(n.id),n.label+' ручний сигнал');
    }
    const values=new Map(), conflicts=[];
    for(const pin of network.keys) {
      const id=network.find(pin), list=drivers.get(id)||[];
      const high=list.some(d=>d.value>0), low=list.some(d=>d.value===0);
      if(high&&low) { values.set(pin,null); if(!conflicts.some(c=>c.net===id))conflicts.push({net:id,labels:list.map(d=>d.label)}); }
      else values.set(pin,list.length?Math.max(...list.map(d=>d.value)):pullups.has(id)?1:0);
    }
    return {values,conflicts,network};
  }
  function servoStates(scene,catalog,values,pulses,pressed=new Set()){
    const network=topology(scene,catalog,pressed,true),grounds=new Set(),power=new Set(),signals=new Map(),states=new Map();
    for(const n of scene.nodes)if(n.kind==='board')for(const p of catalog.get(n.part).pins){const root=network.find(key(n.id,p.id));if(ground(p.name))grounds.add(root);if(supply(p.name))power.add(root);}
    for(const [k,pulse]of pulses){const root=network.find(k);if(!signals.has(root))signals.set(root,[]);signals.get(root).push(pulse);}
    for(const n of scene.nodes){const electrical=root.WireModels?.model(catalog.get(n.part),n),model=electrical&&['servo','continuousServo'].includes(electrical.type)?{type:'servo',signal:electrical.roles.a,low:electrical.roles.b,high:electrical.roles.power}:modelFor(catalog.get(n.part));if(model?.type!=='servo'||n.kind==='board')continue;
      const high=key(n.id,model.high),low=key(n.id,model.low),input=key(n.id,model.signal),list=signals.get(network.find(input))||[];
      const powered=power.has(network.find(high))&&grounds.has(network.find(low))&&(values.get(high)||0)>.5&&(values.get(low)||0)<.02;
      const active=powered&&list.length===1;
      states.set(n.id,{active,powered,angle:active?list[0].angle:null,pulse:active?list[0].pulse:null,state:!powered?'Немає живлення / GND':list.length===0?'Немає сигналу Servo':list.length>1?'Конфлікт сигналів Servo':'Servo · працює'});
    }
    return states;
  }
  function programPin(part,raw) {
    const name=String(raw).toUpperCase();
    const found=part.pins.find(p=>p.id===raw || p.name.toUpperCase()===name || p.name.toUpperCase().split(/[ /]/)[0]===name
      || p.name.toUpperCase().split(/[ /]/)[0]==='D'+name || p.name.toUpperCase().split(/[ /]/)[0]==='GPIO'+name);
    if(!found)throw Error('Невідомий контакт '+raw);
    return found.id;
  }
  const api={validate,History,nets,topology,audit,modelFor,route,wirePath,resolve,servoStates,programPin,key,clone};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  root.WireEngine=api;
})(typeof globalThis!=='undefined'?globalThis:this);
