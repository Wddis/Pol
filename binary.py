"""Анализ двоичных файлов (прежде всего ELF .so — библиотеки игр/движков под Android и Linux).

Используется pyelftools (разбор ELF) и capstone (дизассемблер). Всё работает потоково
и не требует много памяти — подходит для бесплатного сервера на 256 МБ.
"""
import hashlib
import os
import io
import re

MAX_LIST = 300          # сколько строк списков (символы, строки) отдавать за раз
MAX_DISASM = 400        # сколько инструкций дизассемблировать за раз

_MACHINES = {
    "EM_AARCH64": "ARM64 (arm64-v8a)", "EM_ARM": "ARM 32-bit (armeabi-v7a)",
    "EM_X86_64": "x86-64", "EM_386": "x86 32-bit", "EM_RISCV": "RISC-V", "EM_MIPS": "MIPS",
}


def kind(data: bytes) -> str:
    if data[:4] == b"\x7fELF":
        return "ELF"
    if data[:2] == b"MZ":
        return "PE (Windows .dll/.exe)"
    if data[:4] in (b"\xca\xfe\xba\xbe", b"\xcf\xfa\xed\xfe", b"\xce\xfa\xed\xfe"):
        return "Mach-O (macOS/iOS)"
    if data[:4] == b"dex\n":
        return "DEX (Android)"
    if data[:2] == b"PK":
        return "ZIP/APK/JAR"
    return "неизвестный двоичный формат"


class _MMReader:
    """Файловый интерфейс поверх mmap/bytes без копирования (для файлов до 2 ГБ)."""
    def __init__(self, buf):
        self.buf, self.pos = buf, 0

    def read(self, n=-1):
        end = len(self.buf) if n is None or n < 0 else min(len(self.buf), self.pos + n)
        out = bytes(self.buf[self.pos:end]); self.pos = end
        return out

    def seek(self, off, whence=0):
        self.pos = off if whence == 0 else (self.pos + off if whence == 1 else len(self.buf) + off)
        return self.pos

    def tell(self):
        return self.pos


CHUNK = 8 * 1024 * 1024
OVERLAP = 1024


class FileBlob:
    """Файл на диске, который ведёт себя как bytes (срезы, find, len), но читается кусками.
    Память не зависит от размера файла — подходит для файлов до 2 ГБ на сервере с 256 МБ."""
    def __init__(self, path):
        self.path = path
        self.f = open(path, "rb")
        self.size = os.fstat(self.f.fileno()).st_size

    def __len__(self):
        return self.size

    def __getitem__(self, k):
        if isinstance(k, slice):
            start, stop, _ = k.indices(self.size)
            if stop <= start:
                return b""
            self.f.seek(start)
            return self.f.read(stop - start)
        self.f.seek(k if k >= 0 else self.size + k)
        return self.f.read(1)[0]

    def find(self, sub, start=0, end=None):
        end = self.size if end is None else end
        pos = start
        while pos < end:
            chunk = self[pos:min(end, pos + CHUNK + len(sub))]
            i = chunk.find(sub)
            if i != -1:
                return pos + i
            pos += CHUNK
        return -1

    def close(self):
        self.f.close()


def open_blob(path: str):
    """Маленькие файлы читаем целиком (быстрее), большие — потоково."""
    if os.path.getsize(path) <= 64 * 1024 * 1024:
        with open(path, "rb") as f:
            return f.read()
    return FileBlob(path)


def _chunks(data, limit=None):
    """(смещение, кусок) с перекрытием — для поиска по большим файлам."""
    size = len(data) if limit is None else min(len(data), limit)
    if isinstance(data, (bytes, bytearray)) and size == len(data):
        yield 0, data
        return
    pos = 0
    while pos < size:
        yield pos, data[pos:min(size, pos + CHUNK + OVERLAP)]
        pos += CHUNK


def _sha256(data):
    h = hashlib.sha256()
    for off, ch in _chunks(data):
        h.update(ch[:CHUNK] if off + CHUNK < len(data) else ch)
    return h.hexdigest()


def _elf(data):
    from elftools.elf.elffile import ELFFile
    return ELFFile(_MMReader(data))


