/*
 * FarmerShoot — Verifier v8 · LIVE FIELD INSPECTOR (Frida / frida-il2cpp-bridge)
 * ---------------------------------------------------------------------------
 * Что делает: подключается к ЖИВОМУ процессу вашей игры, читает реальную
 * IL2CPP-метадату прямо из памяти (ту самую global-metadata.dat, которой НЕ было
 * в test.so), и:
 *   1) DUMP   — выгружает актуальное дерево сборок/классов/полей ("свежие данные").
 *   2) WATCH  — следит за выбранными полями (патроны, HP, координаты и т.п.)
 *                и на каждом тике печатает ИЗМЕНЕНИЯ:  old -> new (Δ).
 *   3) CHECK  — разовый снимок всех watch-полей по запросу (RPC `check`).
 *
 * Оффсеты НЕ хардкодятся — всё резолвится ПО ИМЕНИ при каждом запуске,
 * поэтому скрипт переживает обновления игры.
 *
 * Зависимость: frida-il2cpp-bridge (https://github.com/vfsfitvnm/frida-il2cpp-bridge)
 *   Сборка одним файлом:
 *     npm i frida-il2cpp-bridge frida-compile
 *     npx frida-compile farmer_v8.js -o farmer_v8.compiled.js
 *
 * Запуск (выберите один путь):
 *   A) ROOT (frida-server на телефоне):
 *        frida -U -f <package.name> -l farmer_v8.compiled.js
 *   B) NO-ROOT (frida-gadget, вшитый в ВАШ же APK через objection):
 *        objection patchapk -s your_game.apk     # вшивает gadget
 *        # ставим пропатченный apk, запускаем игру, затем:
 *        frida -U Gadget -l farmer_v8.compiled.js
 *
 * ВАЖНО (честно): репак+переподпись ломают подпись → Play Integrity/анти-чит
 * могут не пустить в онлайн. Это ВАШ проект — тестируйте офлайн/dev-билд.
 */

import "frida-il2cpp-bridge";

// ======================= НАСТРОЙКА (редактируйте тут) =======================
// Имена берите из восстановленного манифеста (Extracted/balders_identifiers.txt,
// gameplay_vocab.txt) ИЛИ из живого DUMP ниже. Формат класса: "Namespace.Class".
// Если namespace неизвестен — оставьте пустым ("") или используйте автопоиск (ниже).
const CONFIG = {
  // Сборки для дампа (пусто = все). Обычно игровой код в "Assembly-CSharp".
  dumpAssemblies: [],            // напр. ["Assembly-CSharp"]
  dumpToFile: true,              // писать дамп в /sdcard/Android/data/<pkg>/files/
  tickMs: 400,                   // период опроса watch-полей

  // Поля для слежения. kind: "static" (поле класса) или "instance" (брать живой объект).
  // Для instance скрипт сам находит первый живой объект класса через GC.
  watch: [
    // ПРИМЕРЫ — подставьте реальные имена из DUMP:
    // { cls: "WeaponShop",            field: "AutoBuy",   kind: "static"   },
    // { cls: "Balders.Weapon",        field: "ammo",      kind: "instance" },
    // { cls: "Balders.Weapon",        field: "ammoInMag", kind: "instance" },
    // { cls: "Balders.Player.Health", field: "current",   kind: "instance" },
  ],

  // Автопоиск классов/полей по подстроке имени (если не знаете точный класс).
  // Напр. ["ammo","Ammo","mag","Clip","health","Health","hp"].
  autoFindFieldSubstrings: ["ammo", "Ammo", "clip", "Clip", "mag", "Mag",
                            "health", "Health", "hp", "HP"],
};
// ===========================================================================

function out(line) { console.log(line); }

let FILE_DIR = null;
function resolveFilesDir() {
  try {
    const ActivityThread = Java.use("android.app.ActivityThread");
    const app = ActivityThread.currentApplication();
    const ctx = app.getApplicationContext();
    const f = ctx.getExternalFilesDir(null);
    FILE_DIR = f ? f.getAbsolutePath() : null;
  } catch (e) { FILE_DIR = null; }
  return FILE_DIR;
}

function writeFile(name, text) {
  if (!FILE_DIR) return null;
  const path = FILE_DIR + "/" + name;
  try {
    const f = new File(path, "w");
    f.write(text);
    f.flush(); f.close();
    return path;
  } catch (e) { return null; }
}

// ---- DUMP: живое дерево сборок/классов/полей -------------------------------
function dump() {
  const lines = [];
  lines.push("# FarmerShoot v8 — LIVE il2cpp dump");
  lines.push("# unity: " + Il2Cpp.unityVersion);
  lines.push("");
  const asms = Il2Cpp.domain.assemblies;
  lines.push("# assemblies (" + asms.length + "):");
  for (const a of asms) lines.push("  " + a.name);
  lines.push("");

  const want = CONFIG.dumpAssemblies;
  for (const a of asms) {
    if (want.length && want.indexOf(a.name) < 0) continue;
    const img = a.image;
    lines.push("=== " + a.name + "  (classes: " + img.classCount + ") ===");
    for (const klass of img.classes) {
      const fns = klass.fields;
      const fdesc = fns.map(f =>
        (f.isStatic ? "static " : "") + f.type.name + " " + f.name +
        " @0x" + f.offset.toString(16)).join(", ");
      lines.push("  " + klass.type.name + " { " + fdesc + " }");
    }
    lines.push("");
  }
  const text = lines.join("\n");
  out("[v8] dump: " + asms.length + " assemblies");
  if (CONFIG.dumpToFile) {
    const p = writeFile("v8_dump.txt", text);
    out(p ? "[v8] dump saved -> " + p : "[v8] dump (no file dir); printing head:\n" +
        text.split("\n").slice(0, 40).join("\n"));
  } else {
    out(text);
  }
  return text;
}

