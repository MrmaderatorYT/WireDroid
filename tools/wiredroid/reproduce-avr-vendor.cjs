#!/usr/bin/env node
'use strict';
// Rebuild frozen vendor outputs in a temporary tree; never write production assets.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),crypto=require('node:crypto'),assert=require('node:assert/strict');
const ROOT=path.resolve(__dirname,'../..');
const PINS={
 avr8js:{version:'0.21.0',resolved:'https://registry.npmjs.org/avr8js/-/avr8js-0.21.0.tgz',integrity:'sha512-kjFzxorERow5YFFV25ElhscG57TJqpT6M0m5GDDbXiMYoBmG6K99MQcq6AU1Ly4L+uB1g1o79dMmBNb5O+toxQ=='},
 esbuild:{version:'0.25.12',resolved:'https://registry.npmjs.org/esbuild/-/esbuild-0.25.12.tgz',integrity:'sha512-bbPBYYrtZbkt6Os6FiTLCTFxvq4tt3JKall1vRwshA3fdVztsLAatFaZobhkBC8/BrPetoa0oksYoKXoG4ryJg=='},
};
const EXPECTED={
 'avr8js.js':'583c28e67ff048957aa43240fc4d3fb3a876310bae73070983f125181313e6fd',
 'avr8js.cjs':'aa257dbbab4d30de87618e50477a3db288c939842aeb03faa21cb3a753438b79',
};
const DIST_TREE='56d6c8d635504f157c553fd6bf27eec4688c82bb14cca1ec81c92ebb1c72d31b';
const hash=bytes=>crypto.createHash('sha256').update(bytes).digest('hex');
function readJson(file){return JSON.parse(fs.readFileSync(file,'utf8'));}
function treeHash(root){
 const files=[];
 function walk(folder){for(const entry of fs.readdirSync(folder,{withFileTypes:true})){const file=path.join(folder,entry.name);assert(!entry.isSymbolicLink(),'Unexpected package source symlink');if(entry.isDirectory())walk(file);else if(entry.isFile())files.push(file);}}
 walk(root);
 // Source names are ASCII. Binary lexical order matches the original manifest.
 const relative=file=>path.relative(root,file).split(path.sep).join('/');files.sort((a,b)=>relative(a)<relative(b)?-1:1);
 const digest=crypto.createHash('sha256');for(const file of files){digest.update(path.relative(root,file).split(path.sep).join('/'));digest.update(Buffer.from([0]));digest.update(fs.readFileSync(file));digest.update(Buffer.from([0]));}return digest.digest('hex');
}
function main(){
 let deps='/private/tmp/wiredroid-mcu-deps',output=null;
 for(let i=2;i<process.argv.length;i++){
  const key=process.argv[i];if(key==='--help'){console.log('Usage: node reproduce-avr-vendor.cjs --deps DEPENDENCY_DIRECTORY --output TEMP_OUTPUT_DIRECTORY');return;}
  if(!['--deps','--output'].includes(key)||!process.argv[i+1])throw Error('Unknown or missing argument: '+key);
  const value=path.resolve(process.argv[++i]);if(key==='--deps')deps=value;else output=value;
 }
 deps=fs.realpathSync(deps);const lock=readJson(path.join(deps,'package-lock.json'));
 for(const [name,pin]of Object.entries(PINS)){
  const recorded=lock.packages['node_modules/'+name];for(const field of ['version','resolved','integrity'])assert.equal(recorded[field],pin[field],name+' '+field+' mismatch');
  const installed=readJson(path.join(deps,'node_modules',name,'package.json'));assert.equal(installed.version,pin.version);assert.equal(installed.license,'MIT');
 }
 const avr=path.join(deps,'node_modules/avr8js');assert.equal(treeHash(path.join(avr,'dist')),DIST_TREE,'AVR distribution source tree changed');
 const vendor=path.join(ROOT,'app/src/main/assets/wiredroid/vendor');
 assert.deepEqual(fs.readFileSync(path.join(avr,'LICENSE')),fs.readFileSync(path.join(vendor,'AVR8JS-LICENSE')),'Bundled AVR MIT notice differs');
 const scratch=fs.mkdtempSync(path.join(os.tmpdir(),'wiredroid-avr-repro-source-'));
 try{
  const work=path.join(scratch,'WireDroid-work'),modules=path.join(scratch,'wiredroid-mcu-deps/node_modules');fs.mkdirSync(work);fs.mkdirSync(modules,{recursive:true});fs.symlinkSync(avr,path.join(modules,'avr8js'),'dir');
  output=output||fs.mkdtempSync(path.join(os.tmpdir(),'wiredroid-avr-repro-output-'));
  let ancestor=output;const missing=[];while(!fs.existsSync(ancestor)){missing.unshift(path.basename(ancestor));ancestor=path.dirname(ancestor);}
  const canonicalOutput=path.join(fs.realpathSync(ancestor),...missing),assets=fs.realpathSync(path.join(ROOT,'app/src/main/assets'));
  assert(canonicalOutput!==assets&&!canonicalOutput.startsWith(assets+path.sep),'Production assets cannot be used as output');fs.mkdirSync(output,{recursive:true});
  const esbuild=require(path.join(deps,'node_modules/esbuild'));assert.equal(esbuild.version,PINS.esbuild.version);
  for(const [name,mode,flags]of [['avr8js.js','esm',{format:'iife',globalName:'AVR8',minify:true}],['avr8js.cjs','cjs',{format:'cjs',platform:'node'}]]){
   const result=esbuild.buildSync({entryPoints:['../wiredroid-mcu-deps/node_modules/avr8js/dist/'+mode+'/index.js'],bundle:true,write:false,absWorkingDir:work,preserveSymlinks:true,...flags});
   const bytes=Buffer.from(result.outputFiles[0].contents),sha=hash(bytes);assert.equal(sha,EXPECTED[name],'Reproduced '+name+' hash mismatch');assert.deepEqual(bytes,fs.readFileSync(path.join(vendor,name)),'Current vendor '+name+' differs');
   fs.writeFileSync(path.join(output,name),bytes);console.log('PASS '+name+' '+bytes.length+' bytes SHA256 '+sha+' byte-identical');
  }
  console.log('Output: '+output);
 }finally{fs.rmSync(scratch,{recursive:true,force:true});}
}
try{main();}catch(error){console.error(error.message);process.exitCode=1;}
