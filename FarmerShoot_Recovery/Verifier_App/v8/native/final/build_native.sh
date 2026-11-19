#!/usr/bin/env bash
# Сборка libfarmerv8.so (arm64) — финальный xDL-движок. Требует Android NDK.
# Зависимости тянутся в ./deps (в git не хранятся). Запуск:
#   NDK=/opt/android-sdk/ndk/26.3.11579264 ./build_native.sh
set -e
cd "$(dirname "$0")"
: "${NDK:?Задайте NDK=/путь/к/android-ndk}"
CXX="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang++"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android24-clang"
STRIP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"

mkdir -p deps && cd deps
[ -d resolver ] || git clone --depth 1 https://github.com/Sneaky51/Android-Il2Cpp-Resolver-Template resolver   # берём только папку App/ImGUI
[ -d a64 ]      || git clone --depth 1 https://github.com/Rprop/And64InlineHook a64
[ -d xdl ]      || git clone --depth 1 https://github.com/hexhacking/xDL xdl
cd ..

XDLC=deps/xdl/xdl/src/main/cpp
INC="-Ideps/resolver/App -Ideps/resolver/App/ImGUI -Ideps/a64 -I$XDLC/include -I$XDLC"
FLAGS="-std=c++17 -fPIC -O2 -fvisibility=hidden -DIMGUI_IMPL_OPENGL_ES3 -Wno-everything"

echo "== ImGui + And64 =="
for f in deps/resolver/App/ImGUI/imgui.cpp deps/resolver/App/ImGUI/imgui_draw.cpp \
         deps/resolver/App/ImGUI/imgui_tables.cpp deps/resolver/App/ImGUI/imgui_widgets.cpp \
         deps/resolver/App/ImGUI/imgui_impl_android.cpp deps/resolver/App/ImGUI/imgui_impl_opengl3.cpp \
         deps/a64/And64InlineHook.cpp; do
  $CXX $FLAGS $INC -c "$f" -o "$(basename "$f" .cpp).o"
done
echo "== xDL (C) =="
for c in xdl.c xdl_iterate.c xdl_linker.c xdl_util.c xdl_lzma.c; do
  $CC -std=c11 -fPIC -O2 -fvisibility=hidden -Wno-everything -I$XDLC/include -I$XDLC -c "$XDLC/$c" -o "x_${c%.c}.o"
done
echo "== engine =="
$CXX $FLAGS $INC -c farmer_native.cpp -o farmer_native.o
echo "== link =="
$CXX -shared -o libfarmerv8.so farmer_native.o imgui.o imgui_draw.o imgui_tables.o imgui_widgets.o \
  imgui_impl_android.o imgui_impl_opengl3.o And64InlineHook.o \
  x_xdl.o x_xdl_iterate.o x_xdl_linker.o x_xdl_util.o x_xdl_lzma.o \
  -lEGL -lGLESv3 -landroid -llog -ldl -static-libstdc++
"$STRIP" libfarmerv8.so
echo "готово: libfarmerv8.so (скопируйте в ../../lspatch_module/libs/arm64-v8a/)"