def _scan_labels(data, table, limit=None):
    """Какие метки из table встречаются в файле (без учёта регистра). Быстрый поиск подстрок по кускам."""
    pats = [p.lower() for p, _ in table]
    found = set()
    for _, ch in _chunks(data, limit):
        low = ch.lower()
        for i, p in enumerate(pats):
            if i not in found and p in low:
                found.add(i)
        if len(found) == len(table):
            break
    return [table[i][1] for i in sorted(found)]


def _demangle(name: str) -> str:
    try:
        import cxxfilt  # необязательно
        return cxxfilt.demangle(name)
    except Exception:
        return name


def summary(data: bytes, name: str = "") -> str:
    k = kind(data)
    lines = [f"Файл {name}: {len(data)} байт, формат {k}, sha256 {_sha256(data)[:16]}…"]
    if k != "ELF":
        lines.append("Подробный разбор доступен для ELF (.so, исполняемые Linux/Android). "
                     "Для других форматов доступны strings.")
        return "\n".join(lines)
    from elftools.elf.dynamic import DynamicSection
    from elftools.elf.sections import SymbolTableSection
    e = _elf(data)
    h = e.header
    lines.append(f"Архитектура: {_MACHINES.get(h['e_machine'], h['e_machine'])}, {e.elfclass}-бит, "
                 f"{'little' if e.little_endian else 'big'}-endian, тип {h['e_type']}")
    needed, soname, runpath = [], None, None
    for sec in e.iter_sections():
        if isinstance(sec, DynamicSection):
            for t in sec.iter_tags():
                if t.entry.d_tag == "DT_NEEDED":
                    needed.append(t.needed)
                elif t.entry.d_tag == "DT_SONAME":
                    soname = t.soname
                elif t.entry.d_tag in ("DT_RUNPATH", "DT_RPATH"):
                    runpath = getattr(t, "runpath", None) or getattr(t, "rpath", None)
    if soname:
        lines.append(f"SONAME: {soname}")
    lines.append("Зависимости (NEEDED): " + (", ".join(needed) or "нет"))
    if runpath:
        lines.append(f"RUNPATH: {runpath}")
    exported = imported = 0
    jni = []
    has_symtab = False
    for sec in e.iter_sections():
        if isinstance(sec, SymbolTableSection):
            if sec.name == ".symtab":
                has_symtab = True
            if sec.name != ".dynsym":
                continue
            for s in sec.iter_symbols():
                if not s.name:
                    continue
                if s["st_shndx"] == "SHN_UNDEF":
                    imported += 1
                elif s["st_info"]["bind"] in ("STB_GLOBAL", "STB_WEAK"):
                    exported += 1
                    if s.name.startswith(("Java_", "JNI_On")):
                        jni.append(s.name)
    lines.append(f"Экспортируемых символов: {exported}, импортируемых: {imported}, "
                 f"отладочные символы (.symtab): {'есть' if has_symtab else 'вырезаны (stripped)'}")
    if jni:
        lines.append("JNI (вызываются из Java/Kotlin): " + ", ".join(jni[:20]) + (" …" if len(jni) > 20 else ""))
    # защиты
    nx = relro = False
    for seg in e.iter_segments():
        if seg["p_type"] == "PT_GNU_STACK":
            nx = not (seg["p_flags"] & 1)
        if seg["p_type"] == "PT_GNU_RELRO":
            relro = True
    canary = data.find(b"__stack_chk_fail") != -1
    lines.append(f"Защиты: NX {'да' if nx else 'нет'}, RELRO {'да' if relro else 'нет'}, "
                 f"stack canary {'да' if canary else 'не видно'}")
    # заметные движки/библиотеки по строкам
    hints = _scan_labels(data, [(b"unityplayer", "Unity"), (b"il2cpp", "IL2CPP (Unity)"),
                                  (b"mono_", "Mono"), (b"unreal", "Unreal Engine"),
                                  (b"cocos2d", "Cocos2d"), (b"godot", "Godot"), (b"sdl_", "SDL"),
                                  (b"lua_", "Lua"), (b"gles", "OpenGL ES"), (b"vkcreate", "Vulkan"),
                                  (b"fmod", "FMOD"), (b"box2d", "Box2D"), (b"physx", "PhysX"),
                                  (b"libgdx", "libGDX"), (b"flutter", "Flutter")])
    if hints:
        lines.append("Похоже, используется: " + ", ".join(dict.fromkeys(hints)))
    # защита/обфускация/подпись — часто ради этого и смотрят .so
    prot = _scan_labels(data, [(b"global-metadata", "IL2CPP global-metadata.dat"),
                                 (b" libtprt", "Tencent защита"), (b"jiagu", "360 Jiagu обфускация"),
                                 (b"bangcle", "Bangcle защита"), (b"ijiami", "Ijiami защита"),
                                 (b"obfuscat", "обфускация"), (b"anti-debug", "анти-отладка"),
                                 (b"ptrace", "проверка ptrace (анти-отладка)"),
                                 (b"frida", "детект Frida"), (b"signature", "проверка подписи"),
                                 (b"licens", "лицензирование")])
    if prot:
        lines.append("Защита/подпись (по строкам): " + ", ".join(dict.fromkeys(prot)))
    # сразу показываем экспортируемые функции и секции — чтобы не делать лишних шагов
    exp_funcs = [f for f in _symbols(data, "exports", "") if f[4] == "STT_FUNC"]
    if exp_funcs:
        top = ", ".join((f[1] if f[1] == f[0] else f[1]) for f in exp_funcs[:25])
        lines.append(f"Экспортируемые функции ({len(exp_funcs)}): {top}"
                     + (" … (полный список — exports)" if len(exp_funcs) > 25 else ""))
    secs = [s.name for s in _elf(data).iter_sections() if s.name]
    if secs:
        lines.append("Секции: " + ", ".join(secs))
    lines.append("Дальше по необходимости: functions/exports/imports (query для поиска), "
                 "strings (query), disasm (target=имя функции).")
    return "\n".join(lines)


