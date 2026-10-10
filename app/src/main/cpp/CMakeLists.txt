cmake_minimum_required(VERSION 3.22.1)
project(nesemu)

add_library(nesemu SHARED native-lib.cpp cpu.cpp cartridge.cpp)

find_library(log-lib log)
target_link_libraries(nesemu ${log-lib})
