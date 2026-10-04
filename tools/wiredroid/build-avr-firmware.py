#!/usr/bin/env python3
"""Local Arduino AVR C/C++ build using a supplied official core/toolchain (no remote build)."""
import argparse,fnmatch,hashlib,json,pathlib,re,subprocess,sys,tempfile
ROOT=pathlib.Path(__file__).resolve().parents[2]
SOURCE_EXT={'.c','.cpp','.cc','.cxx','.S'}
DIGEST_EXT={'.cpp','.cc','.cxx','.c','.h','.hpp','.hh','.hxx','.ino','.s'}
INCLUDE=re.compile(r'^\s*#\s*include\s*[<"]([^>"]+)[>"]',re.M)
EXCLUDE={'build','examples','test','tests'}
def contained(root,value):
 p=value.resolve()
 if not p.is_relative_to(root.resolve()):raise ValueError('Файл поза проєктом: '+str(value))
 return p

def read_json(file):return json.loads(file.read_text(encoding='utf8')) if file.is_file() else {}
def files(root,directory):
 if not directory.is_dir():return []
 out=[]
 for p in sorted(directory.rglob('*')):
  contained(root,p)
  relative=p.relative_to(directory)
  if any(x.startswith('.') or x in EXCLUDE for x in relative.parts):continue
  if p.is_file():out.append(p)
 if len(out)>4096:raise ValueError('Проєкт перевищує 4096 файлів')
 return out

def glob(pattern,path):
 regex='';i=0
 while i<len(pattern):
  c=pattern[i]
  if c=='*':
   if i+1<len(pattern) and pattern[i+1]=='*':
    i+=1
    if i+1<len(pattern) and pattern[i+1]=='/':i+=1;regex+='(?:.*/)?'
    else:regex+='.*'
   else:regex+='[^/]*'
  elif c=='?':regex+='[^/]'
  else:regex+=re.escape(c)
  i+=1
 return re.fullmatch(regex,path) is not None

def safe_patterns(values):
 if not isinstance(values,list) or len(values)>256:raise ValueError('Некоректний список шляхів')
 for value in values:
  if not isinstance(value,str) or not value or value.startswith('/') or '\\' in value or ':' in value or '..' in value.split('/'):raise ValueError('Некоректний шлях '+str(value))
 return values

def properties(file):
 result={}
 if file.is_file():
  for line in file.read_text(encoding='utf8').splitlines():
   if '=' in line and not line.lstrip().startswith('#'):
    key,value=line.split('=',1);result[key.strip()]=value.strip()
 return result

def tree_hash(root):
 digest=hashlib.sha256()
 def walk(folder):
  for path in sorted(folder.iterdir(),key=lambda p:p.name):
   if path.is_symlink():raise ValueError('Символічне посилання в бібліотеці')
   if path.is_dir():walk(path)
   else:digest.update(path.relative_to(root).as_posix().encode());digest.update(b'\0');digest.update(path.read_bytes());digest.update(b'\0')
 walk(root);return digest.hexdigest()

def source_digest(project):
 """FirmwareArtifacts contract: relative UTF-8 path NUL exact bytes NUL, all sources.

 Java String ordering uses UTF-16 code units; use the same ordering for unusual
 non-BMP filenames too. Build and hidden entries never affect the snapshot.
 """
 root=project.resolve();paths=[];visited=set()
 def walk(folder,depth):
  if depth>32:raise ValueError('Забагато вкладених папок')
  canonical=contained(root,folder)
  if canonical in visited:return
  visited.add(canonical)
  for path in canonical.iterdir():
   if path.name.startswith('.') or path.name=='build':continue
   candidate=contained(root,path)
   if candidate.is_dir():walk(candidate,depth+1)
   elif candidate.is_file() and path.suffix.lower() in DIGEST_EXT:
    paths.append(candidate)
    if len(paths)>4096:raise ValueError('Забагато вихідних файлів')
 walk(root,0);digest=hashlib.sha256();total=0
 for path in sorted(paths,key=lambda p:p.relative_to(root).as_posix().encode('utf-16-be')):
  if path.stat().st_size>2*1024*1024:raise ValueError('Вихідні файли перевищують ліміт перевірки прошивки')
  with path.open('rb') as source:data=source.read(2*1024*1024+1)
  total+=len(data)
  if len(data)>2*1024*1024 or total>16*1024*1024:raise ValueError('Вихідні файли перевищують ліміт перевірки прошивки')
  digest.update(path.relative_to(root).as_posix().encode('utf8'));digest.update(b'\0');digest.update(data);digest.update(b'\0')
 return digest.hexdigest()

