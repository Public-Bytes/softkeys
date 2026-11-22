# The smart cleanup library. It links llama.cpp statically into libsoftkeys-editor.so, which
# runs the optional text editing model on the CPU. Gradle downloads the pinned llama.cpp
# source, checks its SHA-256, and passes its folder as LLAMA_SOURCE_DIR.
cmake_minimum_required(VERSION 3.22)
project(softkeys_editor C CXX)

set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot product instructions make 4-bit matrix math several times faster. The app checks
    # for them (asimddp) before it loads this library, so older phones never run it.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()

add_subdirectory(${LLAMA_SOURCE_DIR} llama EXCLUDE_FROM_ALL)

add_library(softkeys-editor SHARED editor_jni.cpp)
target_link_libraries(softkeys-editor PRIVATE llama ggml log android)
target_link_options(softkeys-editor PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
target_compile_options(softkeys-editor PRIVATE -O2 -ffunction-sections -fdata-sections)
