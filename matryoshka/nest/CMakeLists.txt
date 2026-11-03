# Nest — userspace virtualization engine for Matryoshka.
# Builds for Android (via the app's Gradle externalNativeBuild) and for Linux hosts (tests).
cmake_minimum_required(VERSION 3.18)
project(nest CXX)

set(CMAKE_CXX_STANDARD 17)
# NEST_PRELOAD_ONLY: build just the fast-path library (used for the 32-bit ARM copy on Android).
option(NEST_PRELOAD_ONLY "Build only libnest_preload" OFF)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
add_compile_options(-Wall -Wextra -Wno-unused-parameter -Wno-missing-field-initializers -O2 -fno-exceptions)

if(NEST_PRELOAD_ONLY)
  add_library(nest_preload SHARED src/preload.cpp src/vfs.cpp src/vfs_share.cpp src/vbinder_client.cpp src/netlink_emu.cpp)
  target_include_directories(nest_preload PRIVATE src)
  set_target_properties(nest_preload PROPERTIES CXX_VISIBILITY_PRESET hidden)
  target_compile_options(nest_preload PRIVATE $<$<STREQUAL:${ANDROID_ABI},armeabi-v7a>:-marm>)
  target_link_options(nest_preload PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/preload.map
                                           -Wl,--exclude-libs,ALL)
  return()
endif()

add_library(nest_core STATIC src/vfs.cpp src/arch.cpp src/tracer.cpp src/probe.cpp src/vbinder.cpp src/vfs_share.cpp src/android_profile.cpp src/guest_log.cpp src/bpf_emu.cpp src/netlink_emu.cpp src/wayland_server.cpp src/gpu_passthrough.cpp)
target_include_directories(nest_core PUBLIC src)
set_target_properties(nest_core PROPERTIES POSITION_INDEPENDENT_CODE ON)

# In-process fast path, LD_PRELOADed into guest processes. Self-contained (static C++ runtime) so
# it loads in any guest regardless of the guest's own C++ libraries.
add_library(nest_preload SHARED src/preload.cpp src/vfs.cpp src/vfs_share.cpp src/vbinder_client.cpp src/netlink_emu.cpp)
target_include_directories(nest_preload PRIVATE src)
set_target_properties(nest_preload PROPERTIES CXX_VISIBILITY_PRESET hidden
                                              LINK_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/src/preload.map)
target_link_options(nest_preload PRIVATE -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/preload.map
                                         -Wl,--exclude-libs,ALL)
if(NOT ANDROID)
  target_link_options(nest_preload PRIVATE -static-libstdc++ -static-libgcc)
endif()

# Supervisor executable. On Android it is packaged as a "native library" (lib*.so) so that it lands
# in the app's native library directory, which apps are always allowed to execute from.
add_executable(nest_run src/nest_run.cpp)
target_link_libraries(nest_run nest_core)
if(NOT ANDROID)
  target_link_libraries(nest_run pthread)
endif()
# Raw-binder test client used by the tests and by the on-device probe (vbinder self-test).
add_executable(nest_binderprog tests/binderprog.cpp)

if(ANDROID)
  set_target_properties(nest_run PROPERTIES OUTPUT_NAME "nest_run" PREFIX "lib" SUFFIX ".so")
  target_link_options(nest_run PRIVATE -pie)
  set_target_properties(nest_binderprog PROPERTIES OUTPUT_NAME "nest_bindertest" PREFIX "lib" SUFFIX ".so")
  target_link_options(nest_binderprog PRIVATE -pie)
else()
  enable_testing()
  add_executable(nest_tests tests/test_main.cpp tests/test_vfs.cpp tests/test_tracer.cpp tests/test_vbinder.cpp tests/test_wayland.cpp)
  target_link_libraries(nest_tests nest_core pthread)
  add_executable(nest_idprog tests/idprog.cpp)
  # Statically linked like Android's bootstrap linkerconfig: no preload, everything traced.
  add_executable(nest_idprog_static tests/idprog.cpp)
  target_link_options(nest_idprog_static PRIVATE -static)
  add_dependencies(nest_tests nest_run nest_idprog nest_idprog_static nest_preload nest_binderprog)
  target_compile_definitions(nest_tests PRIVATE NEST_RUN_PATH="$<TARGET_FILE:nest_run>"
                                                NEST_IDPROG_PATH="$<TARGET_FILE:nest_idprog>"
                                                NEST_IDPROG_STATIC_PATH="$<TARGET_FILE:nest_idprog_static>"
                                                NEST_PRELOAD_PATH="$<TARGET_FILE:nest_preload>"
                                                NEST_BINDERPROG_PATH="$<TARGET_FILE:nest_binderprog>")
  add_test(NAME nest_tests COMMAND nest_tests)

  # 32-bit (compat) processes: i386 builds of the fast path and a test program exercise the same
  # code paths arm32 processes use on phones.
  include(CheckCXXSourceCompiles)
  set(CMAKE_REQUIRED_FLAGS -m32)
  check_cxx_source_compiles("int main(){return 0;}" NEST_HAVE_M32)
  unset(CMAKE_REQUIRED_FLAGS)
  if(NEST_HAVE_M32)
    add_library(nest_preload32 SHARED src/preload.cpp src/vfs.cpp src/vfs_share.cpp src/vbinder_client.cpp src/netlink_emu.cpp)
    target_include_directories(nest_preload32 PRIVATE src)
    set_target_properties(nest_preload32 PROPERTIES CXX_VISIBILITY_PRESET hidden)
    target_compile_options(nest_preload32 PRIVATE -m32)
    target_link_options(nest_preload32 PRIVATE -m32 -static-libstdc++ -static-libgcc
                        -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/src/preload.map -Wl,--exclude-libs,ALL)
    add_executable(nest_idprog32 tests/idprog.cpp)
    target_compile_options(nest_idprog32 PRIVATE -m32 -D_FILE_OFFSET_BITS=64)
    target_link_options(nest_idprog32 PRIVATE -m32)
    add_dependencies(nest_tests nest_preload32 nest_idprog32)
    target_compile_definitions(nest_tests PRIVATE NEST_HAVE_M32=1
                                                  NEST_PRELOAD32_PATH="$<TARGET_FILE:nest_preload32>"
                                                  NEST_IDPROG32_PATH="$<TARGET_FILE:nest_idprog32>")
  endif()
endif()
