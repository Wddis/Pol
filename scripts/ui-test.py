#!/usr/bin/env python3
"""Проверка интерфейса ODA в эмуляторе Android (CI, job «ui»): как человек — ввод тестового ключа, все
вкладки, меню и страницы, смена способа, подключение VPN, тест скорости, конец срока ключа.

Каждый экран — скриншот (уменьшенный JPEG в журнал CI строкой «@@SHOT <имя> <base64>») и проверки
по дереву элементов (uiautomator). Падения приложения ищутся в logcat. Итог — «UI: OK» или «UI: ОШИБКА».

Запуск: python3 scripts/ui-test.py <app.apk> <тестовый ключ> [папка для скриншотов]
Ключ — на несколько минут (создаётся в базе бота перед запуском): после активации тест ждёт конца срока
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


def wait(timeout=20, **kw):
    end = time.time() + timeout
    while time.time() < end:
        n = find(**kw)
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
    """Лист снизу закрылся сам после выбора — иначе закрыть «назад»."""
    time.sleep(1)
    if find(rid="powerButton") is None:
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


def crashed():
    log = adb("logcat -d -b crash", timeout=30).decode(errors="replace")
    return [l for l in log.splitlines() if PKG in l or "FATAL EXCEPTION" in l]


# ---------- сценарий ----------

def main():
    print(f"Эмулятор: {adb('shell getprop ro.build.version.release').decode().strip()} "
          f"({adb('shell getprop ro.product.cpu.abilist').decode().strip()}), экран {adb('shell wm size').decode().strip()}")
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

    print("2) Способ подключения и приложения")
    tap(rid="modeRow")
    shot("способ")
    tap(text="Psiphon")
    close_sheet()
    shot("главная-после-смены-способа")
    ok(find(text="Psiphon", rid="title") is not None, "способ сменился на Psiphon")
    for rid in ("speedCard", "toolsCard", "engineValue", "speedLinkText"):
        ok(find(rid=rid) is not None, f"после смены способа виден {rid} (баг 0.11.1: нижние карточки пропадали)")
    ok(not overlaps(), "после смены способа ничего не налезает")
    tap(rid="modeRow")
    tap(text="Авто")
    close_sheet()
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
        tap(text="Обход DPI")
        close_sheet()
        time.sleep(3)
        shot("подключено-смена-способа")
        ok(find(rid="toolsCard") is not None and not overlaps(), "смена способа при подключении: всё на месте")
    tap(text="История")
    shot("история-после")
    tap(text="Главная")

    print("6) Конец срока ключа")
    left = 300 + 75 - (time.time() - activated)  # срок + минута на проверку KeyWatch
    if left > 0:
        print(f"    жду конца срока ключа: {int(left)} с")
        time.sleep(left)
    adb(f"shell am start -n {PKG}/.MainActivity")
    expired = wait(60, text="Тариф закончился") is not None
    shot("ключ-закончился")
    ok(expired, "срок вышел — экран «Тариф закончился», VPN отключён")

    print("7) Падения")
    c = crashed()
    ok(not c, "приложение ни разу не упало" + ("" if not c else ":\n" + "\n".join(c[:30])))


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
