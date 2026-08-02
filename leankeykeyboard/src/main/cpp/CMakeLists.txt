cmake_minimum_required(VERSION 3.22.1)

project(leankey_whisper C CXX)

# A debug apk builds its native code with CMAKE_BUILD_TYPE=Debug, which compiles ggml at -O0.
# That makes transcription tens of times slower and looks exactly like a hang, so the engine is
# always built optimized no matter which apk variant pulls it in.
set(CMAKE_C_FLAGS_DEBUG   "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)

# whisper.cpp / ggml build switches. Everything we don't need is turned off to keep
# the native library small and the CI build short.
set(BUILD_SHARED_LIBS       OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS     OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES  OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER    OFF CACHE BOOL "" FORCE)
set(WHISPER_CCACHE          OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS    OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE             OFF CACHE BOOL "" FORCE)
set(GGML_ALL_WARNINGS       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE             OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP             OFF CACHE BOOL "" FORCE) # the ndk sysroot ships no libomp
set(GGML_LLAMAFILE          OFF CACHE BOOL "" FORCE)
set(GGML_BLAS               OFF CACHE BOOL "" FORCE)

add_subdirectory(whisper.cpp EXCLUDE_FROM_ALL)

add_library(leankey_whisper SHARED whisper_jni.cpp)

target_compile_options(leankey_whisper PRIVATE -O3 -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(leankey_whisper PRIVATE -Wl,--gc-sections)

target_link_libraries(leankey_whisper PRIVATE whisper android log)
