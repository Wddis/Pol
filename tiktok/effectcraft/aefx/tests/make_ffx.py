"""Собирает синтетический .ffx по описанию — чтобы проверять разбор ключей всех форматов
(1D, многомерные, пространственные, цветовые), которых нет в пресетах из knowledge/.

Формат чанков — тот же, что читает aefx/ir.py (см. там ссылку на описание AEP).
"""
import struct

TS = 30720


def chunk(cid, data):
    return cid + struct.pack(">I", len(data)) + data + (b"\x00" if len(data) % 2 else b"")


def LIST(typ, *children):
    return chunk(b"LIST", typ + b"".join(children))


def utf8(s):
    b = s.encode("utf8")
    return b"Utf8" + struct.pack(">I", len(b)) + b + (b"\x00" if len(b) % 2 else b"")


def tdmn(s):
    return chunk(b"tdmn", s.encode("latin1").ljust(40, b"\x00"))


def fix(x):
    return struct.pack(">i", int(round(x * 65536)))


KIND = {"layer": 0, "slider": 2, "angle": 3, "checkbox": 4, "color": 5, "point": 6, "popup": 7, "float": 10,
        "arbitrary": 11, "group": 13, "group_end": 14, "button": 15}


def pard(kind, name="", default=None, lo=0.0, hi=100.0, options=None, percent=False):
    head = bytes(15) + bytes([KIND[kind]]) + name.encode("latin1")[:31].ljust(32, b"\x00") + bytes(8)
    v = bytearray(92)
    if kind == "slider":
        v[68:88] = fix(lo) + fix(hi) + fix(lo) + fix(hi) + fix(default or 0)
        v[88:92] = struct.pack(">hh", 1, 1 if percent else 0)
    elif kind == "angle":
        v[4:8] = fix(default or 0)
    elif kind == "float":
        v[48:68] = struct.pack(">5f", lo, hi, lo, hi, default or 0)
        v[68:72] = struct.pack(">hh", 1, 1 if percent else 0)
    elif kind == "checkbox":
        v[4] = int(default or 0)
    elif kind == "color":
        v[4:8] = bytes(int(x) for x in (default or [255, 255, 255, 255]))
    elif kind == "point":
        d = default or [0.5, 0.5]
        v[12:20] = fix(d[0] * 100) + fix(d[1] * 100)
    elif kind == "popup":
        v[4:8] = struct.pack(">HH", len(options or []), int(default or 1))
    out = chunk(b"pard", head + bytes(v))
    if kind == "popup" and options:
        out += chunk(b"pdnm", utf8("|".join(options)))
    if kind == "checkbox" and name:
        out += chunk(b"pdnm", utf8(name))
    return out


def tdb4(dims, static=True, spatial=False, color=False, integer=False, novalue=False):
    b = bytearray(124)
    b[0:2] = b"\xdb\x99"
    b[2:4] = struct.pack(">H", dims)
    b[5] = (1 if static else 0) | (8 if spatial else 0)
    b[10:14] = struct.pack(">I", TS)
    b[55] = 1 if novalue else 0
    b[57] = (1 if color else 0) | (4 if integer else 0)
    return chunk(b"tdb4", bytes(b))


def key_head(t, kin, kout, roving=False, continuous=False):
    return struct.pack(">i", int(round(t * TS))) + bytes([kin, kout, 0, 0x07 | (0x08 if continuous else 0) | (0x20 if roving else 0)])


def keys(kind, dims, ks):
    """ks: [{t, v, in, out, ein:[(speed, infl)…], eout, tin, tout, roving, continuous}]"""
    items = b""
    for k in ks:
        h = key_head(k["t"], k.get("in", 1), k.get("out", 1), k.get("roving", False), k.get("continuous", False))
        ein, eout = k.get("ein", [(0, 1 / 6)] * dims), k.get("eout", [(0, 1 / 6)] * dims)
        if kind == "multi":
            body = struct.pack(f">{dims}d", *k["v"])
            body += struct.pack(f">{dims}d", *[e[0] for e in ein]) + struct.pack(f">{dims}d", *[e[1] for e in ein])
            body += struct.pack(f">{dims}d", *[e[0] for e in eout]) + struct.pack(f">{dims}d", *[e[1] for e in eout])
        elif kind == "spatial":
            body = bytes(16) + struct.pack(">4d", ein[0][0], ein[0][1], eout[0][0], eout[0][1])
            body += struct.pack(f">{dims}d", *k["v"]) + struct.pack(f">{dims}d", *k.get("tin", [0] * dims)) + struct.pack(f">{dims}d", *k.get("tout", [0] * dims))
        elif kind == "color":
            body = bytes(16) + struct.pack(">4d", ein[0][0], ein[0][1], eout[0][0], eout[0][1]) + struct.pack(">4d", *k["v"]) + bytes(64)
        items += h + body
    size = len(items) // len(ks)
    hd = bytearray(52)
    hd[0:4] = b"\x00\xd0\x0b\xee"
    hd[10:12] = struct.pack(">H", len(ks))
    hd[18:20] = struct.pack(">H", size)
    hd[23] = 4
    return LIST(b"list", chunk(b"lhd3", bytes(hd)), chunk(b"ldat", items))