def _symbols(data: bytes, which: str, query: str = ""):
    from elftools.elf.sections import SymbolTableSection
    e = _elf(data)
    out = []
    q = query.lower()
    for sec in e.iter_sections():
        if not isinstance(sec, SymbolTableSection):
            continue
        for s in sec.iter_symbols():
            if not s.name:
                continue
            undef = s["st_shndx"] == "SHN_UNDEF"
            if which == "imports" and not undef:
                continue
            if which == "exports" and (undef or s["st_info"]["bind"] not in ("STB_GLOBAL", "STB_WEAK")):
                continue
            if which == "functions" and (undef or s["st_info"]["type"] != "STT_FUNC"):
                continue
            nm = s.name
            dm = _demangle(nm)
            if q and q not in nm.lower() and q not in dm.lower():
                continue
            out.append((nm, dm, s["st_value"], s["st_size"], s["st_info"]["type"], sec.name))
    seen, uniq = set(), []
    for x in out:
        if x[0] not in seen:
            seen.add(x[0]); uniq.append(x)
    return uniq


def symbols(data: bytes, which: str = "exports", query: str = "", offset: int = 0) -> str:
    if kind(data) != "ELF":
        return "Символы доступны только для ELF."
    syms = _symbols(data, which, query)
    part = syms[offset:offset + MAX_LIST]
    lines = [f"{which}: всего {len(syms)}" + (f" по запросу «{query}»" if query else "")
             + (f", показаны {offset}–{offset + len(part)}" if len(syms) > MAX_LIST else "")]
    for nm, dm, addr, size, typ, sec in part:
        t = {"STT_FUNC": "func", "STT_OBJECT": "data"}.get(typ, typ.replace("STT_", "").lower())
        shown = dm if dm == nm else f"{dm}  [{nm}]"
        lines.append(f"0x{addr:x}\t{size}\t{t}\t{shown}" if which != "imports" else f"{t}\t{shown}")
    if len(syms) > offset + MAX_LIST:
        lines.append(f"… ещё {len(syms) - offset - MAX_LIST}, используй offset={offset + MAX_LIST}")
    return "\n".join(lines)


