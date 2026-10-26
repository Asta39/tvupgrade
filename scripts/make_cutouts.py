import sys, numpy as np
# Reference script used to make themes/ian-dusk/depth/*.png: wallpaper + SAM sky mask (inverted) -> foreground
# cut-out with alpha, faded out above a set height so the top of the clock stays readable. Paths point at the
# working folders this was run from; adapt WALL/MASK locations before reuse. Needs Pillow + numpy.
from PIL import Image, ImageFilter, ImageDraw, ImageFont
W,H=1920,1080
def cover(im):
    s=max(3840/im.width,2160/im.height); im=im.resize((round(im.width*s),round(im.height*s)),Image.BILINEAR)
    l=(im.width-3840)//2; t=(im.height-2160)//2
    return im.crop((l,t,l+3840,t+2160)).resize((W,H),Image.LANCZOS)
RAMPS={"02-misty-pines":(380,60),"05-green-lake":(385,50)}
def ramp_for(n):
    a,b=RAMPS.get(n,(280,80)); return np.clip((np.arange(H)-a)/b,0,1)[:,None]
specs={"02-misty-pines":("sky",True),"03-golden-peak":("sky",True),"05-green-lake":("sky",True),"06-tea-hills":("mask",False),"04-fog-forest":(None,None)}
prev=[]
for n,(kind,inv) in specs.items():
    wall=Image.open(f"../wallpapers-4k/{n}.jpg").convert("RGB").resize((W,H),Image.LANCZOS)
    if kind:
        m=np.asarray(cover(Image.open(f"{kind}-{n}.png").convert("L")).filter(ImageFilter.GaussianBlur(1.2)),dtype=np.float32)/255
        if inv: m=1-m
    else:
        lum=np.asarray(wall.convert("L"),dtype=np.float32)
        m=np.clip((70-lum)/25,0,1)
        m=np.asarray(Image.fromarray((m*255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(1.2)),dtype=np.float32)/255
    a=(m*ramp_for(n)*255).astype(np.uint8)
    fg=wall.copy(); fg.putalpha(Image.fromarray(a)); fg.save(f"{n}.fg.png",optimize=True)
    p=wall.convert("RGBA"); d=ImageDraw.Draw(p)
    try: f=ImageFont.truetype("/System/Library/Fonts/Helvetica.ttc",330)
    except Exception: f=ImageFont.load_default()
    d.text((W//2,110),"23:04",font=f,fill=(255,255,255,215),anchor="mt")
    p.alpha_composite(fg); prev.append(p.convert("RGB").resize((480,270)))
sheet=Image.new("RGB",(1440,540))
for i,p in enumerate(prev): sheet.paste(p,((i%3)*480,(i//3)*270))
sheet.save(sys.argv[1])
