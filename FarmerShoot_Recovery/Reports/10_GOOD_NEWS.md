# Приятная весть по Farmer Shoot

Файл НЕ мёртвый. Прямое восстановление заголовка global-metadata стандартными способами
не сработало (магия/версия/оффсеты сбиты при твоих пересборках — это подтвердилось), НО
из бинарника вытащилась ПОЛНАЯ АРХИТЕКТУРА игры.

## Что восстановлено (100% достоверно, из самого файла)
- **502 сборки** проекта, из них **146 твоих** (Balders.*) — полный список: Reconstructed/project_assemblies.txt
- **2378 имён игровых классов** — Reports/recovered_class_symbols.txt
- Unity **6000.3.13f1**, IL2CPP, arm64-v8a, рендер Vulkan/GLES
- Онлайн-бэкенд AxleBolt Bolt (protobuf-схемы: матчмейкинг/инвентарь/валюта/кланы)

## Карта игры по модулям (что в проекте реально есть)
- Режимы: Defuse, RankedDefuse, Duel, RankedDuel, Deathmatch, TeamDeathmatch, Tournament, Training
- Геймплей: Weaponry, Grenades, Bomb(Molly/Molotov), Damage, HitBoxes, Ragdoll, Character,
  PlayerAnimation, Teams/TeamColor, Drop, Markers, StatTrack, Replay(Capture/Watch), Spectator
- Баллистика: Ballistics.Simulation / GrenadeLogic / GrenadePractice
- Сеть: NetCode, NetCode.Transport.Enet, Serialization, ServerPlugins
- Магазин/экономика: WeaponShop(UI/Panels/Controls/Availability), Marketplace, PlayerProgress
- ИИ/навигация: AStar.Runtime / AStar.Builder
- Инфраструктура: Startup, Infrasctucture(sic), Settings, Metrics, Logging, Profiling

## Честный статус восстановления C#-кода
- Декомпиляция C# (Il2CppDumper) пока заблокирована: нужен валидный global-metadata.dat.
  Его заголовок в файле повреждён/перешифрован неизвестной схемой — вслепую не брутится.
- НО код IL2CPP (нативный) цел → через **Cpp2IL** реально получить псевдокод МЕТОДОВ даже без
  метадаты (имена классов у нас уже есть из списка выше — можно сопоставить).

## Следующий шаг
1. Если найдёшь исходный Unity-проект/git или оригинальный APK с чистой global-metadata.dat —
   восстановление становится почти полным (это самый быстрый путь).
2. Либо запускаем Cpp2IL по целым code-сегментам: вернёт структуру методов для сопоставления
   с 146 твоими сборками. Скажи «го cpp2il» — попробую.
