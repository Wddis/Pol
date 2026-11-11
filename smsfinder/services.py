"""Справочники: сервисы и страны в форматах разных провайдеров."""

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
