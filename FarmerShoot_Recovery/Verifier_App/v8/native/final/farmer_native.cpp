// ============================================================================
// FarmerShoot v8 — FINAL native engine (libfarmerv8.so)
// Рисует ImGui-оверлей ПРЯМО на GL-поверхности игры (хук eglSwapBuffers через
// And64InlineHook) — окно видно поверх Unity всегда. Через IL2CPP-резолвер
// показывает ЖИВЫЕ классы/поля (ammo/jump/health) и авто-применяет патчи.
// Авто-режим: без тач-ввода (кнопки не нужны) — всё видно и работает само.
// ============================================================================
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <thread>
#include <atomic>

#include "And64InlineHook.hpp"
#include "ImGUI/imgui.h"
#include "ImGUI/imgui_impl_android.h"
#include "ImGUI/imgui_impl_opengl3.h"
#include "Resolver/IL2CPP_Resolver.hpp"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FarmerV8", __VA_ARGS__)

// ============================ КОНФИГ (правьте тут) ==========================
// Классы для показа в оверлее (по подстроке имени). Имена полей увидите на экране.
static const char* kBrowseMatch[] = {
    "Weapon","Weaponry","Ammo","Magazine","Clip","Bullet",
    "Health","Damage","Jump","Movement","Locomotion","Player","Motor"
};
// Явный патч прыжка: укажите реальные имена из экранного списка и mul.
// kPatchClass="" → патч выключен (только показ). kPatchKind: 0=instance,1=static.
static const char* kPatchClass = "";        // напр. "Balders.farmershoot...PlayerMovement"
static const char* kPatchField = "";        // напр. "jumpForce"
static float       kPatchMul   = 3.0f;       // ×3
static int         kPatchKind  = 0;          // 0 instance, 1 static
// Поле для показа значения (ammo): класс/поле. Пусто → не показывать.
static const char* kWatchClass = "";
static const char* kWatchField = "";
static int         kWatchKind  = 0;          // 0 instance (ctor-capture), 1 static
// ===========================================================================

// ---- состояние ----
static EGLBoolean (*o_eglSwapBuffers)(EGLDisplay, EGLSurface) = nullptr;
static std::atomic<bool> g_il2cppReady{false};
static std::atomic<bool> g_renderInit{false};
static int g_w = 0, g_h = 0;

// захваченный экземпляр целевого класса (через хук .ctor)
static std::atomic<void*> g_patchInstance{nullptr};
static std::atomic<void*> g_watchInstance{nullptr};
static float g_patchOrig = 0.f, g_patchTarget = 0.f;
static std::atomic<bool> g_patchArmed{false};

// список классов/полей для экрана (строим один раз)
static std::vector<std::string> g_browse;
static std::atomic<bool> g_browseReady{false};

static bool nameMatches(const char* n) {
    if (!n) return false;
    for (const char* s : kBrowseMatch) if (strstr(n, s)) return true;
    return false;
}

// ---- чтение/запись поля по оффсету ----
static float readF(void* obj, int off, int isFloat) {
    if (!obj || off < 0) return 0.f;
    uintptr_t a = (uintptr_t)obj + off;
    return isFloat ? *(float*)a : (float)*(int*)a;
}
static void writeF(void* obj, int off, int isFloat, float v) {
    if (!obj || off < 0) return;
    uintptr_t a = (uintptr_t)obj + off;
    if (isFloat) *(float*)a = v; else *(int*)a = (int)v;
}

// ---- захват instance через хук конструктора ----
typedef void* (*ctor_t)(void*, void*, void*, void*, void*, void*, void*, void*);
static ctor_t o_patchCtor = nullptr, o_watchCtor = nullptr;
static void* h_patchCtor(void* a0, void* a1, void* a2, void* a3, void* a4, void* a5, void* a6, void* a7) {
    g_patchInstance.store(a0);
    return o_patchCtor ? o_patchCtor(a0,a1,a2,a3,a4,a5,a6,a7) : nullptr;
}
static void* h_watchCtor(void* a0, void* a1, void* a2, void* a3, void* a4, void* a5, void* a6, void* a7) {
    g_watchInstance.store(a0);
    return o_watchCtor ? o_watchCtor(a0,a1,a2,a3,a4,a5,a6,a7) : nullptr;
}

