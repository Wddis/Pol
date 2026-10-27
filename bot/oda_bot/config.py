"""Настройки бота и сервера ключей — из переменных окружения (секреты в код не попадают)."""
import os
from dataclasses import dataclass, field
from pathlib import Path


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    return int(raw) if raw else default


@dataclass(frozen=True)
class Settings:
    # Токен от BotFather. Только из окружения: BOT_TOKEN
    bot_token: str = ""
    # Где лежат база и ключ подписи лицензий
    data_dir: Path = Path("data")
    # Сервер ключей для приложения: http://<host>:<port>/v1/…
    api_host: str = "0.0.0.0"
    api_port: int = 8080
    # Цены в рублях (0 — «[ЦЕНА]», цена не задана)
    price_week: int = 0
    price_month: int = 0
    price_3m: int = 0
    # Наценка за каждое устройство сверх первого, %
    extra_device_pct: int = 50
    # Пока оплаты нет: ключи и продления выдаются без оплаты (тестовый режим)
    free_mode: bool = True
    apk_url: str = ""
    support: str = ""
    admin_ids: frozenset = field(default_factory=frozenset)

    @property
    def db_path(self) -> Path:
        return self.data_dir / "oda.db"

    @property
    def signing_key_path(self) -> Path:
        return self.data_dir / "license_ed25519.key"

    @classmethod
    def from_env(cls) -> "Settings":
        admins = frozenset(int(x) for x in os.environ.get("ODA_ADMIN_IDS", "").replace(" ", "").split(",") if x)
        return cls(
            bot_token=os.environ.get("BOT_TOKEN", "").strip(),
            data_dir=Path(os.environ.get("ODA_DATA_DIR", "data")),
            api_host=os.environ.get("ODA_API_HOST", "0.0.0.0"),
            api_port=_int("ODA_API_PORT", 8080),
            price_week=_int("ODA_PRICE_WEEK", 0),
            price_month=_int("ODA_PRICE_MONTH", 0),
            price_3m=_int("ODA_PRICE_3M", 0),
            extra_device_pct=_int("ODA_EXTRA_DEVICE_PCT", 50),
            free_mode=os.environ.get("ODA_FREE_MODE", "1") != "0",
            apk_url=os.environ.get("ODA_APK_URL", "").strip(),
            support=os.environ.get("ODA_SUPPORT", "").strip(),
            admin_ids=admins,
        )
