cmake_minimum_required(VERSION 3.22.1)
project(tyranor_native)

add_library(krkr_bridge_v2 SHARED krkr_bridge.cpp)
add_library(artemis_loader SHARED artemis_loader.cpp)

find_library(log-lib log)
find_library(dl-lib dl)
find_library(android-lib android)

target_link_libraries(krkr_bridge_v2 ${log-lib} ${dl-lib})
target_link_libraries(artemis_loader ${log-lib} ${dl-lib} ${android-lib})
