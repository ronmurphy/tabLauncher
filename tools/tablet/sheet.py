import re, sys
from PIL import Image, ImageDraw
S = sys.argv[1]; out = sys.argv[2]; names = sys.argv[3].split(",")
styles = ["Themed", "Watercolour", "Inked", "Minimal"]
cell = 150
sheet = Image.new("RGB", (cell * len(names) + 110, cell * len(styles) + 30), (38, 38, 38))
d = ImageDraw.Draw(sheet)
for j, n in enumerate(names): d.text((110 + j * cell + 40, 8), n, fill=(230, 230, 230))
for i, st in enumerate(styles):
    d.text((8, 30 + i * cell + 60), st, fill=(230, 230, 230))
    for j, n in enumerate(names):
        for pas in (1, 2):
            txt = open(f"{S}/{st}-{pas}.txt").read()
            m = re.search(re.escape(n) + r"\|\[(\d+),(\d+)\]\[(\d+),(\d+)\]", txt)
            if not m: continue
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 - x1 < 80: continue
            im = Image.open(f"{S}/{st}-{pas}.png")
            cx = (x1 + x2) // 2
            sheet.paste(im.crop((cx - 30, y1 + 6, cx + 30, y1 + 66)).resize((cell - 10, cell - 10), Image.LANCZOS), (110 + j * cell + 5, 30 + i * cell + 5))
            break
sheet.save(out)
