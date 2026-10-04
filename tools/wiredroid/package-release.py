#!/usr/bin/env python3
"""Validate and package a standalone WireDroid source release; no network or source deletion."""
import argparse, collections, hashlib, importlib.util, json, os, pathlib, re, tempfile, zipfile

DIRECTORIES={'build','.gradle','.cxx','.idea','.git','node_modules','__pycache__','.bug-hunter','.pytest_cache'}
PRIVATE_SUFFIXES={'.jks','.keystore','.p12','.pfx','.key','.pk8','.p8'}
PRIVATE_NAMES={'keystore.properties','signing.properties','key.properties','id_rsa','id_dsa','id_ecdsa','id_ed25519'}
APK_NAME='WireDroid/artifacts/WireDroid-debug.apk'
TEST_APK_NAME='WireDroid/artifacts/WireDroid-androidTest.apk'
EXAMPLE='assets/wiredroid/examples/avr-blink/'
VENDOR=('wiredroid/vendor/avr8js.js','wiredroid/vendor/AVR8JS-LICENSE','wiredroid/licenses/usb-serial-for-android-LICENSE.txt')

def sha(file):
 digest=hashlib.sha256()
 with file.open('rb') as stream:
  for block in iter(lambda:stream.read(1024*1024),b''):digest.update(block)
 return digest.hexdigest()

def read_json(file):return json.loads(file.read_text(encoding='utf8'))
def require(condition,message):
 if not condition:raise ValueError(message)

def digest_module(source):
 file=source/'tools/wiredroid/build-avr-firmware.py'
 require(file.is_file(),'Missing build-avr-firmware.py digest implementation')
 spec=importlib.util.spec_from_file_location('wiredroid_avr_release_digests',file)
 module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def validate_hex(raw):
 text=raw.decode('ascii');base=0;ended=False;used=False;addresses={}
 for line in text.strip().splitlines():
  if not line.strip():continue
  require(not ended,'HEX data after EOF');require(re.fullmatch(r':[0-9a-fA-F]+',line) is not None and len(line)%2==1,'Malformed HEX record')
  data=bytes.fromhex(line[1:]);require(len(data)>=5 and len(data)==data[0]+5 and sum(data)%256==0,'HEX count/checksum mismatch')
  count,address,kind=data[0],(data[1]<<8)|data[2],data[3];payload=data[4:-1]
  if kind==0:
   start=base+address;require(start+count<=32768,'HEX exceeds ATmega328P flash')
   for i,value in enumerate(payload):require(start+i not in addresses or addresses[start+i]==value,'Conflicting HEX address');addresses[start+i]=value
   used=used or count>0
  elif kind==1:require(count==0 and address==0,'Malformed HEX EOF');ended=True
  elif kind in (2,4):require(count==2 and address==0,'Malformed HEX address');base=int.from_bytes(payload,'big')*(16 if kind==2 else 65536)
  elif kind in (3,5):require(count==4 and address==0,'Malformed HEX start address')
  else:raise ValueError('Unsupported HEX record')
 require(ended and used,'Empty/incomplete HEX')

def validate_apk_assets(source,apk):
 module=digest_module(source)
 with zipfile.ZipFile(apk) as archive:
  names=archive.namelist();require(len(names)==len(set(names)),'Duplicate APK ZIP entries')
  require(archive.testzip() is None,'APK CRC validation failed')
  for name in VENDOR:
   member='assets/'+name;require(member in names,'Missing APK asset: '+member)
   original=source/'app/src/main/assets'/name;require(original.is_file(),'Missing source asset: '+name)
   require(archive.read(member)==original.read_bytes(),'APK/source vendor asset differs: '+name)
  for name in ('.wiredroid/firmware/uno.hex','.wiredroid/firmware/uno.json','wiredroid.json','sketches/uno.cpp'):
   require(EXAMPLE+name in names,'Missing APK AVR example asset: '+name)
  with tempfile.TemporaryDirectory(prefix='wiredroid-apk-example-') as temporary:
   project=pathlib.Path(temporary);total=0;count=0
   for info in archive.infolist():
    if not info.filename.startswith(EXAMPLE) or info.is_dir():continue
    relative=pathlib.PurePosixPath(info.filename[len(EXAMPLE):]);require(not relative.is_absolute() and '..' not in relative.parts and '\\' not in str(relative),'Unsafe APK example path')
    total+=info.file_size;count+=1;require(count<=4096 and total<=20*1024*1024 and info.file_size<=2*1024*1024,'APK example exceeds source limits')
    file=project.joinpath(*relative.parts);file.parent.mkdir(parents=True,exist_ok=True);file.write_bytes(archive.read(info))
   metadata=read_json(project/'.wiredroid/firmware/uno.json');target=read_json(project/'wiredroid.json')['boards']['uno']
   require(metadata.get('version')==1,'Unsupported AVR firmware metadata version')
   require(metadata.get('profile')==target.get('profile')=='arduino:avr:uno','AVR firmware target mismatch')
   require(metadata.get('coreVersion')==target.get('coreVersion','1.8.6')=='1.8.6','AVR firmware core mismatch')
   hexfile=project/'.wiredroid/firmware/uno.hex';validate_hex(hexfile.read_bytes())
   require(metadata.get('hexSha256')==sha(hexfile),'AVR firmware HEX SHA mismatch')
   require(metadata.get('sourceDigest')==module.source_digest(project),'APK firmware source digest is stale')
   require(metadata.get('configDigest')==module.config_digest(project,'uno'),'APK firmware configuration digest is stale')
   current=source/'app/src/main/assets/wiredroid/examples/avr-blink'
   require(metadata['sourceDigest']==module.source_digest(current),'APK/current source example differs')
   require(metadata['configDigest']==module.config_digest(current,'uno'),'APK/current example build settings differ')
   for relative in ('.wiredroid/firmware/uno.hex','.wiredroid/firmware/uno.json'):
    require((current/relative).read_bytes()==(project/relative).read_bytes(),'APK/current example metadata differs: '+relative)
   return {'apkCrcVerified':True,'apkEntries':len(names),'firmware':{key:metadata[key] for key in ('profile','coreVersion','hexSha256','sourceDigest','configDigest')}}

