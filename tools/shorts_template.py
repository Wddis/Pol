# Brand template for Shorts. Needs fonts from npm @fontsource/rubik-dirt, @fontsource/russo-one and avatar.png (channel avatar) in cwd.
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
for y,h in ((SCR_Y,SCR_H),(CAM_Y,CAM_H)):
    d.rectangle([0,y-6,W-1,y-1],fill=(245,245,245,255)); d.rectangle([0,y+h,W-1,y+h+5],fill=(245,245,245,255))
# title: grunge white with black outline, slight tilt
def tilted_text(txt,font,size,maxw,rot):
    words=txt.split()
    def fo(w,sz): return ImageFont.truetype(F_DIRT_L if w.isascii() else font,sz)
    def width(sz): return sum(d.textlength(w,font=fo(w,sz)) for w in words)+0.33*sz*(len(words)-1)
    while width(size)>maxw and size>40: size-=4
    f=ImageFont.truetype(font,size)
    def width(f, _s=size): return sum(d.textlength(w,font=fo(w,_s)) for w in words)+0.33*_s*(len(words)-1)
    gap=int(0.33*size); bb=d.textbbox((0,0),'ЙДУ',font=f,stroke_width=6)
    im=Image.new('RGBA',(int(width(f))+40,bb[3]-bb[1]+30),(0,0,0,0)); di=ImageDraw.Draw(im); x=20
    for w in words:
        di.text((x,15-bb[1]),w,font=fo(w,size),fill=(255,255,255,255),stroke_width=6,stroke_fill=(0,0,0,255)); x+=d.textlength(w,font=fo(w,size))+gap
    return im.rotate(rot,expand=True,resample=Image.BICUBIC)
y=22
for i,line in enumerate(title):
    im=tilted_text(line,F_DIRT,118 if i==0 else 104,W-70,4 if i%2==0 else 3)
    fg.alpha_composite(im,((W-im.width)//2,y)); y+=im.height-28
# bottom CTA: white brush strip with black text + avatar
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
fg.save(out+'_fg.png')
print('ok', SCR_Y, CAM_Y)
