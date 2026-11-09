"""Декодеры произвольных данных (arb) стандартных эффектов AE: то, что хранится не в обычных параметрах.

Старый конвертер arb-данные не читал вовсе, поэтому, например, Hue/Saturation переносился с нулями:
настоящие значения лежат в блоке Channel Range, а ползунки «Master …» в файле — лишь отображение.
"""
import struct

from . import ir as IR
from .convert import special

CHANNELS = ["reds", "yellows", "greens", "cyans", "blues", "magentas"]


@special("Hue/Saturation")
def hue_saturation(c, tg, e):
    """Channel Range: 45 × int32 = master (h, s, l) + 6 каналов × (спад-начало, начало, конец, спад-конец, h, s, l)."""
    p = c.P("Channel Range")
    if p is None or not p.get("arb"):
        return set()
    b = bytes.fromhex(p["arb"])
    if len(b) != 180 or IR.is_animated(p):
        c.ledger.add(p, "LOSS", "Channel Range: анимированный или нестандартный блок" if IR.is_animated(p)
                     else f"Channel Range: неожиданный размер {len(b)} байт")
        return {p["match"]}
    v = struct.unpack(">45i", b)
    for pid, x in zip(("hue", "saturation", "lightness"), v[0:3]):
        tg.const(pid, x)
    for i, ch in enumerate(CHANNELS):
        f0, r0, r1, f1, h, s, l = v[3 + i * 7: 10 + i * 7]
        start = r0 - 360 if r0 > r1 else r0   # красный диапазон переходит через 0°
        tg.const(f"{ch}RangeStart", start)
        tg.const(f"{ch}RangeEnd", r1)
        tg.const(f"{ch}StartFalloff", (r0 - f0) % 360)
        tg.const(f"{ch}EndFalloff", (f1 - r1) % 360)
        tg.const(f"{ch}Hue", h), tg.const(f"{ch}Saturation", s), tg.const(f"{ch}Lightness", l)
    c.ledger.add(p, "DECODED", "45 значений: master + 6 цветовых диапазонов", "hue, saturation, lightness, <канал>…")
    claimed = {p["match"]}
    for name in ("Master Hue", "Master Saturation", "Master Lightness"):
        q = c.P(name)
        if q is not None and not IR.is_animated(q) and not q.get("expr"):
            c.ledger.add(q, "INERT", "ползунок показывает выбранный канал; настоящее значение взято из Channel Range")
            claimed.add(q["match"])
    return claimed
