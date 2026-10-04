#!/usr/bin/env python3
"""Rebuild the bundled ARM64 C/C++ sysroot from Android NDK r28c."""
from pathlib import Path
import argparse, shutil, zipfile
p=argparse.ArgumentParser();p.add_argument('--ndk',type=Path,required=True);a=p.parse_args()
project=Path(__file__).resolve().parents[2];assets=project/'app/src/main/assets/wiredroid'
if '28.2.13676358' not in (a.ndk/'source.properties').read_text():raise SystemExit('Use NDK 28.2.13676358 (r28c) for the pinned embedded SDK')
chain=next((a.ndk/'toolchains/llvm/prebuilt').iterdir());resource=next((chain/'lib/clang').iterdir());sysroot=chain/'sysroot'
items={}
for source,prefix in [(sysroot/'usr/include','usr/include'),(resource/'include','clang/include')]:
 for file in sorted(source.rglob('*')):
  if file.is_file():items[prefix+'/'+file.relative_to(source).as_posix()]=file
lib=sysroot/'usr/lib/aarch64-linux-android/26'
for file in sorted(lib.iterdir()):
 if file.suffix=='.so' or file.name in ['crtbegin_dynamic.o','crtend_android.o']:items['usr/lib/'+file.name]=file
items['usr/lib/libclang_rt.builtins-aarch64-android.a']=resource/'lib/linux/libclang_rt.builtins-aarch64-android.a'
items['usr/lib/libunwind.a']=resource/'lib/linux/aarch64/libunwind.a'
with zipfile.ZipFile(assets/'sdk.zip','w',zipfile.ZIP_DEFLATED) as archive:
 for name,file in sorted(items.items()):archive.write(file,name)
jni=project/'app/src/main/jniLibs/arm64-v8a';jni.mkdir(parents=True,exist_ok=True)
shutil.copy2(sysroot/'usr/lib/aarch64-linux-android/libc++_shared.so',jni/'libc++_shared.so')
for name in ['NOTICE','NOTICE.toolchain']:shutil.copy2(a.ndk/name,assets/('NDK-'+name))
print('Packaged',len(items),'ARM64 SDK files; rebuild the APK with Gradle.')
