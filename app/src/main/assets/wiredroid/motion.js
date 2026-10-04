(function(root){
  'use strict';
  const NS='http://www.w3.org/2000/svg', entries=new Map(),poses=new Map();let frame=0,lastTime=0;
  const element=(name,attrs)=>{const el=document.createElementNS(NS,name);for(const [k,v]of Object.entries(attrs))el.setAttribute(k,v);return el;};
  function draw(m){
    if(m.type==='potentiometer')m.rotor.setAttribute('transform',`rotate(${m.current},${m.cx},${m.cy})`);
    else m.rotor.setAttribute('transform',`rotate(${m.current-90},${m.cx},${m.cy})`);
    m.label.textContent=m.type==='potentiometer'?Math.round((m.current+135)/270*100)+'%':['motor','continuousServo','escMotor'].includes(m.type)?(m.rpm||0).toFixed(1)+' rpm':Math.round(m.current)+'° · '+(m.type==='servo'?(m.canPreview?'↻':'C++'):'C++');
    m.label.title=m.state||'';m.div.dataset.motionAngle=m.current.toFixed(2);m.div.dataset.motionActive=String(m.active);
    poses.set(m.id,m.current);
    if(m.touch){
      m.touch.setAttribute('aria-valuenow',Math.round(m.type==='servo'?m.target:(m.target+135)/270*1023));
      if(m.type==='servo'){
        m.touch.classList.toggle('program-controlled',!m.canPreview);
        m.touch.setAttribute('aria-disabled',String(!m.canPreview));
        m.touch.title=m.canPreview?'Перетягніть важіль по колу · перегляд 0–180°':'Кут задає C++ програма. Натисніть «Зупинити», щоб крутити вручну.';
      }
    }
    const inspector=document.querySelector('[data-motion-readout="'+m.id+'"]');if(inspector)inspector.textContent=Math.round(m.current)+'° · '+m.state;
  }
  function tick(time){
    const dt=lastTime?Math.min(64,time-lastTime):16;lastTime=time;let moving=false;
    const reduced=window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    for(const m of entries.values()){
      const delta=m.target-m.current,max=dt*(m.type==='potentiometer'?.72:.3);
      m.current=reduced||m.physics?m.target:['motor','stepper','unipolarStepper','continuousServo','escMotor'].includes(m.type)?m.current+delta*Math.min(1,dt/32):m.current+Math.sign(delta)*Math.min(Math.abs(delta),max);
      if(Math.abs(m.target-m.current)>.01)moving=true;else m.current=m.target;draw(m);
    }
    frame=moving?requestAnimationFrame(tick):0;if(!moving)lastTime=0;
  }
  function animate(){if(!frame)frame=requestAnimationFrame(tick);}
  function begin(){if(frame)cancelAnimationFrame(frame);frame=0;lastTime=0;entries.clear();}
  function attach(div,node,part,svg,onInput,onPreview,onBlocked){
    const model=WireModels.model(part,node);if(!['servo','potentiometer','motor','stepper','unipolarStepper','continuousServo','escMotor'].includes(model.type)||node.kind==='board')return;
    const vb=svg.viewBox.baseVal;let cx=vb.x+vb.width*.5,cy=vb.y+vb.height*.4,r=Math.min(vb.width,vb.height)*.15;
    let rotor,parent=svg;
    const basic=['servo','continuousServo'].includes(model.type)&&svg.querySelector('[id="path29"]')&&svg.querySelector('[id="ellipse39"]');
    if(basic){
      const pivot=svg.querySelector('[id="ellipse39"]');cx=Number(pivot.getAttribute('cx'));cy=Number(pivot.getAttribute('cy'));parent=pivot.parentNode.parentNode;
      rotor=element('g',{'class':'motion-rotor servo-arm'});
      const horn=svg.querySelector('[id="path29"]');horn.parentNode.insertBefore(rotor,horn);
      for(const id of ['path29','path31','g33']){const p=svg.querySelector('[id="'+id+'"]');if(p)rotor.append(p);}
      const box=rotor.getBBox();r=Math.max(...[box.x,box.x+box.width].flatMap(x=>[box.y,box.y+box.height].map(y=>Math.hypot(x-cx,y-cy))));
    }else {
      const circles=[...svg.querySelectorAll('circle,ellipse')].filter(p=>!/^connector/.test(p.id));
      circles.sort((a,b)=>(Number(b.getAttribute('r')||b.getAttribute('rx'))||0)-(Number(a.getAttribute('r')||a.getAttribute('rx'))||0));
      if(circles.length){const circle=circles[0];cx=Number(circle.getAttribute('cx'));cy=Number(circle.getAttribute('cy'));r=Number(circle.getAttribute('r')||circle.getAttribute('rx'));parent=circle.parentNode;}
      rotor=element('g',{'class':'motion-rotor '+(model.type==='servo'?'servo-arm':'pot-knob')});
      rotor.append(element('circle',{cx,cy,r,fill:'#25282e',stroke:'#a8b5c5','stroke-width':Math.max(1,r*.055)}));
      rotor.append(element('line',{x1:cx,y1:cy,x2:cx,y2:cy-r*.85,stroke:'#facc15','stroke-width':Math.max(2,r*.12),'stroke-linecap':'round'}));
      rotor.append(element('circle',{cx,cy,r:r*.12,fill:'#e5e7eb'}));parent.append(rotor);
    }
    svg.style.overflow='visible';
    const label=document.createElement('output');label.className='motion-label';div.append(label);
    const initial=model.type==='servo'?90:(node.inputValue??.5)*270-135;
    const m={id:node.id,type:model.type,div,rotor,cx,cy,current:poses.get(node.id)??initial,target:initial,active:false,canPreview:true,state:'Не запущено',label};entries.set(node.id,m);
    if(['servo','potentiometer'].includes(model.type)){
      const point=svg.createSVGPoint();point.x=cx;point.y=cy;const matrix=parent.getCTM(),local=point.matrixTransform(matrix),scale=Math.hypot(matrix.a,matrix.b);
      const servo=model.type==='servo',maximum=servo?180:1,span=servo?1:270,step=servo?5:.01;
      const touch=document.createElement('button');touch.className='dial-control'+(servo?' servo-control':'');touch.setAttribute('role','slider');touch.setAttribute('aria-label',servo?'Крутити важіль серво':'Крутити потенціометр');touch.setAttribute('aria-valuemin','0');touch.setAttribute('aria-valuemax',servo?'180':'1023');
      const diameter=servo?Math.max(42,2*r*scale):Math.max(24,Math.min(div.offsetWidth,2*r*scale));touch.style.width=touch.style.height=diameter+'px';touch.style.left=local.x+'px';touch.style.top=local.y+'px';touch.style.marginLeft=touch.style.marginTop=-diameter/2+'px';
      const blocked=()=>{if(servo&&!m.canPreview){onBlocked?.();return true;}return false;};
      const update=value=>servo?onPreview?.(node.id,value):onInput(node.id,value);
      touch.onclick=e=>{e.preventDefault();e.stopPropagation();};
      touch.onpointerdown=e=>{
        e.preventDefault();e.stopPropagation();if(e.isPrimary===false||e.button!==0||blocked())return;touch.setPointerCapture(e.pointerId);
        const rect=touch.getBoundingClientRect(),x=rect.left+rect.width/2,y=rect.top+rect.height/2;
        let previous=Math.atan2(e.clientY-y,e.clientX-x),value=servo?m.target:(m.target+135)/270;
        touch.onpointermove=ev=>{if(ev.pointerId!==e.pointerId)return;ev.preventDefault();ev.stopPropagation();if(servo&&!m.canPreview)return;const angle=Math.atan2(ev.clientY-y,ev.clientX-x);let delta=angle-previous;if(delta>Math.PI)delta-=Math.PI*2;if(delta< -Math.PI)delta+=Math.PI*2;previous=angle;value=Math.max(0,Math.min(maximum,value+delta*180/Math.PI/span));update(value);};
        touch.onpointerup=touch.onpointercancel=touch.onlostpointercapture=()=>{touch.onpointermove=null;};
      };
      touch.onkeydown=e=>{if(['ArrowLeft','ArrowDown','ArrowRight','ArrowUp','Home','End'].includes(e.key)){e.preventDefault();e.stopPropagation();if(blocked())return;let value=servo?m.target:(m.target+135)/270;if(e.key==='Home')value=0;else if(e.key==='End')value=maximum;else value+=(e.key==='ArrowLeft'||e.key==='ArrowDown'?-1:1)*step;update(Math.max(0,Math.min(maximum,value)));}};
      div.append(touch);m.touch=touch;
    }
    draw(m);animate();
  }
  function sync(scene,inputs,servos,running,motors=new Map()){
    for(const n of scene.nodes){const m=entries.get(n.id);if(!m)continue;m.physics=running;
      if(m.type==='potentiometer'){m.target=(inputs.get(n.id)??n.inputValue??.5)*270-135;m.active=running;m.state='Потенціометр';}
      else if(['motor','stepper','unipolarStepper'].includes(m.type)){const s=motors.get(n.id);m.active=running&&!!s;m.target=s?.angle??m.current;m.mechanicalTarget=s?.targetAngle??m.target;m.rpm=s?.rpm??0;m.state=running?(s?.stalled?'Мотор заблокований навантаженням':s?.missedSteps?'Відставання: '+s.missedSteps+' кроків':'C++ · електромеханічна модель'):'Зупинено';}
      else {m.canPreview=!running;if(running)m.preview=false;const s=servos.get(n.id);m.active=!!s?.active;m.state=s?.state||(running?'Немає сигналу Servo':'Не запущено');if(s?.active){m.target=s.angle;m.mechanicalTarget=s.targetAngle??m.target;m.rpm=s.rpm??0;}else if(!m.preview)m.target=m.current;}
      // The physics state is already integrated. Publish the same angle and
      // active flag together, including the first frame after a manual preview.
      if(m.physics)m.current=m.target;
      draw(m);
    }animate();
  }
  function stop(){for(const m of entries.values())if(m.type!=='potentiometer'){m.target=m.current;m.active=false;m.physics=false;m.canPreview=true;m.preview=false;m.state='Зупинено';draw(m);}}
  function preview(id,angle){const m=entries.get(id);if(!m||m.type!=='servo'||!m.canPreview||!Number.isFinite(angle))return false;m.preview=true;m.target=Math.max(0,Math.min(180,angle));m.state='Перегляд руху';animate();return true;}
  function states(){return Object.fromEntries([...entries].map(([id,m])=>[id,{type:m.type,angle:m.current,target:m.target,targetAngle:m.physics?(m.mechanicalTarget??m.target):m.target,rpm:m.rpm??0,active:m.active,state:m.state}]));}
  root.WireMotion={begin,attach,sync,stop,preview,states};
})(window);
