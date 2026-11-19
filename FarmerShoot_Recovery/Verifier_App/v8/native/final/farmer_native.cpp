// ============================================================================
// FarmerShoot v8 — FINAL native engine (libfarmerv8.so), xDL-based.
// Резолвит libil2cpp.so через xDL (в обход линкер-неймспейсов Android) и
// напрямую вызывает il2cpp API: dump классов/полей, чтение значений, патч.
// ImGui-оверлей рисуется на GL-поверхности (eglSwapBuffers hook). Авто-режим.
// ============================================================================
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include <thread>
#include <atomic>

#include "And64InlineHook.hpp"
#include "xdl.h"
#include "ImGUI/imgui.h"
#include "ImGUI/imgui_impl_android.h"
#include "ImGUI/imgui_impl_opengl3.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "FarmerV8", __VA_ARGS__)

// ============================ КОНФИГ ========================================
static const char* kBrowseMatch[] = {
    "Weapon","Weaponry","Ammo","Magazine","Clip","Bullet","Round",
    "Health","Damage","Jump","Movement","Locomotion","Player","Motor","Character"
};
static const char* kPatchClass = "";   // напр. "...PlayerMovement"
static const char* kPatchField = "";   // напр. "jumpForce"
static float       kPatchMul   = 3.0f;
static const char* kWatchClass = "";
static const char* kWatchField = "";
// ===========================================================================

// ---- il2cpp API (резолвится через xDL) ----
static void*  (*il2cpp_domain_get)() = nullptr;
static void** (*il2cpp_domain_get_assemblies)(void*, size_t*) = nullptr;
static void*  (*il2cpp_assembly_get_image)(void*) = nullptr;
static size_t (*il2cpp_image_get_class_count)(void*) = nullptr;
static void*  (*il2cpp_image_get_class)(void*, size_t) = nullptr;
static void*  (*il2cpp_class_from_name)(void*, const char*, const char*) = nullptr;
static const char* (*il2cpp_class_get_name)(void*) = nullptr;
static const char* (*il2cpp_class_get_namespace)(void*) = nullptr;
static void*  (*il2cpp_class_get_fields)(void*, void**) = nullptr;
static void*  (*il2cpp_class_get_field_from_name)(void*, const char*) = nullptr;
static const char* (*il2cpp_field_get_name)(void*) = nullptr;
static size_t (*il2cpp_field_get_offset)(void*) = nullptr;
static void   (*il2cpp_field_static_get_value)(void*, void*) = nullptr;
static void   (*il2cpp_field_static_set_value)(void*, void*) = nullptr;
static void*  (*il2cpp_class_get_method_from_name)(void*, const char*, int) = nullptr;
static void*  (*il2cpp_thread_attach)(void*) = nullptr;

static std::atomic<bool> g_ready{false};
static std::atomic<bool> g_renderInit{false};
static std::vector<std::string> g_browse;
static std::vector<std::string> g_mods;     // загруженные .so (диагностика)
static std::string g_diag = "скан...";       // что нашли/не нашли
static std::string g_lib;                    // имя либы с il2cpp
static int g_w = 0, g_h = 0;

static EGLBoolean (*o_eglSwapBuffers)(EGLDisplay, EGLSurface) = nullptr;

static std::atomic<void*> g_patchInst{nullptr};
static std::atomic<void*> g_watchInst{nullptr};
static bool g_armed = false; static float g_orig = 0.f, g_target = 0.f;

static const char* baseName(const char* p) {
    const char* s = strrchr(p, '/'); return s ? s + 1 : p;
}
static int modCb(struct dl_phdr_info* info, size_t, void*) {
    if (info->dlpi_name && info->dlpi_name[0]) g_mods.emplace_back(info->dlpi_name);
    return 0;
}

// Резолвим все нужные функции из конкретного хендла. true если ключевые найдены.
static bool bindFrom(void* h) {
    #define R(f) *(void**)(&f) = xdl_sym(h, #f, nullptr);
    R(il2cpp_domain_get); R(il2cpp_domain_get_assemblies); R(il2cpp_assembly_get_image);
    R(il2cpp_image_get_class_count); R(il2cpp_image_get_class); R(il2cpp_class_from_name);
    R(il2cpp_class_get_name); R(il2cpp_class_get_namespace); R(il2cpp_class_get_fields);
    R(il2cpp_class_get_field_from_name); R(il2cpp_field_get_name); R(il2cpp_field_get_offset);
    R(il2cpp_field_static_get_value); R(il2cpp_field_static_set_value);
    R(il2cpp_class_get_method_from_name); R(il2cpp_thread_attach);
    #undef R
    return il2cpp_domain_get && il2cpp_domain_get_assemblies && il2cpp_image_get_class_count
        && il2cpp_image_get_class && il2cpp_class_get_fields && il2cpp_field_get_offset;
}

