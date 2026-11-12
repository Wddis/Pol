"""Справочники: сервисы и страны в форматах разных провайдеров + профили сервисов."""
import re

# ключ — как вводит пользователь; значения — названия у провайдеров
SERVICES = {
    "telegram":  {"5sim": "telegram",  "smsact": "tg", "match": ["telegram"]},
    "whatsapp":  {"5sim": "whatsapp",  "smsact": "wa", "match": ["whatsapp"]},
    "google":    {"5sim": "google",    "smsact": "go", "match": ["google", "g-"]},
    "facebook":  {"5sim": "facebook",  "smsact": "fb", "match": ["facebook", "fb-"]},
    "instagram": {"5sim": "instagram", "smsact": "ig", "match": ["instagram"]},
    "vk":        {"5sim": "vkontakte", "smsact": "vk", "match": ["vk", "vkontakte"]},
    "discord":   {"5sim": "discord",   "smsact": "ds", "match": ["discord"]},
    "tiktok":    {"5sim": "tiktok",    "smsact": "lf", "match": ["tiktok"]},
    "twitter":   {"5sim": "twitter",   "smsact": "tw", "match": ["twitter", " x "]},
    "microsoft": {"5sim": "microsoft", "smsact": "mm", "match": ["microsoft"]},
    "openai":    {"5sim": "openai",    "smsact": "dr", "match": ["openai", "chatgpt"]},
    "amazon":    {"5sim": "amazon",    "smsact": "am", "match": ["amazon"]},
    "uber":      {"5sim": "uber",      "smsact": "ub", "match": ["uber"]},
    "yandex":    {"5sim": "yandex",    "smsact": "ya", "match": ["yandex"]},
    "other":     {"5sim": "other",     "smsact": "ot", "match": []},
}

# ISO2 -> (название у 5sim, телефонный код)
COUNTRIES = {
    "us": ("usa", "1"), "ca": ("canada", "1"), "gb": ("england", "44"), "uk": ("england", "44"),
    "de": ("germany", "49"), "fr": ("france", "33"), "nl": ("netherlands", "31"),
    "es": ("spain", "34"), "it": ("italy", "39"), "pl": ("poland", "48"),
    "se": ("sweden", "46"), "fi": ("finland", "358"), "dk": ("denmark", "45"),
    "at": ("austria", "43"), "be": ("belgium", "32"), "pt": ("portugal", "351"),
    "cz": ("czech", "420"), "ro": ("romania", "40"), "ee": ("estonia", "372"),
    "lv": ("latvia", "371"), "lt": ("lithuania", "370"), "ua": ("ukraine", "380"),
    "ru": ("russia", "7"), "kz": ("kazakhstan", "7"), "by": ("belarus", "375"),
    "ge": ("georgia", "995"), "am": ("armenia", "374"), "uz": ("uzbekistan", "998"),
    "kg": ("kyrgyzstan", "996"), "tr": ("turkey", "90"), "il": ("israel", "972"),
    "in": ("india", "91"), "id": ("indonesia", "62"), "th": ("thailand", "66"),
    "vn": ("vietnam", "84"), "ph": ("philippines", "63"), "my": ("malaysia", "60"),
    "cn": ("china", "86"), "hk": ("hongkong", "852"), "jp": ("japan", "81"),
    "br": ("brazil", "55"), "mx": ("mexico", "52"), "ar": ("argentina", "54"),
    "co": ("colombia", "57"), "cl": ("chile", "56"), "ng": ("nigeria", "234"),
    "za": ("southafrica", "27"), "eg": ("egypt", "20"), "ke": ("kenya", "254"),
    "au": ("australia", "61"),
}


# Профили сервисов для подбора и извлечения кода.
#   strict  — сервис часто вообще не шлёт SMS на виртуальные номера; номер подходит, только если
#             во входящих уже были SMS ИМЕННО от этого сервиса (он сюда доставляет).
#   code_re — точный формат кода сервиса; коды от других отправителей игнорируются.
#   busy_s  — если SMS сервиса приходило за последние busy_s секунд, номер «занят» (кто-то
#             другой прямо сейчас ждёт код → коды перепутаются).
#   hint    — подсказка пользователю при ожидании.
PROFILES = {
    "telegram": {
        "strict": True,
        # «Telegram code 54325», «Telegram code: 377944 …», «Код для входа в Telegram: 12345»
        "code_re": re.compile(r"(?:telegram|телеграм)\D{0,40}?(\d{5,6})(?!\d)", re.I),
        "busy_s": 15 * 60,
        "hint": ("1) Введите номер в Telegram. 2) Если Telegram пишет «код отправлен в приложение Telegram» — "
                 "на номере уже есть чужой аккаунт, SMS не будет: нажмите «Не подошёл». "
                 "3) Если просит email или пишет «номер заблокирован» — тоже «Не подошёл». "
                 "4) Вводите только код, пришедший ПОСЛЕ вашего запроса (он отмечен ★)."),
    },
    "whatsapp": {"strict": True, "code_re": re.compile(r"whatsapp\D{0,40}?(\d{3}[- ]?\d{3})", re.I),
                 "busy_s": 15 * 60, "hint": ""},
}


def profile(service: str) -> dict:
    return PROFILES.get((service or "").lower().strip(), {})


def service_code(service: str, text: str) -> str:
    """Код в формате сервиса или '' (для сервисов без профиля — любой код)."""
    p = profile(service)
    if not p.get("code_re"):
        return ""
    m = p["code_re"].search(text or "")
    return re.sub(r"\D", "", m.group(1)) if m else ""


def service_verdict(service: str, seen: int, last_s, per24: int):
    """Оценка номера под конкретный сервис по истории входящих.

    Возвращает (ok, label, score_delta). seen — сколько SMS сервиса во входящих,
    last_s — сколько секунд назад было последнее (None — неизвестно).
    """
    p = profile(service)
    if not service or service == "other":
        return True, "", 0
    name = service.capitalize()
    if seen == 0:
        if p.get("strict"):
            return False, f"{name} не присылал SMS на этот номер — код не придёт (уйдёт в приложение или номер в блоке)", 0
        return True, f"{name} здесь ещё не было", 10
    if p.get("busy_s") and last_s is not None and last_s < p["busy_s"]:
        return True, f"⚠ {name} слал код {last_s // 60} мин назад — номер сейчас занят другим человеком", -35
    if seen >= 8:
        return True, f"{name} доставляет, но номер очень заезжен ({seen} кодов)", -10
    return True, f"✓ {name} доставляет SMS сюда ({seen} шт.), сейчас свободен", 30


def service_name(service: str, provider: str) -> str:
    service = (service or "other").lower().strip()
    if service in SERVICES:
        return SERVICES[service][provider]
    return service  # пользователь ввёл «родной» код провайдера


def service_matchers(service: str) -> list[str]:
    service = (service or "").lower().strip()
    if service in SERVICES:
        return SERVICES[service]["match"]
    return [service] if service else []


def country_matches(query: str, *values: str) -> bool:
    """Совпадает ли фильтр страны (ISO2 / название / код) с одним из значений."""
    q = (query or "").lower().strip()
    if not q:
        return True
    vals = {str(v).lower().strip() for v in values if v}
    if q in vals:
        return True
    if q in COUNTRIES:
        name, code = COUNTRIES[q]
        return name in vals or code in vals
    return any(q in v for v in vals if len(v) > 2)
