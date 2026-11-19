cmake_minimum_required(VERSION 3.22)
project(farmerv8 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Зависимости подтягиваются build.sh в ./third_party (в git не хранятся):
#   third_party/ImGUI      <- ocornut/imgui + backends android/opengl3
#   third_party/Dobby      <- jmpews/Dobby (prebuilt libdobby.a или исходники)
#   third_party/Resolver   <- Sneaky51/Android-Il2Cpp-Resolver-Template (App/Resolver)
set(TP ${CMAKE_SOURCE_DIR}/third_party)

add_library(farmerv8 SHARED
    main.cpp
    ${TP}/ImGUI/imgui.cpp
    ${TP}/ImGUI/imgui_draw.cpp
    ${TP}/ImGUI/imgui_tables.cpp
    ${TP}/ImGUI/imgui_widgets.cpp
    ${TP}/ImGUI/imgui_impl_android.cpp
    ${TP}/ImGUI/imgui_impl_opengl3.cpp
)

target_include_directories(farmerv8 PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${TP}
    ${TP}/Dobby
)

# Dobby: либо собранная статическая библиотека, либо добавьте исходники.
find_library(DOBBY_LIB dobby PATHS ${TP}/Dobby HINTS ${TP}/Dobby NO_DEFAULT_PATH)
if(NOT DOBBY_LIB)
    set(DOBBY_LIB ${TP}/Dobby/libdobby.a)
endif()

find_library(EGL   EGL)
find_library(GLES3 GLESv3)
find_library(ANDROID android)
find_library(LOG   log)

target_link_libraries(farmerv8 ${DOBBY_LIB} ${EGL} ${GLES3} ${ANDROID} ${LOG})

target_compile_options(farmerv8 PRIVATE -fvisibility=hidden -O2 -Wno-unused)
