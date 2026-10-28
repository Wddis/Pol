"""Симуляция: пробуем всеми способами получить YouTube на Samsung без Smart TV с Триколором.

Запуск:  python -m tv_simulation.simulate
Логи:    tv_simulation/logs/run.jsonl  и  tv_simulation/logs/report.md
"""
import json
from dataclasses import dataclass
from pathlib import Path

from .devices import (Device, android_stick, phone_or_laptop,
                      samsung_tv_non_smart, tricolor_receiver)
from .facts import FACTS

LOG_DIR = Path(__file__).parent / "logs"

# Итоговые статусы попытки
SUCCESS = "SUCCESS"                      # YouTube установлен и видео идёт
DEGRADED = "DEGRADED"                    # установлен, но видео тормозит
INSTALLED_NO_PLAYBACK = "INSTALLED_NO_PLAYBACK"  # установлен, но сеть не пускает
THEORETICAL = "THEORETICAL"              # возможно только при условиях, которых сейчас нет
FAIL = "FAIL"                            # не устанавливается

NETWORKS = {
    "blocked_dns": "РФ после 10.02.2026: блокировка через DNS",
    "throttled": "РФ 2024–2025: замедление",
    "open": "без ограничений (гипотетически)",
}
NETWORK_FACTS = {"blocked_dns": ("yt_ru_blocked",), "throttled": ("yt_ru_throttled",), "open": ()}

# Имитация образа Android TV: какой процессор и что нужно устройству для прошивки
ANDROID_TV_IMAGE = {"name": "android-tv-11-generic.img", "needs_port": True, "needs_unofficial_fw": True}
YOUTUBE_APK = {"name": "youtube-tv.apk", "runtime": "android"}


class Log:
    def __init__(self):
        self.records: list[dict] = []

    def add(self, scenario: str, network: str, device: Device | None, step: str,
            ok: bool, detail: str, facts: tuple[str, ...] = ()):
        for f in facts:
            assert f in FACTS, f"неизвестный факт {f}"
        self.records.append({
            "n": len(self.records) + 1,
            "scenario": scenario,
            "network": network,
            "device": device.name if device else None,
            "step": step,
            "ok": ok,
            "detail": detail,
            "facts": list(facts),
        })


@dataclass
class Outcome:
    scenario: str
    network: str
    status: str
    youtube_installed: bool
    brick_risk: bool
    reason: str


def _flash(log, sc, net, dev: Device) -> bool:
    """Пытаемся прошить образ Android TV. True — прошивка встала."""
    log.add(sc, net, dev, "загрузка образа", True,
            f"образ {ANDROID_TV_IMAGE['name']} скопирован на USB-флешку")
    if not dev.accepts_unofficial_firmware:
        log.add(sc, net, dev, "проверка прошивки загрузчиком", False,
                "неофициальная прошивка отклонена: устройство принимает только ПО производителя/оператора",
                dev.facts)
        return False
    if not dev.android_port_exists:
        log.add(sc, net, dev, "совместимость с процессором", False,
                "нет сборки Android под этот процессор и драйверов экрана/тюнера", dev.facts)
        return False
    return True


def _install_and_play(log, sc, net, host: Device) -> tuple[str, bool, str]:
    """Ставим YouTube на устройство с Android и пробуем воспроизвести видео."""
    if host.app_runtime != YOUTUBE_APK["runtime"]:
        log.add(sc, net, host, "установка YouTube", False,
                f"{YOUTUBE_APK['name']} не запускается: среда устройства «{host.app_runtime}», нужен Android",
                host.facts)
        return FAIL, False, "нет Android-среды для APK"
    how = "из магазина" if host.app_store else "из APK"
    log.add(sc, net, host, "установка YouTube", True, f"YouTube установлен {how}", host.facts)
    host.installed_apps.append("YouTube")

    if net == "open":
        log.add(sc, net, host, "воспроизведение", True, "видео 1080p воспроизводится")
        return SUCCESS, True, "работает"
    if net == "throttled":
        log.add(sc, net, host, "воспроизведение", False,
                "видео грузится долго, качество падает до 360p и ниже", NETWORK_FACTS[net])
        return DEGRADED, True, "сеть замедляет YouTube"
    log.add(sc, net, host, "воспроизведение", False,
            "youtube.com не открывается: домен не разрешается через DNS", NETWORK_FACTS[net])
    return INSTALLED_NO_PLAYBACK, True, "YouTube заблокирован в сети"