def config_digest(project,node,resolved_profile=None):
 """Versioned NUL-delimited UTF-8 FirmwareArtifacts.configDigest contract."""
 config=read_json(contained(project,project/'wiredroid.json'));target={**config.get('build',{}),**config.get('boards',{}).get(node,{})}
 digest=hashlib.sha256()
 def token(value):
  text=str(value)
  if '\0' in text:raise ValueError('Некоректне значення налаштувань збірки')
  digest.update(text.encode('utf8'));digest.update(b'\0')
 token('wiredroid-config-v1')
 for name,value in [('profile',target.get('profile') or resolved_profile or ''),('coreVersion',target.get('coreVersion','1.8.6')),('cppStandard',target.get('cppStandard','gnu++11'))]:token(name);token(value)
 for name,values in [(name,target.get(name,[])) for name in ('defines','sources','exclude','includePaths')]+[('libraries',config.get('libraries',[]))]:
  token(name);token(len(values))
  for value in values:token(value)
 token('libraryLock');lock=contained(project,project/'wiredroid-libraries.lock.json')
 if lock.is_file():
  if lock.stat().st_size>128*1024:raise ValueError('Lock бібліотек перевищує 128 KiB')
  token('sha256');token(hashlib.sha256(lock.read_bytes()).hexdigest())
 else:token('absent')
 return digest.hexdigest()

def satisfies(version,expression):
 expression=expression.strip()
 if not expression:return True
 if expression.startswith('(') and expression.endswith(')'):expression=expression[1:-1].strip()
 if '||' in expression:return any(satisfies(version,p) for p in expression.split('||'))
 if '&&' in expression:return all(satisfies(version,p) for p in expression.split('&&'))
 if ',' in expression:return all(satisfies(version,p) for p in expression.split(','))
 m=re.fullmatch(r'([<>]=?|!?=)?\s*(\d+(?:\.\d+){0,3}(?:[-+][A-Za-z0-9.-]+)?)',expression)
 if not m:raise ValueError('Непідтримуване обмеження версії: '+expression)
 def key(s):
  numeric,sep,pre=s.partition('-');numeric=numeric.split('+')[0];values=tuple(int(p) for p in numeric.split('.'));return values+(0,)*(4-len(values)),not bool(sep),pre
 a,b=key(version),key(m[2]);return {'=':a==b,'!=':a!=b,'>':a>b,'>=':a>=b,'<':a<b,'<=':a<=b}[m[1] or '=']