// Перебираем ВСЕ загруженные .so и ищем ту, что экспортирует il2cpp_*.
static bool resolveIl2cpp() {
    g_mods.clear();
    xdl_iterate_phdr(modCb, nullptr, XDL_FULL_PATHNAME);

    // кандидаты: явные имена + всё, где basename похож на движок/игру
    std::vector<std::string> cand = { "libil2cpp.so", "libunity.so", "libmain.so",
                                      "libGameAssembly.so", "libmono.so", "libgame.so" };
    for (auto& m : g_mods) {
        const char* b = baseName(m.c_str());
        if (strstr(b, "il2cpp") || strstr(b, "unity") || strstr(b, "assembly") ||
            strstr(b, "game") || strstr(b, "Game") || strstr(b, "main") || strstr(b, "il2"))
            cand.push_back(m);
    }

    const char* probe = "il2cpp_domain_get";
    void* hit = nullptr; std::string hitName;
    int anyIl2 = 0;
    for (auto& name : cand) {
        void* h = xdl_open(name.c_str(), XDL_DEFAULT);
        if (!h) continue;
        void* s = xdl_sym(h, probe, nullptr);
        if (!s) s = xdl_sym(h, "il2cpp_init", nullptr);  // лib присутствует, но domain_get вырезан?
        if (s) { anyIl2++;
            void* dg = xdl_sym(h, probe, nullptr);
            if (dg) { hit = h; hitName = name; break; }
        }
        xdl_close(h);
    }

    if (hit && bindFrom(hit)) {
        g_lib = baseName(hitName.c_str());
        g_diag = "il2cpp в " + g_lib;
        return true;
    }

    // не нашли — соберём диагностику для экрана
    std::string eng;
    int n = 0;
    for (auto& m : g_mods) {
        const char* b = baseName(m.c_str());
        if (strstr(b, "il2cpp") || strstr(b, "unity") || strstr(b, "assembly") || strstr(b, "mono") || strstr(b, "game")) {
            if (n++ < 10) { eng += b; eng += " "; }
        }
    }
    g_diag = "il2cpp_* НЕ найден. либ: " + std::to_string(g_mods.size()) +
             (anyIl2 ? " (либ есть, экспорты урезаны)" : "") +
             "\nдвиж/игра: " + (eng.empty() ? "нет похожих имён" : eng);
    return false;
}

static bool nameMatch(const char* n) {
    if (!n) return false;
    for (const char* s : kBrowseMatch) if (strstr(n, s)) return true;
    return false;
}

// найти класс по полному имени "Namespace.Name"
static void* findClass(const char* full) {
    if (!full || !full[0]) return nullptr;
    std::string s(full);
    size_t dot = s.find_last_of('.');
    std::string ns = (dot == std::string::npos) ? "" : s.substr(0, dot);
    std::string nm = (dot == std::string::npos) ? s : s.substr(dot + 1);
    size_t n = 0; void** asms = il2cpp_domain_get_assemblies(il2cpp_domain_get(), &n);
    for (size_t i = 0; i < n; ++i) {
        void* img = il2cpp_assembly_get_image(asms[i]);
        if (!img) continue;
        void* k = il2cpp_class_from_name(img, ns.c_str(), nm.c_str());
        if (k) return k;
    }
    return nullptr;
}

static float readF(void* obj, size_t off) { return obj ? *(float*)((char*)obj + off) : 0.f; }
static void  writeF(void* obj, size_t off, float v) { if (obj) *(float*)((char*)obj + off) = v; }

// ---- захват instance через хук .ctor ----
typedef void* (*ctor_t)(void*, void*, void*, void*, void*, void*, void*, void*);
static ctor_t o_pc = nullptr, o_wc = nullptr;
static void* h_pc(void* a0, void* a1, void* a2, void* a3, void* a4, void* a5, void* a6, void* a7) { g_patchInst.store(a0); return o_pc ? o_pc(a0,a1,a2,a3,a4,a5,a6,a7) : nullptr; }
static void* h_wc(void* a0, void* a1, void* a2, void* a3, void* a4, void* a5, void* a6, void* a7) { g_watchInst.store(a0); return o_wc ? o_wc(a0,a1,a2,a3,a4,a5,a6,a7) : nullptr; }

