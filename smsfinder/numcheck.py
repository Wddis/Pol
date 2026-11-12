"""Проверка самого номера по базе Google libphonenumber (пакет phonenumbers).

Метод взят из OPRecon (AbirHasan2005/OPRecon, files/phoneinfoga.py: parse → is_valid_number →
carrier/region); типы линий — по документации python-phonenumbers (number_type → PhoneNumberType).

Что проверяется без единого сетевого запроса:
  * номер существует по плану нумерации страны (is_valid_number);
  * тип линии: мобильный / стационарный / VoIP / бесплатный 800 / платный и т.п.;
  * страна по номеру (region_code_for_number) и исходный оператор (carrier.name_for_number);
  * задвоенный код страны на сайте («+49 491 5587761404» → +49 1558 7761404) исправляется.

Проверка на живых сайтах показала, что база отстаёт от новых диапазонов: номера, на которые
Telegram доставлял коды в последние сутки, она называла «несуществующими». Поэтому номер по ней
НЕ отсеивается — только понижается в рейтинге и получает предупреждение. Решает реальная доставка.

Если пакет не установлен, проверка пропускается.
"""
from __future__ import annotations

from functools import lru_cache

try:
    import phonenumbers
    from phonenumbers import PhoneNumberType as T
    from phonenumbers import carrier as _carrier
except ImportError:  # pragma: no cover
    phonenumbers = None

TYPE_NAMES = {
    "MOBILE": "мобильный", "FIXED_LINE_OR_MOBILE": "мобильный или стационарный",
    "FIXED_LINE": "стационарный", "VOIP": "VoIP", "TOLL_FREE": "бесплатный 800",
    "PREMIUM_RATE": "платный", "SHARED_COST": "с разделённой оплатой", "PERSONAL_NUMBER": "персональный",
    "PAGER": "пейджер", "UAN": "единый номер компании", "VOICEMAIL": "голосовая почта", "UNKNOWN": "неизвестный",
}
# куда SMS обычно доходят
SMS_OK = {"MOBILE", "FIXED_LINE_OR_MOBILE"}
# куда строгие сервисы (Telegram) SMS не шлют
STRICT_BAD = {"FIXED_LINE", "TOLL_FREE", "PREMIUM_RATE", "SHARED_COST", "PAGER", "UAN", "VOICEMAIL"}


@lru_cache(maxsize=4096)
def info(number: str) -> dict:
    """{checked, valid, type, type_ru, region, carrier}"""
    if phonenumbers is None or not number:
        return {"checked": False, "valid": True, "type": "UNKNOWN", "type_ru": "не проверялся",
                "region": "", "carrier": ""}
    try:
        n = phonenumbers.parse(number if number.startswith("+") else "+" + number, None)
    except phonenumbers.NumberParseException:
        return {"checked": True, "valid": False, "type": "UNKNOWN", "type_ru": "не номер",
                "region": "", "carrier": ""}
    valid = phonenumbers.is_valid_number(n)
    t = phonenumbers.number_type(n) if valid else T.UNKNOWN
    tname = next((k for k, v in vars(T).items() if v == t and k.isupper()), "UNKNOWN")
    return {"checked": True, "valid": valid, "type": tname, "type_ru": TYPE_NAMES.get(tname, tname),
            "region": (phonenumbers.region_code_for_number(n) or "") if valid else "",
            "carrier": _carrier.name_for_number(n, "en") if valid else ""}


def normalize(number: str) -> tuple[str, str]:
    """Исправить задвоенный код страны: '+494915587761404' → '+4915587761404'.
    Возвращает (номер, пояснение) — пояснение пустое, если ничего не меняли."""
    i = info(number)
    if phonenumbers is None or not number or (i["valid"] and i["type"] in SMS_OK):
        return number, ""  # уже мобильный — не трогаем
    d = number.lstrip("+")
    for cc_len in (1, 2, 3):
        cc = d[:cc_len]
        if d[cc_len:].startswith(cc):
            fixed = "+" + cc + d[2 * cc_len:]
            if info(fixed)["type"] == "MOBILE":  # +49 491… «стационарный» → +49 15… мобильный
                return fixed, f"на сайте код страны задвоен ({number}) — правильный номер {fixed}"
    return number, ""


def verdict(number: str, strict: bool) -> tuple[bool, str, dict]:
    """(подходит, причина, info). strict — строгий сервис вроде Telegram."""
    i = info(number)
    if not i["checked"]:
        return True, "", i
    if not i["valid"]:
        return False, "номер не существует по плану нумерации страны", i
    if strict and i["type"] in STRICT_BAD:
        return False, f"{i['type_ru']} номер — сервис не шлёт на такие SMS", i
    return True, "", i


def label(i: dict) -> str:
    if not i.get("checked"):
        return ""
    return i["type_ru"] + (f", {i['carrier']}" if i.get("carrier") else "")