def prop(match, name, dims=1, value=None, anim=None, expr=None, spatial=False, color=False, integer=False, arb=None):
    """одно свойство эффекта: статическое значение либо anim=(layout, keys)."""
    ch = [chunk(b"tdsb", struct.pack(">I", 1)), chunk(b"tdsn", utf8(name)),
          tdb4(dims, static=anim is None, spatial=spatial, color=color, integer=integer)]
    vals = list(value or [0.0] * dims)
    ch.append(chunk(b"cdat", struct.pack(f">{max(len(vals), 5)}d", *(vals + [0.0] * (5 - len(vals))))))
    if expr:
        ch.append(utf8(expr))
    if anim:
        ch.append(keys(anim[0], dims, anim[1]))
    out = tdmn(match) + LIST(b"tdbs", *ch)
    if arb is not None:
        out += LIST(b"aRbs", chunk(b"aRbp", arb))
    return out


def effect(match, display, params, enabled=True, opacity=100.0, user_name=None):
    """params: [(pard_bytes, prop_bytes | None, match)]."""
    part = [chunk(b"parn", struct.pack(">I", len(params)))]
    body = [chunk(b"tdsb", struct.pack(">I", 1 if enabled else 0)), chunk(b"tdsn", utf8(user_name or display))]
    for m, pd, pr in params:
        part += [tdmn(m), pd]
        if pr is not None:
            body.append(pr)
    builtin = LIST(b"tdgp", chunk(b"tdsb", struct.pack(">I", 1)), chunk(b"tdsn", utf8("Compositing Options")),
                   prop("ADBE Effect Mask Opacity", "-_0_/-", value=[opacity]), tdmn("ADBE Group End"))
    body += [tdmn("ADBE Effect Built In Params"), builtin, tdmn("ADBE Group End")]
    sspc = LIST(b"sspc", chunk(b"fnam", utf8(display)), LIST(b"parT", *part), LIST(b"tdgp", *body))
    tdsp = LIST(b"tdsp", chunk(b"tdot", b"\xff\xff\xff\xff"), chunk(b"tdpl", struct.pack(">I", 2)),
                LIST(b"tdsi", chunk(b"tdix", b"\xff\xff\xff\xff"), tdmn("ADBE Effect Parade")),
                LIST(b"tdsi", chunk(b"tdix", struct.pack(">I", 0)), tdmn(match)))
    return tdsp + chunk(b"tdsn", utf8(user_name or display)), sspc


def ffx(*effects):
    heads = b"".join(e[0] for e in effects)
    bodies = b"".join(e[1] for e in effects)
    beso = chunk(b"beso", struct.pack(">IIII", 1, 1, 0, TS) + bytes(40))
    data = b"FaFX" + chunk(b"head", bytes(16)) + LIST(b"besc", beso, heads, bodies)
    return b"RIFX" + struct.pack(">I", len(data)) + data


def P(match, kind, name, value=None, **kw):
    """короткая запись параметра: определение + значение."""
    pk = {k: kw.pop(k) for k in ("default", "lo", "hi", "options", "percent") if k in kw}
    dims = {"point": 2, "color": 4}.get(kind, 1)
    pd = pard(kind, name, **pk)
    if kind in ("group", "group_end", "button"):
        return match, pd, prop(match, name, value=[0.0])
    return match, pd, prop(match, name, dims=dims, value=value, spatial=(kind == "point"), color=(kind == "color"),
                           integer=(kind in ("popup", "checkbox", "layer")), **kw)


