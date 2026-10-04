"""Generate the bundled, editable circuit projects and source-distribution examples."""
from pathlib import Path
import json

root = Path(__file__).resolve().parents[2]
assets = root / 'app/src/main/assets/wiredroid/examples'
export = root / 'examples'
UNO = 'arduino_Uno_Rev3(fix)'
NANO = 'Arduino Nano3(fix)'
ESP32 = 'contrib/adafruit_188853bab86e1c9d356b60caa91fb4cc_1_96'
RED = 'LED-generic-5mm_6852162_005'
GREEN = 'obsolete/LED-green-5mm'
YELLOW = 'obsolete/LED-yellow-5mm'

def node(id, part, x, y, label, scale=1):
    return dict(id=id, part=part, x=x, y=y, rotation=0, label=label, scale=scale)

def wire(a, ap, b, bp, color='#4ade80'):
    return dict(a=dict(node=a, pin=ap), b=dict(node=b, pin=bp), color=color)

def led_branch(board, pin, ground, suffix, x, y, color_part=RED):
    resistor, led = 'resistor'+suffix, 'led'+suffix
    ns=[node(resistor,'resistor',x,y,'220 Ω',.75),node(led,color_part,x+145,y,'LED'+suffix,.8)]
    # Use the same compact 5 mm package for all colours; old library variants
    # have long leads that otherwise overlap adjacent traffic-light branches.
    ns[1]['part']=RED
    ns[1]['ledColor']={RED:'#e60000',GREEN:'#16a34a',YELLOW:'#e6c700'}[color_part]
    ns[1]['label']={RED:'Червоний LED',GREEN:'Зелений LED',YELLOW:'Жовтий LED'}[color_part]
    ws=[wire(board,pin,resistor,'connector0'),wire(resistor,'connector1',led,'connector1'),wire(led,'connector0',board,ground,'#94a3b8')]
    return ns, ws

def sketch(body):
    return '#include <Arduino.h>\n\n' + body.strip() + '\n'

items=[]
def add(id,title,description,name,board,ns,ws,code,instructions):
    for i,w in enumerate(ws): w['id']='wire_'+str(i+1)
    scene=dict(version=1,name=title,nodes=ns,wires=ws)
    text=f'# {title}\n\n{description}\n\n{instructions}\n\nВідкрийте circuit.wire.json у WireDroid, натисніть «Код» для редагування sketches/{board}.cpp і «Запустити» для цифрової GPIO-візуалізації.\n\nРозміри деталей змінюються окремо в інспекторі (25–200%). Приклад можна змінювати й експортувати як звичайний проєкт.\n\nЦе цифрова GPIO-модель. Приклад не містить SDK виробника або прошивання фізичної плати.\n'
    files={'circuit.wire.json':json.dumps(scene,ensure_ascii=False,indent=2)+'\n',f'sketches/{board}.cpp':code,'README.md':text,'.gitignore':'.wiredroid/\n*.bak\n'}
    for base in (assets,export):
        for path,content in files.items():
            target=base/id/path;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(content)
    # Android omits dotfiles from packaged assets; the native factory creates .gitignore.
    items.append(dict(id=id,title=title,description=description,projectName=name,board=board,files=[p for p in files if not p.startswith('.')]))

n,w=led_branch('uno','connector56','connector88','',390,110)
add('blink-uno','Arduino Uno · миготіння LED','Uno + резистор + LED; увімкнення та вимкнення кожні 500 мс.','Uno_Blink','uno',
    [node('uno',UNO,50,80,'Arduino Uno',.85)]+n,w,
    sketch('''void setup() {
  pinMode(13, OUTPUT);
  Serial.begin(9600);
}
void loop() {
  digitalWrite(13, HIGH);
  Serial.println("LED on");
  delay(500);
  digitalWrite(13, LOW);
  delay(500);
}'''),'D13 → 220 Ω → анод LED; катод LED → GND.')

n,w=led_branch('uno','connector56','connector88','',390,110)
w += [wire('uno','connector63','button','connector0','#60a5fa'),wire('button','connector1','uno','connector88','#94a3b8')]
add('button-uno','Arduino Uno · кнопка та LED','Кнопка на D2 з INPUT_PULLUP керує LED на D13.','Uno_Button_LED','uno',
    [node('uno',UNO,50,80,'Arduino Uno',.85)]+n+[node('button','Pushbutton',420,280,'Кнопка',.85)],w,
    sketch('''int previous = -1;
void setup() {
  pinMode(13, OUTPUT);
  pinMode(2, INPUT_PULLUP);
  Serial.begin(9600);
}
void loop() {
  int pressed = digitalRead(2) == LOW;
  digitalWrite(13, pressed ? HIGH : LOW);
  if (pressed != previous) {
    Serial.println(pressed ? "Button pressed" : "Button released");
    previous = pressed;
  }
  delay(20);
}'''),'Виберіть кнопку на схемі, відкрийте «Контакти» й утримуйте «Натиснути». LED світить лише під час натискання. Кнопка з’єднує D2 із GND; зовнішній pull-up не потрібен.')

ns=[node('uno',UNO,40,120,'Arduino Uno',.85)];ws=[]
for suffix,pin,y,color in [('_red','connector51',40,RED),('_yellow','connector52',175,YELLOW),('_green','connector53',310,GREEN)]:
    n,w=led_branch('uno',pin,'connector88',suffix,390,y,color);ns+=n;ws+=w
