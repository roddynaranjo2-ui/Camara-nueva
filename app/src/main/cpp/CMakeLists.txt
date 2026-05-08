# Define la versión mínima de CMake requerida
cmake_minimum_required(VERSION 3.22.1)

# Nombre del proyecto
project("rodytolenspro")

# Crea la librería compartida a partir del archivo C++ que creaste antes
add_library(
        rodytolenspro
        SHARED
        native-lib.cpp
)

# Busca y enlaza las librerías del sistema Android necesarias para la cámara
target_link_libraries(
        rodytolenspro
        camera2ndk  # API Nativa de la Cámara
        mediandk    # Para manejo de video/media
        android     # Funciones base de Android
        log         # Para que podamos ver errores en el Logcat
)
