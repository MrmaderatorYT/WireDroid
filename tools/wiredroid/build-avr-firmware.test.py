#!/usr/bin/env python3
import importlib.util,pathlib,tempfile,unittest,json,hashlib
spec=importlib.util.spec_from_file_location('avrbuild',pathlib.Path(__file__).with_name('build-avr-firmware.py'));module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class BuildPlanTest(unittest.TestCase):
 def setUp(self):self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name);self.core=self.root/'core';self.core.mkdir()
 def tearDown(self):self.tmp.cleanup()
 def write(self,path,text):p=self.root/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);return p
 def project(self):
  self.write('wiredroid.json',json.dumps({'boards':{'uno':{'profile':'arduino:avr:uno','sources':['src/**'],'defines':['VALUE=6']}}}))
  entry=self.write('sketches/uno.cpp','#include <Counter.h>\nvoid setup(){} void loop(){}');self.write('src/shared.c','int common;');self.write('src/boards/uno/helper.cpp','int selected;');self.write('src/boards/nano/helper.cpp','#error wrong board');self.write('libraries/Counter/library.properties','name=Counter\nversion=1.0.0\narchitectures=avr\ndepends=Support (>=1.0 && <2.0)\n');self.write('libraries/Counter/src/Counter.h','int counter();');self.write('libraries/Counter/src/Counter.cpp','int counter(){return 1;}');self.write('libraries/Support/library.properties','name=Support\nversion=1.2.0\narchitectures=avr\n');self.write('libraries/Support/src/Support.h','int support();');self.write('libraries/Support/src/Support.cpp','int support(){return 4;}');return entry
 def test_selects_source_libraries_and_target(self):
  board,target,sources,includes,libraries=module.plan(self.root,self.project(),'uno',self.core)
  self.assertEqual(board['core']['variant'],'standard');self.assertEqual(len(sources),5);self.assertFalse(any('boards/nano' in str(p) for p in sources));self.assertIn('Support',libraries)
 def test_rejects_hash_mismatch_and_incompatible_arch(self):
  entry=self.project();self.write('wiredroid-libraries.lock.json',json.dumps({'version':1,'libraries':[{'name':'Counter','folder':'Counter','treeSha256':'0'*64}]}))
  with self.assertRaisesRegex(ValueError,'змінена'):module.plan(self.root,entry,'uno',self.core)
  (self.root/'wiredroid-libraries.lock.json').unlink();self.write('libraries/Counter/library.properties','name=Counter\nversion=1.0.0\narchitectures=esp32\n')
  with self.assertRaisesRegex(ValueError,'AVR'):module.plan(self.root,entry,'uno',self.core)
 def test_rejects_escape_and_version_conflict(self):
  entry=self.project();self.write('libraries/Support/library.properties','name=Support\nversion=2.0.0\narchitectures=avr\n')
  with self.assertRaisesRegex(ValueError,'Версія'):module.plan(self.root,entry,'uno',self.core)
  self.assertTrue(module.glob('src/**/*.cpp','src/helper.cpp'));self.assertTrue(module.glob('src/**/*.cpp','src/deep/helper.cpp'))
  with self.assertRaises(ValueError):module.safe_patterns(['../outside'])
 def test_digest_java_contract_all_boards_hidden_build_and_renames(self):
  self.write('src/Z.CPP','Z\r\n');self.write('include/a.HXX','A\n');self.write('.cache/skip.cpp','ignored');self.write('build/skip.cpp','ignored');self.write('src/.skip.cpp','ignored');self.write('readme.txt','ignored')
  # Write exact CRLF bytes, avoiding platform newline translation.
  (self.root/'src/Z.CPP').write_bytes(b'Z\r\n')
  expected=hashlib.sha256(b'include/a.HXX\0A\n\0src/Z.CPP\0Z\r\n\0').hexdigest()
  self.assertEqual(module.source_digest(self.root),expected)
  (self.root/'src/Z.CPP').rename(self.root/'src/A.CPP');self.assertNotEqual(module.source_digest(self.root),expected)
 def test_digest_rejects_escape_and_large_file(self):
  with tempfile.TemporaryDirectory() as outside:
   other=pathlib.Path(outside)/'other.cpp';other.write_text('int other;');(self.root/'alias.cpp').symlink_to(other)
   with self.assertRaisesRegex(ValueError,'поза'):module.source_digest(self.root)
  (self.root/'alias.cpp').unlink();(self.root/'large.cpp').write_bytes(bytes(2*1024*1024+1))
  with self.assertRaisesRegex(ValueError,'ліміт'):module.source_digest(self.root)
 def test_config_digest_java_golden_mode_defaults_and_field_changes(self):
  base={'profile':'arduino:avr:uno','coreVersion':'1.8.6','mode':'mcu'}
  def settings(target,libraries=None):
   cfg={'boards':{'uno':target}}
   if libraries is not None:cfg['libraries']=libraries
   self.write('wiredroid.json',json.dumps(cfg));return module.config_digest(self.root,'uno')
  expected='3340e25f66e7c893fbe297f5f92a4573ef53eed5f7316e0b6ab38ccd772d6664'
  self.assertEqual(settings(base),expected);self.assertEqual(settings({**base,'mode':'usb'}),expected)
  for name,value in [('defines',['VALUE=2']),('sources',['src/**']),('exclude',['src/no.cpp']),('includePaths',['headers']),('cppStandard','gnu++17'),('coreVersion','1.8.7'),('profile','arduino:avr:nano:atmega328')]:self.assertNotEqual(settings({**base,name:value}),expected)
  self.assertNotEqual(settings(base,['Extra']),expected)
  with self.assertRaisesRegex(ValueError,'Некоректне'):settings(base,['X\0Y'])
 def test_config_digest_order_and_exact_lock_contents(self):
  base={'profile':'arduino:avr:uno'}
  self.write('wiredroid.json',json.dumps({'boards':{'uno':{**base,'defines':['VALUE=1','VALUE=2']}}}));first=module.config_digest(self.root,'uno')
  self.write('wiredroid.json',json.dumps({'boards':{'uno':{**base,'defines':['VALUE=2','VALUE=1']}}}));self.assertNotEqual(module.config_digest(self.root,'uno'),first)
  self.write('wiredroid.json',json.dumps({'boards':{'uno':base}}));absent=module.config_digest(self.root,'uno')
  self.write('wiredroid-libraries.lock.json','{"version":1,"libraries":[]}');one=module.config_digest(self.root,'uno');self.assertNotEqual(one,absent)
  self.assertEqual(one,'0a9607135771c648fcb0482ec3fcdc0a2db2a577174b6bad8110694a31fea7c2')
  self.write('wiredroid-libraries.lock.json','{"version":1, "libraries":[]}');self.assertNotEqual(module.config_digest(self.root,'uno'),one)
  self.write('wiredroid-libraries.lock.json','{"version":2,"libraries":[]}');self.assertNotEqual(module.config_digest(self.root,'uno'),one)
 def test_bundled_firmware_metadata_matches_snapshots(self):
  for project in [module.ROOT/'examples/avr-blink',module.ROOT/'app/src/main/assets/wiredroid/examples/avr-blink']:
   stamp=json.loads((project/'.wiredroid/firmware/uno.json').read_text());self.assertEqual(stamp['sourceDigest'],module.source_digest(project));self.assertEqual(stamp['configDigest'],module.config_digest(project,'uno'));self.assertEqual(stamp['hexSha256'],hashlib.sha256((project/'.wiredroid/firmware/uno.hex').read_bytes()).hexdigest())
if __name__=='__main__':unittest.main()