def plan(project,entry,node,core):
 config=read_json(contained(project,project/'wiredroid.json'));target={**config.get('build',{}),**config.get('boards',{}).get(node,{})}
 profile=target.get('profile','')
 if not profile:
  scene=read_json(project/'circuit.wire.json');part=next((n.get('part') for n in scene.get('nodes',[]) if n.get('id')==node),None)
  registry=read_json(ROOT/'app/src/main/assets/wiredroid/boards.json');profile=next((b['profile'] for b in registry['boards'] if b['catalogId']==part),'')
 if not profile:raise ValueError('Оберіть profile для '+node+' у wiredroid.json')
 registry=read_json(ROOT/'app/src/main/assets/wiredroid/boards.json');board=next((p for p in registry['targets'] if p['id']==profile),None)
 if not board or board['architecture']!='avr':raise ValueError('Локальна збірка підтримує підтверджені AVR-профілі')
 if target.get('coreVersion','1.8.6')!=board['core']['version']:raise ValueError('Потрібен Arduino AVR core '+board['core']['version'])
 for name in ('sources','exclude','includePaths'):safe_patterns(target.get(name,[]))
 defines=target.get('defines',[])
 if any(not re.fullmatch(r'[A-Za-z_][A-Za-z0-9_]*(=[A-Za-z0-9_.+-]{1,80})?',d) for d in defines):raise ValueError('Некоректний define')
 includes=[p for p in [project/'include',project/'src',entry.parent] if p.is_dir()]
 for path in target.get('includePaths',[]):
  p=contained(project,project/path)
  if not p.is_dir() or '*' in path or '?' in path:raise ValueError('Папка заголовків відсутня: '+path)
  includes.append(p)
 sources={entry};candidates=set(files(project,project/'src')+files(project,entry.parent))
 scene=read_json(project/'circuit.wire.json');other_nodes={n['id'] for n in scene.get('nodes',[])}-{node}
 for p in sorted(candidates):
  if p.suffix not in SOURCE_EXT:continue
  rel=p.relative_to(project).as_posix()
  if rel.startswith(('libraries/','lib/')) or rel.startswith('src/boards/') and not rel.startswith('src/boards/'+node+'/'):continue
  if any(glob(v,rel) for v in target.get('exclude',[])):continue
  if target.get('sources') and not any(glob(v,rel) for v in target['sources']):continue
  if p.stem in other_nodes or p!=entry and re.search(r'\bvoid\s+(setup|loop)\s*\(',p.read_text(encoding='utf8')):continue
  sources.add(p)
 libs={}
 for location,local in [(project/'libraries',True),(project/'lib',True),(core/'libraries',False),(ROOT/'app/src/main/assets/wiredroid/runtime/libraries',False)]:
  if not location.is_dir():continue
  for lib in sorted(location.iterdir()):
   if not lib.is_dir() or lib.name.startswith('.'):continue
   if local:contained(project,lib)
   meta=properties(lib/'library.properties');name=meta.get('name',lib.name)
   for key in (lib.name,name):libs.setdefault(key,(lib,meta,local))
 lock=read_json(contained(project,project/'wiredroid-libraries.lock.json'))
 if lock:
  if lock.get('version')!=1:raise ValueError('Непідтримуваний lock бібліотек')
  for pin in lock.get('libraries',[]):
   folder=pin['folder']
   if not re.fullmatch('[A-Za-z0-9_-]{1,100}',folder):raise ValueError('Некоректний lock шлях')
   lib=contained(project,project/'libraries'/folder)
   if not lib.is_dir() or tree_hash(lib)!=pin['treeSha256']:raise ValueError('Бібліотека змінена: '+pin['name'])
 wanted=set(config.get('libraries',[]));selected=set();scanned=set();queue=list(sources);direct=set()
 while wanted-selected or queue:
  for name in sorted(wanted-selected):
   if name not in libs:raise ValueError('Відсутня бібліотека: '+name)
   library,meta,local=libs[name];aliases={k for k,v in libs.items() if v[0]==library};selected|=aliases
   arch=meta.get('architectures','*').split(',')
   if not any(a.strip() in ('*','avr') for a in arch):raise ValueError('Бібліотека '+name+' не декларує архітектуру AVR')
   base=library/'src' if (library/'src').is_dir() else library;includes.append(base)
   if base==library and (library/'utility').is_dir():includes.append(library/'utility')
   for p in files(project if local else library,base):
    if p.suffix in SOURCE_EXT:sources.add(p)
    if p.suffix in SOURCE_EXT|{'.h','.hpp','.hh'}:queue.append(p)
   for dep in re.split(r',\s*(?![^()]*\))',meta.get('depends','')):
    dep=dep.strip()
    if not dep:continue
    m=re.fullmatch(r'(.+?)(?:\s*\((.*)\))?',dep);depname=m[1].strip();wanted.add(depname)
    if depname in libs and m[2] and not satisfies(libs[depname][1].get('version',''),m[2]):raise ValueError('Версія залежності не відповідає '+dep)
  while queue:
   file=queue.pop()
   if file in scanned:continue
   scanned.add(file)
   for header in INCLUDE.findall(file.read_text(encoding='utf8')):
    resolved=next((p for p in [file.parent/header]+[i/header for i in includes] if p.is_file()),None)
    if resolved:
     resolved=resolved.resolve()
     if not any(resolved.is_relative_to(base.resolve()) for base in [project,core,ROOT/'app/src/main/assets/wiredroid/runtime/libraries']):raise ValueError('Заголовок поза каталогами збірки')
     queue.append(resolved)
     if resolved.suffix in SOURCE_EXT:direct.add(resolved)
    else:
     match=next((name for name,(lib,meta,local) in libs.items() if (lib/'src'/header).is_file() or (lib/header).is_file()),None)
     if match:wanted.add(match)
   if len(scanned)>4096:raise ValueError('Забагато залежностей')
 sources-=direct-{entry}
 if len(sources)>256:raise ValueError('До 256 одиниць компіляції')
 return board,target,sorted(sources),list(dict.fromkeys(includes)),sorted(selected)

