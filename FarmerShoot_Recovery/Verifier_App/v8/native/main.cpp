// ============================================================================
// FarmerShoot Verifier v8 — нативный оверлей (реализация).
// Показывает окно поверх игры: таблица watch-полей (имя | значение | Δ),
// кнопка «ПРОВЕРИТЬ» и переключатель авто-обновления. Значения читаются из
// ЖИВОГО процесса игры через IL2CPP-резолвер (по имени класса/поля).
// ============================================================================
#include "main.h"

EglState egl;
std::atomic<bool> g_menuOpen{true};
std::atomic<bool> g_autoRefresh{true};

// original fn pointers
static EGLBoolean (*o_EglSwapBuffers)(EGLDisplay, EGLSurface) = nullptr;

// ---- хранилище значений/диффов ---------------------------------------------
struct Cell { std::string value; std::string prev; bool changed = false; double delta = 0; bool hasDelta = false; };
static std::unordered_map<std::string, Cell> g_cells;

static std::string keyOf(const WatchItem& w) {
    return std::string(w.cls) + "." + w.field;
}

// Прочитать одно watch-поле из живого процесса по ИМЕНИ.
static bool readOne(const WatchItem& w, std::string& outStr, double& outNum, bool& hasNum) {
    hasNum = false;
    IL2CPP::Unity::il2cppClass* klass = IL2CPP::Class::Find(w.cls);
    if (!klass) { outStr = "<no class>"; return false; }

    // instance: найти живой объект класса (резолвер умеет перечислять через GC-хелпер;
    // если его нет — используем статический путь ниже как фоллбэк).
    void* instance = nullptr;
    if (w.kind == WKind::InstanceField) {
        // GetFirstInstance — хелпер из нашего Resolver/Utils (см. overlay.md).
        instance = IL2CPP::Class::Utils::GetFirstInstance(klass);
        if (!instance) { outStr = "<no instance>"; return false; }
    }

    int off = IL2CPP::Class::Utils::GetFieldOffset(klass, w.field);
    if (off < 0) { outStr = "<no field>"; return false; }

    char buf[64];
    if (w.kind == WKind::StaticField) {
        void* v = IL2CPP::Class::Utils::GetStaticField(klass, w.field);
        switch (w.type) {
            case WType::Int:   snprintf(buf, sizeof buf, "%d", (int)(intptr_t)v); outNum = (double)(intptr_t)v; hasNum = true; break;
            case WType::Bool:  snprintf(buf, sizeof buf, "%s", v ? "true" : "false"); break;
            case WType::Float: { float f; memcpy(&f, &v, sizeof f); snprintf(buf, sizeof buf, "%.3f", f); outNum = f; hasNum = true; break; }
            default:           snprintf(buf, sizeof buf, "%p", v); break;
        }
    } else {
        uintptr_t base = reinterpret_cast<uintptr_t>(instance) + off;
        switch (w.type) {
            case WType::Int:   { int   i = *reinterpret_cast<int*>(base);   snprintf(buf, sizeof buf, "%d", i);   outNum = i; hasNum = true; break; }
            case WType::Float: { float f = *reinterpret_cast<float*>(base); snprintf(buf, sizeof buf, "%.3f", f); outNum = f; hasNum = true; break; }
            case WType::Bool:  { bool  b = *reinterpret_cast<bool*>(base);  snprintf(buf, sizeof buf, "%s", b ? "true" : "false"); break; }
            default:           { void* p = *reinterpret_cast<void**>(base); snprintf(buf, sizeof buf, "%p", p); break; }
        }
    }
    outStr = buf;
    return true;
}

// Снимок всех watch-полей; считаем, что изменилось относительно прошлого.
void App::DoCheck() {
    for (const auto& w : kWatch) {
        std::string s; double num = 0; bool hasNum = false;
        bool ok = readOne(w, s, num, hasNum);
        Cell& c = g_cells[keyOf(w)];
        if (ok) {
            if (!c.value.empty() && c.value != s) {
                c.changed = true;
                c.prev = c.value;
                if (hasNum) { c.hasDelta = true; /* delta считаем из строк ниже при показе */ }
            } else {
                c.changed = false;
            }
            c.value = s;
            if (hasNum && !c.prev.empty()) { c.delta = num - atof(c.prev.c_str()); }
        } else {
            c.value = s; c.changed = false;
        }
    }
}

