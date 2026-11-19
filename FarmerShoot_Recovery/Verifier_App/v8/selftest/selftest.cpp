// ============================================================================
// FarmerShoot v8 — САМОТЕСТ 3/3 для СКАНЕРА ПАМЯТИ.
// Доказывает метод «найти значение -> уточнить по изменению -> пропатчить»
// (как GameGuardian) БЕЗ символов/метадаты/расшифровки lib, используя ТЕ ЖЕ
// функции движка из ../native/final/engine_logic.hpp.
//
//   TEST 1/3  Поиск: в «куче» находим все адреса с текущим числом патронов (30).
//   TEST 2/3  Уточнение: после выстрела (30->29) остаётся РОВНО один адрес ammo.
//   TEST 3/3  Патч: по найденному адресу ставим ammo=999 и прыжок ×3, затем откат.
//
// Сборка/запуск: c++ -std=c++17 -I../native/final selftest.cpp -o selftest && ./selftest
// ============================================================================
#include "engine_logic.hpp"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

static int g_fail = 0;
#define CHECK(cond, msg) do { if (cond) printf("    [ok] %s\n", msg); \
    else { printf("    [FAIL] %s\n", msg); g_fail++; } } while(0)

int main() {
    printf("================ FarmerShoot v8 — SELF-TEST 3/3 (сканер) ================\n");
    printf("(проверяет engine_logic.hpp — тот же код, что в libfarmerv8.so)\n\n");

    // «куча игры»: 4096 int, заполнены мусором; несколько «ложных» 30 (декои) и
    // одна ячейка — настоящие патроны; отдельная ячейка — прыжок (float).
    const size_t N = 4096;
    std::vector<int32_t> heap(N);
    for (size_t i = 0; i < N; i++) heap[i] = (int)(i * 2654435761u) & 0x3FFFFFFF; // псевдослучайно
    // декои со значением 30 (другие объекты с таким же числом)
    size_t decoys[] = { 100, 900, 2500, 3600 };
    for (size_t d : decoys) heap[d] = 30;
    // настоящая ячейка патронов
    size_t ammoIdx = 1777; heap[ammoIdx] = 30;
    // ячейка прыжка (float 8.0)
    size_t jumpIdx = 2222; float jv = 8.0f; memcpy(&heap[jumpIdx], &jv, 4);

    unsigned char* base = (unsigned char*)heap.data();
    uintptr_t baseAddr = (uintptr_t)base;
    size_t bytes = N * 4;

    // ---------------- TEST 1/3 : поиск значения ----------------
    printf("TEST 1/3 — поиск: ищем все адреса, где лежит 30 (текущие патроны)\n");
    std::vector<uintptr_t> cand;
    fv8::find_i32(base, bytes, baseAddr, 30, cand, 100000);
    bool ammoFound = false;
    for (auto a : cand) if (a == baseAddr + ammoIdx * 4) ammoFound = true;
    printf("    кандидатов с значением 30: %zu (включая декои)\n", cand.size());
    CHECK(cand.size() >= 5, "найдено несколько кандидатов (декои + настоящий)");
    CHECK(ammoFound, "адрес настоящих патронов среди кандидатов");
    bool t1 = cand.size() >= 5 && ammoFound;
    printf("  => %s\n\n", t1 ? "PASS" : "FAIL");

    // ---------------- TEST 2/3 : уточнение после выстрела ----------------
    printf("TEST 2/3 — уточнение: стреляем (30->29), оставляем изменившийся адрес\n");
    heap[ammoIdx] = 29;                     // «выстрел»: уменьшилось только у ammo
    std::vector<uintptr_t> refined;
    for (auto a : cand)
        if (fv8::at_equals_i32((const unsigned char*)a, 29)) refined.push_back(a);
    printf("    после уточнения осталось: %zu\n", refined.size());
    CHECK(refined.size() == 1, "остался РОВНО один адрес");
    CHECK(!refined.empty() && refined[0] == baseAddr + ammoIdx * 4, "это адрес патронов");
    bool t2 = refined.size() == 1 && refined[0] == baseAddr + ammoIdx * 4;
    printf("  => %s  (адрес ammo = 0x%llx)\n\n", t2 ? "PASS" : "FAIL",
           (unsigned long long)(t2 ? refined[0] : 0));

    // ---------------- TEST 3/3 : патч по найденному адресу ----------------
    printf("TEST 3/3 — патч: ammo=999 и прыжок ×3 по найденным адресам, затем откат\n");
    bool t3 = false;
    if (t2) {
        void* ammoAddr = (void*)refined[0];
        fv8::write_field_i(ammoAddr, 0, 999);                 // патроны = 999
        CHECK(fv8::read_field_i(ammoAddr, 0) == 999, "ammo записан = 999");

        // прыжок: находим 8.0, ×3 -> 24.0
        std::vector<uintptr_t> jc;
        fv8::find_f32(base, bytes, baseAddr, 8.0f, 0.0001f, jc, 100000);
        bool jok = false; void* jumpAddr = nullptr;
        for (auto a : jc) if (a == baseAddr + jumpIdx * 4) { jok = true; jumpAddr = (void*)a; }
        CHECK(jok, "адрес прыжка (float 8.0) найден");
        float orig = fv8::read_field_f(jumpAddr, 0);
        fv8::write_field_f(jumpAddr, 0, fv8::compute_target(orig, 3.0f));
        CHECK(fv8::read_field_f(jumpAddr, 0) == 24.0f, "прыжок ×3: 8.0 -> 24.0");
        fv8::write_field_f(jumpAddr, 0, orig);
        CHECK(fv8::read_field_f(jumpAddr, 0) == 8.0f, "откат прыжка -> 8.0");

        t3 = fv8::read_field_i(ammoAddr, 0) == 999 && jok;
    }
    printf("  => %s\n\n", t3 ? "PASS" : "FAIL");

    int passed = (t1 ? 1 : 0) + (t2 ? 1 : 0) + (t3 ? 1 : 0);
    printf("=================== ИТОГ: %d/3 (провалов проверок: %d) ===================\n", passed, g_fail);
    printf("%s\n", (passed == 3 && g_fail == 0)
        ? "БАЗА ДОКАЗАНА: поиск значения -> уточнение -> точный адрес -> патч. Без символов/метадаты/расшифровки lib."
        : "ЕСТЬ ПРОВАЛ.");
    return (passed == 3 && g_fail == 0) ? 0 : 1;
}
