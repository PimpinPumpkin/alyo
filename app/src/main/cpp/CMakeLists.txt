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

add_library(alyo-llm SHARED llm_jni.cpp)
target_compile_features(alyo-llm PRIVATE cxx_std_17)
target_link_libraries(alyo-llm PRIVATE llama log)
