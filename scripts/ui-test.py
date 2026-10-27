#!/usr/bin/env python3
"""Проверка интерфейса ODA в эмуляторе Android (CI, job «ui»): как человек — ввод тестового ключа, все
вкладки, меню и страницы, смена способа, подключение VPN, тест скорости, конец срока ключа.

Каждый экран — скриншот (уменьшенный JPEG в журнал CI строкой «@@SHOT <имя> <base64>») и проверки
по дереву элементов (uiautomator). Падения приложения ищутся в logcat. Итог — «UI: OK» или «UI: ОШИБКА».

Запуск: python3 scripts/ui-test.py <app.apk> <тестовый ключ> [папка для скриншотов]
Ключ — на 10 минут (TEST_KEY_SECONDS, по умолчанию 600) (создаётся в базе бота перед запуском): после активации тест ждёт конца срока
и проверяет экран «Тариф закончился».
"""
import base64
import io
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.example.vpnapp"
# Срок тестового ключа, секунды (TEST_KEY_SECONDS в workflow): весь сценарий до шага «Конец срока» — ~6 минут
KEY_SECONDS = int(os.environ.get("TEST_KEY_SECONDS", "600"))
APK, KEY = sys.argv[1], sys.argv[2]
OUT = sys.argv[3] if len(sys.argv) > 3 else "ui-shots"
os.makedirs(OUT, exist_ok=True)
failed = []
shots = 0


def sh(cmd, check=False, timeout=120):
    r = subprocess.run(cmd, shell=True, capture_output=True, timeout=timeout)
    if check and r.returncode:
        raise RuntimeError(f"{cmd}: {r.stderr.decode(errors='replace')}")
    return r.stdout


def adb(args, **kw):
    return sh("adb " + args, **kw)


def ok(cond, what):
    print(("  ✓ " if cond else "  ✗ ") + what, flush=True)
    if not cond:
        failed.append(what)
    return cond


# ---------- экран ----------

def dump():
    for _ in range(3):
        adb("shell uiautomator dump /sdcard/ui.xml", timeout=30)
        raw = adb("exec-out cat /sdcard/ui.xml", timeout=30)
        try:
            return ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1)
    return ET.fromstring(b"<hierarchy/>")


def nodes(root):
    return list(root.iter("node"))


def center(n):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(text=None, rid=None, desc=None, contains=False, root=None):
    root = root if root is not None else dump()
    for n in nodes(root):
        t = n.get("text") or ""
        if rid and n.get("resource-id") != f"{PKG}:id/{rid}":
            continue
        if desc and desc not in (n.get("content-desc") or ""):
            continue
        if text and not ((text in t) if contains else (t == text)):
            continue
        return n
    return None


SYSTEM_DIALOGS = (("isn't responding", "Wait"), ("не отвечает", "Подождать"), ("wants to add the following tile", "Do not add tile"))


def system_dialog(root):
    """Окна системы поверх ODA (зависший рабочий стол эмулятора и т. п.) — закрыть, ODA не трогать."""
    for title, button in SYSTEM_DIALOGS:
        if find(text=title, contains=True, root=root) is not None:
            b = find(text=button, root=root)
            if b is not None:
                print(f"    системное окно «{title}» → «{button}»", flush=True)
                tap_node(b)
                return True
    return False


def wait(timeout=20, **kw):
    end = time.time() + timeout
    while time.time() < end:
        root = dump()
        if system_dialog(root):
            continue
        n = find(root=root, **kw)
        if n is not None:
            return n
        time.sleep(1)
    return None


def tap_node(n):
    x, y = center(n)
    adb(f"shell input tap {x} {y}")
    time.sleep(1.2)


def tap(timeout=15, **kw):
    n = wait(timeout, **kw)
    if n is None:
        ok(False, f"нет элемента {kw}")
        return False
    tap_node(n)
    return True


def close_sheet():
    """Лист снизу закрылся сам после выбора — иначе закрыть «назад». На экране ключа «назад» не жать:
    он закрывает приложение (так и задумано)."""
    time.sleep(1)
    root = dump()
    if find(rid="powerButton", root=root) is None and find(rid="primary", root=root) is None:
        back()


