cmake_minimum_required(VERSION 3.22.1)

project(iam_demucs_android LANGUAGES CXX)

# Activar la integración JNI de Demucs
set(SDENGINE_JNI ON CACHE BOOL "Build Demucs JNI library" FORCE)

# Paquete exacto de NativeEngine.kt
set(
    SDENGINE_JNI_CLASS
    "io/github/jpaauwe/demucsengine/NativeEngine"
    CACHE STRING "Demucs JNI class"
    FORCE
)

# Incorporar el motor Demucs descargado por GitHub Actions
add_subdirectory(
    "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/demucs-android"
    "${CMAKE_CURRENT_BINARY_DIR}/demucs-engine"
)
