"""Generates a procedural night city map of our own, with no third-party data, in a navigation style.

Output: map.ppm (3200x2200), labels.txt (x y text angle) and arrow.png (the car arrow, RGBA).
Usage: python3 -I gen_map.py <output_dir>
"""
import os, random, struct, sys, zlib, math

out = sys.argv[1]
random.seed(20261015)
MW, MH = 3200, 2200
BG = (0x24, 0x2c, 0x33)
BLOCK = (0x2b, 0x34, 0x3c)
BUILD = [(0x33, 0x3d, 0x46), (0x37, 0x41, 0x4b), (0x30, 0x3a, 0x43)]
PARK = (0x26, 0x40, 0x33)
WATER = (0x1a, 0x33, 0x4d)
MINOR = (0x4b, 0x58, 0x63)
MAJOR = (0x66, 0x77, 0x84)
HIGHWAY = (0x8c, 0x7b, 0x4f)
ROUTE = (0x42, 0x85, 0xf4)
ROUTE_EDGE = (0x1c, 0x4e, 0xa8)

buf = bytearray(bytes(BG) * (MW * MH))

def rect(x0, y0, x1, y1, c):
    x0, y0 = max(0, int(x0)), max(0, int(y0))
    x1, y1 = min(MW, int(x1)), min(MH, int(y1))
    if x1 <= x0 or y1 <= y0:
        return
    row = bytes(c) * (x1 - x0)
    for y in range(y0, y1):
        o = (y * MW + x0) * 3
        buf[o:o + len(row)] = row

def thick_line(xa, ya, xb, yb, w, c):
    n = int(max(abs(xb - xa), abs(yb - ya)) / max(1, w / 3)) + 1
    for i in range(n + 1):
        t = i / n
        x, y = xa + (xb - xa) * t, ya + (yb - ya) * t
        rect(x - w / 2, y - w / 2, x + w / 2, y + w / 2, c)

# Streets: an irregular grid.
def cuts(total, lo, hi):
    v, out_ = [0], 0
    while out_ < total:
        out_ += random.randint(lo, hi)
        v.append(min(out_, total))
    return v

xs, ys = cuts(MW, 110, 230), cuts(MH, 100, 200)
major_x = set(random.sample(range(1, len(xs) - 1), 4))
major_y = set(random.sample(range(1, len(ys) - 1), 3))

# Blocks with buildings, parks and a river.
for i in range(len(xs) - 1):
    for j in range(len(ys) - 1):
        bx0, bx1, by0, by1 = xs[i] + 8, xs[i + 1] - 8, ys[j] + 8, ys[j + 1] - 8
        if random.random() < 0.08:
            rect(bx0, by0, bx1, by1, PARK)
            for _ in range(random.randint(2, 5)):
                px, py = random.randint(bx0, max(bx0, bx1 - 20)), random.randint(by0, max(by0, by1 - 20))
                rect(px, py, px + random.randint(8, 18), py + random.randint(8, 18), (0x2c, 0x4a, 0x3a))
            continue
        rect(bx0, by0, bx1, by1, BLOCK)
        for _ in range(random.randint(6, 16)):
            w, h = random.randint(14, 60), random.randint(14, 60)
            px, py = random.randint(bx0 + 4, max(bx0 + 4, bx1 - w - 4)), random.randint(by0 + 4, max(by0 + 4, by1 - h - 4))
            rect(px, py, px + w, py + h, random.choice(BUILD))

# A river on a gentle diagonal.
for k in range(0, MW, 4):
    cy = 1650 + 140 * math.sin(k / 420.0)
    rect(k, cy - 55, k + 4, cy + 55, WATER)

for i, x in enumerate(xs[1:-1], 1):
    w = 24 if i in major_x else 10
    rect(x - w / 2, 0, x + w / 2, MH, MAJOR if i in major_x else MINOR)
for j, y in enumerate(ys[1:-1], 1):
    w = 24 if j in major_y else 10
    rect(0, y - w / 2, MW, y + w / 2, MAJOR if j in major_y else MINOR)
# Two diagonal avenues and a highway.
thick_line(0, 300, MW, 1900, 16, MAJOR)
thick_line(400, MH, 2600, 0, 16, MAJOR)
thick_line(0, 1050, MW, 980, 34, HIGHWAY)

# The route follows real streets of the grid.
rx = [xs[2], xs[2], xs[7], xs[7], xs[12], xs[12]]
ry = [ys[1], ys[5], ys[5], ys[9], ys[9], ys[11]]
for a in range(len(rx) - 1):
    thick_line(rx[a], ry[a], rx[a + 1], ry[a + 1], 22, ROUTE_EDGE)
for a in range(len(rx) - 1):
    thick_line(rx[a], ry[a], rx[a + 1], ry[a + 1], 14, ROUTE)

with open(os.path.join(out, "map.ppm"), "wb") as f:
    f.write(b"P6 %d %d 255\n" % (MW, MH))
    f.write(buf)

# Invented street names.
names = ["Oak Ave", "River Rd", "Pine St", "Maple Dr", "Elm St", "Cedar Ln", "Hill St", "Lake Ave",
         "Park Rd", "Mill St", "Bay St", "North Ave", "Church St", "Market St", "Bridge Rd", "Station Rd",
         "Harbor Way", "King St", "Queen St", "Union Ave", "West End", "Garden St", "Spring St", "Forest Dr"]
labels = []
for i, x in enumerate(xs[1:-1], 1):
    for _ in range(3):
        labels.append((x + 8, random.randint(40, MH - 200), random.choice(names), "v"))
for j, y in enumerate(ys[1:-1], 1):
    for _ in range(3):
        labels.append((random.randint(40, MW - 300), y - 30, random.choice(names), "h"))
with open(os.path.join(out, "labels.txt"), "w") as f:
    for x, y, t, o in labels:
        f.write(f"{x} {y} {o} {t}\n")

# The car arrow: a white triangle with a blue edge, RGBA.
S = 64
px = bytearray()
for y in range(S):
    px.append(0)
    for x in range(S):
        # Triangle pointing up.
        half = (y / (S - 1)) * (S / 2 - 4)
        inside = abs(x - S / 2) <= half and 4 <= y <= S - 6
        edge = abs(x - S / 2) <= half + 4 and 0 <= y <= S - 2 and not inside
        if inside:
            px += bytes((255, 255, 255, 255))
        elif edge:
            px += bytes((0x42, 0x85, 0xf4, 255))
        else:
            px += bytes((0, 0, 0, 0))
def chunk(t, d):
    return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(bytes(px), 9)) + chunk(b"IEND", b"")
open(os.path.join(out, "arrow.png"), "wb").write(png)
print("ok", len(xs), len(ys), len(labels))
