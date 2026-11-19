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
