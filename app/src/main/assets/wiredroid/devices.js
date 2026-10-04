(function(root){
  'use strict';
  const E=root.WireEngine||(typeof require==='function'?require('./engine.js'):null),M=root.WireModels||(typeof require==='function'?require('./models.js'):null),key=E.key;
  const clamp=(v,a,b)=>Math.max(a,Math.min(b,v));
  class Devices{
    constructor(){this.states=new Map();this.drives=new Map();this.time=0;this.transitions=[];}
    reset(){this.states.clear();this.drives.clear();this.time=0;this.transitions=[];}
    state(n,m){
      if(!this.states.has(n.id)||this.states.get(n.id).type!==m.type)this.states.set(n.id,{type:m.type,data:new Uint8Array(m.type==='ssd1306'?1024:m.type==='lcd'?128:4096).fill(m.type==='eeprom'?255:m.type==='lcd'?32:0),cgram:new Uint8Array(64),address:0,shift:0,latched:m.type==='pcf8574'?255:0,clock:false,enable:false,fourBit:false,half:null,display:!['ssd1306','lcd'].includes(m.type),increment:true,displayShift:0,column:0,page:0,startColumn:0,endColumn:127,startPage:0,endPage:7,addressMode:2,phase:Math.PI/4,wakeAt:0,sleeping:true,pending:null});
      const s=this.states.get(n.id);s.ground=m.roles.b;s.supply=m.type==='darlington'?null:m.roles.logic||m.roles.power;return s;
    }
    voltage(n,p,result){return p?(result.volts.get(key(n.id,p))||0)-(result.volts.get(key(n.id,this.states.get(n.id)?.ground))||0):0;}
    powered(n,m,result){
      const p=m.roles.power,g=m.roles.b;if(!p||!g)return false;
      return (result.network.adjacency.get(key(n.id,p))?.length||0)>0&&(result.network.adjacency.get(key(n.id,g))?.length||0)>0&&(result.volts.get(key(n.id,p))||0)-(result.volts.get(key(n.id,g))||0)>2;
    }
    level(n,p,result){const s=this.states.get(n.id),supply=this.voltage(n,s?.supply,result);return !!p&&this.voltage(n,p,result)>Math.max(s?.type==='darlington'?1.4:.1,supply*(s?.type==='stepDriver'?.7:.5));}
    lcdByte(s,rs,value){
      if(rs){const memory=s.cgramSelected?s.cgram:s.data,mask=s.cgramSelected?63:127;memory[s.address&mask]=s.cgramSelected?value&31:value;s.address=(s.address+(s.increment?1:127))&mask;if(!s.cgramSelected&&s.entryShift)s.displayShift=(s.displayShift+(s.increment?1:39))%40;return;}
      if(value&128){s.address=value&127;s.cgramSelected=false;}
      else if(value&64){s.address=value&63;s.cgramSelected=true;}
      else if(value===1){s.data.fill(32);s.address=0;s.displayShift=0;s.increment=true;s.cgramSelected=false;}
      else if(value===2){s.address=0;s.displayShift=0;s.cgramSelected=false;}
      else if((value&248)===8){s.display=!!(value&4);s.cursor=!!(value&2);s.blink=!!(value&1);}
      else if((value&252)===4){s.increment=!!(value&2);s.entryShift=!!(value&1);}
      else if((value&240)===16){if(value&8)s.displayShift=(s.displayShift+(value&4?39:1))%40;else s.address=(s.address+(value&4?1:127))&127;}
      else if((value&224)===32){s.fourBit=!(value&16);s.half=null;}
    }
    lcdNibble(s,rs,value){if(!s.fourBit){if(!rs&&value===2){s.fourBit=true;s.half=null;}else this.lcdByte(s,rs,value<<4);return;}if(s.half===null){s.half=value;s.rs=rs;}else{const byte=(s.half<<4)|value;s.half=null;this.lcdByte(s,s.rs,byte);}}
    oledCommand(s,command){
      if(s.pending){s.pending.args.push(command);if(s.pending.args.length<s.pending.count)return;const {command:c,args:a}=s.pending;s.pending=null;
        if(c===0x20)s.addressMode=[0,1,2].includes(a[0])?a[0]:2;
        else if(c===0x21){s.startColumn=clamp(a[0],0,127);s.endColumn=clamp(a[1],s.startColumn,127);s.column=s.startColumn;}
        else if(c===0x22){s.startPage=clamp(a[0],0,7);s.endPage=clamp(a[1],s.startPage,7);s.page=s.startPage;}
        else if(c===0xd3)s.displayOffset=a[0]&63;else if(c===0x81)s.contrast=a[0];return;
      }
      const counts={32:1,33:2,34:2,129:1,141:1,168:1,211:1,213:1,217:1,218:1,219:1,38:6,39:6,41:5,42:5,163:2};
      if(counts[command]){s.pending={command,count:counts[command],args:[]};return;}
      if(command>=0xb0&&command<=0xb7)s.page=command&7;
      else if(command<=15)s.column=((s.column||0)&112)|(command&15);
      else if(command>=16&&command<=31)s.column=((s.column||0)&15)|((command&7)<<4);
      else if(command===0xae)s.display=false;else if(command===0xaf)s.display=true;
      else if(command===0xa6)s.inverted=false;else if(command===0xa7)s.inverted=true;
      else if(command===0xa4)s.allOn=false;else if(command===0xa5)s.allOn=true;
      else if(command===0xa0)s.segmentRemap=false;else if(command===0xa1)s.segmentRemap=true;
      else if(command===0xc0)s.comReverse=false;else if(command===0xc8)s.comReverse=true;
      else if(command>=0x40&&command<=0x7f)s.startLine=command&63;
    }
    oledData(s,byte){
      s.data[(s.page&7)*128+(s.column&127)]=byte;
      if(s.addressMode===1){if(++s.page>s.endPage){s.page=s.startPage;if(++s.column>s.endColumn)s.column=s.startColumn;}}
      else {if(++s.column>(s.addressMode===2?127:s.endColumn)){s.column=s.addressMode===2?0:s.startColumn;if(s.addressMode===0&&++s.page>s.endPage)s.page=s.startPage;}}
      s.address=s.page*128+s.column;
    }
    resetVolatile(s){
      s.clock=false;s.enable=false;s.half=null;s.phase=Math.PI/4;s.steps=0;s.wakeAt=0;s.sleeping=true;
      if(s.type==='pcf8574')s.latched=255;
      if(s.type==='shift595'){s.shift=0;s.latched=0;}
      if(s.type==='lcd'){s.data.fill(32);s.cgram.fill(0);s.address=0;s.fourBit=false;s.display=false;s.increment=true;s.displayShift=0;s.cgramSelected=false;}
      if(s.type==='ssd1306'){s.display=false;s.addressMode=2;s.column=0;s.page=0;s.pending=null;s.inverted=false;s.allOn=false;}
    }
    step(scene,catalog,result,outputs=new Map(),dt=.001,inputs=new Map(),mechanicalStates=result.states){
      dt=clamp(Number.isFinite(dt)?dt:0,0,.1);const previousTime=this.time;this.time+=dt;this.transitions=[];const drives=new Map(),duty=(n,p)=>{if(!p)return 0;const net=result.network.find(key(n.id,p));for(const [pin,value]of outputs)if(result.network.find(pin)===net)return value;return this.level(n,p,result)?1:0;};
      for(const n of scene.nodes){if(n.kind==='board')continue;const part=catalog.get(n.part),m=M.model(part,n);if(!['lcd','shift595','pcf8574','ssd1306','eeprom','hbridge','darlington','stepDriver','encoder'].includes(m.type))continue;
        const s=this.state(n,m);
        if(m.type==='encoder'){
          const source=n.electrical?.mechanicalSource,sourceState=source?mechanicalStates?.get(source):null;
          s.missingSource=!!source&&!Number.isFinite(sourceState?.angle);s.powered=!!m.roles.b&&(result.network.adjacency.get(key(n.id,m.roles.b))?.length||0)>0;
          const manual=(inputs.get(n.id)??n.inputValue??0)*360,angle=sourceState?.angle??(source? s.encoderAngle||0 :m.params.encoderRpm?(s.encoderAngle||0)+m.params.encoderRpm*6*dt:manual),count=Math.floor(angle/360*m.params.pulsesPerTurn*4),oldCount=s.phaseCount??(m.params.encoderRpm?0:count),delta=count-oldCount;
          if(Math.abs(delta)>4096)throw Error('Енкодер: понад 4096 переходів за один крок; зменшіть крок часу або швидкість');
          const sequence=[0,1,3,2],bits=step=>sequence[(step%4+4)%4];let previous=bits(oldCount);
          for(let step=1;step<=Math.abs(delta);step++){const next=bits(oldCount+Math.sign(delta)*step),time=(previousTime+dt*step/Math.abs(delta))*1e6;for(const [role,mask]of [['a',1],['channelB',2]])if(s.powered&&(previous&mask)!==(next&mask)&&m.roles[role])this.transitions.push({node:n.id,pin:m.roles[role],value:next&mask?1:0,time});previous=next;}
          s.encoderAngle=angle;s.phaseCount=count;s.phaseBits=bits(count);s.powered=!!m.roles.b&&(result.network.adjacency.get(key(n.id,m.roles.b))?.length||0)>0;
          if(s.powered)for(const [role,mask]of [['a',1],['channelB',2]])if(m.roles[role]&&!(s.phaseBits&mask))drives.set(key(n.id,m.roles[role]),{voltage:0,resistance:25,ground:key(n.id,m.roles.b)});
          continue;
        }
        const wasPowered=s.powered;s.powered=m.type==='darlington'?!!m.roles.b&&(result.network.adjacency.get(key(n.id,m.roles.b))?.length||0)>0:this.powered(n,m,result);if(!s.powered){if(wasPowered)this.resetVolatile(s);continue;}
        const named=name=>part.pins.find(p=>p.name.toUpperCase()===name)?.id,g=key(n.id,m.roles.b),supply=this.voltage(n,m.roles.power,result),drive=(pin,v,resistance=m.params.driverResistance)=>{if(pin)drives.set(key(n.id,pin),{voltage:v,resistance,ground:g});},roleDrive=(role,v,r)=>drive(m.roles[role],v,r);
        if(['hbridge','darlington','stepDriver'].includes(m.type)){
          if(m.type==='hbridge'){if(!this.level(n,m.roles.logic,result))continue;for(let i=1;i<=4;i++)if(duty(n,m.roles['enable'+Math.ceil(i/2)])>.01){const d=duty(n,m.roles['in'+i])*duty(n,m.roles['enable'+Math.ceil(i/2)]);roleDrive('out'+i,Math.max(0,supply-m.params.driverDrop)*d);}}
          else if(m.type==='darlington'){for(let i=1;i<=8;i++)if(duty(n,m.roles['in'+i])>.5)roleDrive('out'+i,m.params.driverDrop);}
          else{
            const logic=this.voltage(n,m.roles.logic,result),reset=this.level(n,m.roles.reset,result),awake=this.level(n,m.roles.sleep,result),step=this.level(n,m.roles.step,result);
            s.powered=supply>=8&&logic>=3&&logic<=5.5;if(!s.powered){this.resetVolatile(s);continue;}
            if(!reset||!awake){s.phase=Math.PI/4;s.steps=0;s.sleeping=!awake;s.clock=step;s.wakeAt=this.time+.001;continue;}
            if(s.sleeping){s.sleeping=false;s.wakeAt=this.time+.001;}
            const bits=['ms1','ms2','ms3'].reduce((v,role,i)=>v|(this.level(n,m.roles[role],result)?1<<i:0),0),resolution=({0:1,1:2,2:4,3:8,7:16})[bits];s.resolution=resolution||null;s.invalidMode=!resolution;if(!resolution){s.enabled=false;s.clock=step;continue;}
            // ENABLE affects the output bridges, while the translator continues processing STEP.
            if(step&&!s.clock&&this.time>=s.wakeAt){const direction=this.level(n,m.roles.dir,result)?1:-1;s.phase+=direction*Math.PI/(2*s.resolution);s.steps=(s.steps||0)+direction/s.resolution;}
            s.clock=step;s.enabled=!this.level(n,m.roles.enable,result)&&this.time>=s.wakeAt;if(!s.enabled)continue;
            const ref=m.roles.reference?this.voltage(n,m.roles.reference,result):m.params.referenceVoltage;s.currentLimit=Math.min(m.params.currentLimit,Math.max(0,ref/(8*m.params.senseResistance)));
            const phases=[Math.cos(s.phase),Math.sin(s.phase)];s.targetCurrents=phases.map(v=>v*s.currentLimit);
            [['out1a','out1b'],['out2a','out2b']].forEach(([a,b],i)=>{
              const ar=m.roles[a]?result.network.find(key(n.id,m.roles[a])):null,br=m.roles[b]?result.network.find(key(n.id,m.roles[b])):null;
              const motor=scene.nodes.find(other=>{const model=M.model(catalog.get(other.part),other);return model.type==='stepper'&&[['a','b'],['c','d']].some(([x,y])=>{const xnet=result.network.find(key(other.id,model.roles[x])),ynet=result.network.find(key(other.id,model.roles[y]));return xnet===ar&&ynet===br||xnet===br&&ynet===ar;});});
              // The averaged regulator uses known winding resistance; chopping ripple and thermal protection are outside this model.
              const resistance=motor?M.model(catalog.get(motor.part),motor).params.coilResistance:20,voltage=clamp(s.targetCurrents[i]*(resistance+2*m.params.driverResistance),-supply,supply);
              roleDrive(a,(supply+voltage)/2);roleDrive(b,(supply-voltage)/2);
            });
          }
        }else if(m.type==='lcd'){
          const enable=this.level(n,m.roles.clock,result),read=this.level(n,m.roles.rw,result),rs=this.level(n,m.roles.a,result);
          if(enable&&read){const byte=rs?(s.cgramSelected?s.cgram:s.data)[s.address&(s.cgramSelected?63:127)]:s.address&127;for(let i=s.fourBit?4:0;i<8;i++)drive(named('D'+i)||named('DB'+i),((s.fourBit&&s.readHalf?byte<<4:byte)>>i&1)*supply);}
          if(s.enable&&!enable){if(read){if(s.fourBit){s.readHalf=!s.readHalf;if(!s.readHalf&&rs)s.address=(s.address+(s.increment?1:127))&(s.cgramSelected?63:127);}else if(rs)s.address=(s.address+(s.increment?1:127))&(s.cgramSelected?63:127);}
            else {let value=0;for(let i=0;i<8;i++)if(this.level(n,named('D'+i)||named('DB'+i),result))value|=1<<i;
              const hasLow=[0,1,2,3].some(i=>{const pin=named('D'+i)||named('DB'+i);return pin&&(result.network.adjacency.get(key(n.id,pin))?.length||0)>0;});
              if(s.fourBit||!hasLow)this.lcdNibble(s,rs,value>>4);else this.lcdByte(s,rs,value);
            }
          }s.enable=enable;
        }else if(m.type==='shift595'){
          const clock=this.level(n,m.roles.clock,result),latch=this.level(n,m.roles.latch,result),oldShift=s.shift;
          if(m.roles.clear&&!this.level(n,m.roles.clear,result))s.shift=0;
          else if(clock&&!s.clock)s.shift=((s.shift<<1)|(this.level(n,m.roles.a,result)?1:0))&255;
          if(latch&&!s.latch)s.latched=m.roles.clear&&!this.level(n,m.roles.clear,result)?0:clock&&!s.clock?oldShift:s.shift;s.clock=clock;s.latch=latch;
          if(!this.level(n,m.roles.enable,result))for(let i=0;i<8;i++)drive(named('Q'+i)||named('QA QB QC QD QE QF QG QH'.split(' ')[i]),(s.latched>>i&1)*supply,25);
          drive(named("Q7'")||named('QH\'')||named('SERIAL_OUT'),(s.shift>>7&1)*supply,25);
        }else if(m.type==='pcf8574')for(let i=0;i<8;i++)drive(named('P'+i),(s.latched>>i&1)*supply,(s.latched>>i&1)?50000:25);
      }
      const changed=JSON.stringify([...drives])!==JSON.stringify([...this.drives]);this.drives=drives;return changed;
    }
    address(n,m,part,result){if(m.type!=='pcf8574'||n.electrical?.parameters?.address!==undefined)return m.params.address;this.state(n,m);let address=m.params.address;for(let i=0;i<3;i++){const pin=part.pins.find(p=>p.name.toUpperCase()==='A'+i);if(pin&&this.level(n,pin.id,result))address|=1<<i;}return address;}
    bus(board,event,scene,catalog,result){
      const network=result.network,connected=(a,b)=>a&&b&&network.find(a)===network.find(b);if(event.data&&!/^(?:[0-9a-f]{2})*$/i.test(event.data))throw Error('Некоректні дані шини');const bytes=event.data?event.data.match(/../g).map(x=>parseInt(x,16)):[];
      if(!Number.isInteger(event.count)||event.count<0||event.count>4096)throw Error('Некоректна довжина транзакції');
      if(event.bus==='i2c'){
        if(!Array.isArray(event.pins)||event.pins.length!==2||event.pins.some(p=>!p))throw Error('Задайте контакти SDA/SCL у Wire.begin');
        const found=scene.nodes.filter(n=>n.kind!=='board').filter(n=>{const m=M.model(catalog.get(n.part),n);return ['eeprom','pcf8574','ssd1306'].includes(m.type)&&this.address(n,m,catalog.get(n.part),result)===event.address&&this.powered(n,m,result)&&connected(key(board.id,event.pins[0]),key(n.id,m.roles.a))&&connected(key(board.id,event.pins[1]),key(n.id,m.roles.clock));});
        if(found.length>1)throw Error('Два пристрої I²C мають однакову адресу');if(!found.length)return event.count?[]:[2];
        const n=found[0],m=M.model(catalog.get(n.part),n),s=this.state(n,m);s.powered=true;
        if(m.type==='eeprom'){if(bytes.length>=2){s.address=((bytes[0]<<8)|bytes[1])%s.data.length;for(const byte of bytes.slice(2))s.data[s.address++%s.data.length]=byte;}return event.count?Array.from({length:event.count},()=>s.data[s.address++%s.data.length]):[0];}
        if(m.type==='pcf8574'){
          if(bytes.length)s.latched=bytes.at(-1);if(!event.count)return [0];let value=0;for(let i=0;i<8;i++){const p=catalog.get(n.part).pins.find(p=>p.name.toUpperCase()==='P'+i);if(p&&((s.latched>>i&1)&&this.level(n,p.id,result)))value|=1<<i;}return Array(event.count).fill(value);
        }
        for(let i=0;i<bytes.length;){const control=bytes[i++],end=control&128?Math.min(i+1,bytes.length):bytes.length;for(;i<end;i++)if(control&64)this.oledData(s,bytes[i]);else this.oledCommand(s,bytes[i]);}
        return event.count?Array(event.count).fill(0):[0];
      }
      if(event.bus==='spi'){
        if(!Array.isArray(event.pins)||event.pins.length<2||!event.pins[0]||!event.pins[1])throw Error('Задайте SCK/MOSI для SPI');
        const found=scene.nodes.filter(n=>{const m=M.model(catalog.get(n.part),n);return m.type==='shift595'&&this.powered(n,m,result)&&connected(key(board.id,event.pins[0]),key(n.id,m.roles.clock))&&connected(key(board.id,event.pins[1]),key(n.id,m.roles.a));});
        if(found.length){for(const n of found){const m=M.model(catalog.get(n.part),n),s=this.state(n,m);for(let byte of bytes){if(m.roles.clear&&!this.level(n,m.roles.clear,result)){s.shift=0;continue;}if(event.address&4){let reversed=0;for(let i=0;i<8;i++)reversed=(reversed<<1)|((byte>>i)&1);byte=reversed;}s.shift=byte;}}return Array(event.count).fill(0);}
        throw Error('Для під’єднаного пристрою SPI ще немає моделі');
      }
      throw Error('Непідтримувана шина '+event.bus);
    }
    snapshot(scene,catalog){
      const out={};for(const n of scene.nodes){const s=this.states.get(n.id);if(!s)continue;const m=M.model(catalog.get(n.part),n);out[n.id]={type:s.type,powered:!!s.powered,latched:s.latched,display:s.display};
        if(s.type==='encoder')Object.assign(out[n.id],{angle:s.encoderAngle,phaseCount:s.phaseCount,channelA:!!(s.phaseBits&1),channelB:!!(s.phaseBits&2),missingSource:s.missingSource});
        if(s.type==='stepDriver')Object.assign(out[n.id],{enabled:s.enabled,resolution:s.resolution,steps:s.steps||0,targetCurrents:s.targetCurrents,currentLimit:s.currentLimit,invalidMode:s.invalidMode});
        if(s.type==='lcd'){out[n.id].text=Array.from({length:m.params.rows},(_,row)=>Array.from({length:m.params.cols},(_,x)=>{const offset=[0,64,20,84][row],base=offset>=64?64:0,col=(offset-base+x+s.displayShift)%40,value=s.data[base+col];return value>=32?String.fromCharCode(value):' ';}).join(''));Object.assign(out[n.id],{address:s.address,cursor:s.cursor,blink:s.blink,customCharacters:Array.from(s.cgram)});}
        if(s.type==='ssd1306'){const pixels=new Uint8Array(1024);for(let y=0;y<64;y++)for(let x=0;x<128;x++){const sourceX=s.segmentRemap?127-x:x,sourceY=((s.comReverse?63-y:y)+(s.startLine||0)+(s.displayOffset||0))%64,bit=s.allOn?1:(s.data[(sourceY>>3)*128+sourceX]>>(sourceY&7)&1)^(s.inverted?1:0);pixels[(y>>3)*128+x]|=bit<<(y&7);}out[n.id].pixels=Array.from(pixels);}
      }return out;
    }
  }
  const api={Devices};if(typeof module!=='undefined')module.exports=api;root.WireDevices=api;
})(typeof globalThis!=='undefined'?globalThis:this);