static void setupTargets() {
    // список классов на экран
    size_t nAsm = 0;
    Unity::il2cppAssembly** asms = IL2CPP::Domain::GetAssemblies(&nAsm);
    char line[256];
    for (size_t i = 0; i < nAsm && g_browse.size() < 400; ++i) {
        Unity::il2cppAssembly* a = asms[i];
        if (!a || !a->m_pImage) continue;
        std::vector<Unity::il2cppClass*> classes;
        IL2CPP::Class::FetchClasses(&classes, a->m_pImage->m_pNameNoExt, nullptr);
        for (auto* k : classes) {
            if (!k || !nameMatches(k->m_pName)) continue;
            std::vector<Unity::il2cppFieldInfo*> fields;
            IL2CPP::Class::FetchFields(k, &fields);
            std::string fs;
            for (auto* f : fields) {
                if (!f) continue;
                snprintf(line, sizeof line, "%s@0x%x ", f->m_pName, (unsigned)f->m_iOffset);
                fs += line;
            }
            snprintf(line, sizeof line, "%s.%s { %s}", k->m_pNamespace ? k->m_pNamespace : "", k->m_pName, fs.c_str());
            g_browse.emplace_back(line);
            if (g_browse.size() >= 400) break;
        }
    }
    g_browseReady = true;
    LOG("browse classes: %zu", g_browse.size());

    // патч: захват instance через .ctor и расчёт цели
    if (kPatchClass[0]) {
        Unity::il2cppClass* k = IL2CPP::Class::Find(kPatchClass);
        if (k) {
            if (kPatchKind == 0) {
                void* ctor = IL2CPP::Class::Utils::GetMethodPointer(k, ".ctor", -1);
                if (ctor) A64HookFunction(ctor, (void*)h_patchCtor, (void**)&o_patchCtor);
                LOG("patch ctor hooked: %p", ctor);
            }
        } else LOG("patch class not found: %s", kPatchClass);
    }
    if (kWatchClass[0] && kWatchKind == 0) {
        Unity::il2cppClass* k = IL2CPP::Class::Find(kWatchClass);
        if (k) {
            void* ctor = IL2CPP::Class::Utils::GetMethodPointer(k, ".ctor", -1);
            if (ctor) A64HookFunction(ctor, (void*)h_watchCtor, (void**)&o_watchCtor);
        }
    }
}

// ---- применить патч (каждый кадр, enforce) ----
static void applyPatch() {
    if (!kPatchClass[0]) return;
    Unity::il2cppClass* k = IL2CPP::Class::Find(kPatchClass);
    if (!k) return;
    int off = IL2CPP::Class::Utils::GetFieldOffset(k, kPatchField);
    if (off < 0) return;
    if (kPatchKind == 1) { // static
        void* cur = IL2CPP::Class::Utils::GetStaticField(k, kPatchField);
        float orig; memcpy(&orig, &cur, sizeof(float));
        if (!g_patchArmed.load()) { g_patchOrig = orig; g_patchTarget = orig * kPatchMul; g_patchArmed = true; }
        void* nv = nullptr; float t = g_patchTarget; memcpy(&nv, &t, sizeof(float));
        IL2CPP::Class::Utils::SetStaticField(k, kPatchField, nv);
    } else {
        void* inst = g_patchInstance.load();
        if (!inst) return;
        if (!g_patchArmed.load()) { g_patchOrig = readF(inst, off, 1); g_patchTarget = g_patchOrig * kPatchMul; g_patchArmed = true; }
        writeF(inst, off, 1, g_patchTarget);
    }
}

// ---- ImGui overlay ----
static void drawOverlay() {
    ImGui::SetNextWindowPos(ImVec2(20, 60), ImGuiCond_Once);
    ImGui::SetNextWindowSize(ImVec2(g_w * 0.6f > 520 ? 520 : g_w * 0.6f, g_h * 0.7f), ImGuiCond_Once);
    ImGui::SetNextWindowBgAlpha(0.85f);
    ImGui::Begin("FarmerShoot v8", nullptr, ImGuiWindowFlags_NoCollapse);

    ImGui::TextColored(ImVec4(0.6f, 1, 0.5f, 1), "FarmerShoot v8 — LIVE (LSPatch)");
    ImGui::Text("il2cpp: %s", g_il2cppReady.load() ? "READY" : "waiting...");

    if (kWatchClass[0]) {
        Unity::il2cppClass* k = IL2CPP::Class::Find(kWatchClass);
        int off = k ? IL2CPP::Class::Utils::GetFieldOffset(k, kWatchField) : -1;
        void* inst = (kWatchKind == 0) ? g_watchInstance.load() : nullptr;
        if (k && off >= 0 && (kWatchKind == 1 || inst)) {
            float v = (kWatchKind == 1) ? 0.f : readF(inst, off, 1);
            ImGui::Text("%s.%s = %.3f", kWatchClass, kWatchField, v);
        } else ImGui::TextDisabled("watch: ждём instance/класс");
    }
    if (kPatchClass[0]) {
        ImGui::TextColored(g_patchArmed.load() ? ImVec4(1,0.6f,0.3f,1) : ImVec4(0.7f,0.7f,0.7f,1),
            "patch %s.%s x%.2f  %s", kPatchClass, kPatchField, kPatchMul,
            g_patchArmed.load() ? "ACTIVE" : "ждём instance");
    }

    ImGui::Separator();
    ImGui::Text("Живые классы (ammo/jump/health): %d", (int)g_browse.size());
    ImGui::BeginChild("browse", ImVec2(0, 0), true);
    for (auto& s : g_browse) ImGui::TextUnformatted(s.c_str());
    ImGui::EndChild();
    ImGui::End();
}

