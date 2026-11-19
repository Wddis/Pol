#!/usr/bin/env bash
# ============================================================================
# FarmerShoot v8 — сборка нативного оверлея libfarmerv8.so (arm64-v8a).
# Требует Android NDK (r26+). Зависимости (ImGui/Dobby/Resolver) тянутся сюда,
# в git НЕ хранятся. Запуск:  NDK=/path/to/android-ndk ./build.sh
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"

: "${NDK:?Задайте NDK=/путь/к/android-ndk}"
TP=third_party
mkdir -p "$TP"

# --- ImGui (docking или master) + backends ---------------------------------
if [ ! -d "$TP/ImGUI" ]; then
  echo "[v8] fetch ImGui…"
  git clone --depth 1 https://github.com/ocornut/imgui "$TP/imgui_src"
  mkdir -p "$TP/ImGUI"
  cp "$TP"/imgui_src/imgui*.cpp "$TP"/imgui_src/imgui*.h "$TP"/imgui_src/imstb_*.h "$TP/ImGUI/"
  cp "$TP"/imgui_src/backends/imgui_impl_android.* "$TP/ImGUI/"
  cp "$TP"/imgui_src/backends/imgui_impl_opengl3*.* "$TP/ImGUI/"
fi

# --- IL2CPP Resolver (headers-only) ----------------------------------------
if [ ! -d "$TP/Resolver" ]; then
  echo "[v8] fetch IL2CPP resolver…"
  git clone --depth 1 https://github.com/Sneaky51/Android-Il2Cpp-Resolver-Template "$TP/resolver_src"
  cp -r "$TP"/resolver_src/App/Resolver "$TP/Resolver"
  cp "$TP"/resolver_src/App/Dobby/Dobby.h "$TP/" 2>/dev/null || true
  # добавить наш хелпер GetFirstInstance (см. overlay.md), если его нет
  if ! grep -q GetFirstInstance "$TP/Resolver/API/Class.hpp"; then
    echo "[v8] ПРИМЕЧАНИЕ: добавьте GetFirstInstance из overlay.md в Resolver/API/Class.hpp (namespace Utils)"
  fi
fi

# --- Dobby (нужна libdobby.a под arm64) ------------------------------------
if [ ! -e "$TP/Dobby/libdobby.a" ]; then
  echo "[v8] ВНИМАНИЕ: положите собранную $TP/Dobby/libdobby.a (jmpews/Dobby) и Dobby.h."
  echo "[v8] Сборка Dobby: cmake из https://github.com/jmpews/Dobby под android arm64-v8a."
fi

# --- собираем .so ----------------------------------------------------------
cmake -B build \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build -j"$(nproc)"

echo "[v8] готово -> build/libfarmerv8.so"
echo "[v8] дальше: вшейте .so в СВОЙ apk (frida-gadget/objection — no-root) или грузите инжектором (root)."
