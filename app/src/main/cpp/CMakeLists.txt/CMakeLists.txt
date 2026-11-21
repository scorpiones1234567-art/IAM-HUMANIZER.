cmake_minimum_required(VERSION 3.22.1)

project(iam_demucs_android LANGUAGES CXX)

set(SDENGINE_JNI ON CACHE BOOL "Build Demucs JNI library" FORCE)

set(
    SDENGINE_JNI_CLASS
    "io/github/jpaauwe/demucsengine/NativeEngine"
    CACHE STRING "Demucs JNI class"
    FORCE
)

add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/demucs-android"
    "${CMAKE_CURRENT_BINARY_DIR}/demucs-engine"
)
