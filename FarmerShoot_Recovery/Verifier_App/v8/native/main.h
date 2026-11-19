#pragma once
// ============================================================================
// FarmerShoot Verifier v8 — нативный ImGui-оверлей поверх окна игры.
// Ядро: IL2CPP-резолвер (resolve-by-name) + Dobby (hook eglSwapBuffers и ввода)
//        + Dear ImGui (рендер поверх GL-поверхности игры).
// Эти три зависимости НЕ хранятся в репозитории (как и в прежних версиях) —
// они подтягиваются скриптом build.sh из апстрима. См. overlay.md.
// ============================================================================
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/input.h>
#include <thread>
#include <atomic>
#include <string>
#include <unordered_map>

#include "Dobby.h"
#include "ImGUI/imgui.h"
#include "ImGUI/imgui_impl_android.h"
#include "ImGUI/imgui_impl_opengl3.h"
#include "Resolver/IL2CPP_Resolver.hpp"

#include "WatchConfig.hpp"

namespace App {
    // render hook
    EGLBoolean  h_EglSwapBuffers(EGLDisplay display, EGLSurface surface);
    // touch input hook (чтобы кнопка «Проверить» была тапабельна)
    int32_t     h_handleInput(void* app, AInputEvent* event);
    bool        Initialize();
    void        DrawMenu();
    void        DoCheck();   // «кнопка Проверить» — разовый снимок watch-полей
}

// глобальное состояние GL/overlay
struct EglState { int width = 0, height = 0; bool renderInitialized = false; };
extern EglState egl;

// состояние меню
extern std::atomic<bool> g_menuOpen;
extern std::atomic<bool> g_autoRefresh;