// ---- Автопоиск полей по подстроке ------------------------------------------
function autoFind() {
  const subs = CONFIG.autoFindFieldSubstrings;
  if (!subs.length) return;
  out("[v8] autoFind fields matching: " + subs.join(", "));
  const hits = [];
  for (const a of Il2Cpp.domain.assemblies) {
    for (const klass of a.image.classes) {
      for (const f of klass.fields) {
        if (subs.some(s => f.name.indexOf(s) >= 0)) {
          hits.push(klass.type.name + " . " + f.name +
            "  (" + f.type.name + (f.isStatic ? ", static" : "") + ")");
        }
      }
    }
  }
  const text = hits.join("\n");
  out("[v8] autoFind: " + hits.length + " candidate fields");
  const p = writeFile("v8_field_candidates.txt", text);
  if (p) out("[v8] candidates saved -> " + p);
  else out(text.split("\n").slice(0, 60).join("\n"));
}

// ---- WATCH / CHECK ----------------------------------------------------------
function findClass(name) {
  // Пробуем как "Namespace.Class" по всем сборкам.
  for (const a of Il2Cpp.domain.assemblies) {
    try {
      const k = a.image.tryClass(name);
      if (k) return k;
    } catch (e) {}
  }
  // Фоллбэк: по короткому имени.
  for (const a of Il2Cpp.domain.assemblies) {
    for (const klass of a.image.classes) {
      if (klass.name === name || klass.type.name === name) return klass;
    }
  }
  return null;
}

function readWatchValue(w) {
  const klass = findClass(w.cls);
  if (!klass) return { ok: false, v: "<class not found: " + w.cls + ">" };
  try {
    if (w.kind === "static") {
      const f = klass.field(w.field);
      return { ok: true, v: String(f.value) };
    } else {
      // instance: берём первый живой объект этого класса из кучи
      const objs = Il2Cpp.gc.choose(klass);
      if (!objs.length) return { ok: false, v: "<no live instance>" };
      const f = objs[0].field(w.field);
      return { ok: true, v: String(f.value), n: objs.length };
    }
  } catch (e) {
    return { ok: false, v: "<field err: " + w.field + " / " + e.message + ">" };
  }
}

const lastValues = {};
function snapshot(tag) {
  if (!CONFIG.watch.length) { out("[v8] watch[] пуст — заполните CONFIG.watch (см. DUMP)"); return; }
  out("──── " + tag + " @" + new Date().toISOString().substr(11, 8) + " ────");
  for (const w of CONFIG.watch) {
    const key = w.cls + "." + w.field + "." + w.kind;
    const r = readWatchValue(w);
    const prev = lastValues[key];
    let mark = "";
    if (r.ok && prev !== undefined && prev !== r.v) {
      mark = "   <<< CHANGED  " + prev + " -> " + r.v;
      try { const d = Number(r.v) - Number(prev); if (!isNaN(d)) mark += "  (Δ " + (d > 0 ? "+" : "") + d + ")"; } catch (e) {}
    }
    out("  " + w.cls + "." + w.field +
        (r.n ? " [inst×" + r.n + "]" : "") + " = " + r.v + mark);
    if (r.ok) lastValues[key] = r.v;
  }
}

// ---- RPC (чтобы дёргать из консоли как "кнопку Проверить") ------------------
rpc.exports = {
  dump: () => Il2Cpp.perform(() => dump()),
  autofind: () => Il2Cpp.perform(() => autoFind()),
  check: () => Il2Cpp.perform(() => snapshot("CHECK (вручную)")),
};

// ---- MAIN -------------------------------------------------------------------
Il2Cpp.perform(() => {
  resolveFilesDir();
  out("================ FarmerShoot Verifier v8 (LIVE) ================");
  out("[v8] il2cpp готов. Unity: " + Il2Cpp.unityVersion +
      (FILE_DIR ? "  files: " + FILE_DIR : "  (нет files dir)"));
  out("[v8] команды: rpc.exports.dump() / .autofind() / .check()");

  dump();            // свежий дамп при старте
  autoFind();        // подсказки: где лежат ammo/health/…

  if (CONFIG.watch.length) {
    out("[v8] watch включён, тик " + CONFIG.tickMs + " мс. Поиграйте — изменения появятся ниже.");
    snapshot("BASELINE");
    setInterval(() => Il2Cpp.perform(() => snapshot("tick")), CONFIG.tickMs);
  } else {
    out("[v8] watch[] пуст. Откройте v8_field_candidates.txt / v8_dump.txt, выберите поля,");
    out("[v8] впишите их в CONFIG.watch, пересоберите и перезапустите — увидите патроны вживую.");
  }
});
