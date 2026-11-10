import sys, json, math, random
from PIL import Image, ImageDraw, ImageFont, ImageFilter

F = "/usr/share/fonts/opentype/inter/"
def font(name, size): return ImageFont.truetype(F + name, size)

def make(d, out):
    W, H = 1280, 720
    S = 2  # supersample
    w, h = W * S, H * S
    img = Image.new("RGB", (w, h), (10, 8, 18))
    px = img.load()
    # diagonal gradient: deep violet -> near black
    c1, c2 = (46, 18, 92), (8, 7, 14)
    grad = Image.new("RGB", (w, h))
    gd = ImageDraw.Draw(grad)
    for x in range(0, w, 4):
        t = x / w
        col = tuple(int(c1[i] * (1 - t) ** 1.4 + c2[i] * (1 - (1 - t) ** 1.4)) for i in range(3))
        gd.rectangle([x, 0, x + 4, h], fill=col)
    img = grad
    # glow blobs
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    g = ImageDraw.Draw(glow)
    g.ellipse([w * 0.55, -h * 0.35, w * 1.25, h * 0.75], fill=(145, 70, 255))
    g.ellipse([-w * 0.25, h * 0.55, w * 0.35, h * 1.4], fill=(255, 60, 140))
    glow = glow.filter(ImageFilter.GaussianBlur(260))
    img = Image.blend(img, Image.composite(glow, img, Image.new("L", (w, h), 255)), 0.38)
    dr = ImageDraw.Draw(img, "RGBA")

    # film strip on the right
    sx = int(w * 0.80)
    dr.rectangle([sx, 0, sx + 170 * S // 2, h], fill=(0, 0, 0, 120))
    for y in range(-40, h, 70 * S // 2):
        dr.rounded_rectangle([sx + 14, y, sx + 44, y + 40], 6, fill=(255, 255, 255, 40))
        dr.rounded_rectangle([sx + 170 * S // 2 - 44, y, sx + 170 * S // 2 - 14, y + 40], 6, fill=(255, 255, 255, 40))
    # big play triangle
    cx, cy, r = sx + 85 * S // 2, h // 2, 120 * S // 2
    dr.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(145, 70, 255, 235))
    tri = [(cx - r * 0.32, cy - r * 0.5), (cx - r * 0.32, cy + r * 0.5), (cx + r * 0.55, cy)]
    dr.polygon(tri, fill=(255, 255, 255, 255))

    L = 80 * S
    # brand
    dr.rounded_rectangle([L, 64 * S, L + 14 * S, 78 * S], 4 * S, fill=(145, 70, 255))
    dr.text((L + 26 * S, 58 * S), "КИНОВАЙБ", font=font("Inter-ExtraBold.otf", 26 * S), fill=(255, 255, 255))
    dr.text((L + 26 * S + dr.textlength("КИНОВАЙБ", font=font("Inter-ExtraBold.otf", 26 * S)) + 14 * S, 61 * S),
            "фильмы со стримерами", font=font("Inter-Medium.otf", 22 * S), fill=(200, 190, 230))

    # part tag (top right of the text column)
    if d.get("part"):
        pt = d["part"].upper()
        tf = font("Inter-ExtraBold.otf", 22 * S)
        tw = dr.textlength(pt, font=tf)
        x2 = sx - 40 * S
        dr.rounded_rectangle([x2 - tw - 36 * S, 50 * S, x2, 94 * S], 22 * S, fill=(255, 255, 255, 235))
        dr.text((x2 - tw - 18 * S, 59 * S), pt, font=tf, fill=(46, 18, 92))

    # title fit
    maxw = sx - L - 60 * S
    size = 96
    while True:
        tf = font("InterDisplay-Black.otf", size * S)
        words, lines, cur = d["title"].upper().split(), [], ""
        for wd in words:
            t = (cur + " " + wd).strip()
            if dr.textlength(t, font=tf) <= maxw: cur = t
            else: lines.append(cur); cur = wd
        lines.append(cur)
        if len(lines) <= 3 and all(dr.textlength(l, font=tf) <= maxw for l in lines): break
        size -= 4
    lh = int(size * 1.0 * S)
    ev_h = 66 * S if d.get("event") else 0
    block = ev_h + lh * len(lines) + 22 * S + 36 * S
    top, bottom = 120 * S, (H - 170) * S
    y = top + max(0, (bottom - top - block) // 2)

    if d.get("event"):
        ef = font("Inter-Bold.otf", 22 * S)
        tw = dr.textlength(d["event"], font=ef)
        dr.rounded_rectangle([L, y, L + tw + 36 * S, y + 44 * S], 22 * S, fill=(255, 60, 140, 60), outline=(255, 90, 160), width=2 * S)
        dr.text((L + 18 * S, y + 9 * S), d["event"], font=ef, fill=(255, 200, 225))
        y += ev_h
    for l in lines:
        dr.text((L, y), l, font=tf, fill=(255, 255, 255))
        y += lh
    y += 22 * S
    dr.text((L, y), d["meta"], font=font("Inter-SemiBold.otf", 30 * S), fill=(205, 195, 235))

    # streamer pill
    pf = font("Inter-ExtraBold.otf", 30 * S)
    lab = font("Inter-Medium.otf", 20 * S)
    nick = d["streamer"]
    pw = max(dr.textlength(nick, font=pf), dr.textlength("СТРИМЕР", font=lab)) + 110 * S
    by = H * S - 150 * S
    dr.rounded_rectangle([L, by, L + pw, by + 84 * S], 42 * S, fill=(145, 70, 255, 255))
    # mic icon
    mx, my = L + 42 * S, by + 42 * S
    dr.rounded_rectangle([mx - 9 * S, my - 22 * S, mx + 9 * S, my + 6 * S], 9 * S, fill=(255, 255, 255))
    dr.arc([mx - 16 * S, my - 12 * S, mx + 16 * S, my + 16 * S], 0, 180, fill=(255, 255, 255), width=4 * S)
    dr.line([mx, my + 16 * S, mx, my + 24 * S], fill=(255, 255, 255), width=4 * S)
    dr.text((L + 74 * S, by + 10 * S), "СТРИМЕР", font=lab, fill=(225, 210, 255))
    dr.text((L + 74 * S, by + 34 * S), nick, font=pf, fill=(255, 255, 255))

    # rating badges
    bx = L + pw + 24 * S
    for name, val, bg, fg in [("IMDb", d["imdb"], (245, 197, 24), (0, 0, 0)), ("КП", d["kp"], (255, 102, 0), (255, 255, 255))]:
        nf, vf = font("Inter-Black.otf", 24 * S), font("Inter-ExtraBold.otf", 34 * S)
        nw = dr.textlength(name, font=nf) + 28 * S
        vw = dr.textlength(val, font=vf) + 36 * S
        dr.rounded_rectangle([bx, by + 8 * S, bx + nw + vw, by + 76 * S], 16 * S, fill=(255, 255, 255, 30), outline=(255, 255, 255, 60), width=2 * S)
        dr.rounded_rectangle([bx, by + 8 * S, bx + nw, by + 76 * S], 16 * S, fill=bg)
        dr.text((bx + 14 * S, by + 26 * S), name, font=nf, fill=fg)
        dr.text((bx + nw + 18 * S, by + 20 * S), val, font=vf, fill=(255, 255, 255))
        bx += nw + vw + 18 * S

    # subtle grain
    random.seed(1)
    noise = Image.effect_noise((w, h), 18).convert("RGB")
    img = Image.blend(img, noise, 0.04)
    img = img.resize((W, H), Image.LANCZOS)
    img.save(out, quality=93)

if __name__ == "__main__":
    make(json.loads(sys.argv[1]), sys.argv[2])