def back():
    adb("shell input keyevent KEYCODE_BACK")
    time.sleep(1.2)


def texts():
    return [n.get("text") for n in nodes(dump()) if n.get("text")]


def shot(name):
    """Скриншот в журнал CI (JPEG 360 px) и в папку (PNG)."""
    global shots
    shots += 1
    name = f"{shots:02d}-{name}"
    png = adb("exec-out screencap -p", timeout=30)
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(png)
    try:
        from PIL import Image
        im = Image.open(io.BytesIO(png)).convert("RGB")
        im.thumbnail((360, 800))
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=62)
        print(f"@@SHOT {name} {base64.b64encode(buf.getvalue()).decode()}", flush=True)
    except Exception as e:  # без Pillow — только файлы
        print(f"@@SHOT {name} (нет Pillow: {e})", flush=True)
    print(f"    экран {name}: " + " | ".join(t for t in texts()[:40]), flush=True)


def overlaps():
    """Видимые элементы ODA с текстом, которые налезают друг на друга (баг раскладки)."""
    boxes = []
    for n in nodes(dump()):
        if not (n.get("package") == PKG and n.get("text")):
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        if x2 - x1 > 2 and y2 - y1 > 2:
            boxes.append((n.get("text"), x1, y1, x2, y2))
    bad = []
    for i, a in enumerate(boxes):
        for b in boxes[i + 1:]:
            ix = min(a[3], b[3]) - max(a[1], b[1])
            iy = min(a[4], b[4]) - max(a[2], b[2])
            if ix > 6 and iy > 6:
                bad.append(f"«{a[0]}» и «{b[0]}»")
    return bad


def bounds(n):
    return tuple(map(int, re.findall(r"\d+", n.get("bounds"))))


def cards_ok(where):
    """Нижние карточки (тест скорости, способ) видны целиком, с содержимым и не уходят под навигацию
    (баг 0.11.x: после смены способа или подключения через Psiphon карточки пустели или уезжали вниз)."""
    root = dump()
    nav, speed, tools = (find(rid=r, root=root) for r in ("nav", "speedCard", "toolsCard"))
    ok(speed is not None and tools is not None and nav is not None, f"{where}: карточки и навигация на экране")
    if speed is None or tools is None or nav is None:
        return
    nav_top = bounds(nav)[1]
    for name, n in (("тест скорости", speed), ("способ и Kill Switch", tools)):
        x1, y1, x2, y2 = bounds(n)
        ok(y2 - y1 > 60 and y2 <= nav_top + 2, f"{where}: карточка «{name}» целиком над навигацией ({y1}–{y2}, навигация с {nav_top})")
    all_text = " ".join(n.get("text") or "" for n in nodes(root))
    for t in ("Пинг", "Загрузка", "Тест скорости", "Kill Switch"):
        ok(t in all_text, f"{where}: в карточках есть «{t}»")


def crashed():
    """Падения процессов ODA (Java — «Process: <пакет>», нативные — «>>> … <<<» и Cmdline с путём APK).
    Падения служебных процессов эмулятора (UiAutomation самого теста и т. п.) не считаются."""
    log = adb("logcat -d -b crash", timeout=30).decode(errors="replace")
    return [l for l in log.splitlines() if PKG in l]


def crash_report():
    """Полный отчёт о падении: буфер crash и последний tombstone (бэктрейс нативного кода)."""
    print("---- logcat -b crash ----")
    print(adb("logcat -d -b crash", timeout=30).decode(errors="replace")[-12000:])
    adb("root", timeout=30)
    time.sleep(2)
    names = adb("shell ls -t /data/tombstones", timeout=30).decode(errors="replace").split()
    txt = [n for n in names if n.startswith("tombstone_") and not n.endswith(".pb")]
    if txt:
        print(f"---- /data/tombstones/{txt[0]} ----")
        print(adb(f"shell head -c 9000 /data/tombstones/{txt[0]}", timeout=30).decode(errors="replace"))
    print("---- конец отчёта ----", flush=True)


def foreground():
    """ODA на экране: после падения или системного окна — запустить снова."""
    if find(rid="nav") is None and find(rid="powerButton") is None:
        system_dialog(dump())
        adb(f"shell am start -n {PKG}/.MainActivity")
        time.sleep(3)