static void hookCtor(const char* cls, bool patch) {
    void* k = findClass(cls); if (!k) { LOG("ctor: class not found %s", cls); return; }
    void* m = il2cpp_class_get_method_from_name(k, ".ctor", -1); if (!m) return;
    void* ptr = *(void**)m; // methodPointer = первое поле MethodInfo
    if (!ptr) return;
    if (patch) A64HookFunction(ptr, (void*)h_pc, (void**)&o_pc);
    else       A64HookFunction(ptr, (void*)h_wc, (void**)&o_wc);
    LOG("ctor hooked %s @%p", cls, ptr);
}

static void buildBrowse() {
    char line[320];
    size_t n = 0; void** asms = il2cpp_domain_get_assemblies(il2cpp_domain_get(), &n);
    for (size_t i = 0; i < n && g_browse.size() < 500; ++i) {
        void* img = il2cpp_assembly_get_image(asms[i]); if (!img) continue;
        size_t cc = il2cpp_image_get_class_count(img);
        for (size_t c = 0; c < cc && g_browse.size() < 500; ++c) {
            void* k = il2cpp_image_get_class(img, c); if (!k) continue;
            const char* nm = il2cpp_class_get_name(k);
            if (!nameMatch(nm)) continue;
            const char* ns = il2cpp_class_get_namespace(k);
            std::string fs; void* it = nullptr; void* f;
            while ((f = il2cpp_class_get_fields(k, &it))) {
                snprintf(line, sizeof line, "%s@0x%x ", il2cpp_field_get_name(f), (unsigned)il2cpp_field_get_offset(f));
                fs += line;
            }
            snprintf(line, sizeof line, "%s.%s { %s}", ns ? ns : "", nm ? nm : "?", fs.c_str());
            g_browse.emplace_back(line);
        }
    }
    LOG("browse: %zu", g_browse.size());
}

static void applyPatch() {
    if (!kPatchClass[0]) return;
    void* inst = g_patchInst.load(); if (!inst) return;
    void* k = findClass(kPatchClass); if (!k) return;
    void* f = il2cpp_class_get_field_from_name(k, kPatchField); if (!f) return;
    size_t off = il2cpp_field_get_offset(f);
    if (!g_armed) { g_orig = readF(inst, off); g_target = g_orig * kPatchMul; g_armed = true; }
    writeF(inst, off, g_target);
}

// ---- ImGui overlay ----
static void drawOverlay() {
    ImGui::SetNextWindowPos(ImVec2(20, 60), ImGuiCond_Once);
    float w = g_w * 0.6f; if (w > 560) w = 560;
    ImGui::SetNextWindowSize(ImVec2(w, g_h * 0.7f), ImGuiCond_Once);
    ImGui::SetNextWindowBgAlpha(0.85f);
    ImGui::Begin("FarmerShoot v8 (native)");
    ImGui::TextColored(ImVec4(0.6f,1,0.5f,1), "il2cpp: %s", g_ready.load() ? "READY" : "...");
    ImGui::TextWrapped("%s", g_diag.c_str());
    if (kWatchClass[0]) {
        void* inst = g_watchInst.load();
        void* k = findClass(kWatchClass);
        void* f = k ? il2cpp_class_get_field_from_name(k, kWatchField) : nullptr;
        if (inst && f) ImGui::Text("%s.%s = %.3f", kWatchClass, kWatchField, readF(inst, il2cpp_field_get_offset(f)));
    }
    if (kPatchClass[0]) ImGui::TextColored(ImVec4(1,0.6f,0.3f,1), "patch %s.%s x%.1f %s",
        kPatchClass, kPatchField, kPatchMul, g_armed ? "ON" : "ждём");
    ImGui::Separator();
    ImGui::Text("Живые классы: %d", (int)g_browse.size());
    ImGui::BeginChild("b", ImVec2(0,0), true);
    for (auto& s : g_browse) ImGui::TextUnformatted(s.c_str());
    ImGui::EndChild();
    ImGui::End();
}

static EGLBoolean h_egl(EGLDisplay d, EGLSurface s) {
    if (!g_renderInit.load()) {
        eglQuerySurface(d, s, EGL_WIDTH, &g_w); eglQuerySurface(d, s, EGL_HEIGHT, &g_h);
        ImGui::CreateContext(); ImGui::GetIO().IniFilename = nullptr;
        ImGui_ImplAndroid_Init(ImVec2((float)g_w, (float)g_h));
        ImGui_ImplOpenGL3_Init("#version 300 es");
        g_renderInit = true; LOG("overlay %dx%d", g_w, g_h);
    }
    if (g_ready.load()) applyPatch();
    ImGui_ImplOpenGL3_NewFrame(); ImGui_ImplAndroid_NewFrame(); ImGui::NewFrame();
    drawOverlay();
    ImGui::Render(); ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData()); ImGui::EndFrame();
    return o_eglSwapBuffers(d, s);
}

