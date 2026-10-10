cmake_minimum_required(VERSION 3.22.1)
project(nesemu)

add_library(nesemu SHARED native-lib.cpp cpu.cpp cartridge.cpp ppu.cpp apu.cpp)
target_compile_options(nesemu PRIVATE -O2)

find_library(log-lib log)
target_link_libraries(nesemu ${log-lib})
