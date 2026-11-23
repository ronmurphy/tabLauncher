import re, sys, os
from PIL import Image, ImageDraw
# sheet_rows.py <dir> <out> "<row1,row2>" "<name1,name2,...>" <ymin> <ymax>: crop each named chip from <row>-<pass>.png
S, out, rows, names, ymin, ymax = sys.argv[1], sys.argv[2], sys.argv[3].split(","), sys.argv[4].split(","), int(sys.argv[5]), int(sys.argv[6])
cell = 120
sheet = Image.new("RGB", (cell * len(names) + 100, cell * len(rows) + 30), (38, 38, 38))
d = ImageDraw.Draw(sheet)
for j, n in enumerate(names): d.text((100 + j * cell + 10, 8), n, fill=(230, 230, 230))
for i, r in enumerate(rows):
    d.text((6, 30 + i * cell + 50), r, fill=(230, 230, 230))
    for j, n in enumerate(names):
        p = 1
        while os.path.exists(f"{S}/{r}-{p}.txt"):
            txt = open(f"{S}/{r}-{p}.txt").read()
            for m in re.finditer(r"(?m)^" + re.escape(n) + r"\|\[(\d+),(\d+)\]\[(\d+),(\d+)\]", txt):
                x1, y1, x2, y2 = map(int, m.groups())
                if x2 - x1 < 80 or not (ymin <= y1 <= ymax): continue
                im = Image.open(f"{S}/{r}-{p}.png"); cx = (x1 + x2) // 2
                sheet.paste(im.crop((cx - 30, y1 + 6, cx + 30, y1 + 66)).resize((cell - 8, cell - 8), Image.LANCZOS), (100 + j * cell + 4, 30 + i * cell + 4))
                break
            p += 1
sheet.save(out)