static void worker() {
    o_eglSwapBuffers = (EGLBoolean(*)(EGLDisplay,EGLSurface))eglSwapBuffers;
    A64HookFunction((void*)&eglSwapBuffers, (void*)h_egl, (void**)&o_eglSwapBuffers);
    LOG("egl hooked");
    // ждём libil2cpp и резолвим через xDL
    for (int i = 0; i < 600 && !g_ready.load(); ++i) {
        if (resolveIl2cpp()) {
            if (il2cpp_thread_attach && il2cpp_domain_get) il2cpp_thread_attach(il2cpp_domain_get());
            buildBrowse();
            if (kPatchClass[0]) hookCtor(kPatchClass, true);
            if (kWatchClass[0]) hookCtor(kWatchClass, false);
            g_ready = true; LOG("il2cpp ready");
            break;
        }
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    if (!g_ready.load()) LOG("il2cpp NOT resolved");
}

__attribute__((constructor)) static void entry() { std::thread(worker).detach(); }

// ---- JNI ----
extern "C" JNIEXPORT jstring JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeInfo(JNIEnv* env, jclass) {
    std::string s; char h[320];
    snprintf(h, sizeof h, "il2cpp: %s\n%s\noverlay(GL): %s\nклассов: %d\n",
             g_ready.load() ? ("READY в " + g_lib).c_str() : "waiting(xdl)...",
             g_diag.c_str(),
             g_renderInit.load() ? "ON" : "нет",
             (int)g_browse.size());
    s = h;
    if (g_ready.load()) {
        s += "— живые классы/поля —\n"; int n = 0;
        for (auto& l : g_browse) { s += l; s += "\n"; if (++n >= 40) { s += "...(полный в DUMP)\n"; break; } }
    }
    return env->NewStringUTF(s.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeDumpTo(JNIEnv* env, jclass, jstring jp) {
    const char* path = env->GetStringUTFChars(jp, nullptr);
    FILE* f = fopen(path, "w"); env->ReleaseStringUTFChars(jp, path);
    if (!f) return JNI_FALSE;
    if (g_mods.empty()) xdl_iterate_phdr(modCb, nullptr, XDL_FULL_PATHNAME);
    // всегда пишем список загруженных .so и диагностику
    fprintf(f, "# FarmerShoot v8 diag\n# %s\n# modules: %zu\n", g_diag.c_str(), g_mods.size());
    for (auto& m : g_mods) fprintf(f, "LIB %s\n", m.c_str());
    fprintf(f, "\n");
    if (!g_ready.load()) { fclose(f); LOG("dump: modules only"); return JNI_TRUE; }
    size_t n = 0; void** asms = il2cpp_domain_get_assemblies(il2cpp_domain_get(), &n);
    fprintf(f, "# FarmerShoot v8 LIVE dump — assemblies: %zu\n\n", n);
    for (size_t i = 0; i < n; ++i) {
        void* img = il2cpp_assembly_get_image(asms[i]); if (!img) continue;
        size_t cc = il2cpp_image_get_class_count(img);
        for (size_t c = 0; c < cc; ++c) {
            void* k = il2cpp_image_get_class(img, c); if (!k) continue;
            const char* ns = il2cpp_class_get_namespace(k); const char* nm = il2cpp_class_get_name(k);
            fprintf(f, "%s.%s {", ns ? ns : "", nm ? nm : "?");
            void* it = nullptr; void* fl;
            while ((fl = il2cpp_class_get_fields(k, &it)))
                fprintf(f, " %s@0x%x", il2cpp_field_get_name(fl), (unsigned)il2cpp_field_get_offset(fl));
            fprintf(f, " }\n");
        }
    }
    fclose(f); LOG("dump ok"); return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativePatchJump(JNIEnv*, jclass, jfloat) {
    return (kPatchClass[0] && g_armed) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeRestore(JNIEnv*, jclass) {
    if (!kPatchClass[0] || !g_armed) return JNI_FALSE;
    void* inst = g_patchInst.load(); void* k = findClass(kPatchClass);
    void* f = k ? il2cpp_class_get_field_from_name(k, kPatchField) : nullptr;
    if (inst && f) { writeF(inst, il2cpp_field_get_offset(f), g_orig); g_armed = false; return JNI_TRUE; }
    return JNI_FALSE;
}
