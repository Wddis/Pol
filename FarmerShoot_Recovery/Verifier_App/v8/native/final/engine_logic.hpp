#pragma once
// Чистая логика движка v8 (без Android-зависимостей) — используется И нативной
// libfarmerv8.so, И самотестом (v8/selftest). Значит тест проверяет ТОТ ЖЕ код.
#include <cstdint>
#include <cstddef>
#include <cstring>
#include <vector>
#include <algorithm>
#include <cmath>

namespace fv8 {

// Магия заголовка global-metadata.dat (little-endian).
static const uint32_t METADATA_MAGIC = 0xFAB11BAFu;

// Поиск магии в буфере памяти. Возврат: смещение или -1.
inline long scan_magic(const unsigned char* buf, size_t len) {
    for (size_t i = 0; i + 4 <= len; ++i) {
        uint32_t v; memcpy(&v, buf + i, 4);
        if (v == METADATA_MAGIC) return (long)i;
    }
    return -1;
}

// Оценка полного размера метадаты по заголовку: ints[0]=magic, ints[1]=version,
// далее пары (offset,size) секций; размер = max(offset+size). Фоллбэк 48 МБ.
inline uint64_t estimate_metadata_size(const uint32_t* hdr, size_t hdrInts) {
    uint64_t maxEnd = 0;
    for (size_t k = 2; k + 1 < hdrInts; k += 2) {
        uint64_t o = hdr[k], ln = hdr[k + 1];
        if (o < (256ull << 20) && ln < (256ull << 20)) {
            uint64_t end = o + ln;
            if (end > maxEnd) maxEnd = end;
        }
    }
    if (maxEnd < 0x1000 || maxEnd > (256ull << 20)) maxEnd = (48ull << 20);
    return maxEnd;
}

// Валидна ли версия метадаты (грубая проверка диапазона, как в движке).
inline bool version_sane(uint32_t version) { return version != 0 && version <= 100; }

// Патч-математика: целевое значение = оригинал * множитель.
inline float compute_target(float orig, float mul) { return orig * mul; }

// Чтение/запись float по оффсету объекта (base+offset) — ядро патча ammo/jump.
inline float read_field_f(void* obj, size_t off) {
    return obj ? *reinterpret_cast<float*>((char*)obj + off) : 0.f;
}
inline int read_field_i(void* obj, size_t off) {
    return obj ? *reinterpret_cast<int*>((char*)obj + off) : 0;
}
inline void write_field_f(void* obj, size_t off, float v) {
    if (obj) *reinterpret_cast<float*>((char*)obj + off) = v;
}
inline void write_field_i(void* obj, size_t off, int v) {
    if (obj) *reinterpret_cast<int*>((char*)obj + off) = v;
}

// ======================= СКАНЕР ПАМЯТИ (ядро) ==============================
// Поиск значения в буфере (как GameGuardian): находим адреса, где лежит
// нужное число. Потом «уточняем» после изменения значения в игре.

// Найти 4-байтовые int == value, шаг 4 байта. base — абсолютный адрес буфера.
inline void find_i32(const unsigned char* buf, size_t len, uintptr_t base,
                     int value, std::vector<uintptr_t>& out, size_t cap) {
    for (size_t i = 0; i + 4 <= len && out.size() < cap; i += 4) {
        int v; memcpy(&v, buf + i, 4);
        if (v == value) out.push_back(base + i);
    }
}
// Найти float ≈ value (с допуском eps).
inline void find_f32(const unsigned char* buf, size_t len, uintptr_t base,
                     float value, float eps, std::vector<uintptr_t>& out, size_t cap) {
    for (size_t i = 0; i + 4 <= len && out.size() < cap; i += 4) {
        float v; memcpy(&v, buf + i, 4);
        if (std::fabs(v - value) <= eps) out.push_back(base + i);
    }
}
// Проверка одного кандидата (для «уточнения» после изменения значения).
inline bool at_equals_i32(const unsigned char* at, int value) {
    int v; memcpy(&v, at, 4); return v == value;
}
inline bool at_equals_f32(const unsigned char* at, float value, float eps) {
    float v; memcpy(&v, at, 4); return std::fabs(v - value) <= eps;
}

// ======================= ОФСЕТ-КАЛЬКУЛЯТОР + POINTER-SCAN ===================
// Чтение 8 байт по адресу (в тесте — из буфера, в движке — pread /proc/self/mem).
typedef uint64_t (*mem_rd)(uintptr_t addr, void* ctx, bool* ok);

// Разрешить путь: base; (n-1) разыменований по offs[i]; финальный +offs[n-1].
// resolve([o0,o1,...,ok]) = (*(…*(*(base+o0)+o1)… ) ) + ok
inline uintptr_t resolve_path(uintptr_t base, const long long* offs, int n,
                              mem_rd rd, void* ctx, bool* ok) {
    *ok = true;
    if (n <= 0) return base;
    uintptr_t cur = base;
    for (int i = 0; i < n - 1; ++i) {
        bool o = false;
        uint64_t v = rd(cur + (uintptr_t)offs[i], ctx, &o);
        if (!o) { *ok = false; return 0; }
        cur = (uintptr_t)v;
    }
    return cur + (uintptr_t)offs[n - 1];
}

struct PtrSlot { uintptr_t addr; uint64_t val; };
struct PtrPath { bool found; int n; long long offs[8]; };

// Обратный поиск стабильного пути из статической области (libunity) к target.
// snap — слоты (addr, 8-байтовое значение) из памяти (будет отсортирован по val).
inline PtrPath find_pointer_path(uintptr_t target, uintptr_t sBase, uintptr_t sEnd,
                                 std::vector<PtrSlot>& snap, long long maxOff, int maxDepth,
                                 mem_rd rd, void* ctx) {
    PtrPath none; none.found = false; none.n = 0;
    std::sort(snap.begin(), snap.end(), [](const PtrSlot& a, const PtrSlot& b){ return a.val < b.val; });

    struct FN { uintptr_t D; std::vector<long long> suf; };
    std::vector<FN> frontier; frontier.push_back({ target, {} });

    for (int depth = 0; depth < maxDepth; ++depth) {
        std::vector<FN> next;
        for (auto& fn : frontier) {
            uint64_t lo = (fn.D >= (uint64_t)maxOff) ? (fn.D - (uint64_t)maxOff) : 0;
            PtrSlot key{ 0, lo };
            auto it = std::lower_bound(snap.begin(), snap.end(), key,
                        [](const PtrSlot& a, const PtrSlot& b){ return a.val < b.val; });
            for (; it != snap.end() && it->val <= fn.D; ++it) {
                long long off = (long long)(fn.D - it->val);
                std::vector<long long> ns; ns.reserve(fn.suf.size() + 1);
                ns.push_back(off); for (auto x : fn.suf) ns.push_back(x);
                if (it->addr >= sBase && it->addr < sEnd) {
                    long long path[8]; int n = 0;
                    path[n++] = (long long)(it->addr - sBase);
                    for (auto x : ns) if (n < 8) path[n++] = x;
                    bool ok; uintptr_t r = resolve_path(sBase, path, n, rd, ctx, &ok);
                    if (ok && r == target) { PtrPath p; p.found = true; p.n = n; for (int i = 0; i < n; ++i) p.offs[i] = path[i]; return p; }
                } else if (next.size() < 200000 && (int)ns.size() < 7) {
                    next.push_back({ it->addr, ns });
                }
            }
        }
        frontier.swap(next);
        if (frontier.empty()) break;
    }
    return none;
}

} // namespace fv8
