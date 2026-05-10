cmake_minimum_required(VERSION 3.22.1)
project("rodytolenspro")

add_library(rodytolenspro SHARED native-lib.cpp)

# Busca la librería de logs de Android
find_library(log-lib log)

target_link_libraries(
        rodytolenspro
        camera2ndk
        mediandk
        android
        ${log-lib} # Enlace correcto
)
