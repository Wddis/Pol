// ============================================================================
// FarmerShoot v8 — САМОТЕСТ 3/3 для ОФСЕТ-КАЛЬКУЛЯТОРА и POINTER-SCAN.
// Доказывает: по статической базе (libunity) + цепочке оффсетов находим точный
// адрес значения и меняем его; pointer-scan строит стабильный путь из найденного
// адреса ammo. Используются ТЕ ЖЕ функции движка (engine_logic.hpp).
//
//   TEST 1/3  resolve_path: [base+o0]->+o1 даёт точный адрес; читаем значение.
//   TEST 2/3  find_pointer_path: из адреса ammo находим путь [libunity+off]->+off,
//             который РАЗРЕШАЕТСЯ обратно в тот же адрес (валидация).
//   TEST 3/3  правим значение по найденному пути: ammo 24 -> 999, читаем назад.
//
// Сборка/запуск: c++ -std=c++17 -I../native/final selftest.cpp -o selftest && ./selftest
// ============================================================================
#include "engine_logic.hpp"
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

static int g_fail = 0;
#define CHECK(c, m) do { if (c) printf("    [ok] %s\n", m); else { printf("    [FAIL] %s\n", m); g_fail++; } } while(0)

// ---- симулированная память процесса ----
// Непрерывный буфер из 8-байтовых слов. Адрес слова i = BASE + 8*i.
static const uintptr_t BASE = 0x7000000000ull;
static std::vector<uint64_t> MEM;
static uint64_t sim_read(uintptr_t addr, void* , bool* ok) {
    if (addr < BASE || ((addr - BASE) % 8) != 0) { *ok = false; return 0; }
    size_t i = (addr - BASE) / 8;
    if (i >= MEM.size()) { *ok = false; return 0; }
    *ok = true; return MEM[i];
}
static uintptr_t A(size_t wordIdx) { return BASE + 8 * wordIdx; }

int main() {
    printf("================ FarmerShoot v8 — SELF-TEST 3/3 (офсеты) ================\n");
    printf("(проверяет engine_logic.hpp — тот же код, что в libfarmerv8.so)\n\n");

    // Раскладка «памяти»:
    //  [0..255]  — статическая область (как libunity data): sBase..sEnd
    //  слот 10 (static) хранит указатель на «оружие» (объект)
    //  объект начинается со слова 500; поле ammo (int) по смещению +0x18 от объекта
    MEM.assign(4096, 0);
    uintptr_t sBase = A(0), sEnd = A(256);
    uintptr_t weaponObj = A(500);
    long long AMMO_OFF = 0x18;                       // смещение поля ammo в объекте
    uintptr_t ammoAddr = weaponObj + AMMO_OFF;       // точный адрес ammo

    MEM[10] = (uint64_t)weaponObj;                   // static pointer -> weaponObj
    // ammo лежит в младших 4 байтах слова (ammoAddr кратен 8 здесь: 500*8+0x18)
    MEM[(ammoAddr - BASE) / 8] = 24;                 // патроны = 24

    // ---------------- TEST 1/3 : resolve_path ----------------
    printf("TEST 1/3 — resolve_path: [libunity+0x50] -> +0x18 = адрес ammo\n");
    long long staticOff = (long long)(A(10) - sBase); // 10*8 = 0x50
    long long path[2] = { staticOff, AMMO_OFF };
    bool ok = false;
    uintptr_t r = fv8::resolve_path(sBase, path, 2, sim_read, nullptr, &ok);
    CHECK(ok && r == ammoAddr, "путь [0x50, 0x18] разрешается в адрес ammo");
    bool okr; int val = (int)(sim_read(r, nullptr, &okr) & 0xFFFFFFFF);
    CHECK(okr && val == 24, "по адресу читается 24 (текущие патроны)");
    bool t1 = ok && r == ammoAddr && val == 24;
    printf("  => %s\n\n", t1 ? "PASS" : "FAIL");

    // ---------------- TEST 2/3 : pointer-scan ----------------
    printf("TEST 2/3 — find_pointer_path: из адреса ammo строим стабильный путь\n");
    // снимок слотов (addr,val) — как сделает движок из памяти
    std::vector<fv8::PtrSlot> snap;
    for (size_t i = 0; i < MEM.size(); ++i) snap.push_back({ A(i), MEM[i] });
    fv8::PtrPath p = fv8::find_pointer_path(ammoAddr, sBase, sEnd, snap, 0x100, 4, sim_read, nullptr);
    CHECK(p.found, "путь найден");
    bool validates = false;
    if (p.found) {
        bool o; uintptr_t rr = fv8::resolve_path(sBase, p.offs, p.n, sim_read, nullptr, &o);
        validates = o && rr == ammoAddr;
        printf("    путь:");
        for (int i = 0; i < p.n; ++i) printf(" %s0x%llx", i == 0 ? "[libunity+" : "+", (unsigned long long)p.offs[i]);
        printf(" ]  -> 0x%llx\n", (unsigned long long)rr);
    }
    CHECK(validates, "найденный путь разрешается обратно в адрес ammo");
    bool t2 = p.found && validates;
    printf("  => %s\n\n", t2 ? "PASS" : "FAIL");

    // ---------------- TEST 3/3 : правка значения по пути ----------------
    printf("TEST 3/3 — правка по пути: ammo 24 -> 999, читаем назад\n");
    bool t3 = false;
    if (t2) {
        bool o; uintptr_t a = fv8::resolve_path(sBase, p.offs, p.n, sim_read, nullptr, &o);
        // «запись» в симулированную память
        MEM[(a - BASE) / 8] = (MEM[(a - BASE) / 8] & 0xFFFFFFFF00000000ull) | (uint32_t)999;
        bool o2; int nv = (int)(sim_read(a, nullptr, &o2) & 0xFFFFFFFF);
        CHECK(o && o2 && nv == 999, "по стабильному пути значение стало 999");
        // стабильность: путь не зависит от адреса объекта — «перезапуск»: двигаем объект
        uintptr_t weapon2 = A(800);
        MEM[10] = (uint64_t)weapon2;                         // новый объект (другое оружие/запуск)
        MEM[(weapon2 + AMMO_OFF - BASE) / 8] = 7;            // патроны нового оружия = 7
        bool o3; uintptr_t a2 = fv8::resolve_path(sBase, p.offs, p.n, sim_read, nullptr, &o3);
        int nv2 = (int)(sim_read(a2, nullptr, &o3) & 0xFFFFFFFF);
        CHECK(o3 && a2 == weapon2 + AMMO_OFF && nv2 == 7, "тот же путь нашёл ammo нового оружия (вне зависимости от оружия)");
        t3 = (nv == 999) && (a2 == weapon2 + AMMO_OFF);
    }
    printf("  => %s\n\n", t3 ? "PASS" : "FAIL");

    int passed = (t1 ? 1 : 0) + (t2 ? 1 : 0) + (t3 ? 1 : 0);
    printf("=================== ИТОГ: %d/3 (провалов проверок: %d) ===================\n", passed, g_fail);
    printf("%s\n", (passed == 3 && g_fail == 0)
        ? "БАЗА ДОКАЗАНА: офсет-калькулятор + pointer-scan дают стабильный точечный адрес ammo и правят значение."
        : "ЕСТЬ ПРОВАЛ.");
    return (passed == 3 && g_fail == 0) ? 0 : 1;
}