def flash_android_on_tv(log, net) -> Outcome:
    sc = "1. Прошить Android TV в сам телевизор Samsung"
    tv = samsung_tv_non_smart()
    log.add(sc, net, tv, "сервисное меню", True, "сервисное меню открыто, раздела для установки приложений нет")
    log.add(sc, net, tv, "поиск эксплойта SamyGO", False,
            "SamyGO не поддерживает модели без Smart TV", ("samygo_limited",))
    if _flash(log, sc, net, tv):
        return Outcome(sc, net, *_install_and_play(log, sc, net, tv), brick_risk=True)
    return Outcome(sc, net, FAIL, False, True, "телевизор не принимает чужую прошивку, Android-сборки нет")


def install_apk_on_receiver(log, net) -> Outcome:
    sc = "2. Установить YouTube APK на приёмник Триколора"
    rx = tricolor_receiver()
    log.add(sc, net, rx, "подключение к Wi-Fi", True, "приёмник в сети")
    log.add(sc, net, rx, "поиск магазина приложений", False,
            "в «Приложениях» только фирменные: " + ", ".join(rx.installed_apps), ("gs_changelog_no_youtube",))
    status, installed, reason = _install_and_play(log, sc, net, rx)
    return Outcome(sc, net, status, installed, False, reason)


def flash_android_on_receiver(log, net) -> Outcome:
    sc = "3. Прошить Android TV в приёмник Триколора"
    rx = tricolor_receiver()
    log.add(sc, net, rx, "поиск кастомной прошивки на GitHub", False,
            "ничего не найдено", ("gs_no_custom_fw",))
    if _flash(log, sc, net, rx):
        return Outcome(sc, net, *_install_and_play(log, sc, net, rx), brick_risk=True)
    log.add(sc, net, rx, "оценка риска", False,
            "при принудительной прошивке: приёмник может не загрузиться, каналы по подписке пропадут",
            ("gs_updates_official_only",))
    return Outcome(sc, net, FAIL, False, True, "приёмник принимает только ПО оператора")


def homebrew_on_receiver(log, net, early_firmware: bool) -> Outcome:
    fw = "ранняя прошивка" if early_firmware else "текущая прошивка"
    sc = f"4. Своё QML-приложение YouTube на приёмник ({fw})"
    rx = tricolor_receiver(early_firmware=early_firmware)
    if not early_firmware:
        log.add(sc, net, rx, "установка homebrew", False,
                "установка сторонних приложений закрыта, только эмулятор GS из SDK", ("gs_homebrew_closed",))
        return Outcome(sc, net, FAIL, False, False, "homebrew закрыт в текущих прошивках")
    log.add(sc, net, rx, "установка homebrew", True,
            "в ранней прошивке homebrew ставится", ("gs_homebrew_closed",))
    log.add(sc, net, rx, "поиск YouTube-клиента для StingrayTV", False,
            "готового клиента нет, его пришлось бы писать с нуля", ("gs_no_custom_fw",))
    log.add(sc, net, rx, "автообновление", False,
            "оператор обновит прошивку и закроет homebrew", ("gs_updates_official_only",))
    return Outcome(sc, net, THEORETICAL, False, False,
                   "нужны старая прошивка и самописный клиент, обновление всё сломает")


def screen_mirroring(log, net) -> Outcome:
    sc = "5. Повтор экрана с телефона (Miracast)"
    tv = samsung_tv_non_smart()
    log.add(sc, net, tv, "поиск Screen Mirroring в «Источнике»", False,
            "в телевизоре без Smart TV нет приёмника Miracast", tv.facts[:1])
    return Outcome(sc, net, FAIL, False, False, "телевизор не умеет принимать картинку по Wi-Fi")


