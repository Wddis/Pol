# Brand template for Shorts. Needs fonts from npm @fontsource/rubik-dirt, @fontsource/russo-one and avatar.png (channel avatar) in cwd.
# ARCHIVE: old grunge Shorts design. MAIN since 12.11.2026 = tools/shorts_template2.py --assets clean (owner chose v2 «clean»).
"""Brand template for Shorts (1080x1920): bg.png (under video) + fg.png (over video).
usage: python3 tpl.py "TITLE LINE1|LINE2" outprefix"""
import sys, random, math
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps
W,H=1080,1920
F_DIRT='x_fontsource-rubik-dirt-5.3.0/files/rubik-dirt-cyrillic-400-normal.woff'
F_DIRT_L='x_fontsource-rubik-dirt-5.3.0/files/rubik-dirt-latin-400-normal.woff'
F_RUSSO='x_fontsource-russo-one-5.3.0/files/russo-one-cyrillic-400-normal.woff'
title=sys.argv[1].split('|'); out=sys.argv[2]
SCR_Y,SCR_H=380,607   # screen panel
CAM_Y,CAM_H=1000,615   # webcam panel
random.seed(7)
bg=Image.new('RGB',(W,H),(8,8,8)); d=ImageDraw.Draw(bg)
# speed-line slashes like the banner (white streaks radiating)
for i in range(70):
    a=random.uniform(-0.5,0.5)+ (math.pi if random.random()<.5 else 0)
    x0,y0=random.randint(0,W),random.choice([random.randint(0,18),random.randint(1625,H)])
    L=random.randint(120,520); w=random.choice([1,2,2,3,5])
    d.line([(x0,y0),(x0+L*math.cos(a),y0+L*math.sin(a)*0.35)],fill=(random.randint(150,255),)*3,width=w)
# film strip on bottom edge
for x in range(0,W,46): d.rectangle([x+8,H-34,x+30,H-14],fill=(235,235,235))
d.rectangle([0,H-44,W,H-42],fill=(235,235,235))
bg=bg.filter(ImageFilter.GaussianBlur(0.6))
bg.save(out+'_bg.png')
fg=Image.new('RGBA',(W,H),(0,0,0,0)); d=ImageDraw.Draw(fg)
# panel frames (white rough borders)
import os as _os
PANELS=((SCR_Y,CAM_Y+CAM_H-SCR_Y),) if _os.environ.get('TALK')=='1' else ((SCR_Y,SCR_H),(CAM_Y,CAM_H))   # TALK=1: one big webcam panel
for y,h in PANELS:
    d.rectangle([0,y-6,W-1,y-1],fill=(245,245,245,255)); d.rectangle([0,y+h,W-1,y+h+5],fill=(245,245,245,255))
# title: grunge white with black outline, slight tilt
def tilted_text(txt,font,size,maxw,rot):
    # Rubik Dirt ships Cyrillic and Latin as separate files: the Cyrillic one has no "?!" etc.
    # Pick the font PER CHARACTER (Cyrillic letters -> Cyrillic file, everything else -> Latin file).
    def fch(ch,sz): return ImageFont.truetype(font if '\u0400'<=ch<='\u04FF' else F_DIRT_L, sz)
    def tw(t,sz): return sum(d.textlength(ch,font=fch(ch,sz)) for ch in t)
    while tw(txt,size)>maxw and size>40: size-=4
    f=ImageFont.truetype(font,size); bb=d.textbbox((0,0),'ЙДУ',font=f,stroke_width=6)
    im=Image.new('RGBA',(int(tw(txt,size))+40,bb[3]-bb[1]+30),(0,0,0,0)); di=ImageDraw.Draw(im); x=20
    for ch in txt:
        fo=fch(ch,size)
        di.text((x,15-bb[1]),ch,font=fo,fill=(255,255,255,255),stroke_width=6,stroke_fill=(0,0,0,255)); x+=d.textlength(ch,font=fo)
    return im.rotate(rot,expand=True,resample=Image.BICUBIC)
y=22
for i,line in enumerate(title):
    im=tilted_text(line,F_DIRT,104 if i==0 else 96,W-70,4 if i%2==0 else 3)
    fg.alpha_composite(im,((W-im.width)//2,y)); y+=im.height-28
assert y<=380+40, f'title too tall ({y}px): shorten lines'
# Bottom: by default ONLY the channel avatar (owner, 12.11.2026: "убери нижний текст, верхнего достаточно").
# The old white strip "ПОЛНАЯ ЗАПИСЬ НА КАНАЛЕ" + "ДВОЙНАЯ РЕЗКА НАРЕЗКИ СТРИМЕРА" is kept behind CTA=1.
import os
if os.environ.get('CTA') == '1':
    strip=Image.new('RGBA',(W,170),(0,0,0,0)); sd=ImageDraw.Draw(strip)
    pts=[(20,40),(W-10,10),(W-30,150),(0,165)]
    sd.polygon(pts,fill=(250,250,250,255))
    for i in range(40):  # rough edges
        x=random.randint(0,W); sd.line([(x,random.randint(0,25)),(x+random.randint(20,90),random.randint(0,25))],fill=(0,0,0,0),width=random.randint(2,6))
    txt='ПОЛНАЯ ЗАПИСЬ НА КАНАЛЕ'; fs=64
    f=ImageFont.truetype(F_RUSSO,fs)
    while sd.textlength(txt,font=f)>W-290: fs-=2; f=ImageFont.truetype(F_RUSSO,fs)
    tw=sd.textlength(txt,font=f); sd.text((240+(W-270-tw)/2,85-fs//2),txt,font=f,fill=(10,10,10,255))
    strip=strip.rotate(-2,expand=False,resample=Image.BICUBIC)
    fg.alpha_composite(strip,(0,1650))
    av=Image.open('avatar.png').convert('RGB').resize((210,210))
    mask=Image.new('L',(210,210),0); ImageDraw.Draw(mask).ellipse([0,0,209,209],fill=255)
    ring=Image.new('RGBA',(226,226),(0,0,0,0)); ImageDraw.Draw(ring).ellipse([0,0,225,225],fill=(250,250,250,255))
    fg.alpha_composite(ring,(12,1630)); avr=Image.new('RGBA',(210,210)); avr.paste(av,(0,0),mask); fg.alpha_composite(avr,(20,1638))
    # small arrow / pointer to "subscribe"
    f2=ImageFont.truetype(F_RUSSO,40)
    d.text((250,1822),'ДВОЙНАЯ РЕЗКА   НАРЕЗКИ СТРИМЕРА',font=f2,fill=(240,240,240,255),stroke_width=3,stroke_fill=(0,0,0,255))

else:
    av=Image.open('avatar.png').convert('RGB').resize((190,190))
    mask=Image.new('L',(190,190),0); ImageDraw.Draw(mask).ellipse([0,0,189,189],fill=255)
    ring=Image.new('RGBA',(206,206),(0,0,0,0)); ImageDraw.Draw(ring).ellipse([0,0,205,205],fill=(250,250,250,255))
    fg.alpha_composite(ring,((W-206)//2,1652)); avr=Image.new('RGBA',(190,190)); avr.paste(av,(0,0),mask); fg.alpha_composite(avr,((W-190)//2,1660))
fg.save(out+'_fg.png')
print('ok', SCR_Y, CAM_Y)
