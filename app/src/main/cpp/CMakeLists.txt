cmake_minimum_required(VERSION 3.22.1)
project("rodytolenspro")

set(CMAKE_CXX_STANDARD 17)

add_library(rodytolenspro SHARED native-lib.cpp)

find_library(log-lib log)
find_library(camera2ndk-lib camera2ndk)
find_library(mediandk-lib mediandk)

target_link_libraries(rodytolenspro ${camera2ndk-lib} ${mediandk-lib} ${log-lib})