def sections(data: bytes) -> str:
    if kind(data) != "ELF":
        return "Секции доступны только для ELF."
    e = _elf(data)
    lines = ["имя\tадрес\tразмер\tфлаги"]
    for s in e.iter_sections():
        if s.name:
            f = s["sh_flags"]
            flags = ("A" if f & 2 else "") + ("W" if f & 1 else "") + ("X" if f & 4 else "")
            lines.append(f"{s.name}\t0x{s['sh_addr']:x}\t{s['sh_size']}\t{flags}")
    return "\n".join(lines)


_PRINT = set(range(0x20, 0x7f)) | {9} | set(range(0x80, 0xc0)) | {0xd0, 0xd1}


def _expand(ch: bytes, i: int, j: int):
    """Расширить найденный фрагмент до границ печатной строки."""
    a, b = i, j
    while a > 0 and ch[a - 1] in _PRINT and i - a < 300:
        a -= 1
    while b < len(ch) and ch[b] in _PRINT and b - j < 300:
        b += 1
    return a, ch[a:b]


def strings(data, query: str = "", min_len: int = 5, offset: int = 0) -> str:
    """Строки внутри файла. С query — быстрый поиск подстроки (гигабайты за секунды);
    без query — первые строки файла (для больших файлов лучше задавать query)."""
    total, part = 0, []
    big = len(data) > 64 * 1024 * 1024
    if query:
        q = query.lower().encode("utf-8", "ignore")
        for base, ch in _chunks(data):
            low = ch.lower()
            pos = low.find(q)
            while pos != -1:
                if pos < CHUNK:                       # начало в зоне перекрытия — его найдёт следующий кусок
                    a, frag = _expand(ch, pos, pos + len(q))
                    if offset <= total < offset + MAX_LIST:
                        part.append((base + a, frag.decode("utf-8", "replace")[:200]))
                    total += 1
                pos = low.find(q, pos + 1)
    else:
        rx = re.compile(rb"(?:[\xd0-\xd1][\x80-\xbf]|[\x20-\x7e\t]){%d,}" % max(3, min_len))
        stop = False
        for base, ch in _chunks(data):
            for m in rx.finditer(ch):
                if m.start() >= CHUNK:
                    continue
                if offset <= total < offset + MAX_LIST:
                    part.append((base + m.start(), m.group()[:400].decode("utf-8", "replace")[:200]))
                total += 1
                if big and total >= offset + MAX_LIST:
                    stop = True
                    break
            if stop:
                break
        if stop:
            return "\n".join([f"Первые {len(part)} строк (файл большой — для поиска задай query, это быстро):"]
                             + [f"0x{o:x}\t{t}" for o, t in part])
    lines = [f"Строк найдено: {total}" + (f" по запросу «{query}»" if query else "")]
    lines += [f"0x{o:x}\t{t}" for o, t in part]
    if total > offset + MAX_LIST:
        lines.append(f"… ещё {total - offset - MAX_LIST}, используй offset={offset + MAX_LIST} или уточни query")
    return "\n".join(lines)


