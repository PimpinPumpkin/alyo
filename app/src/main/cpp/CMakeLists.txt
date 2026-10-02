cmake_minimum_required(VERSION 3.22.1)
project(alyo_llm C CXX)

# llama.cpp runs the summary model. It is built from source at a pinned release, so there is no
# prebuilt binary in the app. Pass -DLLAMA_SRC=<checkout> to build from a local copy instead of
# fetching (faster when iterating, and what a build with no network needs).
set(LLAMA_TAG "v0.5.0")

# The model is unusably slow unoptimized, so the library is built for speed in every variant.
set(CMAKE_BUILD_TYPE Release)
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_RELEASE}")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_RELEASE}")

# Built twice (see llm-fast/build.gradle.kts): once for any 64-bit ARM chip, and once with the
# dot-product and half-float instructions, under another name so both can sit in the APK.
set(ALYO_LLM_LIB alyo-llm)
if(ALYO_LLM_VARIANT STREQUAL "dotprod" AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(ALYO_LLM_LIB alyo-llm-dotprod)
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)

if(LLAMA_SRC)
    add_subdirectory(${LLAMA_SRC} llama EXCLUDE_FROM_ALL)
else()
    include(FetchContent)
    FetchContent_Declare(llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG ${LLAMA_TAG}
        GIT_SHALLOW TRUE)
    FetchContent_GetProperties(llama_cpp)
    if(NOT llama_cpp_POPULATED)
        FetchContent_Populate(llama_cpp)
        add_subdirectory(${llama_cpp_SOURCE_DIR} ${llama_cpp_BINARY_DIR} EXCLUDE_FROM_ALL)
    endif()
endif()

# A few lines of glue that have ONNX Runtime rewrite a speech model in its optimized form. Only
# the app's own build has it; the runtime itself is found at run time, not linked here.
if(NOT ALYO_LLM_VARIANT)
    add_library(alyo-ort SHARED ort_optimize.cpp)
    target_include_directories(alyo-ort PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/onnxruntime/include)
    target_compile_features(alyo-ort PRIVATE cxx_std_17)
    target_link_libraries(alyo-ort PRIVATE log dl)
endif()

add_library(${ALYO_LLM_LIB} SHARED llm_jni.cpp)
target_compile_features(${ALYO_LLM_LIB} PRIVATE cxx_std_17)
target_link_libraries(${ALYO_LLM_LIB} PRIVATE llama log)
