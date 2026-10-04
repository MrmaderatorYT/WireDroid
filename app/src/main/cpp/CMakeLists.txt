cmake_minimum_required(VERSION 3.22.1)
project("native_compiler" C)

# ── TCC target architecture detection ──────────────────────────────
# TCC uses its own #defines for target selection.
# We map Android ABI to the correct TCC_TARGET_xxx define.
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(TCC_TARGET_DEF "TCC_TARGET_ARM64")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(TCC_TARGET_DEF "TCC_TARGET_ARM")
    set(TCC_ARM_EXTRAS "-DTCC_ARM_EABI=1" "-DTCC_ARM_VFP=1" "-DTCC_ARM_HARDFLOAT=1")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(TCC_TARGET_DEF "TCC_TARGET_X86_64")
elseif(ANDROID_ABI STREQUAL "x86")
    set(TCC_TARGET_DEF "TCC_TARGET_I386")
else()
    message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
endif()

# ── Build libtcc as a static library ───────────────────────────────
# TCC supports ONE_SOURCE mode: libtcc.c #includes all other .c files
add_library(tcc STATIC tcc/libtcc.c)
target_compile_definitions(tcc PRIVATE
    ONE_SOURCE=1
    ${TCC_TARGET_DEF}=1
    ${TCC_ARM_EXTRAS}
    CONFIG_TCC_ASM=1
    CONFIG_TCC_BACKTRACE=0
    CONFIG_TCC_BCHECK=0
    TCC_TARGET_UNIX=1
    TARGETOS_Linux=1
    TARGETOS_ANDROID=1
    CONFIG_TCC_SEMLOCK=1
)
target_include_directories(tcc PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/tcc
)
# Suppress warnings from TCC source (it's third-party code)
target_compile_options(tcc PRIVATE
    -w
    -fPIC
    -O2
)

# ── Build the JNI wrapper shared library ───────────────────────────
add_library(native_compiler SHARED native_compiler_jni.c)
target_include_directories(native_compiler PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/tcc
)
target_link_libraries(native_compiler
    tcc
    log
    dl
)
target_compile_options(native_compiler PRIVATE
    -Wall
    -O2
)
# 16 KB page size alignment for Android 15+ (API 35)
target_link_options(native_compiler PRIVATE -Wl,-z,max-page-size=16384)

# Minimal JLI bridge for the downloaded OpenJDK Mobile runtime. It is kept in a
# separate library so loading the normal native compiler never touches HotSpot.
add_library(java_se_launcher SHARED java_se_launcher.c)
target_link_libraries(java_se_launcher log dl)
target_compile_options(java_se_launcher PRIVATE -Wall -O2)
target_link_options(java_se_launcher PRIVATE -Wl,-z,max-page-size=16384)