def build(args):
 project=args.project.resolve();entry=contained(project,args.entry if args.entry.is_absolute() else project/args.entry)
 core=args.core.resolve();toolchain=args.toolchain.resolve();output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
 if not entry.is_file():raise ValueError('Програма не знайдена')
 board,target,sources,includes,libraries=plan(project,entry,args.node,core)
 source_snapshot=source_digest(project)
 config_snapshot=config_digest(project,args.node,board['id'])
 core_dir=core/'cores'/board['core']['name'];variant=core/'variants'/board['core']['variant']
 if not core_dir.is_dir() or not variant.is_dir() or 'version=1.8.6' not in (core/'platform.txt').read_text():raise ValueError('Потрібна оригінальна структура Arduino AVR core1.8.6')
 bins=toolchain/'bin';compiler=bins/'avr-g++';version=subprocess.check_output([str(compiler),'--version'],text=True).splitlines()[0]
 if '7.3.0' not in version:raise ValueError('Потрібен avr-gcc7.3.0 для відтворюваної збірки')
 base=['-mmcu='+board['mcu'],'-DF_CPU='+str(board['clockHz'])+'L','-DARDUINO=10819','-DARDUINO_'+board['core']['board'],'-DARDUINO_ARCH_AVR']+['-D'+d for d in target.get('defines',[])]+['-I'+str(p) for p in [core_dir,variant]+includes]
 commands=[];objects=[];core_objects=[];all_sources=sources+sorted(p for p in core_dir.iterdir() if p.suffix in SOURCE_EXT)
 standard=target.get('cppStandard','gnu++11')
 if standard not in ('gnu++11','gnu++14','gnu++17'):raise ValueError('Непідтримуваний AVR C++ standard')
 def run(command):
  commands.append([str(c) for c in command]);process=subprocess.run(commands[-1],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
  if process.returncode:raise RuntimeError(process.stdout.strip())
  if process.stdout.strip():print(process.stdout.strip())
 for index,source in enumerate(all_sources):
  object_file=output/('unit-'+str(index)+'.o');is_cpp=source.suffix in ('.cpp','.cc','.cxx','.ino');assembly=source.suffix=='.S'
  flags=['-c','-g','-Os','-ffunction-sections','-fdata-sections','-flto']
  if assembly:flags+=['-x','assembler-with-cpp']
  elif is_cpp:flags+=['-std='+standard,'-fpermissive','-fno-exceptions','-fno-threadsafe-statics','-Wno-error=narrowing']
  else:flags+=['-std=gnu11','-fno-fat-lto-objects']
  if source==entry and is_cpp:flags+=['-include','Arduino.h','-x','c++']
  run([bins/('avr-g++' if is_cpp else 'avr-gcc')]+flags+base+[source,'-o',object_file])
  (core_objects if source.parent==core_dir else objects).append(object_file)
 archive=output/'core.a';archive.unlink(missing_ok=True);run([bins/'avr-gcc-ar','rcs',archive]+core_objects)
 elf=output/(args.node+'.elf');hexfile=output/(args.node+'.hex')
 run([bins/'avr-gcc','-Os','-g','-flto','-fuse-linker-plugin','-Wl,--gc-sections','-mmcu='+board['mcu'],'-o',elf]+objects+[archive,'-L'+str(output),'-lm'])
 run([bins/'avr-objcopy','-O','ihex','-R','.eeprom',elf,hexfile]);size=subprocess.check_output([str(bins/'avr-size'),'-A',str(elf)],text=True)
 sections={m[1]:int(m[2]) for m in re.finditer(r'^(\.\S+)\s+(\d+)',size,re.M)};flash=sum(sections.get(s,0) for s in ('.text','.data','.bootloader'));ram=sum(sections.get(s,0) for s in ('.data','.bss','.noinit'))
 if flash>board['upload']['maxFlash'] or ram>board['ramBytes']:hexfile.unlink(missing_ok=True);raise ValueError('Прошивка перевищує flash або RAM вибраної плати')
 if source_digest(project)!=source_snapshot:
  hexfile.unlink(missing_ok=True);raise ValueError('Вихідні файли змінилися під час збірки. Повторіть збірку')
 if config_digest(project,args.node,board['id'])!=config_snapshot:
  hexfile.unlink(missing_ok=True);raise ValueError('Налаштування збірки змінилися під час компіляції. Повторіть збірку')
 metadata={'version':1,'profile':board['id'],'node':args.node,'compiler':version,'coreVersion':board['core']['version'],'sourceDigest':source_snapshot,'configDigest':config_snapshot,'origin':'local-build','sourceRelationship':'Compiled locally from this source/configuration snapshot; digests checked before and after compilation','variant':board['core']['variant'],'mcu':board['mcu'],'clockHz':board['clockHz'],'flashBytes':flash,'ramBytes':ram,'hexSha256':hashlib.sha256(hexfile.read_bytes()).hexdigest(),'sources':[str(p.relative_to(project)) if p.is_relative_to(project) else str(p) for p in sources],'libraries':libraries,'defines':target.get('defines',[]),'commands':commands,'limitations':['External local AVR compiler required; not an Android-native toolchain.','AVR core default uses C++11 without exceptions and has no general STL runtime.','Imported .ino requires explicit function prototypes.']}
 (output/(args.node+'.build.json')).write_text(json.dumps(metadata,ensure_ascii=False,indent=2)+'\n');print(f'PASS {board["id"]}: flash {flash}/{board["upload"]["maxFlash"]}; RAM {ram}/{board["ramBytes"]}; {hexfile}')
 # Object files are temporary; preserve the ELF/HEX/manifest only.
 for object_file in objects+core_objects:object_file.unlink(missing_ok=True)
 archive.unlink(missing_ok=True)
 return metadata

def main():
 parser=argparse.ArgumentParser(description=__doc__)
 for name in ('project','entry','toolchain','core','output'):parser.add_argument('--'+name,required=True,type=pathlib.Path)
 parser.add_argument('--node',required=True);args=parser.parse_args()
 if not re.fullmatch('[A-Za-z0-9_-]{1,64}',args.node):parser.error('Invalid node ID')
 try:build(args)
 except (OSError,ValueError,RuntimeError,KeyError,subprocess.CalledProcessError) as error:print(str(error),file=sys.stderr);return 1
 return 0
if __name__=='__main__':sys.exit(main())