def sample():
    """эталонный пресет для тестов: все виды ключей + калибровка + сторонний плагин + неизвестный плагин."""
    tile = effect("ADBE Tile", "Motion Tile", [
        P("ADBE Tile-0000", "layer", "", [0.0]),
        P("ADBE Tile-0001", "point", "Tile Center", anim=("spatial", [
            {"t": 2.0, "v": [0.25, 0.25], "out": 2, "eout": [(0.4, 0.5)]},
            {"t": 2.5, "v": [0.5, 0.75], "in": 2, "out": 2, "ein": [(0.2, 0.4)], "eout": [(0.0, 0.7)],
             "tin": [-0.05, 0.02], "tout": [0.06, -0.01]},
            {"t": 3.0, "v": [0.75, 0.25], "in": 2, "out": 3, "ein": [(0.0, 0.9)]}])),
        P("ADBE Tile-0002", "slider", "Tile Width", lo=0, hi=1000, default=100, anim=("multi", [
            {"t": 2.0, "v": [100.0], "out": 2, "eout": [(0.0, 0.75)]},
            {"t": 3.0, "v": [40.0], "in": 2, "ein": [(-12.5, 0.25)], "out": 3},
            {"t": 4.0, "v": [60.0], "in": 2, "out": 2, "ein": [(7.0, 0.3)], "eout": [(7.0, 0.5)], "continuous": True},
            {"t": 5.0, "v": [90.0], "roving": False}])),
        P("ADBE Tile-0003", "slider", "Tile Height", [100.0], lo=0, hi=1000, default=100),
        P("ADBE Tile-0004", "slider", "Output Width", [250.0], lo=0, hi=1000, default=100),
        P("ADBE Tile-0005", "slider", "Output Height", [100.0], lo=0, hi=1000, default=100),
        P("ADBE Tile-0006", "checkbox", "Mirror Edges", [1.0]),
        P("ADBE Tile-0007", "angle", "Phase", [0.0], expr="time * 90"),
        P("ADBE Tile-0008", "checkbox", "Horizontal Phase Shift", [0.0]),
    ])
    modes = ["Normal", "Add", "Multiply", "Screen", "(-", "Overlay", "Soft Light", "Hard Light"]
    glow = effect("ADBE Glo2", "Glow", [
        P("ADBE Glo2-0000", "layer", "", [0.0]),
        P("ADBE Glo2-0001", "popup", "Glow Based On", [2.0], options=["Alpha Channel", "Color Channels"], default=2),
        P("ADBE Glo2-0002", "float", "Glow Threshold", [70.0], lo=0, hi=100, default=60, percent=True),
        P("ADBE Glo2-0003", "float", "Glow Radius", [5000.0], lo=0, hi=32000, default=10),
        P("ADBE Glo2-0004", "float", "Glow Intensity", [1.5], lo=0, hi=255, default=1),
        P("ADBE Glo2-0006", "popup", "Glow Operation", [6.0], options=modes, default=2),
        P("ADBE Glo2-0007", "popup", "Glow Colors", [2.0], options=["Original Colors", "A & B Colors", "Arbitrary Map"], default=1),
        P("ADBE Glo2-0013", "color", "Color A", anim=("color", [
            {"t": 2.0, "v": [255, 255, 0, 0], "out": 2, "eout": [(0.0, 0.8)]},
            {"t": 4.0, "v": [255, 0, 0, 255], "in": 2, "ein": [(180.0, 0.33)]}])),
        P("ADBE Glo2-0014", "color", "Color B", [255.0, 0.0, 128.0, 64.0], default=[255, 0, 0, 0]),
    ], opacity=60.0)
    turb = effect("ADBE Turbulent Displace", "Turbulent Displace", [
        P("ADBE Turbulent Displace-0000", "layer", "", [0.0]),
        P("ADBE Turbulent Displace-0002", "float", "Amount", lo=0, hi=1000, default=50, anim=("multi", [
            {"t": 2.0, "v": [0.0]}, {"t": 2.5, "v": [80.0]}])),
        P("ADBE Turbulent Displace-0003", "float", "Size", [40.0], lo=2, hi=1000, default=100),
    ], enabled=False)
    sglow = effect("S_Glow", "S_Glow", [
        P("S_Glow-0000", "layer", "", [0.0]),
        P("S_Glow-0050", "slider", "Brightness", [0.9], lo=0, hi=32767, default=2),
        P("S_Glow-0051", "color", "Color", [255.0, 255.0, 128.0, 0.0], default=[255, 255, 255, 255]),
        P("S_Glow-0052", "slider", "Threshold", lo=0, hi=32767, default=0.5, anim=("multi", [
            {"t": 2.0, "v": [0.2], "out": 2, "eout": [(0.0, 0.6)]}, {"t": 3.5, "v": [0.9], "in": 2, "ein": [(0.0, 0.6)]}])),
        P("S_Glow-0054", "slider", "Glow Width", [100.0], lo=0, hi=32767, default=96, expr="wiggle(2, 10)"),
        P("S_Glow-0055", "slider", "Width X", [1.0], lo=0, hi=32767, default=1),
        P("S_Glow-0101", "popup", "Combine", [2.0], options=["Mult", "Add", "Screen", "Difference", "Overlay"], default=3),
        P("S_Glow-0061", "slider", "Affect Alpha", [0.25], lo=0, hi=32767, default=1),
    ])
    acme = effect("ACME Wobble", "ACME Wobble", [
        P("ACME Wobble-0000", "layer", "", [0.0]),
        P("ACME Wobble-0001", "float", "Strength", lo=0, hi=10, default=1, anim=("multi", [
            {"t": 2.0, "v": [0.0]}, {"t": 3.0, "v": [5.0]}])),
        P("ACME Wobble-0002", "float", "Speed", [1.0], lo=0, hi=10, default=1),
        P("ACME Wobble-0003", "arbitrary", "Curve", arb=b"\x01\x02\x03\x04"),
    ])
    return ffx(tile, glow, turb, sglow, acme)


