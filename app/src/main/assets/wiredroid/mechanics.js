(function(root){
  'use strict';
  const E=root.WireEngine||(typeof require==='function'?require('./engine.js'):null),M=root.WireModels||(typeof require==='function'?require('./models.js'):null);
  const clamp=(v,lo,hi)=>Math.max(lo,Math.min(hi,v));
  // Parameterized actuator response; this does not claim a calibrated servo motor model.
  class Servos{
    constructor(){this.memory=new Map();}
    reset(){this.memory.clear();}
    update(scene,catalog,result,pulses=new Map(),dt=.01){
      dt=clamp(Number.isFinite(dt)?dt:0,0,.1);const states=new Map(),network=result.network;
      for(const n of scene.nodes){if(n.kind==='board')continue;const m=M.model(catalog.get(n.part),n);if(!['servo','continuousServo','escMotor'].includes(m.type))continue;
        const k=role=>m.roles[role]?E.key(n.id,m.roles[role]):null,p=m.params,old=this.memory.get(n.id)||{angle:m.type==='escMotor'?0:(p.servoMinAngle+p.servoMaxAngle)/2,rpm:0},signal=k('a'),g=k('b'),power=k('power');
        const voltage=power&&g?(result.volts.get(power)||0)-(result.volts.get(g)||0):0;
        const list=[...pulses].filter(([pin,command])=>signal&&network.find(pin)===network.find(signal)&&Number.isFinite(command.pulse));
        const source=list[0]?.[0]?.split(':')[0],sourceBoard=scene.nodes.find(b=>b.id===source&&b.kind==='board');
        const grounded=!!sourceBoard&&!!g&&catalog.get(sourceBoard.part).pins.some(pin=>/^(gnd|ground|g|vss\d*|vss_\d+|vssa|-)$/i.test(pin.name.trim())&&network.find(E.key(source,pin.id))===network.find(g));
        const powered=!!power&&!!g&&voltage>=(m.type==='escMotor'?p.escMinSupply:p.servoMinSupply)&&voltage<=(m.type==='escMotor'?p.escMaxSupply:p.servoMaxSupply);
        const command=list[0]?.[1],validPulse=!!command&&command.pulse>=100&&command.pulse<=5000,active=powered&&grounded&&list.length===1&&validPulse;
        const load=clamp(p.loadTorque/p.stallTorque,0,1),stalled=active&&load>=1;
        let angle=old.angle,rpm=0,targetAngle=old.targetAngle??angle,state=!powered?'Живлення поза межами моделі':!grounded?'Немає спільного GND із джерелом Servo':!list.length?'Немає сигналу Servo':list.length>1?'Конфлікт сигналів Servo':!validPulse?'Некоректний імпульс Servo':stalled?'Серво заблоковане навантаженням':'Servo · працює';
        let armed=old.armed||false,armElapsed=old.armElapsed||0,outputActive=active;
        if(m.type==='escMotor'){
          const valid=active&&command.pulse>=p.escMinPulse-p.deadband&&command.pulse<=p.escMaxPulse+p.deadband;
          if(!valid){armed=false;armElapsed=0;outputActive=false;state=!powered?'Немає живлення ESC':!grounded?'Немає спільного GND ESC':!list.length?'Немає сигналу ESC':'Імпульс ESC поза профілем';}
          else{
            if(!armed){armElapsed=Math.abs(command.pulse-p.escArmPulse)<=p.deadband?armElapsed+dt:0;armed=armElapsed+1e-9>=p.escArmingSeconds;}
            outputActive=armed;
            if(!armed)state='ESC · тримайте імпульс активації '+p.escArmPulse+' µs';
            else {let throttle;if(p.escReversible){const d=command.pulse-p.neutralPulse,span=d>=0?p.escMaxPulse-p.neutralPulse:p.neutralPulse-p.escMinPulse;throttle=Math.abs(d)<=p.deadband?0:Math.sign(d)*clamp((Math.abs(d)-p.deadband)/Math.max(1,span-p.deadband),0,1);}else throttle=clamp((command.pulse-p.escMinPulse)/(p.escMaxPulse-p.escMinPulse),0,1);
              const targetRpm=throttle*p.maxRpm*(1-load);rpm=stalled?0:old.rpm+clamp(targetRpm-old.rpm,-p.servoAcceleration*dt,p.servoAcceleration*dt);angle+=rpm*6*dt;targetAngle=angle;state=stalled?'ESC · мотор заблокований навантаженням':rpm?'ESC · усереднена швидкість':'ESC · активовано, мотор стоїть';
            }
          }
        }else if(active&&m.type==='continuousServo'){
          const deviation=command.pulse-p.neutralPulse,span=deviation>=0?Math.max(1,p.servoMaxPulse-p.neutralPulse):Math.max(1,p.neutralPulse-p.servoMinPulse);
          const normalized=Math.abs(deviation)<=p.deadband?0:Math.sign(deviation)*clamp((Math.abs(deviation)-p.deadband)/Math.max(1,span-p.deadband),0,1),targetRpm=normalized*p.maxRpm*(1-load);
          rpm=stalled?0:old.rpm+clamp(targetRpm-old.rpm,-p.servoAcceleration*dt,p.servoAcceleration*dt);angle+=rpm*6*dt;targetAngle=angle;if(!targetRpm&&!rpm)state='Servo · нейтраль';
        }else if(active){
          // Native Servo.write supplies its logical angle; customized pulse endpoints opt into a physical pulse profile.
          const custom=n.electrical?.parameters,fromPulse=p.servoMinAngle+(command.pulse-p.servoMinPulse)/(p.servoMaxPulse-p.servoMinPulse)*(p.servoMaxAngle-p.servoMinAngle);
          targetAngle=clamp(custom?.servoMinPulse!==undefined||custom?.servoMaxPulse!==undefined||!Number.isFinite(command.angle)?fromPulse:command.angle,p.servoMinAngle,p.servoMaxAngle);
          const movement=clamp(targetAngle-angle,-p.servoSpeed*(1-load)*dt,p.servoSpeed*(1-load)*dt);angle+=movement;rpm=dt>0?movement/dt/6:0;if(Math.abs(targetAngle-angle)<1e-6)state='Servo · позицію утримано';
        }
        const s={type:m.type,active:outputActive,armed,armElapsed,powered,grounded,stalled,voltage,angle,targetAngle,rpm,pulse:command?.pulse??null,loadTorque:p.loadTorque,state};this.memory.set(n.id,s);states.set(n.id,s);
      }
      return states;
    }
  }
  const api={Servos};if(typeof module!=='undefined')module.exports=api;root.WireMechanics=api;
})(typeof globalThis!=='undefined'?globalThis:this);
