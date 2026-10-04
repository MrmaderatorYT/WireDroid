const fs=require('node:fs'),path=require('node:path'),base=path.resolve(__dirname,'../..'),assets=base+'/app/src/main/assets/wiredroid',catalog=new Map(JSON.parse(fs.readFileSync(assets+'/catalog.json')).map(p=>[p.id,p])),E=require(assets+'/engine.js'),M=require(assets+'/models.js');
const ids=['a4988-microsteps','unipolar-stepper','esc-bldc','quadrature-encoder'],index=JSON.parse(fs.readFileSync(assets+'/examples/index.json')).filter(x=>!ids.includes(x.id));
function make(id,title,description){const scene={version:1,name:title,nodes:[],wires:[]};
 const node=(id,part,x,y,label,parameters)=>scene.nodes.push({id,part,x,y,rotation:0,label:label||catalog.get(part).title,scale:id==='uno'?.8:1,...parameters&&{electrical:{type:M.model(catalog.get(part)).type,parameters}}});
 const pin=(n,name)=>{const part=catalog.get(scene.nodes.find(x=>x.id===n).part),p=part.pins.find(p=>p.id===name||p.name===name)||part.pins.find(p=>p.name.toUpperCase().split(/[ /]/)[0]===name.toUpperCase());if(!p)throw Error(n+': '+name);return p.id;};
 const wire=(a,ap,b,bp,color='#60a5fa')=>scene.wires.push({id:'wire_'+(scene.wires.length+1),a:{node:a,pin:pin(a,ap)},b:{node:b,pin:pin(b,bp)},color,routing:'orthogonal'});
 node('uno','arduino_Uno_Rev3(fix)',30,60,'Arduino Uno');
 const save=(program,note)=>{E.validate(scene,catalog);const files={'circuit.wire.json':JSON.stringify(scene,null,2)+'\n','sketches/uno.cpp':program,'README.md':'# '+title+'\n\n'+description+'\n\n'+note+'\n\nВідкрийте «Плати та з’єднання» та запустіть програму. Параметри деталей доступні в інспекторі.\n'};for(const root of [assets+'/examples/'+id,base+'/examples/'+id])for(const [name,text]of Object.entries(files)){fs.mkdirSync(path.dirname(root+'/'+name),{recursive:true});fs.writeFileSync(root+'/'+name,text);}index.push({id,title,description,projectName:'Wire_'+id.replaceAll('-','_'),board:'uno',files:Object.keys(files)});};return {node,wire,save};
}
let x=make('a4988-microsteps','Arduino Uno · A4988 та мікрокроки','STEP/DIR, п’ять режимів від повного до 1/16 кроку, enable і sleep. Зовнішнє джерело 9 V.');
x.node('driver','pololu0002_a988',390,100,'A4988',{currentLimit:.4,referenceVoltage:.32,senseResistance:.1});x.node('motor','stepper_motor_bipolar',650,100,'Біполярний мотор',{coilResistance:4,coilInductance:.005,stepsPerTurn:200});x.node('supply','Battery block 9V',370,350,'Живлення мотору 9 V',{voltage:9});
x.wire('uno','5V','driver','VDD','#f87171');x.wire('uno','GND','driver','GND','#94a3b8');x.wire('uno','GND','driver','connector14','#94a3b8');x.wire('supply','-','uno','GND','#94a3b8');x.wire('supply','+','driver','VMOT','#f87171');x.wire('uno','5V','driver','RESET','#f87171');for(const [pin,role]of [[2,'STEP'],[3,'DIR'],[4,'MS1'],[5,'MS2'],[6,'MS3'],[7,'ENABLE'],[8,'SLEEP']])x.wire('uno','D'+pin,'driver',role);for(const [output,winding]of [['1A','A'],['1B','C'],['2A','B'],['2B','D']])x.wire('driver',output,'motor',winding);
x.save(`#include <Arduino.h>
const uint8_t STEP=2, DIR=3, MS1=4, MS2=5, MS3=6, ENABLE=7, SLEEP=8;
void pulse(){digitalWrite(STEP,HIGH);delayMicroseconds(2000);digitalWrite(STEP,LOW);delayMicroseconds(2000);}
void setup(){Serial.begin(9600);for(int pin=2;pin<=8;pin++)pinMode(pin,OUTPUT);digitalWrite(ENABLE,LOW);digitalWrite(SLEEP,HIGH);digitalWrite(DIR,HIGH);delay(2);}
void loop(){
  const uint8_t masks[]={0,1,2,3,7},resolutions[]={1,2,4,8,16};
  for(int mode=0;mode<5;mode++){
    digitalWrite(MS1,masks[mode]&1?HIGH:LOW);digitalWrite(MS2,masks[mode]&2?HIGH:LOW);digitalWrite(MS3,masks[mode]&4?HIGH:LOW);
    Serial.print("Microsteps: ");Serial.println(resolutions[mode]);
    for(int i=0;i<50*resolutions[mode];i++)pulse();delay(200);
  }
  digitalWrite(ENABLE,HIGH);Serial.println("Outputs disabled");delay(500);digitalWrite(ENABLE,LOW);
  digitalWrite(SLEEP,LOW);Serial.println("Sleep");delay(500);digitalWrite(SLEEP,HIGH);delay(2);
  digitalWrite(DIR,digitalRead(DIR)?LOW:HIGH);
}
`,'Модель обмежує середній струм обмотки на 0.4 A. Вона враховує STEP/DIR, MS1–MS3, ENABLE, RESET, SLEEP та затримку пробудження 1 ms. Імпульси chopper, тепловий захист і паразитні ефекти не моделюються. Значення мотора — параметри прикладу, а не паспорт конкретного виробу.');
x=make('unipolar-stepper','Arduino Uno · Уніполярний мотор та ULN2003','Чотири напівобмотки, спільні контакти живлення та напівкрокова послідовність. Рух вперед і назад.');x.node('driver','ULN2003A',390,100);x.node('motor','stepper_motor_unipolar',650,100,'Уніполярний мотор',{coilResistance:20,coilInductance:.005,stepsPerTurn:200});x.wire('uno','GND','driver','0V','#94a3b8');x.wire('uno','5V','driver','COM - Common','#f87171');for(const p of ['COMMON_1','COMMON_2'])x.wire('uno','5V','motor',p,'#f87171');for(let i=1;i<=4;i++){x.wire('uno','D'+(i+1),'driver','In '+i);x.wire('driver','Out '+i,'motor',['A','B','C','D'][i-1]);}
x.save(`#include <Arduino.h>
const uint8_t phases[]={1,3,2,6,4,12,8,9};
int phase=0;
void halfStep(int direction){phase=(phase+direction+8)%8;for(int i=0;i<4;i++)digitalWrite(i+2,phases[phase]&(1<<i)?HIGH:LOW);delay(8);}
void setup(){Serial.begin(9600);for(int i=2;i<=5;i++)pinMode(i,OUTPUT);}
void loop(){Serial.println("Unipolar forward");for(int i=0;i<200;i++)halfStep(1);delay(300);Serial.println("Unipolar reverse");for(int i=0;i<200;i++)halfStep(-1);for(int i=2;i<=5;i++)digitalWrite(i,LOW);delay(500);}
`,'Кожна напівобмотка має власний опір та індуктивність. ULN2003 стягує вибрані контакти до GND; спільні контакти мотора живляться від 5 V. Механічна модель має налаштовувані момент утримання, навантаження та межу швидкості. Вона показує відставання позиції; точна динаміка ротора конкретного мотора ще потребує калібрування.');
x=make('esc-bldc','Arduino Uno · ESC та BLDC','Активація ESC імпульсом 1000 µs, плавне прискорення, зупинка та повторний запуск.');
x.node('motor','wiredroid/esc-bldc',400,100,'ESC + BLDC',{maxRpm:1200,servoAcceleration:800,escArmingSeconds:1,escArmPulse:1000,escMinSupply:5,escMaxSupply:24});x.node('supply','Battery block 9V',620,320,'Живлення 9 V',{voltage:9});x.wire('uno','D9','motor','SIGNAL');x.wire('uno','GND','motor','GND','#94a3b8');x.wire('supply','-','uno','GND','#94a3b8');x.wire('supply','+','motor','POWER','#f87171');
x.save(`#include <Arduino.h>
#include <Servo.h>
Servo esc;
void setup(){Serial.begin(9600);esc.attach(9);esc.writeMicroseconds(1000);Serial.println("ESC arming");delay(1500);}
void loop(){Serial.println("BLDC accelerate");for(int pulse=1000;pulse<=1800;pulse+=50){esc.writeMicroseconds(pulse);delay(150);}delay(1000);Serial.println("BLDC stop");esc.writeMicroseconds(1000);delay(2000);}
`,'Це налаштовувана усереднена модель ESC разом із мотором. Вона перевіряє живлення та спільний GND, вимагає початкового імпульсу активації, враховує прискорення й навантаження. Фазові струми BLDC, commutation, датчики Hall, sensorless back-EMF та тепловий захист тут не розраховуються. Цей блок не є моделлю конкретного ESC виробника.');
x=make('quadrature-encoder','Arduino Uno · Квадратурний енкодер','Входи D2/D3 з INPUT_PULLUP, переривання CHANGE і лічильник положення. Автоматичне обертання 30 rpm.');x.node('encoder','Rotary_Encoder_fix',430,120,'Квадратурний енкодер',{pulsesPerTurn:20,encoderRpm:30});x.wire('uno','D2','encoder','EncoderPinA');x.wire('uno','D3','encoder','EncoderPinB');x.wire('uno','GND','encoder','EncoderPinC','#94a3b8');
x.save(`#include <Arduino.h>
volatile long position=0;
volatile uint8_t previous=0;
void edge(){const int8_t delta[]={0,1,-1,0,-1,0,0,1,1,0,0,-1,0,-1,1,0};uint8_t phase=(digitalRead(2)?1:0)|(digitalRead(3)?2:0);position+=delta[(previous<<2)|phase];previous=phase;}
void setup(){Serial.begin(9600);pinMode(2,INPUT_PULLUP);pinMode(3,INPUT_PULLUP);previous=(digitalRead(2)?1:0)|(digitalRead(3)?2:0);attachInterrupt(digitalPinToInterrupt(2),edge,CHANGE);attachInterrupt(digitalPinToInterrupt(3),edge,CHANGE);}
void loop(){noInterrupts();long value=position;interrupts();Serial.print("Encoder: ");Serial.println(value);delay(10);}
`,'Контакти енкодера стягуються до спільного C/GND; високий рівень забезпечують INPUT_PULLUP. Частоту можна змінити параметром encoderRpm або прив’язати енкодер до іншого мотора. Усередині кожного кроку моделі формуються всі переходи A/B з часовими мітками. Програма в GPIO-середовищі Android читає дискретні стани, тому надто швидке обертання може пропускати переходи; точність до такту для нього не заявляється.');
fs.writeFileSync(assets+'/examples/index.json',JSON.stringify(index,null,2)+'\n');console.log('Generated motor and encoder examples; total '+index.length);
