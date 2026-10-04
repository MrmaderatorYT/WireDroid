#!/usr/bin/env python3
import importlib.util,json,pathlib,shutil,tempfile,unittest,zipfile
HERE=pathlib.Path(__file__).resolve().parent
def load(name,path):
 spec=importlib.util.spec_from_file_location(name,path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
package=load('releasepack',HERE/'package-release.py')
digest=load('avrbuild',HERE/'build-avr-firmware.py')
class ReleaseTest(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory();self.root=pathlib.Path(self.tmp.name)/'project';self.root.mkdir();self.output=pathlib.Path(self.tmp.name)/'WireDroid.zip'
  self.write('app/build.gradle',"android {\n applicationId 'com.ccs.wiredroid'\n versionCode 1\n versionName '1.0.0'\n}")
  self.write('tools/wiredroid/build-avr-firmware.py',(HERE/'build-avr-firmware.py').read_text())
  self.example=self.root/'app/src/main/assets/wiredroid/examples/avr-blink'
  self.write('app/src/main/assets/wiredroid/examples/avr-blink/wiredroid.json',json.dumps({'boards':{'uno':{'profile':'arduino:avr:uno','coreVersion':'1.8.6','mode':'mcu'}}}))
  self.write('app/src/main/assets/wiredroid/examples/avr-blink/sketches/uno.cpp','void setup(){} void loop(){}\n')
  self.write('app/src/main/assets/wiredroid/examples/avr-blink/.wiredroid/firmware/uno.hex',':0400000001020304F2\n:00000001FF\n')
  stamp={'version':1,'profile':'arduino:avr:uno','coreVersion':'1.8.6','sourceDigest':digest.source_digest(self.example),'configDigest':digest.config_digest(self.example,'uno'),'hexSha256':package.sha(self.example/'.wiredroid/firmware/uno.hex')}
  self.write('app/src/main/assets/wiredroid/examples/avr-blink/.wiredroid/firmware/uno.json',json.dumps(stamp))
  for member in package.VENDOR:self.write('app/src/main/assets/'+member,'public fixture license/vendor')
  self.apk=self.root/'app/build/outputs/apk/debug/app-debug.apk';self.apk.parent.mkdir(parents=True,exist_ok=True)
  self.metadata=self.write('app/build/outputs/apk/debug/output-metadata.json',json.dumps({'artifactType':{'type':'APK'},'applicationId':'com.ccs.wiredroid','variantName':'debug','elements':[{'outputFile':'app-debug.apk','versionCode':1,'versionName':'1.0.0'}]}))
  self.release=self.root/'artifacts/release-info.json';self.rebuild_apk()
 def tearDown(self):self.tmp.cleanup()
 def write(self,path,text):
  file=self.root/path;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text,encoding='utf8');return file
 def rebuild_apk(self,omit=()):
  assets=self.root/'app/src/main/assets'
  with zipfile.ZipFile(self.apk,'w',zipfile.ZIP_DEFLATED) as archive:
   for file in assets.rglob('*'):
    if file.is_file():
     member='assets/'+file.relative_to(assets).as_posix()
     if member not in omit:archive.write(file,member)
  self.write('artifacts/release-info.json',json.dumps({'versionCode':1,'versionName':'1.0.0','apkSha256':package.sha(self.apk)}))
 def run_package(self,**kwargs):return package.make_release(self.root,self.output,self.apk,self.metadata,self.release,**kwargs)
 def test_preserves_hidden_firmware_locks_and_source_excludes_intermediates_keys(self):
  for path in ('app/build/temporary.bin','.gradle/cache','app/.cxx/cache','.idea/workspace.xml','.git/HEAD','tools/node_modules/pkg/file','tools/__pycache__/file.pyc','local.properties','.DS_Store','keystore.properties','signing/release.jks','signing/private.pem','artifacts/old.apk','artifacts/old.zip'):
   self.write(path,'-----BEGIN PRIVATE KEY-----' if path.endswith('.pem') else 'private/intermediate')
  self.write('tools/deps/package-lock.json','{}');self.write('docs/runtime.md','Public runtime docs');self.write('examples/test/.wiredroid/firmware/uno.hex','public hidden firmware')
  report=self.run_package()
  with zipfile.ZipFile(self.output) as archive:
   names=archive.namelist();self.assertIn('WireDroid/examples/test/.wiredroid/firmware/uno.hex',names);self.assertIn('WireDroid/tools/deps/package-lock.json',names);self.assertIn(package.APK_NAME,names);self.assertIn('WireDroid/app/src/main/assets/wiredroid/examples/avr-blink/.wiredroid/firmware/uno.json',names)
   self.assertFalse(any('/build/' in name or '/node_modules/' in name or name.endswith(('.jks','.pem','local.properties','old.apk','old.zip')) for name in names));self.assertIsNone(archive.testzip());self.assertEqual(len(names),report['entryCount'])
  self.assertTrue(report['zipCrcVerified']);self.assertEqual(package.sha(self.output),report['zipSha256']);self.assertEqual(report['apkSha256'],package.sha(self.apk))
  self.assertTrue(self.output.with_suffix('.zip.report.json').is_file());self.assertTrue((self.root/'signing/release.jks').is_file());self.assertTrue((self.root/'app/build/temporary.bin').is_file())
 def test_check_only_validates_without_creating_release(self):
  report=self.run_package(check_only=True);self.assertTrue(report['checkOnly']);self.assertFalse(self.output.exists());self.assertFalse(self.output.with_suffix('.zip.report.json').exists())
 def test_rejects_missing_hidden_firmware_asset_or_license(self):
  for name in (package.EXAMPLE+'.wiredroid/firmware/uno.hex','assets/wiredroid/vendor/AVR8JS-LICENSE'):
   self.rebuild_apk(omit={name})
   with self.assertRaisesRegex(ValueError,'Missing APK'):self.run_package(check_only=True)
 def test_rejects_versions_sha_and_stale_apk_configuration(self):
  original=json.loads(self.release.read_text());self.release.write_text(json.dumps({**original,'versionCode':37}))
  with self.assertRaisesRegex(ValueError,'Version mismatch'):self.run_package(check_only=True)
  self.release.write_text(json.dumps({**original,'apkSha256':'0'*64}))
  with self.assertRaisesRegex(ValueError,'SHA mismatch'):self.run_package(check_only=True)
  self.write('app/src/main/assets/wiredroid/examples/avr-blink/wiredroid.json',json.dumps({'boards':{'uno':{'profile':'arduino:avr:uno','defines':['PIN=12']}}}));self.rebuild_apk()
  with self.assertRaisesRegex(ValueError,'configuration digest is stale'):self.run_package(check_only=True)
 def test_rejects_changed_apk_source_and_symlink_escape(self):
  self.write('app/src/main/assets/wiredroid/examples/avr-blink/sketches/uno.cpp','int changed;');self.rebuild_apk()
  with self.assertRaisesRegex(ValueError,'source digest is stale'):self.run_package(check_only=True)
  outside=pathlib.Path(self.tmp.name)/'external.cpp';outside.write_text('outside');(self.root/'escape.cpp').symlink_to(outside)
  with self.assertRaisesRegex(ValueError,'symlink escapes'):package.collect_files(self.root)
 def test_rejects_overwrite_and_optional_instrumentation_apk_has_canonical_name(self):
  test=self.root/'app/build/outputs/apk/androidTest/app-test.apk';test.parent.mkdir(parents=True,exist_ok=True)
  with zipfile.ZipFile(test,'w') as archive:archive.writestr('AndroidManifest.xml','test')
  report=self.run_package(android_test_apk=test)
  with zipfile.ZipFile(self.output) as archive:self.assertIn(package.TEST_APK_NAME,archive.namelist())
  self.assertEqual(report['androidTestApkSha256'],package.sha(test))
  with self.assertRaisesRegex(ValueError,'already exists'):self.run_package()
if __name__=='__main__':unittest.main()