add('traffic-uno','Arduino Uno · світлофор','Три LED на D8, D9 і D10 з окремими резисторами.','Uno_Traffic_Lights','uno',ns,ws,
    sketch('''void setup() {
  pinMode(8, OUTPUT);
  pinMode(9, OUTPUT);
  pinMode(10, OUTPUT);
}
void loop() {
  digitalWrite(8, HIGH); digitalWrite(9, LOW); digitalWrite(10, LOW);
  delay(1200);
  digitalWrite(8, LOW); digitalWrite(9, LOW); digitalWrite(10, HIGH);
  delay(1200);
  digitalWrite(10, LOW); digitalWrite(9, HIGH);
  delay(600);
}'''),'Червоний LED — D8, жовтий — D9, зелений — D10. Порядок: червоний → зелений → жовтий. Кожен анод під’єднано через 220 Ω, катоди — до GND.')

ns=[node('nano',NANO,60,65,'Arduino Nano',.65)];ws=[]
for suffix,pin,y,color in [('_red','connector20',80,RED),('_green','connector21',240,GREEN)]:
    n,w=led_branch('nano',pin,'connector19',suffix,320,y,color);ns+=n;ws+=w
add('alternating-nano','Arduino Nano · два LED','Червоний і зелений LED по черзі на D2 та D3.','Nano_Alternating_LED','nano',ns,ws,
    sketch('''void setup() {
  pinMode(2, OUTPUT);
  pinMode(3, OUTPUT);
}
void loop() {
  digitalWrite(2, HIGH); digitalWrite(3, LOW);
  delay(500);
  digitalWrite(2, LOW); digitalWrite(3, HIGH);
  delay(500);
}'''),'D2 керує червоним LED, D3 — зеленим. Кожен LED має власний резистор 220 Ω та з’єднання катода з GND.')

n,w=led_branch('esp32','connector17','connector41','',390,110)
add('blink-esp32','ESP32 QT Py · миготіння LED','Модуль ESP32, вихід A0, резистор і LED.','ESP32_Blink','esp32',
    [node('esp32',ESP32,60,75,'ESP32 QT Py',.7)]+n,w,
    sketch('''const char *LED_PIN = "connector17"; // A0 у Fritzing-профілі
void setup() {
  pinMode(LED_PIN, OUTPUT);
  Serial.begin(9600);
}
void loop() {
  digitalWrite(LED_PIN, HIGH);
  Serial.println("ESP32 GPIO high");
  delay(350);
  digitalWrite(LED_PIN, LOW);
  delay(350);
}'''),'A0 (connector17) → 220 Ω → LED → GND (connector41). Контакт вибрано за ID з каталогу. Приклад демонструє загальну GPIO-модель модуля, а не ESP-IDF чи емуляцію CPU ESP32.')

add('servo-sweep','Arduino Uno · рух сервопривода','Серво плавно повертається між 0° та 180°.','Uno_Servo_Sweep','uno',
    [node('uno',UNO,50,80,'Arduino Uno',.85),node('servo','servo',430,100,'Сервопривід',1.1)],
    [wire('uno','connector88','servo','connector0','#94a3b8'),wire('uno','connector87','servo','connector1','#ef4444'),wire('uno','connector52','servo','connector2','#60a5fa')],
    '#include <Arduino.h>\n#include <Servo.h>\n\nServo motor;\n\nvoid setup() {\n  motor.attach(9);\n}\n\nvoid loop() {\n  motor.write(0);\n  delay(1000);\n  motor.write(180);\n  delay(1000);\n}\n',
    'GND → gnd, 5V → vcc, D9 → pulse. Після запуску рухається важіль, корпус і проводи залишаються нерухомими. Це модель позиційного серво 0–180°, без моделі струму, навантаження чи реального таймера плати.')

add('servo-pot','Arduino Uno · потенціометр керує серво','Крутіть ручку потенціометра: C++ задає серво кут від 0° до 180°.','Uno_Potentiometer_Servo','uno',
    [node('uno',UNO,50,80,'Arduino Uno',.85),node('servo','servo',440,80,'Сервопривід',1.1),node('pot','potentiometer_rotary_9mm_6',430,310,'Потенціометр',.5)],
    [wire('uno','connector88','servo','connector0','#94a3b8'),wire('uno','connector87','servo','connector1','#ef4444'),wire('uno','connector52','servo','connector2','#60a5fa'),wire('uno','connector88','pot','connector0','#94a3b8'),wire('uno','connector87','pot','connector2','#ef4444'),wire('pot','connector1','uno','connector0','#facc15')],
    '#include <Arduino.h>\n#include <Servo.h>\n\nServo motor;\n\nvoid setup() {\n  motor.attach(9);\n}\n\nvoid loop() {\n  int value = analogRead(A0);\n  motor.write(map(value, 0, 1023, 0, 180));\n  delay(20);\n}\n',
    'Потенціометр: leg1 → GND, leg2 → 5V, wiper → A0. Серво: gnd → GND, vcc → 5V, pulse → D9. Рухайте повзунок у «Контакти» або тягніть по колу ручку на полотні; стрілки клавіатури теж працюють.')

(assets/'index.json').write_text(json.dumps(items,ensure_ascii=False,indent=2)+'\n')
(export/'README.md').write_text('# Готові приклади WireDroid\n\n'+ '\n'.join(f'- **{i["title"]}** — `{i["id"]}/`: {i["description"]}' for i in items)+'\n\nУ застосунку: «Приклади схем» на початковому екрані або «Приклади» у конструкторі. Кожне відкриття створює окремий редагований проєкт.\n')
print(f'Generated {len(items)} complete circuit/C++ projects in assets and examples/.')
