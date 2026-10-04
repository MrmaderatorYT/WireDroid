(function(root){
  'use strict';
  const E=root.WireEngine||(typeof require==='function'?require('./engine.js'):null),M=root.WireModels||(typeof require==='function'?require('./models.js'):null);
  const k=E.key,clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
  function linear(a,b){
    const n=b.length;
    for(let col=0;col<n;col++){
      let pivot=col;for(let row=col+1;row<n;row++)if(Math.abs(a[row][col])>Math.abs(a[pivot][col]))pivot=row;
      if(Math.abs(a[pivot][col])<1e-18)throw Error('Несумісні джерела напруги або невизначена схема');
      if(pivot!==col){[a[col],a[pivot]]=[a[pivot],a[col]];[b[col],b[pivot]]=[b[pivot],b[col]];}
      for(let row=col+1;row<n;row++){const ratio=a[row][col]/a[col][col];if(!ratio)continue;a[row][col]=0;for(let j=col+1;j<n;j++)a[row][j]-=ratio*a[col][j];b[row]-=ratio*b[col];}
    }
    const x=new Float64Array(n);for(let row=n-1;row>=0;row--){let sum=b[row];for(let j=row+1;j<n;j++)sum-=a[row][j]*x[j];x[row]=sum/a[row][row];if(!Number.isFinite(x[row]))throw Error('Електричний розрахунок не збігається');}return x;
  }
  function linearSparse(a,b,budget={operations:0,peakEntries:0}){
    const n=b.length;let entries=a.reduce((sum,row)=>sum+row.size,0);
    const account=()=>{budget.peakEntries=Math.max(budget.peakEntries,entries);if(++budget.operations>30000000||entries>400000)throw Error('Щільна велика мережа перевищує бюджет розрахунку; розділіть схему');};account();
    for(let col=0;col<n;col++){
      let pivot=col,pivotValue=Math.abs(a[col].get(col)||0);for(let row=col+1;row<n;row++){account();const value=Math.abs(a[row].get(col)||0);if(value>pivotValue){pivot=row;pivotValue=value;}}
      if(pivotValue<1e-18)throw Error('Несумісні джерела напруги або невизначена схема');
      if(pivot!==col){[a[col],a[pivot]]=[a[pivot],a[col]];[b[col],b[pivot]]=[b[pivot],b[col]];}
      const diagonal=a[col].get(col),remaining=[...a[col]].filter(([j])=>j>col);
      for(let row=col+1;row<n;row++){const cell=a[row].get(col);if(!cell)continue;const ratio=cell/diagonal;a[row].delete(col);entries--;
        for(const [j,value]of remaining){const previous=a[row].get(j),next=(previous||0)-ratio*value;if(Math.abs(next)<1e-30){if(previous!==undefined){a[row].delete(j);entries--;}}else {if(previous===undefined)entries++;a[row].set(j,next);}account();}
        b[row]-=ratio*b[col];
      }
    }
    const x=new Float64Array(n);for(let row=n-1;row>=0;row--){let sum=b[row];for(const [j,value]of a[row])if(j>row){sum-=value*x[j];account();}x[row]=sum/a[row].get(row);if(!Number.isFinite(x[row]))throw Error('Електричний розрахунок не збігається');}return x;
  }
  const ground=name=>/^(gnd|ground|g|vss\d*|vss_\d+|vssa|-)$/i.test(name.trim());
  function rail(name,logic){const s=name.trim().toUpperCase().replace(/^\+/,'');if(/^(VCC|VDD\d*|VDD_\d+|VDDA)$/.test(s))return logic;if(/^(VBUS|VUSB)$/.test(s))return 5;const m=s.match(/^(\d+)(?:V(\d+)|(\.\d+)?V)$/);return m?Number(m[1]+(m[2]?'.'+m[2]:m[3]||'')):null;}
  class Solver{
    constructor(options={}){this.method=options.method||'auto';if(!['auto','dense','sparse'].includes(this.method))throw Error('Невідомий метод електричного розрахунку');this.memory=new Map();this.previous=new Map();this.last=null;}
    reset(){this.memory.clear();this.previous.clear();this.last=null;}
    solve(scene,catalog,outputs=new Map(),modes=new Map(),pressed=new Set(),inputs=new Map(),dt=.01,external=new Map()){
      if(!Number.isFinite(dt)||dt<0)throw Error('Некоректний крок часу');dt=clamp(dt,0,.05);const advancing=dt>0,network=E.topology(scene,catalog,pressed),active=new Set(),branches=[],nodeModels=new Map(),boardGround=new Map();
      const voltageSources=new Map();
      const rootKey=pin=>network.find(pin),add=(a,b,type,value,extra={})=>{if(!a||!b)return;const ar=rootKey(a),br=rootKey(b);if(ar===br){if(type==='v'&&Math.abs(value)>1e-9)throw Error('Живлення замкнене на GND');return;}
        if(type==='v'){const forward=ar<br,key=(forward?ar:br)+'|'+(forward?br:ar),v=forward?value:-value;if(voltageSources.has(key)){if(Math.abs(voltageSources.get(key)-v)>1e-9)throw Error('З’єднані джерела різної напруги');return;}voltageSources.set(key,v);}
        active.add(ar);active.add(br);branches.push({a:ar,b:br,type,value,...extra});};
      for(const w of scene.wires){active.add(rootKey(k(w.a.node,w.a.pin)));active.add(rootKey(k(w.b.node,w.b.pin)));}
      let reference;
      for(const n of scene.nodes)if(n.kind==='board'){
        const part=catalog.get(n.part),profile=M.board(n,part),grounds=part.pins.filter(p=>ground(p.name));const g=grounds[0]?k(n.id,grounds[0].id):'@ground:'+n.id;
        boardGround.set(n.id,g);reference??=rootKey(g);active.add(rootKey(g));
        for(const p of grounds)add(k(n.id,p.id),g,'v',0);
        for(const p of part.pins){const pin=k(n.id,p.id),v=rail(p.name,profile.logicVoltage),mode=modes.get(pin);
          if(v!==null&&active.has(rootKey(pin)))add(pin,g,'v',v,{owner:n.id});
          if((mode==='OUTPUT'||mode==='OUTPUT_OPENDRAIN')&&outputs.has(pin)){const value=outputs.get(pin);if(mode!=='OUTPUT_OPENDRAIN'||value===0)add(pin,g,'thevenin',value*profile.logicVoltage,{r:profile.outputResistance,owner:n.id,output:pin});}
          if(mode==='INPUT_PULLUP')add(pin,g,'thevenin',profile.logicVoltage,{r:profile.pullupResistance});
          if(mode==='INPUT_PULLDOWN')add(pin,g,'r',profile.pullupResistance);
        }
      }
      if(!reference)reference=active.values().next().value||'@ground';
      for(const n of scene.nodes){if(n.kind==='board')continue;const part=catalog.get(n.part),m=M.model(part,n);nodeModels.set(n.id,m);const pin=role=>m.roles[role]?k(n.id,m.roles[role]):null,p=m.params,state=this.memory.get(n.id)||{};
        if(['resistor','ldr','thermistor'].includes(m.type)){let r=p.resistance;if(m.type==='ldr')r=p.darkResistance+(p.lightResistance-p.darkResistance)*(inputs.get(n.id)??n.inputValue??.5);if(m.type==='thermistor')r=p.r25*Math.exp(p.betaThermistor*(1/(p.temperature+273.15)-1/298.15));add(pin('a'),pin('b'),'r',Math.max(1e-9,r),{id:n.id});}
        else if(m.type==='capacitor')add(pin('a'),pin('b'),'c',p.capacitance,{id:n.id,old:state.voltage||0});
        else if(m.type==='inductor')add(pin('a'),pin('b'),'l',p.inductance,{id:n.id,old:state.current||0});
        else if(m.type==='battery')add(pin('a'),pin('b'),'v',p.voltage,{id:n.id});
        else if(m.type==='potentiometer'){const position=inputs.get(n.id)??n.inputValue??.5;add(pin('a'),pin('wiper'),'r',Math.max(.001,p.potResistance*position),{id:n.id,leg:'a'});add(pin('wiper'),pin('b'),'r',Math.max(.001,p.potResistance*(1-position)),{id:n.id,leg:'b'});}
        else if(m.type==='led'||m.type==='diode')add(pin('a'),pin('b'),'diode',m.type==='led'?.02/Math.expm1(p.forwardVoltage/(p.emission*.02585)):p.saturationCurrent,{id:n.id,vt:p.emission*.02585});
        else if(m.type==='npn'||m.type==='pnp'){add(pin('base'),pin('b'),m.type==='pnp'?'reverse-diode':'diode',p.saturationCurrent,{id:n.id+':be',vt:.02585});const base=pin('base');if(base)active.add(rootKey(base));add(pin('a'),pin('b'),'bjt',p.beta,{id:n.id,base:base?rootKey(base):null,is:p.saturationCurrent,polarity:m.type==='pnp'?-1:1});}
        else if(m.type==='nmos'||m.type==='pmos'){if(p.bodyDiode)add(m.type==='pmos'?pin('a'):pin('b'),m.type==='pmos'?pin('b'):pin('a'),'diode',p.saturationCurrent,{id:n.id+':body',vt:.02585});const gate=pin('gate');if(gate)active.add(rootKey(gate));add(pin('a'),pin('b'),'mos',p.conductance,{id:n.id,gate:gate?rootKey(gate):null,threshold:p.threshold,polarity:m.type==='pmos'?-1:1});}
        else if(m.type==='motor'){const held=p.loadTorque>0&&Math.abs(state.omega||0)<1e-6&&Math.abs((state.current||0)*p.torqueConstant)<=p.loadTorque,denominator=p.inertia+dt*p.friction,load=p.loadTorque*Math.sign(state.omega||state.current||0);add(pin('a'),pin('b'),'motor',p.armatureResistance+(held?0:p.backEmf*p.torqueConstant*dt/denominator),{id:n.id,l:p.armatureInductance,old:state.current||0,emf:held?0:p.backEmf*(p.inertia*(state.omega||0)-dt*load)/denominator});}
        else if(m.type==='stepper'){add(pin('a'),pin('b'),'motor',p.coilResistance,{id:n.id+':a',l:p.coilInductance,old:state.coilA||0,emf:0});add(pin('c'),pin('d'),'motor',p.coilResistance,{id:n.id+':b',l:p.coilInductance,old:state.coilB||0,emf:0});}
        else if(m.type==='unipolarStepper'){for(const [role,common] of [['a','common'],['b','common'],['c','common2'],['d','common2']])add(pin(common),pin(role),'motor',p.coilResistance,{id:n.id+':'+role,l:p.coilInductance,old:state.windings?.[role]||0,emf:0});}
        else if(['lcd','shift595','pcf8574','ssd1306','eeprom','hbridge','stepDriver','servo','continuousServo','escMotor'].includes(m.type))add(pin('power'),pin('b'),'r',1e6,{id:n.id+':supply'});
        else if(m.type==='sensor'&&pin('a')&&pin('power')&&pin('b'))add(pin('a'),pin('b'),'vcvs',inputs.get(n.id)??n.inputValue??.5,{control:rootKey(pin('power'))});
        if(n.simulation?.pin)add(k(n.id,n.simulation.pin),reference,'thevenin',(inputs.get(n.id)??n.inputValue??.5)*5,{r:100});
      }
      for(const [pin,value]of external)add(pin,typeof value==='object'&&value.ground?value.ground:reference,'thevenin',typeof value==='object'?value.voltage:value,{r:typeof value==='object'?value.resistance:25});
      active.delete(reference);const roots=[...active],index=new Map(roots.map((r,i)=>[r,i])),sources=branches.filter(b=>b.type==='v'||b.type==='vcvs'||!advancing&&b.type==='c');
      if(roots.length+sources.length>1024)throw Error('До 1024 електричних невідомих у розрахунку');
      const size=roots.length+sources.length,sparse=this.method==='sparse'||this.method==='auto'&&size>96,at=r=>r===reference?-1:index.get(r),voltage=(x,r)=>r===reference?0:x[index.get(r)]||0;
      let x=Float64Array.from([...roots.map(r=>this.previous.get(r)||0),...sources.map(()=>0)]),converged=false;const sparseBudget={operations:0,peakEntries:0};
      const diode=(v,is,vt)=>{const ex=Math.exp(clamp(v/vt,-40,40));return {i:is*(ex-1),g:is*ex/vt};};
      for(let iteration=0;iteration<160;iteration++){
        const a=Array.from({length:size},()=>sparse?new Map():new Float64Array(size)),b=new Float64Array(size),stamp=(row,col,value)=>{if(!value)return;if(sparse){const next=(a[row].get(col)||0)+value;if(Math.abs(next)<1e-30)a[row].delete(col);else a[row].set(col,next);}else a[row][col]+=value;};for(let i=0;i<roots.length;i++)stamp(i,i,1e-12);
        const flow=(ar,br,i,terms=[])=>{const ai=at(ar),bi=at(br);if(ai>=0)b[ai]-=i;if(bi>=0)b[bi]+=i;for(const [r,g]of terms){const j=at(r);if(j<0)continue;if(ai>=0)stamp(ai,j,g);if(bi>=0)stamp(bi,j,-g);}};
        const conduct=(q,g,i=0)=>flow(q.a,q.b,i,[[q.a,g],[q.b,-g]]);
        for(const q of branches){const v=voltage(x,q.a)-voltage(x,q.b);
          if(q.type==='r')conduct(q,1/q.value);
          else if(q.type==='thevenin')conduct(q,1/q.r,-q.value/q.r);
          else if(q.type==='c'){if(advancing)conduct(q,q.value/dt,-q.value/dt*q.old);}
          else if(q.type==='l')advancing?conduct(q,dt/q.value,q.old):flow(q.a,q.b,q.old);
          else if(q.type==='motor'){if(advancing){const g=dt/(q.l+dt*q.value);conduct(q,g,(q.old*q.l-dt*q.emf)/(q.l+dt*q.value));}else flow(q.a,q.b,q.old);}
          else if(q.type==='diode'||q.type==='reverse-diode'){const s=q.type==='reverse-diode'?-1:1,d=diode(s*v,q.value,q.vt);conduct(q,d.g,s*d.i-d.g*v);}
          else if(q.type==='bjt'&&q.base){const s=q.polarity,vbe=s*(voltage(x,q.base)-voltage(x,q.b)),vce=s*v,be=diode(vbe,q.is,.02585),sat=vce/Math.hypot(vce,.05),gb=q.value*be.g*sat,gc=q.value*Math.max(0,be.i)*.0025/Math.pow(vce*vce+.0025,1.5),ge=-gb-gc;
            const i=s*q.value*Math.max(0,be.i)*sat;flow(q.a,q.b,i-gb*voltage(x,q.base)-gc*voltage(x,q.a)-ge*voltage(x,q.b),[[q.base,gb],[q.a,gc],[q.b,ge]]);}
          else if(q.type==='mos'&&q.gate){const s=q.polarity,over=s*(voltage(x,q.gate)-voltage(x,q.b))-q.threshold,vd=Math.max(0,s*v);let i=0,gg=0,gd=0;
            if(over>0){if(vd<over){i=q.value*(over*vd-vd*vd/2);gg=q.value*vd;gd=q.value*(over-vd);}else{i=q.value*over*over/2;gg=q.value*over;gd=1e-9;}}const gs=-gg-gd;flow(q.a,q.b,s*i-gg*voltage(x,q.gate)-gd*voltage(x,q.a)-gs*voltage(x,q.b),[[q.gate,gg],[q.a,gd],[q.b,gs]]);}
        }
        for(let j=0;j<sources.length;j++){const q=sources[j],row=roots.length+j,ai=at(q.a),bi=at(q.b);if(ai>=0){stamp(ai,row,1);stamp(row,ai,1);}if(bi>=0){stamp(bi,row,-1);stamp(row,bi,-1);}
          if(q.type==='vcvs'){const ci=at(q.control);if(ci>=0)stamp(row,ci,-q.value);if(bi>=0)stamp(row,bi,q.value);}else b[row]=q.type==='c'?q.old:q.value;
        }
        const next=sparse?linearSparse(a,b,sparseBudget):linear(a,b);let delta=0;for(let i=0;i<size;i++){const d=next[i]-x[i];delta=Math.max(delta,Math.abs(d));x[i]+=i<roots.length?clamp(d,-.5,.5):d;}if(delta<1e-8){x=next;converged=true;break;}
      }
      if(!converged)throw Error('Схема не збіглася за 160 ітерацій; перевірте параметри та джерела');
      this.previous=new Map(roots.map((r,i)=>[r,x[i]]));this.previous.set(reference,0);
      const volts=new Map(),values=new Map(),currents=new Map(),states=new Map(),outputCurrents=new Map();
      for(const n of scene.nodes){const part=catalog.get(n.part),profile=M.board(n,part),g=n.kind==='board'?voltage(x,rootKey(boardGround.get(n.id))):0;for(const p of part.pins){const pin=k(n.id,p.id),v=voltage(x,rootKey(pin));volts.set(pin,v);values.set(pin,clamp((v-g)/(n.kind==='board'?profile.logicVoltage:5),0,1));}}
      for(const q of branches){const v=voltage(x,q.a)-voltage(x,q.b);let i=0;
        if(q.type==='r')i=v/q.value;else if(q.type==='thevenin')i=(v-q.value)/q.r;else if(q.type==='c')i=advancing?q.value/dt*(v-q.old):x[roots.length+sources.indexOf(q)];else if(q.type==='l')i=advancing?q.old+dt/q.value*v:q.old;else if(q.type==='motor')i=advancing?(q.old*q.l+dt*(v-q.emf))/(q.l+dt*q.value):q.old;else if(q.type==='diode')i=diode(v,q.value,q.vt).i;else if(q.type==='reverse-diode')i=-diode(-v,q.value,q.vt).i;else if(q.type==='bjt'&&q.base){const vbe=q.polarity*(voltage(x,q.base)-voltage(x,q.b)),vce=q.polarity*v;i=q.polarity*q.value*Math.max(0,diode(vbe,q.is,.02585).i)*vce/Math.hypot(vce,.05);}
        else if(q.type==='mos'&&q.gate){const over=q.polarity*(voltage(x,q.gate)-voltage(x,q.b))-q.threshold,vd=Math.max(0,q.polarity*v);if(over>0)i=q.polarity*q.value*(vd<over?over*vd-vd*vd/2:over*over/2);}
        else if(q.type==='v')i=x[roots.length+sources.indexOf(q)];
        if(q.id){currents.set(q.id,i);if(advancing&&(q.type==='c'||q.type==='l'||q.type==='motor'))this.memory.set(q.id,{...(this.memory.get(q.id)||{}),voltage:v,current:i});}if(q.output)outputCurrents.set(q.output,-i);
      }
      for(const n of scene.nodes){const m=nodeModels.get(n.id);if(!m)continue;const old=this.memory.get(n.id)||{},current=currents.get(n.id)||0;
        if(m.type==='motor'){if(!advancing){states.set(n.id,{...old,current,power:current*((volts.get(k(n.id,m.roles.a))||0)-(volts.get(k(n.id,m.roles.b))||0)),angle:old.angle||0,omega:old.omega||0,rpm:(old.omega||0)*60/(2*Math.PI),dissipation:current*current*m.params.armatureResistance});continue;}const p=m.params,torque=p.torqueConstant*current;let omega=(p.inertia*(old.omega||0)+dt*(torque-p.loadTorque*Math.sign(old.omega||current)))/(p.inertia+dt*p.friction);
          if(Math.abs(old.omega||0)<1e-6&&Math.abs(torque)<=p.loadTorque||Math.sign(omega)!==Math.sign(old.omega||torque)&&Math.sign(torque)===Math.sign(old.omega||torque))omega=0;
          const angle=(old.angle||0)+omega*dt*180/Math.PI,state={...old,omega,angle,rpm:omega*60/(2*Math.PI),current,torque,stalled:Math.abs(omega)<1e-6&&Math.abs(current)>1e-6,dissipation:current*current*p.armatureResistance};this.memory.set(n.id,state);states.set(n.id,state);}
        else if(['stepper','unipolarStepper'].includes(m.type)){
          if(!advancing){states.set(n.id,{...old,angle:old.angle||0,current:old.current||0,rpm:old.rpm||0});continue;}
          const unipolar=m.type==='unipolarStepper',windings=Object.fromEntries(['a','b','c','d'].map(role=>[role,currents.get(n.id+':'+role)||0])),ia=unipolar?windings.a-windings.b:currents.get(n.id+':a')||0,ib=unipolar?windings.c-windings.d:currents.get(n.id+':b')||0;
          const amplitude=Math.hypot(ia,ib);let phase=old.phase??Math.atan2(ib,ia),targetAngle=old.targetAngle||0,angle=old.angle||0;
          if(amplitude>.00001){const next=Math.atan2(ib,ia);let d=next-phase;if(d>Math.PI)d-=2*Math.PI;if(d< -Math.PI)d+=2*Math.PI;targetAngle+=d*180/Math.PI*4/m.params.stepsPerTurn;phase=next;}
          const availableTorque=m.params.holdingTorque*Math.min(1,amplitude/m.params.currentLimit),load=availableTorque?Math.min(1,m.params.loadTorque/availableTorque):1,delta=targetAngle-angle,maxMove=m.params.stepperMaxSpeed*dt*(1-load),movement=amplitude>.00001?clamp(delta,-maxMove,maxMove):0;angle+=movement;
          const state={coilA:ia,coilB:ib,windings,phase,angle,targetAngle,current:Math.max(Math.abs(ia),Math.abs(ib)),rpm:movement/dt/6,stalled:amplitude>.00001&&load>=1,positionError:targetAngle-angle,missedSteps:Math.floor(Math.abs(targetAngle-angle)/(360/m.params.stepsPerTurn)),dissipation:unipolar?Object.values(windings).reduce((sum,i)=>sum+i*i*m.params.coilResistance,0):(ia*ia+ib*ib)*m.params.coilResistance};this.memory.set(n.id,state);states.set(n.id,state);
        }
        else states.set(n.id,{...old,current,power:current*((volts.get(k(n.id,m.roles.a))||0)-(volts.get(k(n.id,m.roles.b))||0))});
      }
      this.last={values,volts,currents,states,outputCurrents,network,conflicts:[],converged,method:sparse?'sparse':'dense',unknowns:size,sparseWork:sparseBudget.operations,sparsePeakEntries:sparseBudget.peakEntries};this.last.ratingIssues=M.ratingIssues(scene,catalog,this.last);return this.last;
    }
  }
  const api={Solver,linear,linearSparse,rail,ground};if(typeof module!=='undefined')module.exports=api;root.WireElectronics=api;
})(typeof globalThis!=='undefined'?globalThis:this);