if __name__ == "__main__":
    import sys
    open(sys.argv[1], "wb").write(sample())


def _fx(match, **params):
    """сторонний плагин с параметрами-слайдерами/цветами/точками по именам рецепта."""
    ps = [P(f"{match}-0000", "layer", "", [0.0])]
    for i, (name, v) in enumerate(params.items(), 1):
        name = name.replace("_", " ")
        m = f"{match}-{i:04d}"
        if isinstance(v, dict):
            ps.append(P(m, "float", name, lo=-1e4, hi=1e4, default=0, **v))
        elif isinstance(v, tuple) and len(v) == 4:
            ps.append(P(m, "color", name, list(v)))
        elif isinstance(v, tuple):
            ps.append(P(m, "point", name, list(v)))
        elif isinstance(v, int):
            ps.append(P(m, "popup", name, [float(v)], options=[f"Option {k}" for k in range(1, 8)], default=1))
        else:
            ps.append(P(m, "float", name, [v], lo=-1e4, hi=1e4, default=0))
    return effect(match, match, ps)


def recipes_sample():
    """по одному экземпляру каждого плагина, для которого есть рецепт, — чтобы формулы проверялись на сайте."""
    amp = {"anim": ("multi", [{"t": 0.0, "v": [40.0], "out": 2, "eout": [(0.0, 0.5)]}, {"t": 0.2, "v": [0.0], "in": 2, "ein": [(0.0, 0.8)]}])}
    return ffx(
        _fx("S_Shake", Amplitude=amp, Frequency=8.0, Seed=0.2, Stillness=0.3, Drift=0.1, Twitch_Frequency=1.0,
            X_Rand_Amp=20.0, X_Rand_Freq=1.0, Y_Rand_Amp=20.0, Y_Rand_Freq=1.0, Z_Rand_Amp=2.0, Z_Rand_Freq=1.0,
            Tilt_Rand_Amp=1.0, Tilt_Rand_Freq=1.0, Tilt_Wave_Amp=0.5, Tilt_Wave_Freq=0.5, Mo_Blur_Length=0.5),
        _fx("S_Emboss", Light_Color=(255.0, 255.0, 200.0, 150.0), Bumps_Scale=1.5, Brightness=1.2, Light_Dir_XY=(0.8, 0.2)),
        _fx("S_WarpChroma", From_Z_Dist=1.0, To_Z_Dist=0.9, Warp_Amount=0.2),
        _fx("S_ScanLines", Frequency=200.0),
        _fx("BCC6LensBlur", Iris_Scale=amp, Iris_Shape=5, Gamma=130.0, Highlight_Threshold=80.0),
        _fx("BCC3Rays Puffy", Light_Source=(0.3, 0.4), Intensity=120.0),
        _fx("BCC Halftone", Cell_Size=8.0),
        _fx("PEDG", Radius=200.0, Exposure=1.2, Tint=1.0, Color=(255.0, 255.0, 120.0, 40.0)),
        _fx("PEGR", Exposure=1.5, Length=300.0),
    )
