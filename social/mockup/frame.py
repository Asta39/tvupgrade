"""Puts a phone screenshot into the iPhone 16 Pro Max mockup (Magnific, free vector) and writes a transparent PNG."""
import sys
from PIL import Image, ImageDraw, ImageChops, ImageFilter

MOCK = 'social/mockup/iphone16promax.jpg'
SX0, SY0, SX1, SY1 = 1550, 414, 3454, 4568   # the screen inside the bezel
RADIUS = 250
ISLAND = (2238, 475, 2768, 638)              # the Dynamic Island's box, kept on top of the screenshot

def frame(src, out, width=900):
    m = Image.open(MOCK).convert('RGB')
    W, H = m.size
    # Outside the phone: white becomes transparent, the shadow under it stays as soft black.
    lum = m.convert('L')
    reach = lum.point(lambda v: 255 if v > 175 else 0)
    ImageDraw.floodfill(reach, (0, 0), 128)
    outside = reach.point(lambda v: 255 if v == 128 else 0).filter(ImageFilter.MaxFilter(3))
    alpha_out = ImageChops.invert(lum)                           # darker = more opaque (shadow)
    alpha = Image.composite(alpha_out, Image.new('L', (W, H), 255), outside)
    rgb = Image.composite(Image.new('RGB', (W, H), (0, 0, 0)), m, outside)
    phone = rgb.convert('RGBA'); phone.putalpha(alpha)
    # The screenshot, filling the screen, with the screen's rounded corners.
    sw, sh = SX1 - SX0, SY1 - SY0
    s = Image.open(src).convert('RGB')
    scale = max(sw / s.width, sh / s.height)
    s = s.resize((round(s.width * scale), round(s.height * scale)), Image.LANCZOS)
    s = s.crop(((s.width - sw) // 2, 0, (s.width - sw) // 2 + sw, sh))
    mask = Image.new('L', (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw - 1, sh - 1), RADIUS, fill=255)
    phone.paste(s, (SX0, SY0), mask)
    # Dynamic Island back on top.
    x0, y0, x1, y1 = ISLAND
    isl = Image.new('L', (x1 - x0, y1 - y0), 0)
    ImageDraw.Draw(isl).rounded_rectangle((0, 0, x1 - x0 - 1, y1 - y0 - 1), (y1 - y0) // 2, fill=255)
    phone.paste(m.crop(ISLAND), (x0, y0), isl)
    bbox = phone.getbbox()
    phone = phone.crop(bbox)
    phone = phone.resize((width, round(phone.height * width / phone.width)), Image.LANCZOS)
    phone.save(out, optimize=True)

if __name__ == '__main__':
    for name in sys.argv[1:]:
        frame(f'screenshots/{name}.jpg', f'screenshots/mockup-{name}.png')
        print('ok', name)
