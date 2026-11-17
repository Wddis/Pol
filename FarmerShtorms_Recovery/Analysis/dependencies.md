# Зависимости

## Нативные (из уцелевшего .dynstr; DT_NEEDED обнулён)
`libc.so`, `libm.so`, `libdl.so`, `liblog.so`, `libandroid.so`, `libunity.so` (самоссылка). Vulkan и GLES грузятся динамически.

## Подсистемы и библиотеки (по строкам, число упоминаний)
| Компонент | Признак | Упоминаний |
|---|---|---|
| PhysX (3D-физика) | `Px*` | 288 |
| Burst | `Burst` | 182 |
| Firebase SDK | `Firebase.*` | 325 |
| Adjust SDK (атрибуция) | `Adjust*` | 274 |
| ENet (UDP-сетевой транспорт) | `enet_*`, `Axbot.NetCode.Transport.Enet` | 46 |
| Steam Audio (пространственный звук) | `phonon`/`SteamAudio` | 40 |
| Unity Input System | `UnityEngine.InputSystem` | 37 |
| TextMeshPro | `TMPro` | 35 |
| VContainer (DI) | `VContainer` | 26 |
| Intercom (поддержка) | `Intercom` | 26 |
| Agones (выделенные игровые серверы, Kubernetes) | `Agones.dll` | 10 |
| Sign in with Apple, GDPR, QR, TWA | `Axbot.Integrations.*` | — |

Полный список: 502 управляемые сборки (`Extracted/assemblies.txt`), из них 110 — `Axbot.farmershoot.*`.