def validate_inputs(source,apk,metadata_file,release_file):
 require(source.is_dir(),'Source directory missing');require(apk.is_file(),'APK missing')
 gradle=source/'app/build.gradle'
 if not gradle.is_file():gradle=source/'app/build.gradle.kts'
 text=gradle.read_text(encoding='utf8')
 def value(name,pattern):
  values=re.findall(r'^\s*'+name+r'\s*(?:=\s*)?'+pattern,text,re.M);require(len(values)==1,'Expected one literal '+name+' in app Gradle');return values[0]
 version={'versionCode':int(value('versionCode',r'(\d+)\b')),'versionName':value('versionName',r'[\'"]([^\'"]+)[\'"]')}
 app_id=value('applicationId',r'[\'"]([^\'"]+)[\'"]')
 metadata=read_json(metadata_file);release=read_json(release_file);elements=metadata.get('elements',[])
 matching=[element for element in elements if element.get('outputFile')==apk.name]
 element=matching[0] if len(matching)==1 else elements[0] if len(elements)==1 else None
 require(element is not None,'Ambiguous APK build output metadata')
 require(metadata.get('artifactType',{}).get('type')=='APK','Build metadata is not APK')
 require(metadata.get('applicationId')==app_id,'APK build applicationId mismatch')
 for key,expected in version.items():require(element.get(key)==expected and release.get(key)==expected,'Version mismatch: '+key)
 if 'applicationId' in release:require(release['applicationId']==app_id,'Release applicationId mismatch')
 checksum=sha(apk);require(release.get('apkSha256')==checksum,'Release APK SHA mismatch')
 output_name=element.get('outputFile','');require(isinstance(output_name,str) and pathlib.Path(output_name).name==output_name,'Unsafe build output filename')
 built=metadata_file.parent/output_name;require(built.is_file(),'Referenced build APK missing')
 require(built.resolve()==apk.resolve() or sha(built)==checksum,'Supplied APK differs from build output')
 checks=validate_apk_assets(source,apk)
 return {**version,'applicationId':app_id,'variantName':metadata.get('variantName'),'apkSha256':checksum,'apkBytes':apk.stat().st_size,**checks}

def excluded(file,relative):
 if any(part in DIRECTORIES for part in relative.parts):return 'intermediate-directory'
 name=file.name.lower()
 if name in {'local.properties','.ds_store','ds_store'}:return 'local-machine-file'
 if name in PRIVATE_NAMES or file.suffix.lower() in PRIVATE_SUFFIXES:return 'private-signing-key'
 if file.suffix.lower()=='.pem':
  with file.open('rb') as stream:header=stream.read(65536)
  if re.search(rb'-----BEGIN [A-Z ]*PRIVATE KEY-----',header):return 'private-signing-key'
 if file.suffix.lower()=='.apk':return 'apk-replaced-by-validated-artifact'
 if relative.parts and relative.parts[0]=='artifacts' and file.suffix.lower()=='.zip':return 'previous-source-archive'
 return None

def collect_files(source,omit=()):
 selected=[];counts=collections.Counter();omit={p.resolve() for p in omit}
 def walk(folder,ancestors):
  canonical=folder.resolve();require(canonical.is_relative_to(source),'Source symlink escapes project: '+folder.relative_to(source).as_posix());require(canonical not in ancestors,'Source symlink cycle')
  for file in sorted(folder.iterdir()):
   relative=file.relative_to(source)
   if file.name in DIRECTORIES:counts['intermediate-directory']+=1;continue
   if file.resolve() in omit:counts['output-file']+=1;continue
   if file.is_dir():walk(file,ancestors|{canonical})
   elif file.is_file():
    require(file.resolve().is_relative_to(source),'Source file symlink escapes project: '+relative.as_posix())
    reason=excluded(file,relative)
    if reason:counts[reason]+=1;continue
    selected.append((relative.as_posix(),file))
 # Deliberately no blanket hidden-directory rule: .wiredroid/firmware is public.
 walk(source,set())
 return selected,counts

