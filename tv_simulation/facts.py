"""Факты, на которых построена модель. Каждое правило симуляции ссылается сюда.

status:
  confirmed  — прямо сказано в источнике
  not_found  — искали и не нашли (отсутствие данных, а не доказательство)
  assumption — общее инженерное допущение, прямого источника нет
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Fact:
    text: str
    status: str
    source: str


FACTS = {
    "gs_os_stingray": Fact(
        "Приёмник GS B622L работает на StingrayTV: Linux, busybox, Wayland; "
        "основная логика в закрытом бинарнике argile-zapper",
        "confirmed",
        "https://habr.com/en/articles/882254",
    ),
    "gs_homebrew_closed": Fact(
        "В ранних прошивках GS можно было ставить homebrew, потом это закрыли; "
        "приложения (QML) ставятся только в эмулятор GS из SDK",
        "confirmed",
        "https://habr.com/en/articles/882254",
    ),
    "gs_changelog_no_youtube": Fact(
        "В истории версий ПО GS B527 нет YouTube, браузера или установки сторонних приложений; "
        "есть только фирменные (Кино Онлайн, Кино Спутник, Мультиэкран)",
        "confirmed",
        "https://www.gs.ru/support/documentation-and-software/gs-b527/",
    ),
    "gs_updates_official_only": Fact(
        "Обновления ПО приёмников выпускает только оператор; старые модели не обновляются, только обмен",
        "confirmed",
        "https://www.tricolor.ru/help/chastye-voprosy/programmnoe-obespechenie/",
    ),
    "gs_no_custom_fw": Fact(
        "Кастомных прошивок или YouTube-клиента для приёмников Триколора на GitHub не найдено",
        "not_found",
        "https://github.com/search?q=tricolor+GS+receiver",
    ),
    "gs_signature_unknown": Fact(
        "Проверяет ли загрузчик GS подпись прошивки, публично не описано",
        "not_found",
        "https://habr.com/en/articles/882254",
    ),
    "samsung_nonsmart_no_apps": Fact(
        "У Samsung без Smart TV нет платформы приложений и магазина",
        "assumption",
        "https://forum.samygo.tv/viewtopic.php?p=38771",
    ),
    "samygo_limited": Fact(
        "SamyGO поддерживал отдельные модели Samsung 2009+ и конкретные версии прошивок; "
        "для non-smart моделей root не работает, ошибка может «окирпичить» ТВ",
        "confirmed",
        "https://forum.samygo.tv/viewtopic.php?p=38771",
    ),
    "no_android_port_samsung": Fact(
        "Сборок Android/Android TV под процессоры телевизоров Samsung не найдено",
        "not_found",
        "https://en.wikipedia.org/wiki/SamyGO",
    ),
    "smartstick": Fact(
        "Триколор «СмартСтик» TR-STB-01: Android 11, вход HDMI, разрешена установка APK, 5 499 ₽",
        "confirmed",
        "https://www.ixbt.com/infopages/tricolor-smartstick.html",
    ),
    "yt_ru_throttled": Fact(
        "YouTube в России замедляют с лета 2024",
        "confirmed",
        "https://www.fontanka.ru/2026/02/10/76257666/",
    ),
    "yt_ru_blocked": Fact(
        "С 10.02.2026 сообщают о блокировке YouTube в РФ через DNS; без обхода сервис не открывается",
        "confirmed",
        "https://meduza.io/feature/2026/02/11/vse-pishut-chto-v-rossii-primenili-novyy-sposob-blokirovki-yutyuba-instagrama-i-votsapa",
    ),
}
