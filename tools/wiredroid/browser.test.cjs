const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const http=require('node:http'),fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const root=path.resolve('app/src/main/assets/wiredroid');
const types={'.js':'application/javascript','.html':'text/html','.svg':'image/svg+xml','.json':'application/json','.css':'text/css'};
const server=http.createServer((req,res)=>{const p=path.join(root,decodeURIComponent(req.url.split('?')[0])==='/'?'index.html':decodeURIComponent(req.url.split('?')[0]));if(!p.startsWith(root+path.sep)){res.writeHead(403).end();return;}fs.readFile(p,(err,data)=>{if(err){res.writeHead(404).end();return;}res.writeHead(200,{'Content-Type':types[path.extname(p)]||'text/plain'});res.end(data);});});
(async()=>{let browser;try{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 browser=await chromium.launch({headless:true,channel:'chrome'});const page=await browser.newPage({viewport:{width:1360,height:900}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{
   const timers=new Map();window.nativeReplies=[];
   window.WireHost={
     loadScene:()=>localStorage.getItem('wiredroid-scene')||'',saveScene:s=>{localStorage.setItem('wiredroid-scene',s);return '';},
     getTheme:()=>'{"dark":true}',projectName:()=> 'Embedded browser bridge test',shouldRun:()=>false,
     ensureSketch:(id,code)=>{if(!localStorage.getItem('code-'+id))localStorage.setItem('code-'+id,code);return '';},
     editSketch:()=>{},openExamples:()=>{window.examplesOpened=(window.examplesOpened||0)+1;},
     startNative:(id,pins)=>{const list=JSON.parse(pins);if(id==='uno'){setTimeout(()=>{
       WireApp.nativeEvent(id,{type:'mode',pin:'connector56',mode:'OUTPUT'});
       WireApp.nativeEvent(id,{type:'mode',pin:'connector63',mode:'INPUT_PULLUP'});
       WireApp.nativeEvent(id,{type:'read',pin:'connector63'});
       WireApp.nativeEvent(id,{type:'log',text:'Bridge test'});
       let high=0;timers.set(id,setInterval(()=>{high=1-high;WireApp.nativeEvent(id,{type:'write',pin:'connector56',value:high});},200));
     },50);}return '';},
     stopNative:()=>{for(const timer of timers.values())clearInterval(timer);timers.clear();},
     nativeReply:(id,value)=>window.nativeReplies.push({id,value})
   };
 });
 await page.goto('http://127.0.0.1:'+server.address().port+'/');
 await page.waitForFunction(()=>document.querySelectorAll('.node .pin').length>40);
 assert.equal(await page.locator('.node').count(),3);assert.equal(await page.locator('#parts button').count(),5);
 // Contact overlay geometry must match the original Fritzing SVG contact after scaling.
 const geometry=await page.evaluate(()=>{
  const node=document.querySelector('[data-node=uno]'),pin=node.querySelector('[data-pin=connector56]'),shape=node.querySelector('[id=connector56pin]');
  const b=shape.getBBox(),p=node.querySelector('svg').createSVGPoint();p.x=b.x+b.width/2;p.y=b.y+b.height/2;const target=p.matrixTransform(shape.getScreenCTM()),dot=pin.getBoundingClientRect();return Math.hypot(target.x-dot.left-dot.width/2,target.y-dot.top-dot.height/2);
 });assert.ok(geometry<2,'pin geometry delta '+geometry);
 // Per-component size must change artwork, contacts and connected wire ends together.
 await page.evaluate(()=>WireApp.select('uno'));
 await page.locator('#shrink').click();await page.waitForFunction(()=>WireApp.getScene().nodes[0].scale===.75&&document.querySelector('[data-node=uno]').offsetWidth===248);
 await page.evaluate(()=>WireApp.resizeSelected(.5));
 const scaled=await page.evaluate(()=>{
  const n=document.querySelector('[data-node=uno]'),pin=n.querySelector('[data-pin=connector56]'),shape=n.querySelector('[id=connector56pin]'),b=shape.getBBox(),p=n.querySelector('svg').createSVGPoint();
  p.x=b.x+b.width/2;p.y=b.y+b.height/2;const t=p.matrixTransform(shape.getScreenCTM()),r=pin.getBoundingClientRect();
  const path=document.querySelector('#wires .wire:not(.wire-hit)'),start=path.getPointAtLength(0),wirePoint=new DOMPoint(start.x,start.y).matrixTransform(path.getScreenCTM());
  return {width:n.offsetWidth,contact:Math.hypot(t.x-r.left-r.width/2,t.y-r.top-r.height/2),wire:Math.hypot(wirePoint.x-r.left-r.width/2,wirePoint.y-r.top-r.height/2)};
 });assert.equal(scaled.width,165);assert.ok(scaled.contact<2);assert.ok(scaled.wire<2);
 await page.locator('#save').click();await page.reload();await page.waitForFunction(()=>WireApp.ready);
 assert.equal(await page.evaluate(()=>WireApp.getScene().nodes[0].scale),.5,'per-part size persists');
 await page.evaluate(()=>WireApp.select('uno'));await page.locator('#grow').click();await page.waitForFunction(()=>WireApp.getScene().nodes[0].scale===.75);
 await page.locator('#undo').click();await page.waitForFunction(()=>WireApp.getScene().nodes[0].scale===.5);
 await page.locator('#redo').click();await page.waitForFunction(()=>WireApp.getScene().nodes[0].scale===.75);
 await page.evaluate(()=>{WireApp.select('uno');return WireApp.resizeSelected(1);});
 await page.evaluate(()=>{WireApp.select('led');return WireApp.resizeSelected(.5);});
 assert.ok((await page.locator('[data-node=led] .art').boundingBox()).width<50,'components resize independently');
 await page.evaluate(()=>{WireApp.select('led');return WireApp.resizeSelected(1);});
 await page.locator('#run').click();await page.waitForFunction(()=>document.querySelector('[data-node=led]').classList.contains('led-on'),{timeout:4000});
 await page.waitForFunction(()=>!document.querySelector('[data-node=led]').classList.contains('led-on'),{timeout:4000});
 assert.match(await page.locator('#logs').textContent(),/Bridge test/);await page.locator('#run').click();
 const before=await page.evaluate(()=>WireApp.getScene().nodes[0].x),box=await page.locator('[data-node=uno] .art').boundingBox();
 await page.mouse.move(box.x+box.width/2,box.y+box.height/2);await page.mouse.down();await page.mouse.move(box.x+box.width/2+65,box.y+box.height/2+30,{steps:8});await page.mouse.up();
 assert.ok((await page.evaluate(()=>WireApp.getScene().nodes[0].x))>before);
 await page.locator('#undo').click();assert.equal(await page.evaluate(()=>WireApp.getScene().nodes[0].x),before);
 await page.locator('#redo').click();assert.ok((await page.evaluate(()=>WireApp.getScene().nodes[0].x))>before);
 await page.evaluate(async()=>{ const scene=WireApp.getScene(); const n=scene.nodes[0]; WireApp.select(n.id); });
 await page.locator('#rotate').click();
 await page.waitForFunction(()=>WireApp.getScene().nodes[0].rotation===90&&document.querySelectorAll('.pin').length>40);
 const rotated=await page.evaluate(()=>{const node=document.querySelector('[data-node=uno]'),pin=node.querySelector('[data-pin=connector56]'),shape=node.querySelector('[id=connector56pin]'),b=shape.getBBox(),p=node.querySelector('svg').createSVGPoint();p.x=b.x+b.width/2;p.y=b.y+b.height/2;const t=p.matrixTransform(shape.getScreenCTM()),r=pin.getBoundingClientRect();return Math.hypot(t.x-r.left-r.width/2,t.y-r.top-r.height/2);});
 assert.ok(rotated<2,'rotated contact geometry '+rotated);
 await page.evaluate(()=>{WireApp.select('uno');return WireApp.resizeSelected(.5);});
 const rotatedScaled=await page.evaluate(()=>{const n=document.querySelector('[data-node=uno]'),pin=n.querySelector('[data-pin=connector56]'),shape=n.querySelector('[id=connector56pin]'),b=shape.getBBox(),p=n.querySelector('svg').createSVGPoint();p.x=b.x+b.width/2;p.y=b.y+b.height/2;const t=p.matrixTransform(shape.getScreenCTM()),r=pin.getBoundingClientRect();return Math.hypot(t.x-r.left-r.width/2,t.y-r.top-r.height/2);});
 assert.ok(rotatedScaled<2,'rotated resized contacts');
 await page.locator('#undo').click();await page.waitForFunction(()=>WireApp.getScene().nodes[0].scale===1);
 await page.locator('#undo').click();
 await page.locator('#save').click();await page.reload();await page.waitForFunction(()=>document.querySelectorAll('.node').length===3);
 assert.ok((await page.evaluate(()=>WireApp.getScene().nodes[0].x))>before,'positions persist');
 assert.match(await page.evaluate(()=>localStorage.getItem('code-uno')),/#include <Arduino.h>/);
 assert.deepEqual(await page.evaluate(()=>nativeReplies),[]); // reload starts a fresh bridge
 await page.locator('#run').click();await page.waitForFunction(()=>nativeReplies.length>0);
 assert.equal(await page.evaluate(()=>nativeReplies[0].value),1023);
 await page.locator('#run').click();
 await page.locator('[data-category=boards]').click();assert.ok(await page.locator('#parts button').count()>80);
 await page.locator('#search').fill('esp32');assert.ok(await page.locator('#parts .part-card').count()>0);
 await page.locator('#search').fill('');await page.locator('[data-category=starter]').click();
 const beforeExamples=await page.evaluate(()=>JSON.stringify(WireApp.getScene()));await page.locator('#examples').click();
 assert.equal(await page.evaluate(()=>window.examplesOpened),1);assert.equal(await page.evaluate(()=>JSON.stringify(WireApp.getScene())),beforeExamples);
 // Tool window colours come from the IDE theme, including live theme changes.
 await page.evaluate(()=>WireApp.applyTheme({embedded:true,dark:true,bg:'#212121',panel:'#303030',text:'#dddddd',muted:'#888888',line:'#505050',accent:'#6297de',console:'#181818'}));
 assert.equal(await page.locator('body').evaluate(el=>getComputedStyle(el).backgroundColor),'rgb(33, 33, 33)');
 assert.equal(await page.locator('.brand').isVisible(),false);
 await page.locator('#clear').click();
 await page.screenshot({path:'/private/tmp/WireDroid-desktop.png'});
 await page.setViewportSize({width:412,height:850});await page.locator('#fit').click();
 assert.equal(await page.locator('#library').isVisible(),false);
 await page.locator('#libraryTab').click();assert.equal(await page.locator('#library').isVisible(),true);
 await page.locator('#inspectorTab').click();assert.equal(await page.locator('#library').isVisible(),false);assert.equal(await page.locator('#inspector').isVisible(),true);
 await page.locator('#canvasTab').click();assert.equal(await page.locator('#inspector').isVisible(),false);
 assert.ok((await page.locator('#viewport').boundingBox()).width>400,'phone canvas uses the whole tool window');
 // Long compiler output must retain the diagnostic at the end.
 await page.locator('#run').click();await page.waitForFunction(()=>document.getElementById('logs').textContent.includes('Bridge test'));
 await page.evaluate(()=>WireApp.nativeEvent('uno',{type:'error',text:Array.from({length:100},(_,i)=>'warning '+i).join('\n')+'\nerror: missing_symbol'}));
 assert.match(await page.locator('#logs').textContent(),/error: missing_symbol/);
 await page.locator('#clear').click();await page.screenshot({path:'/private/tmp/WireDroid-phone.png'});
 assert.deepEqual(errors,[]);console.log('PASS: per-part resizing, rotated contact/wire alignment, size persistence and undo/redo, examples button, library, SVG contacts, native bridge signals, input reply, C++ defaults, board catalog, LED signal, drag, undo/redo, persistence, live IDE theme, narrow panel tabs, complete diagnostics, desktop and phone rendering');
 }finally{if(browser)await browser.close();server.close();}})().catch(e=>{console.error(e);process.exitCode=1;});