def make_release(source,output,apk,metadata_file,release_file,report_file=None,android_test_apk=None,check_only=False):
 source=source.resolve();output=output.resolve();report_file=(report_file or output.with_suffix(output.suffix+'.report.json')).resolve()
 checks=validate_inputs(source,apk,metadata_file,release_file)
 inputs={file.resolve() for file in (apk,metadata_file,release_file)}
 if android_test_apk:inputs.add(android_test_apk.resolve())
 require(output not in inputs and report_file not in inputs and report_file!=output,'Output would replace an input APK/metadata/report')
 require(not output.exists(),'Output archive already exists; choose a new output name')
 require(not report_file.exists(),'Output report already exists; choose a new report name')
 require(output.suffix.lower()=='.zip','Output must end with .zip')
 selected,counts=collect_files(source,(output,report_file));plan=[('WireDroid/'+name,file) for name,file in selected]
 require(all(name!=APK_NAME and name!=TEST_APK_NAME for name,_ in plan),'Unexpected source APK artifact')
 evidence={'WireDroid/artifacts/release-info.json':release_file,'WireDroid/artifacts/build-output-metadata.json':metadata_file}
 plan=[(name,file) for name,file in plan if name not in evidence];plan.extend(evidence.items());plan.append((APK_NAME,apk))
 if android_test_apk:
  require(android_test_apk.is_file(),'Instrumentation APK missing')
  with zipfile.ZipFile(android_test_apk) as test:require(test.testzip() is None,'Instrumentation APK CRC failed')
  test_hash=sha(android_test_apk);info=read_json(release_file)
  if 'androidTestApkSha256' in info:require(info['androidTestApkSha256']==test_hash,'Instrumentation APK release SHA mismatch')
  plan.append((TEST_APK_NAME,android_test_apk));checks['androidTestApkSha256']=test_hash
 names=[name for name,_ in plan];require(len(names)==len(set(names)),'Duplicate release entries')
 report={**checks,'root':'WireDroid/','entryCount':len(plan),'inputBytes':sum(file.stat().st_size for _,file in plan),'excludedCounts':dict(counts),'apkEntry':APK_NAME,'checkOnly':check_only}
 if check_only:return report
 snapshot={file:(file.stat().st_size,file.stat().st_mtime_ns,file.stat().st_ino) for _,file in plan}
 output.parent.mkdir(parents=True,exist_ok=True)
 descriptor,temporary=tempfile.mkstemp(prefix='.'+output.name+'.',suffix='.partial',dir=output.parent);os.close(descriptor);temporary=pathlib.Path(temporary)
 try:
  with zipfile.ZipFile(temporary,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=True) as archive:
   for name,file in sorted(plan):archive.write(file,name,compress_type=zipfile.ZIP_STORED if file.suffix.lower() in {'.apk','.zip','.xz','.png','.jpg','.jpeg','.webp'} else zipfile.ZIP_DEFLATED)
  with zipfile.ZipFile(temporary) as archive:
   require(len(archive.infolist())==len(plan) and set(archive.namelist())==set(names),'Release ZIP file count mismatch');require(archive.testzip() is None,'Release ZIP CRC failed')
   with archive.open(APK_NAME) as entry:
    digest=hashlib.sha256()
    for block in iter(lambda:entry.read(1024*1024),b''):digest.update(block)
    require(digest.hexdigest()==checks['apkSha256'],'Packaged APK SHA mismatch')
  for file,previous in snapshot.items():require(file.is_file() and (file.stat().st_size,file.stat().st_mtime_ns,file.stat().st_ino)==previous,'Source changed during packaging: '+file.name)
  require(not output.exists(),'Output appeared during packaging; refusing overwrite')
  report.update(zipCrcVerified=True,zipBytes=temporary.stat().st_size,zipSha256=sha(temporary),entries=sorted(names));os.replace(temporary,output)
  report_file.parent.mkdir(parents=True,exist_ok=True);report_file.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
  return report
 finally:temporary.unlink(missing_ok=True)

def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--source',type=pathlib.Path,required=True);parser.add_argument('--output',type=pathlib.Path,required=True)
 for name in ('apk','metadata','release-info','report','android-test-apk'):parser.add_argument('--'+name,type=pathlib.Path)
 parser.add_argument('--check-only',action='store_true');args=parser.parse_args();source=args.source.resolve()
 try:
  report=make_release(source,args.output,args.apk or source/'artifacts/WireDroid-debug.apk',args.metadata or source/'app/build/outputs/apk/debug/output-metadata.json',args.release_info or source/'artifacts/release-info.json',args.report,args.android_test_apk,args.check_only)
  print(json.dumps(report if args.check_only else {key:report[key] for key in ('versionName','versionCode','entryCount','zipBytes','zipSha256','zipCrcVerified','apkSha256')},ensure_ascii=False,indent=2));return 0
 except (OSError,ValueError,KeyError,zipfile.BadZipFile,UnicodeError) as error:parser.exit(1,str(error)+'\n')

if __name__=='__main__':raise SystemExit(main())
