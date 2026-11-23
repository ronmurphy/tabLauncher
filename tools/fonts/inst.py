import sys
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
src, out = sys.argv[1], sys.argv[2]
for name, axes in [("static_fill0", dict(FILL=0, wght=400, GRAD=0, opsz=40)),
                   ("static_fill1", dict(FILL=1, wght=400, GRAD=0, opsz=40)),
                   ("fill_axis", dict(FILL=(0, 1), wght=400, GRAD=0, opsz=40)),
                   ("fill_wght", dict(FILL=(0, 1), wght=(300, 500), GRAD=0, opsz=40))]:
    f = TTFont(src)
    instancer.instantiateVariableFont(f, axes, inplace=True)
    f.save(f"{out}/{name}.ttf")
    print(name, "done")
