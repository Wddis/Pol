#pragma once
// ============================================================================
// FarmerShoot v8 — список полей для слежения в оверлее.
// Имена резолвятся ПО ИМЕНИ в рантайме (переживает обновления игры).
// Заполните реальными именами из живого дампа (v8 пишет v8_dump.txt при старте)
// или из восстановленного манифеста (Extracted/balders_identifiers.txt).
// ============================================================================
#include <vector>
#include <string>

enum class WKind { StaticField, InstanceField };
enum class WType { Int, Float, Bool, Ptr };

struct WatchItem {
    const char* cls;     // "Namespace.Class"  (напр. "Balders.Weapon")
    const char* field;   // имя поля           (напр. "ammoInMag")
    WKind       kind;    // статик или поле живого объекта
    WType       type;    // как интерпретировать значение
    const char* label;   // подпись в оверлее  (напр. "Патроны")
};

// --- РЕДАКТИРУЙТЕ ТУТ -------------------------------------------------------
static const std::vector<WatchItem> kWatch = {
    // ПРИМЕРЫ (подставьте реальные имена из v8_dump.txt):
    // { "WeaponShop",            "AutoBuy",    WKind::StaticField,   WType::Bool,  "AutoBuy"  },
    // { "Balders.Weapon",        "ammoInMag",  WKind::InstanceField, WType::Int,   "Патроны"  },
    // { "Balders.Weapon",        "totalAmmo",  WKind::InstanceField, WType::Int,   "Всего"    },
    // { "Balders.Player.Health", "current",    WKind::InstanceField, WType::Float, "HP"       },
};

// ============================================================================
// PATCH — запись значений (кнопки в оверлее). mul!=0 → умножить (прыжок ×3),
// иначе поставить value. enforce=true → держать каждый кадр (если игра сбрасывает).
// ============================================================================
struct PatchItem {
    const char* cls;
    const char* field;
    WKind       kind;
    WType       type;
    float       value;    // абсолютное значение (если mul==0)
    float       mul;      // множитель (0 = не использовать)
    bool        enforce;
    const char* label;    // подпись кнопки
};

static const std::vector<PatchItem> kPatch = {
    // ПРИМЕРЫ (подставьте реальные cls/field из v8_dump.txt / autofind):
    // { "<...PlayerMovement>", "jumpForce", WKind::InstanceField, WType::Float, 0, 3.0f, true, "Прыжок ×3"  },
    // { "<...Weaponry.Magazine>","ammo",    WKind::InstanceField, WType::Int,   999, 0,   true, "Патроны=999"},
    // { "<...PlayerMovement>", "moveSpeed", WKind::InstanceField, WType::Float, 0, 2.0f, true, "Скорость ×2"},
};
