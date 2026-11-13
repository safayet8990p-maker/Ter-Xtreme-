cmake_minimum_required(VERSION 3.22.1)
project(terpty C)

add_library(terpty SHARED terpty.c)

target_compile_options(terpty PRIVATE -Wall -Wextra -Werror=implicit-function-declaration)
target_link_options(terpty PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(terpty PRIVATE log)
