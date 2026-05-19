# ============================================================================
#  Rodyto Lens Pro · CMakeLists.txt · v3.8
#
#  NOVEDADES v3.8 (sobre v3.6):
#   • set(CMAKE_BUILD_TYPE Release) por defecto si no viene de afuera —
#     evita binarios mixtos Release/RelWithDebInfo cuando AGP no propaga
#     CMAKE_BUILD_TYPE correctamente (caso conocido en NDK r26 + AGP 8.6).
#   • set(CMAKE_VERBOSE_MAKEFILE OFF) explícito — silencia logs ruidosos
#     en CI sin afectar a la depuración local (que puede activarlo en
#     externalNativeBuild { cmake { arguments "-DCMAKE_VERBOSE_MAKEFILE=ON" } }).
#   • Patrón target_link_libraries() con keyword PRIVATE — recomendado
#     por CMake 3.13+. Las versiones implícitas pueden romper con futuras
#     NDK.
#   • find_library() con REQUIRED → si falta camera2ndk o mediandk el
#     build FALLA con mensaje claro (antes podía seguir y luego romper
#     opaco en el link).
# ============================================================================
cmake_minimum_required(VERSION 3.22.1)

project("rodytolenspro" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# v3.8: defaults seguros si AGP no los propaga.
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)
endif()
set(CMAKE_VERBOSE_MAKEFILE OFF)

# Optimizaciones de tamaño/velocidad para Release.
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O2 -fvisibility=hidden -fvisibility-inlines-hidden")
endif()

add_library(rodytolenspro SHARED native-lib.cpp)

# v3.8: REQUIRED para que el build falle pronto si falta el sysroot.
find_library(log-lib        log         REQUIRED)
find_library(camera2ndk-lib camera2ndk  REQUIRED)
find_library(mediandk-lib   mediandk    REQUIRED)

# v3.8: keyword PRIVATE explícito.
target_link_libraries(
    rodytolenspro
    PRIVATE
    ${camera2ndk-lib}
    ${mediandk-lib}
    ${log-lib}
)
