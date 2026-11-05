cmake_minimum_required(VERSION 3.22.1)

# Native code is always built optimized, even for the debug variant: an -O0
# whisper.cpp is unusably slow on a phone.
set(CMAKE_BUILD_TYPE "Release" CACHE STRING "" FORCE)

project(calltranscriber LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ---- whisper.cpp -----------------------------------------------------------
# Pinned release, fetched at configure time. Set WHISPER_CPP_DIR (env var) to
# use a local checkout instead (offline builds / host smoke tests).
set(WHISPER_CPP_TAG "v1.9.5")

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

if (ANDROID)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    if (ANDROID_ABI STREQUAL "arm64-v8a")
        # Safe baseline for every arm64 phone from ~2018 on (incl. the S24):
        # NEON dot-product + fp16 arithmetic.
        set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    endif ()
endif ()

include(FetchContent)
if (DEFINED ENV{WHISPER_CPP_DIR})
    set(FETCHCONTENT_SOURCE_DIR_WHISPER "$ENV{WHISPER_CPP_DIR}")
endif ()
FetchContent_Declare(
    whisper
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG        ${WHISPER_CPP_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(whisper)

# ---- JNI bridge ------------------------------------------------------------
add_library(calltranscriber SHARED whisper_jni.cpp)
target_link_libraries(calltranscriber PRIVATE whisper)

if (ANDROID)
    find_library(LOG_LIB log)
    target_link_libraries(calltranscriber PRIVATE ${LOG_LIB})
    # Android 15+ devices may use 16 KB pages.
    target_link_options(calltranscriber PRIVATE -Wl,-z,max-page-size=16384)
    target_compile_options(calltranscriber PRIVATE -O3 -fvisibility=hidden)
else ()
    find_package(JNI REQUIRED)
    target_include_directories(calltranscriber PRIVATE ${JNI_INCLUDE_DIRS})
endif ()
