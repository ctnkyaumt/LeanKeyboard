cmake_minimum_required(VERSION 3.22.1)

project(leankey_whisper C CXX)

# A debug apk configures cmake with CMAKE_BUILD_TYPE=Debug, and the ndk's debug flags carry no
# -O at all, so ggml lands on clang's -O0 default and transcription becomes tens of times
# slower - indistinguishable from a hang on a tv box.
#
# Overriding CMAKE_<LANG>_FLAGS_DEBUG does not survive here: ggml/CMakeLists.txt runs its own
# project() call, which re-reads the toolchain and restores the defaults. Directory compile
# options are inherited by every subdirectory instead, and land after the config flags on the
# command line, so this wins wherever it ends up.
add_compile_options(-O3 -DNDEBUG)

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