def disasm(data: bytes, target: str = "", count: int = 120, targets: list = None) -> str:
    """target — имя функции (или часть имени) либо адрес 0x…"""
    if kind(data) != "ELF":
        return "Дизассемблер доступен только для ELF."
    import capstone as cs
    e = _elf(data)
    m = e.header["e_machine"]
    if m == "EM_AARCH64":
        md = cs.Cs(cs.CS_ARCH_ARM64, cs.CS_MODE_ARM)
    elif m == "EM_ARM":
        md = cs.Cs(cs.CS_ARCH_ARM, cs.CS_MODE_THUMB)        # Android armeabi-v7a — почти всегда Thumb-2
    elif m == "EM_X86_64":
        md = cs.Cs(cs.CS_ARCH_X86, cs.CS_MODE_64)
    elif m == "EM_386":
        md = cs.Cs(cs.CS_ARCH_X86, cs.CS_MODE_32)
    else:
        return f"Архитектура {m} пока не поддерживается дизассемблером."
    md.detail = False
    funcs = _symbols(data, "functions", "")
    symmap = {}
    for nm, dm, a, sz, _, _ in funcs + _symbols(data, "exports", "") + _symbols(data, "imports", ""):
        if a:
            symmap[a & ~1] = dm if dm != nm else nm

    def vaddr_off(a):
        for seg in e.iter_segments():
            if seg["p_type"] == "PT_LOAD" and seg["p_vaddr"] <= a < seg["p_vaddr"] + seg["p_filesz"]:
                return seg["p_offset"] + (a - seg["p_vaddr"])
        return None

    def read_cstr(a):
        o = vaddr_off(a)
        if o is None:
            return None
        end = data.find(b"\x00", o, o + 200)
        if end < 0:
            return None
        s = data[o:end]
        if len(s) >= 2 and all(0x20 <= b < 0x7f or b in (9,) for b in s):
            return s.decode("ascii", "replace")
        return None

    # диапазоны исполняемых секций — чтобы не комментировать переходы внутри кода как «данные»
    exec_ranges = []
    for sec in e.iter_sections():
        if sec["sh_flags"] & 4 and sec["sh_addr"]:
            exec_ranges.append((sec["sh_addr"], sec["sh_addr"] + sec["sh_size"]))

    def in_code(a):
        return any(lo <= a < hi for lo, hi in exec_ranges)

    def annotate(a, is_call):
        if a in symmap:
            return f"  ; → {symmap[a]}"
        if is_call or in_code(a):
            return ""                       # переход/вызов внутри кода без имени — не шумим
        st = read_cstr(a)
        if st is not None:
            return f'  ; "{st[:60]}"'
        o = vaddr_off(a)
        if o is not None and o + 4 <= len(data):
            return f"  ; data@0x{a:x} = {int.from_bytes(data[o:o+4], 'little')}"
        return ""

    def one(tgt):
        a, size, label = None, 0, tgt
        if tgt.lower().startswith("0x"):
            a = int(tgt, 16)
        else:
            exact = [f for f in funcs if f[0] == tgt or f[1] == tgt]
            cands = exact or [f for f in funcs if tgt.lower() in f[0].lower() or tgt.lower() in f[1].lower()]
            if not cands:
                return f"Функция «{tgt}» не найдена (смотри what=functions)."
            if len(cands) > 1 and not exact:
                return "Несколько функций подходит, уточни: " + ", ".join(c[1] for c in cands[:20])
            _, label, a, size, _, _ = cands[0]
        if m == "EM_ARM":
            a &= ~1
        off = vaddr_off(a)
        if off is None:
            return f"Адрес 0x{a:x} не в загружаемых сегментах."
        n = min(int(count or 120), MAX_DISASM)
        code = data[off:off + min(size or n * 4, n * 15)]
        out = [f"{label} @ 0x{a:x}" + (f", размер {size} байт:" if size else ":")]
        # отслеживаем adrp/adr для разбора ссылок на строки/данные (ARM64)
        pend = {}
        for i, ins in enumerate(md.disasm(code, a)):
            if i >= n:
                out.append(f"… (ещё; увеличь count или дизасемблируй с адреса 0x{ins.address:x})")
                break
            mn, ops = ins.mnemonic, ins.op_str
            note = ""
            tgt_a = None
            if mn in ("bl", "b", "b.eq", "b.ne", "b.lt", "b.gt", "b.le", "b.ge", "b.hi", "b.lo",
                      "call", "jmp") and "#0x" in ops:
                try:
                    tgt_a = int(ops.rsplit("#", 1)[1].split()[0], 16)
                except ValueError:
                    pass
            if mn == "adrp" and "#0x" in ops:
                reg = ops.split(",")[0].strip()
                pend[reg] = int(ops.rsplit("#", 1)[1], 16)
            elif mn == "adr" and "#0x" in ops:
                tgt_a = int(ops.rsplit("#", 1)[1], 16)
            elif mn == "add" and "," in ops:
                parts = [p.strip() for p in ops.split(",")]
                if len(parts) == 3 and parts[1] in pend and parts[2].startswith("#"):
                    try:
                        tgt_a = pend[parts[0]] = pend[parts[1]] + int(parts[2][1:], 16)
                    except ValueError:
                        pass
            elif mn.startswith("ld") and "[" in ops:
                base = ops.split("[", 1)[1].split(",")[0].split("]")[0].strip()
                if base in pend:
                    disp = 0
                    mm = re.search(r"#(0x[0-9a-f]+|\d+)", ops.split("[", 1)[1])
                    if mm:
                        disp = int(mm.group(1), 16) if mm.group(1).startswith("0x") else int(mm.group(1))
                    tgt_a = pend[base] + disp
            if tgt_a is not None:
                note = annotate(tgt_a, mn in ("bl", "b", "call", "jmp"))
            out.append(f"0x{ins.address:x}:\t{mn}\t{ops}{note}")
        return "\n".join(out)

    tgts = targets if targets else [target]
    return "\n\n".join(one(t) for t in tgts if t)


