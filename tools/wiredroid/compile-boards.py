#!/usr/bin/env python3
"""Verify generated GPIO headers with the real Arduino API and C++ standard library.
This validates host compilation, not vendor firmware or CPU emulation.
"""
from pathlib import Path
import argparse, subprocess, concurrent.futures
parser=argparse.ArgumentParser()
parser.add_argument('--ndk',type=Path,required=True)
parser.add_argument('--headers',type=Path,default=Path('app/build/wiredroid-board-headers'))
args=parser.parse_args()
project=Path(__file__).resolve().parents[2]
runtime=project/'app/src/main/assets/wiredroid/runtime'
toolchain=next((args.ndk/'toolchains/llvm/prebuilt').iterdir());clang=toolchain/'bin/clang++';lld=toolchain/'bin/ld.lld'
headers=args.headers.resolve();output=headers/'compiled';output.mkdir(exist_ok=True)
flags=['--target=aarch64-linux-android26','-std=c++17','-O1','-fPIC','-ffunction-sections','-fdata-sections','-I'+str(runtime),'-I'+str(headers)]
def command(argv):
    result=subprocess.run([str(v) for v in argv],capture_output=True,text=True,timeout=120)
    if result.returncode:raise RuntimeError(' '.join(map(str,argv))+'\n'+result.stdout+result.stderr)
sources=sorted(headers.glob('board-*.cpp'))
if not sources:raise SystemExit('No generated headers: run EmbeddedPinsTest first')
common=[]
for i,name in enumerate(['peripherals.cpp','conversions.cpp','api/Common.cpp','api/Print.cpp','api/Stream.cpp','api/String.cpp','api/IPAddress.cpp']):
    obj=output/f'common-{i}.o';command([clang,*flags,'-include',sources[0].with_suffix('.h'),'-c',runtime/name,'-o',obj]);common.append(obj)
lib=toolchain/'sysroot/usr/lib/aarch64-linux-android';crt=lib/'26'
unwind=next((toolchain/'lib/clang').glob('*/lib/linux/aarch64/libunwind.a'))
builtins=next((toolchain/'lib/clang').glob('*/lib/linux/libclang_rt.builtins-aarch64-android.a'))
def build(source):
    obj=output/(source.stem+'.o');rt=output/(source.stem+'-runtime.o');elf=output/(source.stem+'.elf')
    command([clang,*flags,'-c',source,'-o',obj])
    command([clang,*flags,'-include',source.with_suffix('.h'),'-c',runtime/'runtime.cpp','-o',rt])
    command([lld,'-flavor','gnu','--dynamic-linker','/system/bin/linker64','-pie','--eh-frame-hdr','--gc-sections','-o',elf,crt/'crtbegin_dynamic.o',obj,rt,*common,'-L'+str(crt),'-L'+str(lib),'-lc++_shared','-lc','-lm','-ldl',unwind,builtins,crt/'crtend_android.o'])
    return source.name
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    for count,_ in enumerate(pool.map(build,sources),1):
        if count%50==0:print(f'{count}/{len(sources)} board programs verified',flush=True)
print(f'PASS: {len(sources)} generated board GPIO profiles compiled and linked with Arduino API/STL as ARM64 Android ELF')