static EGLBoolean h_eglSwapBuffers(EGLDisplay dpy, EGLSurface surf) {
    if (!g_renderInit.load()) {
        eglQuerySurface(dpy, surf, EGL_WIDTH, &g_w);
        eglQuerySurface(dpy, surf, EGL_HEIGHT, &g_h);
        ImGui::CreateContext();
        ImGui::GetIO().IniFilename = nullptr;
        ImGui_ImplAndroid_Init(ImVec2((float)g_w, (float)g_h));
        ImGui_ImplOpenGL3_Init("#version 300 es");
        g_renderInit = true;
        LOG("overlay init %dx%d", g_w, g_h);
    }
    if (g_il2cppReady.load()) applyPatch();

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();
    drawOverlay();
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    ImGui::EndFrame();
    return o_eglSwapBuffers(dpy, surf);
}

static void worker() {
    // хук eglSwapBuffers (libEGL прилинкован)
    void* egl = (void*)&eglSwapBuffers;
    A64HookFunction(egl, (void*)h_eglSwapBuffers, (void**)&o_eglSwapBuffers);
    LOG("eglSwapBuffers hooked @%p", egl);
    // ждём и инициализируем il2cpp
    if (IL2CPP::Initialize(true, 90)) {
        g_il2cppReady = true;
        LOG("il2cpp ready");
        setupTargets();
    } else LOG("il2cpp init FAILED");
}

__attribute__((constructor)) static void entry() {
    std::thread(worker).detach();
}

// ---- JNI мост для Java-панели модуля (опционально) ----
extern "C" JNIEXPORT jstring JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeCheck(JNIEnv* env, jclass) {
    char buf[128];
    snprintf(buf, sizeof buf, "il2cpp=%s classes=%d patch=%s",
             g_il2cppReady.load() ? "ready" : "wait", (int)g_browse.size(),
             g_patchArmed.load() ? "active" : "off");
    return env->NewStringUTF(buf);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativePatchJump(JNIEnv*, jclass, jfloat) {
    return g_patchArmed.load() ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeRestore(JNIEnv*, jclass) {
    if (!kPatchClass[0]) return JNI_FALSE;
    Unity::il2cppClass* k = IL2CPP::Class::Find(kPatchClass);
    int off = k ? IL2CPP::Class::Utils::GetFieldOffset(k, kPatchField) : -1;
    void* inst = g_patchInstance.load();
    if (k && off >= 0 && inst && g_patchArmed.load()) { writeF(inst, off, 1, g_patchOrig); g_patchArmed = false; return JNI_TRUE; }
    return JNI_FALSE;
}

// Статус + первые N найденных классов/полей — для Java-оверлея (видно и на Vulkan).
extern "C" JNIEXPORT jstring JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeInfo(JNIEnv* env, jclass) {
    std::string s;
    char h[128];
    snprintf(h, sizeof h, "il2cpp: %s\noverlay(GL): %s\nклассов (ammo/jump/health): %d\n",
             g_il2cppReady.load() ? "READY" : "waiting...",
             g_renderInit.load() ? "ON" : "нет (Vulkan? данные ниже всё равно живые)",
             (int)g_browse.size());
    s = h;
    if (g_browseReady.load()) {
        s += "— живые классы/поля (имена для патча): —\n";
        int n = 0;
        for (auto& line : g_browse) { s += line; s += "\n"; if (++n >= 40) { s += "...(полный список в DUMP-файле)\n"; break; } }
    } else {
        s += "(ждём il2cpp, список появится через пару секунд)\n";
    }
    return env->NewStringUTF(s.c_str());
}

// Полный дамп ВСЕХ классов/полей в файл (чтобы точно получить реальные имена).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeDumpTo(JNIEnv* env, jclass, jstring jpath) {
    if (!g_il2cppReady.load()) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    FILE* f = fopen(path, "w");
    env->ReleaseStringUTFChars(jpath, path);
    if (!f) return JNI_FALSE;
    size_t nAsm = 0;
    Unity::il2cppAssembly** asms = IL2CPP::Domain::GetAssemblies(&nAsm);
    fprintf(f, "# FarmerShoot v8 LIVE dump — assemblies: %zu\n\n", nAsm);
    for (size_t i = 0; i < nAsm; ++i) {
        Unity::il2cppAssembly* a = asms[i];
        if (!a || !a->m_pImage) continue;
        std::vector<Unity::il2cppClass*> classes;
        IL2CPP::Class::FetchClasses(&classes, a->m_pImage->m_pNameNoExt, nullptr);
        fprintf(f, "=== %s (classes: %zu) ===\n", a->m_pImage->m_pName ? a->m_pImage->m_pName : "?", classes.size());
        for (auto* k : classes) {
            if (!k) continue;
            std::vector<Unity::il2cppFieldInfo*> fields;
            IL2CPP::Class::FetchFields(k, &fields);
            fprintf(f, "  %s.%s {", k->m_pNamespace ? k->m_pNamespace : "", k->m_pName ? k->m_pName : "?");
            for (auto* fl : fields) if (fl) fprintf(f, " %s@0x%x", fl->m_pName, (unsigned)fl->m_iOffset);
            fprintf(f, " }\n");
        }
        fprintf(f, "\n");
    }
    fclose(f);
    LOG("dump written: ok");
    return JNI_TRUE;
}
