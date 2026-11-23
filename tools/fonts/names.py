import sys
from collections import OrderedDict
from fontTools.ttLib import TTFont
font, cps, out = sys.argv[1:4]
cmap = TTFont(font).getBestCmap()
by = OrderedDict()
for line in open(cps):
    n, h = line.split()
    cp = int(h, 16)
    if cp in cmap: by.setdefault(cp, []).append(n)
# one line per glyph: hex, then its names (first is the main one)
with open(out, "w") as f:
    for cp, names in by.items(): f.write("%x %s\n" % (cp, " ".join(names)))
print(len(by), "glyphs")
