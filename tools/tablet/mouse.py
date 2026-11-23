"""A short-lived virtual mouse for testing, via Android's `uinput` shell tool (no root needed).

    python3 tools/tablet/mouse.py "to 42 133" "wait 2000" "right" | adb shell uinput -

Prints the JSON that `uinput` reads; the virtual mouse exists until the input ends, then disappears.
Steps (screen pixels, landscape):
    to X Y        put the pointer at X,Y (slams into the top-left corner first, then moves slowly)
    by DX DY      move by DX,DY, slowly
    left | right | middle | back      click that button (back = the side button)
    down | up     press / release the left button (for drags: down, by ..., up)
    wheel N       N notches of the wheel (negative = down, as Linux counts)
    hwheel N      N notches sideways
    wait MS       pause
Moves go 2 mouse units per 10 ms, slow enough that Android doesn't accelerate them. At this tablet's pointer
speed one unit moves the pointer 2.045 px (measured); set SCALE if the speed setting changes. Take
screenshots from another command while a `wait` runs; screenshots show the pointer.
"""
import json, os, sys

SCALE = float(os.environ.get("SCALE", 2.045))   # screen px per mouse unit

EV_SYN, EV_KEY, EV_REL = 0, 1, 2
REL_X, REL_Y, REL_HWHEEL, REL_WHEEL = 0, 1, 6, 8
BTN = {"left": 272, "right": 273, "middle": 274, "back": 275}   # back = BTN_SIDE

out = []
def cmd(**kw): out.append(json.dumps(dict(id=1, **kw)))
def inject(*ev): cmd(command="inject", events=list(ev) + [EV_SYN, 0, 0])
def wait(ms): cmd(command="delay", duration=int(ms))

def move(px, py, step=2, pause=10):
    dx, dy = round(px / SCALE), round(py / SCALE)
    while dx or dy:
        sx = max(-step, min(step, dx)); sy = max(-step, min(step, dy))
        inject(EV_REL, REL_X, sx, EV_REL, REL_Y, sy); wait(pause)
        dx -= sx; dy -= sy

cmd(command="register", name="TabDesk test mouse", vid=6353, pid=1, bus="usb",
    configuration=[{"type": 100, "data": [EV_KEY, EV_REL]},           # UI_SET_EVBIT
                   {"type": 101, "data": list(BTN.values())},          # UI_SET_KEYBIT
                   {"type": 102, "data": [REL_X, REL_Y, REL_HWHEEL, REL_WHEEL]}])  # UI_SET_RELBIT
wait(800)  # let Android add the device

for step in sys.argv[1:]:
    w = step.split(); op = w[0]; a = [int(x) for x in w[1:]]
    if op == "to":
        for _ in range(12): inject(EV_REL, REL_X, -250, EV_REL, REL_Y, -250); wait(8)
        wait(150); move(a[0], a[1])
    elif op == "by": move(a[0], a[1])
    elif op in BTN:
        inject(EV_KEY, BTN[op], 1); wait(70); inject(EV_KEY, BTN[op], 0); wait(50)
    elif op == "down": inject(EV_KEY, BTN["left"], 1); wait(60)
    elif op == "up": inject(EV_KEY, BTN["left"], 0); wait(60)
    elif op == "wheel":
        for _ in range(abs(a[0])): inject(EV_REL, REL_WHEEL, 1 if a[0] > 0 else -1); wait(60)
    elif op == "hwheel":
        for _ in range(abs(a[0])): inject(EV_REL, REL_HWHEEL, 1 if a[0] > 0 else -1); wait(60)
    elif op == "wait": wait(a[0])
    else: sys.exit(f"unknown step: {step}")
wait(200)
print("\n".join(out))