def overview(data: bytes, name: str) -> str:
    """Всё сразу за один вызов: сводка + функции + импорты + заметные строки. Экономит шаги."""
    if kind(data) != "ELF":
        return summary(data, name) + "\n\n" + strings(data)
    parts = [summary(data, name)]
    exp = symbols(data, "functions", "")
    parts.append("\n=== Экспортируемые функции ===\n" + exp)
    imp = symbols(data, "imports", "")
    if "всего 0" not in imp:
        parts.append("\n=== Импорт (вызовы из других библиотек) ===\n" + imp)
    # интересные строки: форматы, пути, ошибки, ключи, url
    hot = strings(data, "", min_len=5)
    keep = [ln for ln in hot.splitlines()[1:]
            if any(w in ln.lower() for w in ("%", "/", "error", "fail", "key", "sign", "http", "token",
                                             "license", "price", "money", "crop", "sell", "buy", ".c", ".cpp",
                                             "assert", "debug", "version"))]
    if keep:
        parts.append(f"\n=== Заметные строки ({len(keep)}) ===\n" + "\n".join(keep[:120]))
    parts.append("\nДальше: disasm (targets=[список функций] за один раз), strings/functions (query).")
    return "\n".join(parts)


def analyze(data: bytes, name: str, what: str = "summary", query: str = "", offset: int = 0,
            target: str = "", count: int = 120, targets: list = None) -> str:
    what = (what or "summary").lower()
    if what == "summary":
        return summary(data, name)
    if what == "overview":
        return overview(data, name)
    if what in ("exports", "imports", "functions"):
        return symbols(data, what, query, offset)
    if what == "sections":
        return sections(data)
    if what == "strings":
        return strings(data, query, offset=offset)
    if what == "disasm":
        return disasm(data, target or query, count, targets)
    return "what должен быть: summary, exports, imports, functions, sections, strings, disasm"


TOOL = {"type": "function", "function": {
    "name": "analyze_binary",
    "description": "Анализ двоичного файла (.so/ELF библиотеки игр и движков, исполняемые). "
                   "what=overview — ВСЁ СРАЗУ за один вызов (сводка + функции + импорт + заметные строки), "
                   "начинай с него; summary — только сводка; exports/imports/functions — символы (query для поиска); "
                   "sections; strings (query); disasm — дизассемблировать функции с аннотациями "
                   "(в комментариях видно строки, данные и вызываемые функции). Для disasm передавай "
                   "targets=[несколько имён функций] — все разберутся за ОДИН шаг (экономит время при лимите).",
    "parameters": {"type": "object", "properties": {
        "name": {"type": "string", "description": "Имя файла в рабочей папке"},
        "what": {"type": "string",
                 "description": "overview|summary|exports|imports|functions|sections|strings|disasm"},
        "query": {"type": "string", "description": "Фильтр по подстроке"},
        "target": {"type": "string", "description": "Для disasm: одна функция (имя или 0xадрес)"},
        "targets": {"type": "array", "items": {"type": "string"},
                    "description": "Для disasm: несколько функций за один вызов"},
        "count": {"type": "integer", "description": "Для disasm: сколько инструкций на функцию (до 400)"},
        "offset": {"type": "integer", "description": "Для длинных списков: с какого элемента"}},
        "required": ["name"]}}}
