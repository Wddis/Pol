"""Иконки на кнопках бота — custom emoji Telegram (icon_custom_emoji_id).

Иконки работают, если у владельца бота Telegram Premium (или у бота купленный username на Fragment).
Набор задаёт владелец: отправляет боту «<имя> <эмодзи>» (команда /icons — список имён), бот запоминает
id в data/icons.json. Нет id — кнопка без иконки; если Telegram откажет — иконки выключаются до перезапуска.
"""
import json
from pathlib import Path

# Имя → для какой кнопки (подсказка владельцу в /icons)
NAMES = {
    "key": "Купить ключ, Получить ключ, Тарифы",
    "trial": "Пробный день",
    "profile": "Профиль, Мой профиль",
    "app": "Приложение",
    "about": "О VPN",
    "support": "Поддержка",
    "back": "Назад, Меню, Отмена",
    "copy": "Копировать ключ",
    "download": "Скачать ODA",
    "extend": "Продлить",
    "devices": "Сменить устройство, Отвязать все",
    "keys": "Мои ключи",
}
# Без иконки у «назад» остаётся стрелка в тексте
FALLBACK = {"back": "‹ "}


class Icons:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.ids: dict[str, str] = {}
        self.enabled = True
        if path and path.exists():
            try:
                data = json.loads(path.read_text(encoding="utf-8"))
                self.ids = {k: str(v) for k, v in data.items() if k in NAMES and str(v).isdigit()}
            except (OSError, ValueError):
                self.ids = {}

    def get(self, name: str | None) -> str | None:
        if not name or not self.enabled:
            return None
        return self.ids.get(name)

    def set(self, name: str, emoji_id: str) -> None:
        self.ids[name] = emoji_id
        self.enabled = True
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.ids, ensure_ascii=False, indent=1), encoding="utf-8")


# Один набор на процесс (views читает, handlers меняет)
ICONS = Icons()
