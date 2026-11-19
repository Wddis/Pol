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
#include <cstdlib>
#include <cstdint>
#include <cinttypes>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <algorithm>
#include <fcntl.h>
#include <unistd.h>

#include "And64InlineHook.hpp"
#include "xdl.h"
#include "engine_logic.hpp"   // общая логика (её же проверяет v8/selftest)
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

// символ из .dynsym (xdl_sym) ИЛИ из .symtab/.gnu_debugdata (xdl_dsym)
static void* sym2(void* h, const char* name) {
    void* s = xdl_sym(h, name, nullptr);
    if (!s) s = xdl_dsym(h, name, nullptr);
    return s;
}

// Резолвим все нужные функции из конкретного хендла. true если ключевые найдены.
static bool bindFrom(void* h) {
    #define R(f) *(void**)(&f) = sym2(h, #f);
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
    void* hit = nullptr; std::string hitName; const char* hitVia = "";
    int anyIl2 = 0;
    for (auto& name : cand) {
        void* h = xdl_open(name.c_str(), XDL_DEFAULT);
        if (!h) continue;
        void* dyn = xdl_sym(h, probe, nullptr);
        void* dbg = dyn ? nullptr : xdl_dsym(h, probe, nullptr);
        if (dyn || dbg) { anyIl2++; hit = h; hitName = name; hitVia = dyn ? "dynsym" : "symtab"; break; }
        // либа есть, но имя вырезано? отметим присутствие il2cpp_init в любом виде
        if (sym2(h, "il2cpp_init")) anyIl2++;
        xdl_close(h);
    }

    if (hit && bindFrom(hit)) {
        g_lib = baseName(hitName.c_str());
        g_diag = "il2cpp в " + g_lib + " (" + hitVia + ")";
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

static float readF(void* obj, size_t off) { return fv8::read_field_f(obj, off); }
static void  writeF(void* obj, size_t off, float v) { fv8::write_field_f(obj, off, v); }

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
    if (!g_armed) { g_orig = readF(inst, off); g_target = fv8::compute_target(g_orig, kPatchMul); g_armed = true; }
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
    // ImGui-оверлей отключён: UI теперь в Java-окне (WindowManager). Хук egl не ставим,
    // чтобы не было второго окна. il2cpp-резолв/скан/дамп работают независимо от рендера.
    (void)&h_egl;
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
// ======================= ДАМП для Il2CppDumper ==============================
// Снимаем расшифрованные файлы прямо из процесса: libunity.so (с диска) и
// global-metadata.dat (из памяти по магии 0xFAB11BAF). Работает при полностью
// вырезанных символах — дальше их обрабатывает Il2CppDumper (сигнатурный скан).
static bool copyFile(const char* src, const char* dst) {
    FILE* in = fopen(src, "rb"); if (!in) return false;
    FILE* out = fopen(dst, "wb"); if (!out) { fclose(in); return false; }
    char buf[1 << 16]; size_t n; bool ok = true;
    while ((n = fread(buf, 1, sizeof buf, in)) > 0) if (fwrite(buf, 1, n, out) != n) { ok = false; break; }
    fclose(in); fclose(out); return ok;
}

// ищем libunity.so путь в maps и копируем файл
static bool dumpLibunity(const char* dir, std::string& msg) {
    FILE* m = fopen("/proc/self/maps", "r"); if (!m) { msg = "maps?"; return false; }
    char line[1024], path[1024]; std::string found;
    while (fgets(line, sizeof line, m)) {
        if (strstr(line, "libunity.so")) {
            char* p = strchr(line, '/');
            if (p) { p[strcspn(p, "\n")] = 0; found = p; break; }
        }
    }
    fclose(m);
    if (found.empty()) { msg = "libunity.so не в maps"; return false; }
    std::string dst = std::string(dir) + "/libunity.so";
    bool ok = copyFile(found.c_str(), dst.c_str());
    msg = ok ? ("libunity.so OK (" + found + ")") : "копия libunity.so не удалась";
    return ok;
}

// скан памяти процесса на магию метадаты и выгрузка расшифрованной копии
static bool dumpMetadata(const char* dir, std::string& msg) {
    int fd = open("/proc/self/mem", O_RDONLY); if (fd < 0) { msg = "mem?"; return false; }
    FILE* m = fopen("/proc/self/maps", "r"); if (!m) { close(fd); msg = "maps?"; return false; }
    const uint32_t MAGIC = fv8::METADATA_MAGIC;
    char line[1024];
    std::vector<char> buf;
    uint64_t foundAddr = 0, foundEnd = 0;
    while (fgets(line, sizeof line, m) && !foundAddr) {
        uint64_t s = 0, e = 0; char perms[8] = {0};
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s", &s, &e, perms) != 3) continue;
        if (perms[0] != 'r') continue;
        uint64_t sz = e - s;
        if (sz < 0x10000 || sz > (512ull << 20)) continue;      // 64КБ..512МБ
        buf.resize(1 << 20);
        for (uint64_t off = 0; off < sz && !foundAddr; off += (buf.size() - 8)) {
            size_t want = (size_t)std::min<uint64_t>(buf.size(), sz - off);
            ssize_t rd = pread(fd, buf.data(), want, (off_t)(s + off));
            if (rd <= 4) continue;
            for (ssize_t i = 0; i + 4 <= rd; ++i) {
                if (*(uint32_t*)(buf.data() + i) == MAGIC) {
                    uint64_t addr = s + off + i;
                    // читаем заголовок (ints) и оцениваем размер метадаты
                    uint32_t hdr[80];
                    ssize_t h = pread(fd, hdr, sizeof hdr, (off_t)addr);
                    if (h < 16) continue;
                    if (!fv8::version_sane(hdr[1])) continue;    // version sanity (24/27/29/31...)
                    uint64_t maxEnd = fv8::estimate_metadata_size(hdr, (size_t)(h / 4));
                    foundAddr = addr; foundEnd = maxEnd; break;
                }
            }
        }
    }
    fclose(m);
    if (!foundAddr) { close(fd); msg = "global-metadata в памяти не найдена (магия 0xFAB11BAF)"; return false; }

    std::string dst = std::string(dir) + "/global-metadata.dat";
    FILE* out = fopen(dst.c_str(), "wb");
    if (!out) { close(fd); msg = "не создать файл metadata"; return false; }
    std::vector<char> cb(1 << 20); uint64_t written = 0;
    while (written < foundEnd) {
        size_t want = (size_t)std::min<uint64_t>(cb.size(), foundEnd - written);
        ssize_t rd = pread(fd, cb.data(), want, (off_t)(foundAddr + written));
        if (rd <= 0) break;
        fwrite(cb.data(), 1, rd, out); written += rd;
    }
    fclose(out); close(fd);
    char b[128]; snprintf(b, sizeof b, "global-metadata.dat OK (%llu МБ, @0x%llx)", (unsigned long long)(written >> 20), (unsigned long long)foundAddr);
    msg = b;
    return written > 0x1000;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeDumpFiles(JNIEnv* env, jclass, jstring jdir) {
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    std::string m1, m2;
    bool a = dumpLibunity(dir, m1);
    bool b = dumpMetadata(dir, m2);
    env->ReleaseStringUTFChars(jdir, dir);
    std::string r = std::string(a ? "✓ " : "✗ ") + m1 + "\n" + (b ? "✓ " : "✗ ") + m2;
    LOG("dumpFiles: %s", r.c_str());
    return env->NewStringUTF(r.c_str());
}

// ======================= СКАНЕР ПАМЯТИ (GameGuardian-style) =================
// Находит адреса значений (ammo/jump) без символов/метадаты: поиск -> уточнение.
static std::vector<uintptr_t> g_cand;
static int g_memRD = -1, g_memRW = -1;
static int memRD() { if (g_memRD < 0) g_memRD = open("/proc/self/mem", O_RDONLY); return g_memRD; }
static int memRW() { if (g_memRW < 0) g_memRW = open("/proc/self/mem", O_RDWR);  return g_memRW; }

static void scanNew(bool isFloat, int iv, float fv) {
    g_cand.clear();
    int fd = memRD(); if (fd < 0) return;
    FILE* m = fopen("/proc/self/maps", "r"); if (!m) return;
    char line[1024];
    std::vector<unsigned char> buf(1 << 20);
    const size_t CAP = 2000000;
    while (fgets(line, sizeof line, m) && g_cand.size() < CAP) {
        uint64_t s = 0, e = 0; char perms[8] = {0};
        if (sscanf(line, "%" SCNx64 "-%" SCNx64 " %7s", &s, &e, perms) != 3) continue;
        if (perms[0] != 'r' || perms[1] != 'w') continue;          // только rw (куча/аноним)
        uint64_t sz = e - s; if (sz > (512ull << 20)) continue;
        for (uint64_t off = 0; off < sz && g_cand.size() < CAP; off += buf.size()) {
            size_t want = (size_t)std::min<uint64_t>(buf.size(), sz - off);
            ssize_t rd = pread(fd, buf.data(), want, (off_t)(s + off));
            if (rd <= 4) continue;
            if (isFloat) fv8::find_f32(buf.data(), rd, (uintptr_t)(s + off), fv, 0.01f, g_cand, CAP);
            else         fv8::find_i32(buf.data(), rd, (uintptr_t)(s + off), iv, g_cand, CAP);
        }
    }
    fclose(m);
}
static void scanRefine(bool isFloat, int iv, float fv) {
    int fd = memRD(); if (fd < 0) return;
    std::vector<uintptr_t> keep; unsigned char t[4];
    for (auto a : g_cand) {
        if (pread(fd, t, 4, (off_t)a) == 4) {
            bool ok = isFloat ? fv8::at_equals_f32(t, fv, 0.01f) : fv8::at_equals_i32(t, iv);
            if (ok) keep.push_back(a);
        }
    }
    g_cand.swap(keep);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeScanNew(JNIEnv* env, jclass, jstring jval, jboolean isFloat) {
    const char* v = env->GetStringUTFChars(jval, nullptr);
    scanNew(isFloat, atoi(v), (float)atof(v));
    env->ReleaseStringUTFChars(jval, v);
    return (jint)g_cand.size();
}
extern "C" JNIEXPORT jint JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeScanRefine(JNIEnv* env, jclass, jstring jval, jboolean isFloat) {
    const char* v = env->GetStringUTFChars(jval, nullptr);
    scanRefine(isFloat, atoi(v), (float)atof(v));
    env->ReleaseStringUTFChars(jval, v);
    return (jint)g_cand.size();
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeScanSave(JNIEnv* env, jclass, jstring jdir, jboolean isFloat) {
    const char* dir = env->GetStringUTFChars(jdir, nullptr);
    std::string path = std::string(dir) + "/v8_addresses.txt";
    env->ReleaseStringUTFChars(jdir, dir);
    FILE* f = fopen(path.c_str(), "w");
    if (!f) return env->NewStringUTF("не удалось создать файл");
    // база libunity.so для относительных адресов (чтобы адрес был стабильнее)
    uintptr_t uniBase = 0; { FILE* m = fopen("/proc/self/maps", "r"); char l[1024];
        while (m && fgets(l, sizeof l, m)) { if (strstr(l, "libunity.so")) { sscanf(l, "%" SCNx64, (uint64_t*)&uniBase); break; } } if (m) fclose(m); }
    int fd = memRD();
    fprintf(f, "# FarmerShoot v8 — найденные адреса (%s)\n# libunity.so base = 0x%llx\n# кандидатов: %zu\n\n",
            isFloat ? "float" : "int", (unsigned long long)uniBase, g_cand.size());
    size_t lim = g_cand.size() < 200 ? g_cand.size() : 200;
    for (size_t i = 0; i < lim; ++i) {
        uintptr_t a = g_cand[i]; unsigned char t[4] = {0}; if (fd >= 0) pread(fd, t, 4, (off_t)a);
        char val[32];
        if (isFloat) { float fv; memcpy(&fv, t, 4); snprintf(val, sizeof val, "%.4f", fv); }
        else         { int iv;   memcpy(&iv, t, 4); snprintf(val, sizeof val, "%d", iv); }
        long rel = uniBase ? (long)(a - uniBase) : 0;
        fprintf(f, "ADDR 0x%llx  REL_unity 0x%lx  VALUE %s\n", (unsigned long long)a, rel, val);
    }
    fclose(f);
    char msg[256]; snprintf(msg, sizeof msg, "сохранено %zu адресов -> %s", lim, path.c_str());
    LOG("%s", msg); return env->NewStringUTF(msg);
}
// Записать значение во все текущие кандидаты (если их немного) — для проверки «на глаз».
extern "C" JNIEXPORT jint JNICALL
Java_com_recovery_farmerv8_FarmerV8Module_nativeScanPatch(JNIEnv* env, jclass, jstring jval, jboolean isFloat) {
    const char* v = env->GetStringUTFChars(jval, nullptr);
    int iv = atoi(v); float fv = (float)atof(v);
    env->ReleaseStringUTFChars(jval, v);
    if (g_cand.empty() || g_cand.size() > 50) return -(jint)g_cand.size(); // слишком много — сначала уточните
    int fd = memRW(); if (fd < 0) return 0; int n = 0;
    for (auto a : g_cand) {
        if (isFloat) { if (pwrite(fd, &fv, 4, (off_t)a) == 4) n++; }
        else         { if (pwrite(fd, &iv, 4, (off_t)a) == 4) n++; }
    }
    return n;
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
