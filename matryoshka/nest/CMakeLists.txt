# Nest — userspace virtualization engine for Matryoshka.
# Builds for Android (via the app's Gradle externalNativeBuild) and for Linux hosts (tests).
cmake_minimum_required(VERSION 3.18)
project(nest CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-Wall -Wextra -Wno-unused-parameter -O2 -fno-exceptions)

add_library(nest_core STATIC src/vfs.cpp src/arch.cpp src/tracer.cpp src/probe.cpp)
target_include_directories(nest_core PUBLIC src)
set_target_properties(nest_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Supervisor executable. On Android it is packaged as a "native library" (lib*.so) so that it lands
# in the app's native library directory, which apps are always allowed to execute from.
add_executable(nest_run src/nest_run.cpp)
target_link_libraries(nest_run nest_core)
if(ANDROID)
  set_target_properties(nest_run PROPERTIES OUTPUT_NAME "nest_run" PREFIX "lib" SUFFIX ".so")
  target_link_options(nest_run PRIVATE -pie)
else()
  enable_testing()
  add_executable(nest_tests tests/test_main.cpp tests/test_vfs.cpp tests/test_tracer.cpp)
  target_link_libraries(nest_tests nest_core)
  add_executable(nest_idprog tests/idprog.cpp)
  add_dependencies(nest_tests nest_run nest_idprog)
  target_compile_definitions(nest_tests PRIVATE NEST_RUN_PATH="$<TARGET_FILE:nest_run>"
                                                NEST_IDPROG_PATH="$<TARGET_FILE:nest_idprog>")
  add_test(NAME nest_tests COMMAND nest_tests)
endif()
