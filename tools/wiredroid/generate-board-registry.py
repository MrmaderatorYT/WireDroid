#!/usr/bin/env python3
"""Create support levels and pinned Arduino AVR profiles; unknown hardware stays unknown."""
import json,re,pathlib
ROOT=pathlib.Path(__file__).resolve().parents[2];ASSETS=ROOT/'app/src/main/assets/wiredroid'
catalog=json.loads((ASSETS/'catalog.json').read_text());parts=catalog['parts'] if isinstance(catalog,dict) else catalog
source={'url':'https://github.com/arduino/ArduinoCore-avr/tree/1.8.6','revision':'1.8.6','verifiedOn':'2026-10-03','documents':['boards.txt','variants/standard/pins_arduino.h','variants/eightanaloginputs/pins_arduino.h']}
targets=[]
for suffix,title,variant,baud,flash,adc,mcu,signature in [('uno','Arduino Uno Rev3','standard',115200,32256,6,'atmega328p',[30,149,15]),('nano:atmega328','Arduino Nano ATmega328P','eightanaloginputs',115200,30720,8,'atmega328p',[30,149,15]),('nano:atmega328old','Arduino Nano ATmega328P — old bootloader','eightanaloginputs',57600,30720,8,'atmega328p',[30,149,15]),('nano:atmega168','Arduino Nano ATmega168','eightanaloginputs',19200,14336,8,'atmega168',[30,148,6])]:
 pins=[];timers={3:'TIMER2B',5:'TIMER0B',6:'TIMER0A',9:'TIMER1A',10:'TIMER1B',11:'TIMER2A'}
 for pin in range(20):
  p={'arduino':pin,'name':'D'+str(pin) if pin<14 else 'A'+str(pin-14),'port':'D' if pin<8 else 'B' if pin<14 else 'C','bit':pin if pin<8 else pin-8 if pin<14 else pin-14,'digital':True,'roles':[]}
  if pin>=14:p['adc']=pin-14
  if pin in timers:p['pwmTimer']=timers[pin]
  p['roles']={0:['RX'],1:['TX'],10:['SS'],11:['MOSI'],12:['MISO'],13:['SCK','LED_BUILTIN'],18:['SDA'],19:['SCL']}.get(pin,[]);pins.append(p)
 if adc==8:pins.extend([{'arduino':20+i,'name':'A'+str(6+i),'adc':6+i,'digital':False,'roles':[]} for i in range(2)])
 targets.append({'id':'arduino:avr:'+suffix,'title':title,'revision':'Rev3' if suffix=='uno' else 'classic','architecture':'avr','mcu':mcu,'clockHz':16000000,'logicVoltage':5,'adcBits':10,'pwmBits':8,'flashBytes':32768 if mcu=='atmega328p' else 16384,'ramBytes':2048 if mcu=='atmega328p' else 1024,'eepromBytes':1024 if mcu=='atmega328p' else 512,'core':{'package':'arduino:avr','version':'1.8.6','name':'arduino','variant':variant,'board':'AVR_UNO' if suffix=='uno' else 'AVR_NANO'},'pins':pins,'upload':{'protocol':'stk500v1','baud':baud,'maxFlash':flash,'pageSize':128,'signature':signature,'status':'implemented-protocol-hardware-unverified'},'backend':'avr8js-atmega328p' if mcu=='atmega328p' else None,'capabilities':{'pinMapping':'source-verified','firmwareBuild':'host-toolchain','mcuSimulation':'implemented' if mcu=='atmega328p' else 'unavailable','usbUpload':'protocol-tested-hardware-unverified'},'evidence':[source]})
targets[0]['usbIds']=[{'vid':vid,'pid':pid} for vid,pid in [(0x2341,0x0043),(0x2341,0x0001),(0x2a03,0x0043),(0x2341,0x0243),(0x2341,0x006a)]]
known={'arduino_Uno_Rev3(fix)':'arduino:avr:uno','arduino_uno(rev3)-icsp(1)':'arduino:avr:uno','arduino_uno(rev3)-icsp':'arduino:avr:uno','obsolete/Arduino_UNO_R3':'arduino:avr:uno','obsolete/arduino_Uno_Rev3':'arduino:avr:uno','Arduino Nano3(fix)':'arduino:avr:nano:atmega328','Arduino Nano3(icsp)':'arduino:avr:nano:atmega328','obsolete/Arduino Nano_v30':'arduino:avr:nano:atmega328','obsolete/Arduino Nano3':'arduino:avr:nano:atmega328'}
boards=[]
for part in parts:
 if not part.get('board'):continue
 target=known.get(part['id']);pins=part.get('pins',[]);mapping=[]
 for pin in pins:
  name=pin['name'];record={'connector':pin['id'],'label':name}
  if target:
   m=re.match(r'^D?(\d+)(?:[ /]|PWM|RX|TX|$)',name,re.I);a=re.match(r'^A([0-7])(?:[/ ]|$)',name,re.I)
   if a:record['arduino']=14+int(a[1]);record['adc']=int(a[1])
   elif m and int(m[1])<14:record['arduino']=int(m[1])
   elif not name.upper().startswith('ICSP2'):
    for label,num in [('MISO',12),('MOSI',11),('SCK',13),('SDA',18),('SCL',19)]:
     if re.search(r'\b'+label+r'\b',name,re.I):record['arduino']=num;break
  mapping.append(record)
 boards.append({'catalogId':part['id'],'title':part['title'],'profile':target,'connectors':mapping,'support':{'image':'available','pins':'catalog-defined' if pins else 'missing','gpio':'implemented-generic' if pins else 'unavailable','firmware':'host-toolchain' if target else 'unavailable','mcu':'avr8js-atmega328p' if target else 'unavailable','usb':'protocol-tested-hardware-unverified' if target else 'unavailable'},'evidence':[{'url':'https://github.com/fritzing/fritzing-parts','sourceFile':part.get('source'),'verifiedOn':'2026-10-03','scope':'catalog-labels-and-connectors'}],'notes':[] if target else ['Точна ревізія й апаратні параметри ще не підтверджені; GPIO-модель є узагальненою.']})
result={'version':1,'updated':'2026-10-03','targets':targets,'boards':boards,'statusDefinitions':{'source-verified':'Параметри з офіційного Arduino core; це не перевірка фізичної плати.','host-toolchain':'Збірка AVR на комп’ютері з встановленим avr-gcc.','implemented-generic':'GPIO-середовище Android; не емуляція MCU.','protocol-tested-hardware-unverified':'Протокол перевірений тестовим транспортом; фізична плата не перевірена.','unavailable':'Операція для цього профілю ще недоступна.'}}
(ASSETS/'boards.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
print(f'{len(boards)} catalog boards; {len(targets)} exact AVR targets; {sum(bool(b["profile"]) for b in boards)} mapped images')