# ---------- сценарий ----------

def main():
    print(f"Эмулятор: {adb('shell getprop ro.build.version.release').decode().strip()} "
          f"({adb('shell getprop ro.product.cpu.abilist').decode().strip()}), экран {adb('shell wm size').decode().strip()}")
    # Эмулятор догружается: дождаться конца загрузки, разблокировать экран, убрать системные окна
    for _ in range(60):
        if adb("shell getprop sys.boot_completed").decode().strip() == "1":
            break
        time.sleep(2)
    adb("shell input keyevent 82")
    time.sleep(10)
    for _ in range(3):
        if not system_dialog(dump()):
            break
    adb("logcat -c -b all")
    adb(f"install -r -g {APK}", check=True, timeout=300)
    adb(f"shell am start -n {PKG}/.MainActivity")
    time.sleep(5)

    print("1) Экран ключа")
    ok(wait(20, rid="keyInput") is not None, "без ключа открывается экран ключа")
    shot("ключ-пусто")
    tap(rid="keyInput")
    adb(f"shell input text {KEY.lower().replace('-', '')}")  # как угодно: строчные, без дефисов
    time.sleep(1)
    n = find(rid="keyInput")
    ok(n is not None and n.get("text") == KEY, f"ключ форматируется на лету: «{n.get('text') if n is not None else ''}»")
    shot("ключ-введён")
    tap(rid="primary")
    ok(wait(30, rid="powerButton") is not None, "ключ активирован — главный экран")
    activated = time.time()
    time.sleep(2)
    shot("главная")
    bad = overlaps()
    ok(not bad, "главная: тексты не налезают друг на друга" + (": " + ", ".join(bad) if bad else ""))
    for rid in ("modeRow", "appsRow", "powerButton", "status", "speedCard", "toolsCard", "nav"):
        ok(find(rid=rid) is not None, f"главная: виден {rid}")
    banner = find(text="Ключ: осталось", contains=True)
    bt = banner.get("text") if banner is not None else ""
    m = re.search(r"осталось (\d+) (минута|минуты|минут)$", bt)
    n = int(m.group(1)) if m else -1
    right = "минута" if n % 10 == 1 and n % 100 != 11 else "минуты" if 2 <= n % 10 <= 4 and not 12 <= n % 100 <= 14 else "минут"
    ok(m is not None and m.group(2) == right, f"полоса ключа по-русски при английском Android: «{bt}»")

    print("2) Способ подключения и приложения")
    tap(rid="modeRow")
    time.sleep(1.5)  # лист выезжает
    shot("способ")
    tap(text="Psiphon")  # другой способ применяется сразу, лист закрывается сам
    time.sleep(1.5)
    ok(find(rid="powerButton") is not None, "лист способа закрылся сам после выбора")
    close_sheet()
    shot("главная-после-смены-способа")
    ok(find(text="Psiphon", rid="title") is not None, "способ сменился на Psiphon")
    for rid in ("speedCard", "toolsCard", "engineValue", "speedLinkText"):
        ok(find(rid=rid) is not None, f"после смены способа виден {rid} (баг 0.11.1: нижние карточки пропадали)")
    ok(not overlaps(), "после смены способа ничего не налезает")
    cards_ok("после смены способа на Psiphon")
    tap(rid="modeRow")
    time.sleep(1.5)
    tap(text="Авто")
    time.sleep(1.5)
    close_sheet()
    ok(find(text="Авто", rid="title") is not None, "способ вернулся на «Авто»")
    tap(rid="appsRow")
    time.sleep(3)
    shot("приложения")
    back()

    print("3) Меню ☰ и страницы")
    pages = [("Информация о ключе", "ключ-страница"), ("Как купить", "как-купить"), ("Инструкция", "инструкция"),
             ("Частые вопросы", "вопросы"), ("Конфиденциальность", "конфиденциальность"), ("О приложении", "о-приложении")]
    for title, name in pages:
        tap(rid="menuButton")
        if title == pages[0][0]:
            shot("меню")
        if tap(text=title):
            time.sleep(1)
            shot(name)
            if title == "Информация о ключе":
                ok(find(text=KEY) is not None, "страница ключа показывает ключ")
                ok(find(text="ПРОДЛИТЬ В TELEGRAM") is not None, "кнопка «Продлить в Telegram»")
            back()
    ok(find(rid="powerButton") is not None, "после страниц — снова главная")

    print("4) Вкладки")
    tap(text="История")
    shot("история")
    tap(text="Настройки")
    shot("настройки")
    adb("shell input swipe 540 1700 540 600 400")
    time.sleep(1)
    shot("настройки-ниже")
    tap(text="Главная")
    ok(find(rid="powerButton") is not None, "вкладка «Главная» возвращается")

    print("5) Подключение")
    tap(rid="powerButton")
    for label in ("OK", "Ok", "ОК", "Allow", "Разрешить"):
        n = wait(6, text=label)
        if n is not None:
            tap_node(n)
            break
    shot("подключение")
    connected = wait(90, text="Подключено", rid="status") is not None
    shot("подключено" if connected else "не-подключилось")
    print(f"    подключение в эмуляторе: {'есть' if connected else 'нет (сеть эмулятора CI)'}")
    if connected:
        tap(rid="speedLink")
        time.sleep(12)
        shot("тест-скорости")
        tap(rid="modeRow")
        time.sleep(1.5)
        tap(text="Обход DPI")
        time.sleep(1.5)
        close_sheet()
        time.sleep(3)
        shot("подключено-смена-способа")
        ok(find(rid="toolsCard") is not None and not overlaps(), "смена способа при подключении: всё на месте")
        cards_ok("подключено через обход DPI")
        # Как на телефоне владельца: подключение через Psiphon с секундомером — карточки не пустеют
        tap(rid="modeRow")
        time.sleep(1.5)
        tap(text="Psiphon")
        time.sleep(1.5)
        close_sheet()
        psi = wait(120, text="Psiphon", contains=True, rid="details") is not None and wait(30, text="Подключено", rid="status") is not None
        time.sleep(3)
        shot("подключено-psiphon")
        print(f"    подключение через Psiphon: {'есть' if psi else 'нет'}")
        cards_ok("подключено через Psiphon" if psi else "подключение через Psiphon")
        ok(not overlaps(), "Psiphon: тексты не налезают друг на друга")
        tap(rid="speedLink")
        # Через Psiphon замер дольше: ждать, пока «Измеряю…» сменится результатом (до 60 с)
        end = time.time() + 60
        time.sleep(5)
        while time.time() < end and find(text="Измеряю…") is not None:
            time.sleep(3)
        ok(find(text="Измеряю…") is None, "тест скорости через Psiphon закончился за минуту")
        shot("psiphon-тест-скорости")
        cards_ok("тест скорости через Psiphon")
    c = crashed()
    ok(not c, "подключение: приложение не упало")
    if c:
        crash_report()
    foreground()
    tap(text="История")
    shot("история-после")
    tap(text="Главная")

    print("6) Конец срока ключа")
    left = KEY_SECONDS + 75 - (time.time() - activated)  # срок + минута на проверку KeyWatch
    if left > 0:
        print(f"    жду конца срока ключа: {int(left)} с")
        time.sleep(left)
    # Экран открыт: ODA должна сама показать «Тариф закончился» (без перезапуска приложения)
    expired = wait(90, text="Тариф закончился") is not None
    shot("ключ-закончился")
    ok(expired, "срок вышел — ODA сама открыла «Тариф закончился»")
    tun = adb("shell ip link show tun0", timeout=30).decode(errors="replace")
    ok("tun0:" not in tun, "срок вышел — VPN отключён (нет tun0)")

    print("7) Падения")
    c = crashed()
    ok(not c, "приложение ни разу не упало")
    if c:
        crash_report()


try:
    main()
except Exception as e:
    ok(False, f"сценарий прервался: {e!r}")
    try:
        shot("ошибка")
    except Exception:
        pass
print("\nUI: " + ("OK" if not failed else "ОШИБКА — " + "; ".join(failed)), flush=True)
sys.exit(1 if failed else 0)
