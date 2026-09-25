cmake_minimum_required(VERSION 3.22.1)
project(tyranor_native)

add_library(krkr_bridge_v2 SHARED krkr_bridge.cpp)
add_library(artemis_loader SHARED artemis_loader.cpp)
add_library(siglus_bridge SHARED siglus_bridge.cpp)
add_library(rfvp_bridge SHARED rfvp_bridge.cpp)
add_library(games_bridge SHARED games_bridge.cpp)

find_library(log-lib log)
find_library(dl-lib dl)
find_library(android-lib android)

target_link_libraries(krkr_bridge_v2 ${log-lib} ${dl-lib})
target_link_libraries(artemis_loader ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(siglus_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(rfvp_bridge ${log-lib} ${dl-lib} ${android-lib})
target_link_libraries(games_bridge ${log-lib} ${dl-lib} ${android-lib})
