(function(root){'use strict';
 /** Workers run bounded, ordered quanta. AVR instruction timestamps are retained; inter-board inputs are sampled every 1 ms. */
 class Runtime {
  constructor(callbacks,options={}){this.callbacks=callbacks;this.sessions=new Map();this.generation=0;this.paused=false;this.timeUs=0;this.timer=null;this.requestSequence=0;this.timeoutMs=Math.max(10,Math.min(60000,options.timeoutMs||5000));}
  async load(id,options){
   if(this.sessions.has(id))throw Error('MCU вже запущений');
   const worker=new Worker('mcu-worker.js'),session={worker,state:null,wait:null};this.sessions.set(id,session);
   worker.onerror=e=>this.fail(id,Error(e.message||'Помилка worker MCU'));
   worker.onmessage=({data})=>{
    if(!this.sessions.has(id))return;
    if(data.type==='error'){this.fail(id,Error(data.text));return;}
    if(data.state){session.state=data.state;this.callbacks.state?.(id,data.state);}
    if(data.events)for(const event of data.events){
     if(event.type==='bus'){try{const reply=this.callbacks.bus(id,event);worker.postMessage({type:'bus-reply',token:event.token,reply});}catch(error){this.fail(id,error);return;}}
     else this.callbacks.event(id,event);
    }
    if(['loaded','state','complete'].includes(data.type)&&session.wait&&data.requestId===session.wait.id){const wait=session.wait;session.wait=null;clearTimeout(wait.timeout);wait.resolve(session.state);}
   };
   return this.request(session,{type:'load',options});
  }
  request(session,message){
   if(session.wait)return Promise.reject(Error('Попередній крок MCU ще виконується'));
   const id=++this.requestSequence;let pending;
   const promise=new Promise((resolve,reject)=>{
    const timeout=setTimeout(()=>{if(session.wait?.id===id)session.wait=null;reject(Error('MCU не відповідає'));},this.timeoutMs);
    pending={id,resolve,reject,timeout,promise:null};session.wait=pending;
    try{session.worker.postMessage({...message,requestId:id});}catch(error){clearTimeout(timeout);session.wait=null;reject(error);}
   });pending.promise=promise;return promise;
  }
  fail(id,error){const s=this.sessions.get(id);if(s?.wait){clearTimeout(s.wait.timeout);s.wait.reject(error);s.wait=null;}this.pause();this.callbacks.error(id,error);}
  inputs(id,volts){this.sessions.get(id)?.worker.postMessage({type:'inputs',volts});}
  serial(id,bytes){this.sessions.get(id)?.worker.postMessage({type:'serial',bytes});}
  breakpoints(id,addresses){this.sessions.get(id)?.worker.postMessage({type:'breakpoints',addresses});}
  run(){if(!this.sessions.size)return;this.paused=false;const generation=++this.generation;const tick=async()=>{
   try{for(const [id,s]of this.sessions){if(this.paused||generation!==this.generation)return;if(s.wait)await s.wait.promise;if(this.paused||generation!==this.generation)return;this.callbacks.inputs?.(id);await this.request(s,{type:'advance',cycles:Math.round(s.state.clockHz/1000)});if(this.paused||generation!==this.generation)return;if(s.state.paused){this.pause();return;}}
    this.timeUs=Math.min(...[...this.sessions.values()].map(s=>s.state.timeUs));this.callbacks.quantum?.(this.timeUs);
    if(!this.paused&&generation===this.generation)this.timer=setTimeout(tick,0);
   }catch(error){this.pause();this.callbacks.error('',error);}
  };this.timer=setTimeout(tick,0);}
  pause(){this.paused=true;++this.generation;clearTimeout(this.timer);for(const s of this.sessions.values())s.worker.postMessage({type:'pause'});}
  async step(id,count=1){this.pause();const s=this.sessions.get(id);if(!s)throw Error('Оберіть запущений MCU');if(s.wait)await s.wait.promise;const state=await this.request(s,{type:'step',count});this.timeUs=state.timeUs;this.callbacks.quantum?.(this.timeUs);return state;}
  async reset(id){this.pause();const s=this.sessions.get(id);if(!s)throw Error('Оберіть запущений MCU');if(s.wait)await s.wait.promise;const state=await this.request(s,{type:'reset'});this.timeUs=state.timeUs;this.callbacks.quantum?.(this.timeUs);return state;}
  stop(){this.pause();for(const s of this.sessions.values()){s.worker.terminate();if(s.wait){clearTimeout(s.wait.timeout);s.wait.reject(Error('MCU зупинено'));}}this.sessions.clear();this.timeUs=0;}
 }
 root.WireAVRRuntime={Runtime};
})(typeof globalThis!=='undefined'?globalThis:this);