def via_hdmi(log, net, host: Device, label: str) -> Outcome:
    sc = label
    tv = samsung_tv_non_smart()
    if tv.hdmi_inputs < 2:
        log.add(sc, net, tv, "свободный HDMI", False, "все HDMI заняты")
        return Outcome(sc, net, FAIL, False, False, "нет свободного HDMI")
    log.add(sc, net, tv, "свободный HDMI", True,
            "HDMI1 — приёмник Триколора, HDMI2 — новое устройство; переключение кнопкой Source")
    status, installed, reason = _install_and_play(log, sc, net, host)
    return Outcome(sc, net, status, installed, False, reason)


def run_all() -> tuple[Log, list[Outcome]]:
    log = Log()
    outcomes = []
    for net in NETWORKS:
        outcomes += [
            flash_android_on_tv(log, net),
            install_apk_on_receiver(log, net),
            flash_android_on_receiver(log, net),
            homebrew_on_receiver(log, net, early_firmware=False),
            homebrew_on_receiver(log, net, early_firmware=True),
            screen_mirroring(log, net),
            via_hdmi(log, net, android_stick(), "6. Android-стик в HDMI телевизора"),
            via_hdmi(log, net, phone_or_laptop(), "7. Телефон/ноутбук по HDMI"),
        ]
    return log, outcomes


def conclude(outcomes: list[Outcome]) -> list[str]:
    """Вывод строится только из результатов прогона, без заранее зашитых ответов."""
    lines = []
    tv_rx = [o for o in outcomes if o.scenario[0] in "12345"]
    if all(not o.youtube_installed for o in tv_rx):
        lines.append("Ни одним способом YouTube не встал ни на телевизор, ни на приёмник Триколора "
                     f"({len(tv_rx)} попыток во всех сетевых условиях).")
    risky = sorted({o.scenario for o in outcomes if o.brick_risk})
    if risky:
        lines.append("Риск испортить устройство: " + "; ".join(risky) + ".")
    for net, title in NETWORKS.items():
        ok = sorted({o.scenario for o in outcomes if o.network == net and o.youtube_installed})
        statuses = sorted({o.status for o in outcomes if o.network == net and o.youtube_installed})
        lines.append(f"Сеть «{title}»: YouTube устанавливается только в сценариях "
                     f"{', '.join(s.split('.')[0] for s in ok)}; статус: {', '.join(statuses)}.")
    return lines


def write_report(log: Log, outcomes: list[Outcome]) -> Path:
    LOG_DIR.mkdir(exist_ok=True)
    with open(LOG_DIR / "run.jsonl", "w", encoding="utf-8") as f:
        for r in log.records:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    scenarios = list(dict.fromkeys(o.scenario for o in outcomes))
    md = ["# Отчёт симуляции: YouTube на Samsung без Smart TV + Триколор", "",
          f"Шагов в логе: {len(log.records)}, попыток: {len(outcomes)}.", "",
          "## Результаты по сценариям", "",
          "| Сценарий | " + " | ".join(NETWORKS.values()) + " |",
          "|---|" + "---|" * len(NETWORKS)]
    for sc in scenarios:
        row = [next(o.status for o in outcomes if o.scenario == sc and o.network == n) for n in NETWORKS]
        md.append(f"| {sc} | " + " | ".join(row) + " |")
    md += ["", "## Вывод", ""] + [f"- {l}" for l in conclude(outcomes)]
    used = sorted({f for r in log.records for f in r["facts"]})
    md += ["", "## Использованные факты", ""]
    md += [f"- **{k}** ({FACTS[k].status}): {FACTS[k].text}. [Источник]({FACTS[k].source})" for k in used]
    path = LOG_DIR / "report.md"
    path.write_text("\n".join(md) + "\n", encoding="utf-8")
    return path


def main():
    log, outcomes = run_all()
    for r in log.records:
        mark = "OK  " if r["ok"] else "FAIL"
        print(f"[{r['n']:03}] {mark} {r['network']:<11} {r['scenario']} :: {r['step']} — {r['detail']}")
    print(f"\nОтчёт: {write_report(log, outcomes)}")
    print("\n".join(conclude(outcomes)))


if __name__ == "__main__":
    main()