// ---- ImGui меню -------------------------------------------------------------
void App::DrawMenu() {
    if (!g_menuOpen.load()) {
        // маленькая кнопка-«пилюля», чтобы открыть
        ImGui::SetNextWindowBgAlpha(0.35f);
        ImGui::SetNextWindowPos(ImVec2(16, 16), ImGuiCond_Once);
        ImGui::Begin("v8", nullptr, ImGuiWindowFlags_NoTitleBar | ImGuiWindowFlags_AlwaysAutoResize |
                                     ImGuiWindowFlags_NoResize | ImGuiWindowFlags_NoMove);
        if (ImGui::Button("v8 ▸")) g_menuOpen = true;
        ImGui::End();
        return;
    }

    ImGui::SetNextWindowBgAlpha(0.88f);
    ImGui::SetNextWindowPos(ImVec2(16, 16), ImGuiCond_Once);
    ImGui::SetNextWindowSize(ImVec2(360, 0), ImGuiCond_Once);
    ImGui::Begin("FarmerShoot v8 — Live Inspector", nullptr, ImGuiWindowFlags_AlwaysAutoResize);

    if (ImGui::Button("ПРОВЕРИТЬ")) App::DoCheck();
    ImGui::SameLine();
    bool ar = g_autoRefresh.load();
    if (ImGui::Checkbox("авто", &ar)) g_autoRefresh = ar;
    ImGui::SameLine();
    if (ImGui::Button("свернуть")) g_menuOpen = false;

    ImGui::Separator();

    if (kWatch.empty()) {
        ImGui::TextWrapped("watch[] пуст. Откройте v8_dump.txt (пишется при старте в "
                           "Android/data/<pkg>/files/), выберите поля и впишите их в "
                           "WatchConfig.hpp, затем пересоберите.");
    } else if (ImGui::BeginTable("watch", 3,
                 ImGuiTableFlags_Borders | ImGuiTableFlags_RowBg | ImGuiTableFlags_SizingStretchProp)) {
        ImGui::TableSetupColumn("Поле");
        ImGui::TableSetupColumn("Значение");
        ImGui::TableSetupColumn("Δ");
        ImGui::TableHeadersRow();
        for (const auto& w : kWatch) {
            const Cell& c = g_cells[keyOf(w)];
            ImGui::TableNextRow();
            ImGui::TableSetColumnIndex(0);
            ImGui::TextUnformatted(w.label ? w.label : w.field);
            ImGui::TableSetColumnIndex(1);
            if (c.changed) ImGui::TextColored(ImVec4(1.0f, 0.55f, 0.25f, 1.0f), "%s", c.value.c_str());
            else           ImGui::TextUnformatted(c.value.empty() ? "—" : c.value.c_str());
            ImGui::TableSetColumnIndex(2);
            if (c.changed && c.hasDelta) ImGui::TextColored(ImVec4(1, 0.4f, 0.4f, 1), "%s%.3g",
                                               c.delta > 0 ? "+" : "", c.delta);
            else if (c.changed)          ImGui::TextColored(ImVec4(1, 0.4f, 0.4f, 1), "изм.");
            else                         ImGui::TextUnformatted("");
        }
        ImGui::EndTable();
    }

    ImGui::Separator();
    ImGui::TextDisabled("resolve-by-name · live process · no hardcoded offsets");
    ImGui::End();
}

// ---- render hook ------------------------------------------------------------
EGLBoolean App::h_EglSwapBuffers(EGLDisplay display, EGLSurface surface) {
    if (!egl.renderInitialized) {
        eglQuerySurface(display, surface, EGL_WIDTH,  &egl.width);
        eglQuerySurface(display, surface, EGL_HEIGHT, &egl.height);
        ImGui::CreateContext();
        ImGui::GetIO().IniFilename = nullptr;
        ImGui_ImplAndroid_Init(ImVec2((float)egl.width, (float)egl.height));
        ImGui_ImplOpenGL3_Init();
        egl.renderInitialized = true;
    }

    if (g_autoRefresh.load()) App::DoCheck();   // авто-обновление значений каждый кадр

    ImGui_ImplOpenGL3_NewFrame();
    ImGui_ImplAndroid_NewFrame();
    ImGui::NewFrame();
    App::DrawMenu();
    ImGui::Render();
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    ImGui::EndFrame();
    return o_EglSwapBuffers(display, surface);
}

// ---- touch input hook (делает кнопку «Проверить» тапабельной) ---------------
// Прокидываем AInputEvent в ImGui. Привязка к конкретной цели хука — в build:
// хукаем ANativeActivity onInputEvent / InputConsumer (см. overlay.md).
static int32_t (*o_handleInput)(void*, AInputEvent*) = nullptr;
int32_t App::h_handleInput(void* app, AInputEvent* event) {
    if (egl.renderInitialized && ImGui::GetCurrentContext())
        ImGui_ImplAndroid_HandleInputEvent(event);
    // если курсор над нашим окном — «съедаем» событие, иначе отдаём игре
    if (ImGui::GetIO().WantCaptureMouse) return 1;
    return o_handleInput ? o_handleInput(app, event) : 0;
}

bool App::Initialize() {
    if (!IL2CPP::Initialize(true)) return false;   // ждём загрузки libil2cpp
    o_EglSwapBuffers = eglSwapBuffers;
    void* sym = reinterpret_cast<void*>(eglSwapBuffers);
    if (!sym) return false;
    return DobbyHook(sym, reinterpret_cast<void*>(h_EglSwapBuffers),
                     reinterpret_cast<void**>(&o_EglSwapBuffers)) == 0;
    // touch-hook ставится отдельно в build-варианте (цель зависит от движка ввода).
}

__attribute__((constructor)) static void v8_init() {
    std::thread([] {
        if (!App::Initialize()) { LOGE("v8: init failed"); }
        else                    { LOGI("v8: overlay ready"); }
    }).detach();
}
