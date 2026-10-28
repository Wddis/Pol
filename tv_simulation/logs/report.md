# Отчёт симуляции: YouTube на Samsung без Smart TV + Триколор

Шагов в логе: 66, попыток: 24.

## Результаты по сценариям

| Сценарий | РФ после 10.02.2026: блокировка через DNS | РФ 2024–2025: замедление | без ограничений (гипотетически) |
|---|---|---|---|
| 1. Прошить Android TV в сам телевизор Samsung | FAIL | FAIL | FAIL |
| 2. Установить YouTube APK на приёмник Триколора | FAIL | FAIL | FAIL |
| 3. Прошить Android TV в приёмник Триколора | FAIL | FAIL | FAIL |
| 4. Своё QML-приложение YouTube на приёмник (текущая прошивка) | FAIL | FAIL | FAIL |
| 4. Своё QML-приложение YouTube на приёмник (ранняя прошивка) | THEORETICAL | THEORETICAL | THEORETICAL |
| 5. Повтор экрана с телефона (Miracast) | FAIL | FAIL | FAIL |
| 6. Android-стик в HDMI телевизора | INSTALLED_NO_PLAYBACK | DEGRADED | SUCCESS |
| 7. Телефон/ноутбук по HDMI | INSTALLED_NO_PLAYBACK | DEGRADED | SUCCESS |

## Вывод

- Ни одним способом YouTube не встал ни на телевизор, ни на приёмник Триколора (18 попыток во всех сетевых условиях).
- Риск испортить устройство: 1. Прошить Android TV в сам телевизор Samsung; 3. Прошить Android TV в приёмник Триколора.
- Сеть «РФ после 10.02.2026: блокировка через DNS»: YouTube устанавливается только в сценариях 6, 7; статус: INSTALLED_NO_PLAYBACK.
- Сеть «РФ 2024–2025: замедление»: YouTube устанавливается только в сценариях 6, 7; статус: DEGRADED.
- Сеть «без ограничений (гипотетически)»: YouTube устанавливается только в сценариях 6, 7; статус: SUCCESS.

## Использованные факты

- **gs_changelog_no_youtube** (confirmed): В истории версий ПО GS B527 нет YouTube, браузера или установки сторонних приложений; есть только фирменные (Кино Онлайн, Кино Спутник, Мультиэкран). [Источник](https://www.gs.ru/support/documentation-and-software/gs-b527/)
- **gs_homebrew_closed** (confirmed): В ранних прошивках GS можно было ставить homebrew, потом это закрыли; приложения (QML) ставятся только в эмулятор GS из SDK. [Источник](https://habr.com/en/articles/882254)
- **gs_no_custom_fw** (not_found): Кастомных прошивок или YouTube-клиента для приёмников Триколора на GitHub не найдено. [Источник](https://github.com/search?q=tricolor+GS+receiver)
- **gs_os_stingray** (confirmed): Приёмник GS B622L работает на StingrayTV: Linux, busybox, Wayland; основная логика в закрытом бинарнике argile-zapper. [Источник](https://habr.com/en/articles/882254)
- **gs_signature_unknown** (not_found): Проверяет ли загрузчик GS подпись прошивки, публично не описано. [Источник](https://habr.com/en/articles/882254)
- **gs_updates_official_only** (confirmed): Обновления ПО приёмников выпускает только оператор; старые модели не обновляются, только обмен. [Источник](https://www.tricolor.ru/help/chastye-voprosy/programmnoe-obespechenie/)
- **no_android_port_samsung** (not_found): Сборок Android/Android TV под процессоры телевизоров Samsung не найдено. [Источник](https://en.wikipedia.org/wiki/SamyGO)
- **samsung_nonsmart_no_apps** (assumption): У Samsung без Smart TV нет платформы приложений и магазина. [Источник](https://forum.samygo.tv/viewtopic.php?p=38771)
- **samygo_limited** (confirmed): SamyGO поддерживал отдельные модели Samsung 2009+ и конкретные версии прошивок; для non-smart моделей root не работает, ошибка может «окирпичить» ТВ. [Источник](https://forum.samygo.tv/viewtopic.php?p=38771)
- **smartstick** (confirmed): Триколор «СмартСтик» TR-STB-01: Android 11, вход HDMI, разрешена установка APK, 5 499 ₽. [Источник](https://www.ixbt.com/infopages/tricolor-smartstick.html)
- **yt_ru_blocked** (confirmed): С 10.02.2026 сообщают о блокировке YouTube в РФ через DNS; без обхода сервис не открывается. [Источник](https://meduza.io/feature/2026/02/11/vse-pishut-chto-v-rossii-primenili-novyy-sposob-blokirovki-yutyuba-instagrama-i-votsapa)
- **yt_ru_throttled** (confirmed): YouTube в России замедляют с лета 2024. [Источник](https://www.fontanka.ru/2026/02/10/76257666/)
